package com.ticketsystem.api.mail.intake;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditEvent;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.mail.MailAuditEvent;
import com.ticketsystem.api.mail.intake.InboundRouter.ReplyMode;
import com.ticketsystem.api.mail.intake.clientdata.InboundMailRepository;
import com.ticketsystem.api.mail.intake.clientdata.InboundMailRepository.BlockedMailRow;
import com.ticketsystem.api.mail.intake.clientdata.InboundPeopleRepository;
import com.ticketsystem.api.mail.intake.clientdata.IntakeStateRepository;
import com.ticketsystem.api.mail.intake.clientdata.MailRulesRepository;
import com.ticketsystem.api.mail.intake.clientdata.MailRulesRepository.BlockRuleRow;
import com.ticketsystem.api.mail.intake.clientdata.MailRulesRepository.SenderRuleRow;
import com.ticketsystem.api.mail.intake.poller.BlockedMailRestorer;
import com.ticketsystem.api.mail.ticketmail.SupportAddresses;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository;
import java.net.IDN;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin email settings this item enforces: the supporter email reply mode, the block list with
 * its guards and the Blocked mail log, and sender rules. Every change is audit-logged. Step-up is
 * checked by the controller (always for the reply mode, and for a block rule that hits a client
 * domain, a contact or a supporter).
 */
@Service
public class EmailRulesService {

  static final int BLOCKED_LOG_LIMIT = 500;

  private static final Pattern ADDRESS =
      Pattern.compile("[a-z0-9._%+'-]{1,64}@[a-z0-9.-]{1,253}\\.[a-z0-9-]{2,63}");
  private static final Pattern DOMAIN = Pattern.compile("[a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63})+");

  /** A block rule as entered. */
  public record BlockRuleRequest(String kind, String value) {}

  /**
   * The guard verdict for a new block rule: a warning naming the client or account, which needs the
   * admin's confirmation and a step-up.
   */
  public record BlockCheck(String kind, String value, String warning) {
    public boolean needsConfirmation() {
      return warning != null;
    }
  }

  /** A block rule (admin view). */
  public record BlockRuleView(long id, String kind, String value, Instant createdAt) {
    static BlockRuleView of(BlockRuleRow r) {
      return new BlockRuleView(r.id(), r.kind(), r.value(), r.createdAt());
    }
  }

  /** A Blocked mail log entry (admin view, no content). */
  public record BlockedMailView(
      long id,
      String fromAddress,
      String subject,
      Instant receivedAt,
      String ruleKind,
      String rule,
      Instant restoredAt,
      Long ticketId) {
    static BlockedMailView of(BlockedMailRow r) {
      return new BlockedMailView(
          r.id(),
          r.fromAddress(),
          r.subject(),
          r.receivedAt(),
          r.ruleKind(),
          r.rule(),
          r.restoredAt(),
          r.ticketId());
    }
  }

  /** A sender rule (admin view). */
  public record SenderRuleView(
      long id,
      String kind,
      String value,
      long organisationId,
      long requestTypeId,
      Instant createdAt) {
    static SenderRuleView of(SenderRuleRow r) {
      return new SenderRuleView(
          r.id(), r.kind(), r.value(), r.organisationId(), r.requestTypeId(), r.createdAt());
    }
  }

  public record SenderRuleRequest(
      String kind, String value, Long organisationId, Long requestTypeId) {}

  private final MailRulesRepository rules;
  private final InboundMailRepository inbound;
  private final IntakeStateRepository state;
  private final InboundPeopleRepository people;
  private final OrganisationRepository organisations;
  private final RequestTypeRepository requestTypes;
  private final SupportAddresses addresses;
  private final BlockedMailRestorer restorer;
  private final AuditService audit;
  private final Clock clock;

  public EmailRulesService(
      MailRulesRepository rules,
      InboundMailRepository inbound,
      IntakeStateRepository state,
      InboundPeopleRepository people,
      OrganisationRepository organisations,
      RequestTypeRepository requestTypes,
      SupportAddresses addresses,
      BlockedMailRestorer restorer,
      AuditService audit,
      Clock clock) {
    this.rules = rules;
    this.inbound = inbound;
    this.state = state;
    this.people = people;
    this.organisations = organisations;
    this.requestTypes = requestTypes;
    this.addresses = addresses;
    this.restorer = restorer;
    this.audit = audit;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------------------------
  // Supporter replies by email

  @Transactional(readOnly = true)
  public ReplyMode replyMode(AccessScope scope) {
    scope.requireAdmin();
    return ReplyMode.valueOf(state.supporterReplyMode(scope));
  }

  @Transactional
  public ReplyMode setReplyMode(AccessScope scope, String mode, String clientIp) {
    scope.requireAdmin();
    ReplyMode m;
    try {
      m = ReplyMode.valueOf(mode == null ? "" : mode.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidRequest("Choose OFF, INTERNAL_ONLY or NOTES_AND_PUBLIC.");
    }
    state.setSupporterReplyMode(scope, m.name(), scope.principalId().orElse(null), clock.instant());
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("mode", m.name());
    audit.record(
        MailAuditEvent.EMAIL_SETTINGS_CHANGED, scope.principalId().orElse(null), null, clientIp, d);
    return m;
  }

  // ---------------------------------------------------------------------------------------------
  // Block list

  @Transactional(readOnly = true)
  public List<BlockRuleView> blockRules(AccessScope scope) {
    scope.requireAdmin();
    return rules.blockRules(scope).stream().map(BlockRuleView::of).toList();
  }

  /**
   * Normalises a rule and checks the guards: our own mailbox domain or address is refused (400); a
   * client's verified domain, a saved contact or a supporter returns a warning to confirm.
   */
  @Transactional(readOnly = true)
  public BlockCheck checkBlockRule(AccessScope scope, BlockRuleRequest r) {
    scope.requireAdmin();
    String kind = r.kind() == null ? "" : r.kind().strip().toUpperCase(Locale.ROOT);
    String value = normalise(kind, r.value());
    for (String ours : addresses.all(scope)) {
      String ourDomain = ours.substring(ours.lastIndexOf('@') + 1);
      boolean hitsUs =
          "DOMAIN".equals(kind)
              ? ourDomain.equals(value) || ourDomain.endsWith("." + value)
              : addresses.isOurs(scope, value);
      if (hitsUs) {
        throw ApiException.invalidRequest("The support mailbox's own domain cannot be blocked.");
      }
    }
    String warning = null;
    if ("DOMAIN".equals(kind)) {
      // A domain rule also blocks subdomains, so warn for any verified domain under it too
      // (blocking "co.uk" would silently block a client on "client.co.uk").
      Optional<InboundPeopleRepository.DomainOwner> owner =
          people.verifiedDomainUnder(scope, value);
      if (owner.isPresent()) {
        String org =
            people.organisationName(scope, owner.get().organisationId()).orElse("a client");
        warning =
            owner.get().domain().equals(value)
                ? value + " is a verified domain of " + org + "."
                : "Blocking "
                    + value
                    + " also blocks "
                    + owner.get().domain()
                    + ", a verified"
                    + " domain of "
                    + org
                    + ".";
      }
    } else {
      Optional<InboundPeopleRepository.Account> account = people.byEmail(scope, value);
      if (account.isPresent()) {
        warning =
            account.get().organisationId() == null
                ? value + " is an agent."
                : value
                    + " is a saved contact of "
                    + people
                        .organisationName(scope, account.get().organisationId())
                        .orElse("a client")
                    + ".";
      }
    }
    return new BlockCheck(kind, value, warning);
  }

  @Transactional
  public BlockRuleView addBlockRule(AccessScope scope, BlockCheck check, String clientIp) {
    scope.requireAdmin();
    Instant now = clock.instant();
    rules.addBlockRule(scope, check.kind(), check.value(), scope.principalId().orElse(null), now);
    BlockRuleRow row =
        rules.blockRules(scope).stream()
            .filter(x -> x.kind().equals(check.kind()) && x.value().equals(check.value()))
            .findFirst()
            .orElseThrow();
    auditRule(scope, MailAuditEvent.BLOCK_RULE_ADDED, row.kind(), row.value(), clientIp);
    return BlockRuleView.of(row);
  }

  @Transactional
  public void removeBlockRule(AccessScope scope, long id, String clientIp) {
    scope.requireAdmin();
    BlockRuleRow row = rules.deleteBlockRule(scope, id).orElseThrow(AccessScope::notFound);
    auditRule(scope, MailAuditEvent.BLOCK_RULE_REMOVED, row.kind(), row.value(), clientIp);
  }

  @Transactional(readOnly = true)
  public List<BlockedMailView> blockedMail(AccessScope scope) {
    scope.requireAdmin();
    return inbound.blocked(scope, BLOCKED_LOG_LIMIT).stream().map(BlockedMailView::of).toList();
  }

  /**
   * Restores a blocked mail into a normal ticket; returns the ticket id. Not transactional itself:
   * the restore commits in its own transaction, and only then are its files handed to the
   * attachment worker.
   */
  public long restore(AccessScope scope, long inboundMailId) {
    scope.requireAdmin();
    InboundMailProcessor.Outcome o = restorer.restore(scope, inboundMailId);
    if (o.ticketId() == null) {
      throw new ApiException(HttpStatus.CONFLICT, "not_restored", "The email was not restored.");
    }
    return o.ticketId();
  }

  // ---------------------------------------------------------------------------------------------
  // Sender rules

  @Transactional(readOnly = true)
  public List<SenderRuleView> senderRules(AccessScope scope) {
    scope.requireAdmin();
    return rules.senderRules(scope).stream().map(SenderRuleView::of).toList();
  }

  @Transactional
  public SenderRuleView addSenderRule(AccessScope scope, SenderRuleRequest r, String clientIp) {
    scope.requireAdmin();
    String kind = r.kind() == null ? "" : r.kind().strip().toUpperCase(Locale.ROOT);
    String value = normalise(kind, r.value());
    if (r.organisationId() == null || organisations.find(scope, r.organisationId()).isEmpty()) {
      throw ApiException.invalidRequest("Choose an organisation.");
    }
    if (r.requestTypeId() == null
        || requestTypes.find(scope, r.requestTypeId()).filter(t -> !t.archived()).isEmpty()) {
      throw ApiException.invalidRequest("Choose a request type.");
    }
    if (rules
        .addSenderRule(
            scope,
            kind,
            value,
            r.organisationId(),
            r.requestTypeId(),
            scope.principalId().orElse(null),
            clock.instant())
        .isEmpty()) {
      throw ApiException.invalidRequest("There is already a rule for this sender.");
    }
    SenderRuleRow row =
        rules.senderRules(scope).stream()
            .filter(x -> x.kind().equals(kind) && x.value().equals(value))
            .findFirst()
            .orElseThrow();
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("kind", kind);
    d.put("rule", value);
    d.put("organisation", r.organisationId());
    audit.record(
        MailAuditEvent.SENDER_RULE_ADDED, scope.principalId().orElse(null), null, clientIp, d);
    return SenderRuleView.of(row);
  }

  @Transactional
  public void removeSenderRule(AccessScope scope, long id, String clientIp) {
    scope.requireAdmin();
    SenderRuleRow row = rules.deleteSenderRule(scope, id).orElseThrow(AccessScope::notFound);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("kind", row.kind());
    d.put("rule", row.value());
    d.put("organisation", row.organisationId());
    audit.record(
        MailAuditEvent.SENDER_RULE_REMOVED, scope.principalId().orElse(null), null, clientIp, d);
  }

  // ---------------------------------------------------------------------------------------------

  /** Lower case, trimmed, IDN to ASCII; a domain may be written as {@code @x} or {@code *.x}. */
  static String normalise(String kind, String raw) {
    String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    if ("DOMAIN".equals(kind)) {
      if (v.startsWith("@")) {
        v = v.substring(1);
      } else if (v.startsWith("*.")) {
        v = v.substring(2);
      }
      while (v.endsWith(".")) {
        v = v.substring(0, v.length() - 1);
      }
      try {
        v = IDN.toASCII(v, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
      } catch (IllegalArgumentException e) {
        throw ApiException.invalidRequest("Enter a valid domain such as spam.example.");
      }
      if (v.length() > 253 || !DOMAIN.matcher(v).matches()) {
        throw ApiException.invalidRequest("Enter a valid domain such as spam.example.");
      }
      return v;
    }
    if ("ADDRESS".equals(kind)) {
      int at = v.lastIndexOf('@');
      if (at > 0) {
        try {
          v =
              v.substring(0, at + 1)
                  + IDN.toASCII(v.substring(at + 1), IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
          throw ApiException.invalidRequest("Enter a valid email address.");
        }
      }
      if (v.length() > 320 || !ADDRESS.matcher(v).matches()) {
        throw ApiException.invalidRequest("Enter a valid email address.");
      }
      return v;
    }
    throw ApiException.invalidRequest("Choose ADDRESS or DOMAIN.");
  }

  private void auditRule(
      AccessScope scope, AuditEvent type, String kind, String value, String clientIp) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("kind", kind);
    d.put("rule", value);
    audit.record(type, scope.principalId().orElse(null), null, clientIp, d);
  }
}
