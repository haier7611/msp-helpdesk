package com.ticketsystem.api.mail.intake;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.PrincipalAccessReader;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.UserStatus;
import com.ticketsystem.api.mail.MailAuditEvent;
import com.ticketsystem.api.mail.MailKind;
import com.ticketsystem.api.mail.MailOutbox;
import com.ticketsystem.api.mail.ReservedDomains;
import com.ticketsystem.api.mail.inbound.AuthenticationResults;
import com.ticketsystem.api.mail.inbound.BlockListMatcher;
import com.ticketsystem.api.mail.inbound.HeaderPeek;
import com.ticketsystem.api.mail.inbound.InboundAddress;
import com.ticketsystem.api.mail.inbound.InboundAnalysis;
import com.ticketsystem.api.mail.inbound.InboundLimits;
import com.ticketsystem.api.mail.inbound.InboundMailEngine;
import com.ticketsystem.api.mail.inbound.InboundMessage;
import com.ticketsystem.api.mail.inbound.ParseResult;
import com.ticketsystem.api.mail.inbound.PlusAddress.MailboxTarget;
import com.ticketsystem.api.mail.inbound.ReferenceCandidate;
import com.ticketsystem.api.mail.intake.InboundRouter.Decision;
import com.ticketsystem.api.mail.intake.InboundRouter.Facts;
import com.ticketsystem.api.mail.intake.InboundRouter.ReplyMode;
import com.ticketsystem.api.mail.intake.InboundRouter.RuleMatch;
import com.ticketsystem.api.mail.intake.InboundRouter.SenderHistory;
import com.ticketsystem.api.mail.intake.InboundRouter.SenderKind;
import com.ticketsystem.api.mail.intake.InboundRouter.TicketMatch;
import com.ticketsystem.api.mail.intake.InboundRouter.TriageReason;
import com.ticketsystem.api.mail.intake.clientdata.InboundAttachmentJobRepository;
import com.ticketsystem.api.mail.intake.clientdata.InboundMailRepository;
import com.ticketsystem.api.mail.intake.clientdata.InboundMailRepository.NewInboundMail;
import com.ticketsystem.api.mail.intake.clientdata.InboundPeopleRepository;
import com.ticketsystem.api.mail.intake.clientdata.InboundPeopleRepository.Account;
import com.ticketsystem.api.mail.intake.clientdata.IntakeStateRepository;
import com.ticketsystem.api.mail.intake.clientdata.MailRulesRepository;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository;
import com.ticketsystem.api.mail.ticketmail.SupportAddresses;
import com.ticketsystem.api.mail.ticketmail.TicketMailComposer;
import com.ticketsystem.api.organisation.VerifiedDomains;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.ticket.ReplyCcService;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.TicketService.ClientReply;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.agent.InternalNoteService;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository.ParticipantRow;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/**
 * Processes one inbound email in one transaction (C11): the inbound mail log row, the ticket or
 * comment it becomes, the outbox mail it causes and the mailbox's processed-UID mark all commit
 * together, so a mail is never lost and never processed twice. The decision is {@link
 * InboundRouter}'s; this class gathers its facts and carries it out through the ticket services.
 *
 * <p>Every email becomes a ticket or a comment except blocked mail, which is only logged. Nothing
 * about a message's content is logged.
 *
 * <p>Ticket first, attachments after (see docs/10-decision-log.md ADR-039): an email with files is
 * saved with a placeholder body ({@link InboundAttachments#placeholderBody}) and no files, and an
 * {@code inbound_attachment_job} row is written in the same transaction. The caller hands the job
 * ids in {@link Outcome#attachmentJobs()} to the attachment worker after the commit. A supporter's
 * emailed public reply is the exception: its files are stored first as before, because they are
 * sent to the client in the same transaction's outgoing email.
 */
@Service
public class InboundMailProcessor {

  static final Duration RAW_RETENTION = Duration.ofDays(90);
  static final Duration BLOCKED_RETENTION = Duration.ofDays(30);

  /** At most this many participants on a ticket (the CC limit). */
  static final int MAX_PARTICIPANTS = ReplyCcService.MAX_CC;

  /** Distinct To/Cc addresses looked at per mail; the rest are ignored. */
  static final int MAX_ADDRESSES_CHECKED = 50;

  /** Addresses named in the "Not added (limit)" system entry. */
  static final int MAX_LISTED_OVER_LIMIT = 20;

  static final int MAX_BODY_HTML = 190_000;
  static final int MAX_BODY_TEXT = 100_000;
  static final String DEFAULT_REQUEST_TYPE = "Other or general question";

  /** The mailbox a message came from. {@code mailboxId} null for a restore of orphaned mail. */
  public record MailboxContext(Long mailboxId, String address, Set<String> trustedAuthservIds) {}

  /**
   * A message as read from IMAP. {@code tooLarge}: {@code raw} is only a truncated copy (headers
   * and the start of the body); {@code size} is the size of the whole message on the server.
   */
  public record Received(Long uidValidity, Long uid, byte[] raw, boolean tooLarge, long size) {

    public Received(Long uidValidity, Long uid, byte[] raw, boolean tooLarge) {
      this(uidValidity, uid, raw, tooLarge, raw.length);
    }
  }

  /**
   * The log row could not be written because the same Message-ID (or mailbox + UID) was committed
   * meanwhile by another poller: the whole transaction rolls back, so the ticket or comment it made
   * is undone, and the caller records the mail as a duplicate.
   */
  public static final class DuplicateMailException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DuplicateMailException() {
      super("inbound mail already processed", null, false, false);
    }
  }

  /**
   * What happened. {@code duplicate}: the Message-ID was processed before and nothing changed
   * except the UID mark. {@code attachmentJobs}: committed jobs for the attachment worker, to
   * dispatch after the commit.
   */
  public record Outcome(
      String outcome, String reason, Long ticketId, boolean duplicate, List<Long> attachmentJobs) {

    public Outcome {
      attachmentJobs = List.copyOf(attachmentJobs);
    }

    public Outcome(String outcome, String reason, Long ticketId, boolean duplicate) {
      this(outcome, reason, ticketId, duplicate, List.of());
    }
  }

  /** Files left for the attachment worker: the comment they go to and whose scope stores them. */
  private record Deferred(
      long ticketId, Long publicCommentId, Long internalNoteId, Long ownerUserId, boolean reply) {}

  private final InboundMailRepository inbound;
  private final MailRulesRepository rules;
  private final IntakeStateRepository state;
  private final MailboxRepository mailboxes;
  private final InboundPeopleRepository people;
  private final TicketService tickets;
  private final InternalNoteService notes;
  private final TicketRepository ticketRows;
  private final TicketParticipantRepository participants;
  private final TicketPeopleRepository ticketPeople;
  private final RequestTypeRepository requestTypes;
  private final ReferenceService references;
  private final MailOutbox outbox;
  private final TicketMailComposer composer;
  private final SupportAddresses addresses;
  private final PrincipalAccessReader principals;
  private final VerifiedDomains verifiedDomains;
  private final InboundAttachments inboundAttachments;
  private final InboundAttachmentJobRepository attachmentJobs;
  private final PublicCommentRepository publicComments;
  private final AuditService audit;
  private final Clock clock;

  public InboundMailProcessor(
      InboundMailRepository inbound,
      MailRulesRepository rules,
      IntakeStateRepository state,
      MailboxRepository mailboxes,
      InboundPeopleRepository people,
      TicketService tickets,
      InternalNoteService notes,
      TicketRepository ticketRows,
      TicketParticipantRepository participants,
      TicketPeopleRepository ticketPeople,
      RequestTypeRepository requestTypes,
      ReferenceService references,
      MailOutbox outbox,
      TicketMailComposer composer,
      SupportAddresses addresses,
      PrincipalAccessReader principals,
      VerifiedDomains verifiedDomains,
      InboundAttachments inboundAttachments,
      InboundAttachmentJobRepository attachmentJobs,
      PublicCommentRepository publicComments,
      AuditService audit,
      Clock clock) {
    this.inbound = inbound;
    this.rules = rules;
    this.state = state;
    this.mailboxes = mailboxes;
    this.people = people;
    this.tickets = tickets;
    this.notes = notes;
    this.ticketRows = ticketRows;
    this.participants = participants;
    this.ticketPeople = ticketPeople;
    this.requestTypes = requestTypes;
    this.references = references;
    this.outbox = outbox;
    this.composer = composer;
    this.addresses = addresses;
    this.principals = principals;
    this.verifiedDomains = verifiedDomains;
    this.inboundAttachments = inboundAttachments;
    this.attachmentJobs = attachmentJobs;
    this.publicComments = publicComments;
    this.audit = audit;
    this.clock = clock;
  }

  /** Processes one message read from {@code mb} (SYSTEM scope, the poller). */
  @Transactional
  public Outcome process(AccessScope scope, MailboxContext mb, Received r) {
    Instant now = clock.instant();
    String sha = sha256(r.raw());
    InboundAnalysis analysis = analyse(scope, mb, r, true);
    String messageId = messageIdOf(analysis, sha);
    if (inbound.existsMessageId(scope, messageId)) {
      logDuplicate(scope, mb, r, sha, messageId, analysis, now);
      advance(scope, mb, r);
      return new Outcome("DUPLICATE", null, null, true);
    }
    if (analysis instanceof InboundAnalysis.Analysed a && isProbe(scope, mb, a, now)) {
      log(scope, mb, r, sha, messageId, a, "PROBE", null, null, false, null, now);
      advance(scope, mb, r);
      return new Outcome("PROBE", null, null, false);
    }
    Facts facts = facts(scope, analysis, false);
    if (r.tooLarge()) {
      facts = blockTooLarge(scope, r, facts);
    }
    Decision decision = InboundRouter.decide(facts);
    List<Deferred> deferred = new ArrayList<>();
    Acted acted = act(scope, decision, analysis, facts, messageId, now, false, deferred);
    long inboundId =
        log(
            scope,
            mb,
            r,
            sha,
            messageId,
            analysis,
            acted.outcome(),
            acted.reason(),
            acted.ticketId(),
            acted.newTicket(),
            decision instanceof Decision.Block b ? b.rule() : null,
            now);
    List<Long> jobs = recordJobs(scope, inboundId, deferred, now);
    advance(scope, mb, r);
    return new Outcome(acted.outcome(), acted.reason(), acted.ticketId(), false, jobs);
  }

  /** Writes the attachment jobs of one mail in its transaction. */
  private List<Long> recordJobs(
      AccessScope scope, long inboundMailId, List<Deferred> deferred, Instant now) {
    List<Long> ids = new ArrayList<>();
    for (Deferred d : deferred) {
      ids.add(
          attachmentJobs.insert(
              scope,
              new InboundAttachmentJobRepository.NewJob(
                  inboundMailId,
                  d.ticketId(),
                  d.publicCommentId(),
                  d.internalNoteId(),
                  d.ownerUserId(),
                  d.reply()),
              now));
    }
    return ids;
  }

  /**
   * After a {@link DuplicateMailException}: the mail was committed by someone else meanwhile. Logs
   * a DUPLICATE row (no raw copy) and moves the UID mark; nothing else changes.
   */
  @Transactional
  public Outcome recordDuplicate(AccessScope scope, MailboxContext mb, Received r) {
    Instant now = clock.instant();
    String sha = sha256(r.raw());
    InboundAnalysis analysis = analyse(scope, mb, r, false);
    logDuplicate(scope, mb, r, sha, messageIdOf(analysis, sha), analysis, now);
    advance(scope, mb, r);
    return new Outcome("DUPLICATE", null, null, true);
  }

  /**
   * The fallback when {@link #process} failed unexpectedly (its transaction rolled back): the mail
   * still becomes an Email triage ticket marked "could not be processed", with the raw {@code .eml}
   * kept, so it is never lost and never retried for ever.
   */
  @Transactional
  public Outcome processFailed(AccessScope scope, MailboxContext mb, Received r) {
    Instant now = clock.instant();
    String sha = sha256(r.raw());
    String messageId = "<" + sha + "@failed.invalid>";
    if (inbound.existsMessageId(scope, messageId)) {
      advance(scope, mb, r);
      return new Outcome("DUPLICATE", null, null, true);
    }
    long ticketId =
        tickets
            .create(
                scope,
                new NewTicket(
                    null,
                    null,
                    null,
                    defaultRequestType(scope),
                    null,
                    null,
                    null,
                    TicketSource.EMAIL,
                    "Email could not be processed",
                    "<p>This email could not be processed automatically. The original is kept"
                        + " for 90 days.</p>",
                    List.of(),
                    null,
                    null,
                    null,
                    null))
            .ticketId();
    notes.addSystemEntry(scope, ticketId, "EMAIL_TRIAGE", TriageReason.PROCESSING_FAILED.label());
    log(
        scope,
        mb,
        r,
        sha,
        messageId,
        null,
        "TRIAGE",
        "PROCESSING_FAILED",
        ticketId,
        true,
        null,
        now);
    advance(scope, mb, r);
    return new Outcome("TRIAGE", "PROCESSING_FAILED", ticketId, false);
  }

  /**
   * Restores a blocked mail into a normal ticket (admin only): routed like a new email (saved
   * contact, verified domain, sender rule, otherwise Email triage), never added to an existing
   * ticket and never sent an automatic reply.
   *
   * @param admin the admin who asked for the restore
   * @param filing the poller's SYSTEM scope the mail is filed with ({@code BlockedMailRestorer})
   */
  @Transactional
  public Outcome restore(AccessScope admin, AccessScope filing, long inboundMailId) {
    admin.requireAdmin();
    if (!filing.isSystem()) {
      throw new IllegalArgumentException("a restore is filed with the poller's SYSTEM scope");
    }
    InboundMailRepository.BlockedForRestore b =
        inbound.lockBlocked(admin, inboundMailId).orElseThrow(AccessScope::notFound);
    MailboxContext mb = contextFor(admin, b.mailboxId());
    // A blocked mail over 30 MB was kept as a truncated copy only: it stays unparseable.
    Received r = new Received(null, null, b.raw(), b.rawSize() > b.raw().length, b.rawSize());
    InboundAnalysis analysis = analyse(admin, mb, r, false);
    Facts facts = facts(admin, analysis, true);
    Decision decision = InboundRouter.decide(facts);
    Instant now = clock.instant();
    // No confirmation for a restored mail: the sender was blocked, they hear nothing.
    // The mail is filed as the poller would have filed it (SYSTEM scope), so its files are the
    // client's, with no uploader, not the restoring admin's uploads. The admin
    // is recorded on the Blocked mail row and in the audit log below.
    List<Deferred> deferred = new ArrayList<>();
    Acted acted = act(filing, decision, analysis, facts, null, now, true, deferred);
    List<Long> jobs = recordJobs(filing, inboundMailId, deferred, now);
    inbound.markRestored(
        admin,
        inboundMailId,
        admin.principalId().orElse(null),
        acted.ticketId(),
        acted.newTicket(),
        now);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("inbound_mail", inboundMailId);
    d.put("ticket", acted.ticketId());
    audit.record(
        MailAuditEvent.BLOCKED_MAIL_RESTORED, admin.principalId().orElse(null), null, null, d);
    return new Outcome(acted.outcome(), acted.reason(), acted.ticketId(), false, jobs);
  }

  // ---------------------------------------------------------------------------------------------
  // Facts

  private InboundAnalysis analyse(
      AccessScope scope, MailboxContext mb, Received r, boolean applyBlockList) {
    if (r.tooLarge()) {
      return new InboundAnalysis.Unparseable(ParseResult.Reason.TOO_LARGE, r.size());
    }
    InboundMailEngine engine =
        new InboundMailEngine(mb.address(), mb.trustedAuthservIds(), InboundLimits.DEFAULT);
    BlockListMatcher blockList = applyBlockList ? blockList(scope) : BlockListMatcher.none();
    return engine.analyse(r.raw(), blockList);
  }

  /**
   * A mail over 30 MB is never parsed, but the block list still applies to it: its From header is
   * read from the start of the truncated copy.
   */
  private Facts blockTooLarge(AccessScope scope, Received r, Facts f) {
    return HeaderPeek.of(r.raw())
        .block(blockList(scope))
        .map(
            b ->
                new Facts(
                    f.unparseable(),
                    b,
                    f.target(),
                    f.dmarcPass(),
                    f.automated(),
                    f.match(),
                    f.referenceNotFound(),
                    f.senderKind(),
                    f.supporterUserId(),
                    f.contactUserId(),
                    f.contactOrganisationId(),
                    f.mode(),
                    f.authenticatedHistory(),
                    f.unauthenticatedHistory(),
                    f.rule(),
                    f.domainOrganisationId()))
        .orElse(f);
  }

  private BlockListMatcher blockList(AccessScope scope) {
    List<String> addressRules = new ArrayList<>();
    List<String> domainRules = new ArrayList<>();
    for (MailRulesRepository.BlockRuleRow rule : rules.blockRules(scope)) {
      ("ADDRESS".equals(rule.kind()) ? addressRules : domainRules).add(rule.value());
    }
    return new BlockListMatcher(addressRules, domainRules);
  }

  private Facts facts(AccessScope scope, InboundAnalysis analysis, boolean restore) {
    if (!(analysis instanceof InboundAnalysis.Analysed a)) {
      return new Facts(
          true,
          null,
          MailboxTarget.SUPPORT,
          false,
          false,
          null,
          false,
          SenderKind.UNKNOWN,
          null,
          null,
          null,
          ReplyMode.INTERNAL_ONLY,
          SenderHistory.NONE,
          SenderHistory.NONE,
          null,
          null);
    }
    InboundAddress from = a.message().from();
    String address = from == null ? null : from.address();
    Account account = address == null ? null : people.byEmail(scope, address).orElse(null);
    SenderKind kind =
        account == null
            ? SenderKind.UNKNOWN
            : account.activeSupporter()
                ? SenderKind.SUPPORTER
                : account.contact() ? SenderKind.CONTACT : SenderKind.UNKNOWN;

    TicketMatch match = null;
    boolean referenceNotFound = false;
    if (!restore) {
      Optional<Long> ticketId = Optional.empty();
      boolean internalThread = false;
      for (ReferenceCandidate c : a.referenceCandidates()) {
        ticketId =
            references
                .resolve(scope, c.prefix(), c.number())
                .map(ReferenceService.Resolved::ticketId);
        if (ticketId.isPresent()) {
          break;
        }
      }
      if (ticketId.isEmpty()) {
        referenceNotFound = !a.referenceCandidates().isEmpty();
        Optional<MailOutbox.ThreadMatch> thread = outbox.findThread(a.message().threadMessageIds());
        if (thread.isPresent()) {
          ticketId = Optional.of(finalTarget(scope, thread.get().ticketId()));
          internalThread = thread.get().kind() == MailKind.INTERNAL;
          referenceNotFound = false;
        }
      }
      if (ticketId.isPresent()) {
        TicketRow t = ticketRows.find(scope, ticketId.get()).orElse(null);
        if (t != null) {
          match =
              new TicketMatch(
                  t.id(),
                  t.organisationId(),
                  t.status(),
                  internalThread,
                  address != null && isReporterOrParticipant(scope, t, address),
                  kind == SenderKind.SUPPORTER
                      && ticketPeople.isActiveSupporterFor(
                          scope, account.userId(), t.organisationId()));
        } else {
          referenceNotFound = true;
        }
      }
    }

    // The per-sender cap and the fold-in target are kept apart per authentication class:
    // forged (DMARC-failing) mail "from" an address never counts towards, or becomes the fold
    // target of, the genuine sender's mail, and the other way round.
    SenderHistory authenticated = SenderHistory.NONE;
    SenderHistory unauthenticated = SenderHistory.NONE;
    RuleMatch rule = null;
    Long domainOrg = null;
    if (address != null) {
      if (!restore) {
        authenticated = history(scope, address, true);
        unauthenticated = history(scope, address, false);
      }
      rule =
          rules
              .match(scope, address, from.domain())
              .map(x -> new RuleMatch(x.organisationId(), x.requestTypeId()))
              .orElse(null);
      // An existing account (e.g. a deactivated customer) never gets a second contact created.
      if (account == null && !verifiedDomains.isPublic(from.domain())) {
        domainOrg = people.organisationOfDomain(scope, from.domain()).orElse(null);
      }
    }
    return new Facts(
        false,
        a.blockMatch(),
        restore ? MailboxTarget.SUPPORT : a.mailboxTarget(),
        a.authentication().dmarcPass(),
        a.automation().automated(),
        match,
        referenceNotFound,
        kind,
        kind == SenderKind.SUPPORTER ? account.userId() : null,
        kind == SenderKind.CONTACT ? account.userId() : null,
        kind == SenderKind.CONTACT ? account.organisationId() : null,
        replyMode(scope),
        authenticated,
        unauthenticated,
        rule,
        domainOrg);
  }

  private SenderHistory history(AccessScope scope, String address, boolean authenticated) {
    Instant since = clock.instant().minus(Duration.ofHours(1));
    int recent = inbound.newTicketsFrom(scope, address, since, authenticated);
    if (recent < InboundRouter.NEW_TICKETS_PER_HOUR) {
      return new SenderHistory(recent, null, false);
    }
    Long latest = inbound.latestNewTicketOf(scope, address, authenticated).orElse(null);
    boolean inTriage =
        latest != null
            && ticketRows.find(scope, latest).map(t -> t.organisationId() == null).orElse(false);
    return new SenderHistory(recent, latest, inTriage);
  }

  private ReplyMode replyMode(AccessScope scope) {
    try {
      return ReplyMode.valueOf(state.supporterReplyMode(scope));
    } catch (IllegalArgumentException e) {
      return ReplyMode.OFF;
    }
  }

  /**
   * The sender's contact account as the author of a client reply, or null (address only) when the
   * sender is a contact of another organisation replying as an outside participant of this ticket
   * (see docs/10-decision-log.md ADR-050): the reply is never linked to their account or
   * organisation.
   */
  private static Long ownContact(Facts f) {
    Long ticketOrg = f.match() == null ? null : f.match().organisationId();
    if (ticketOrg != null && !ticketOrg.equals(f.contactOrganisationId())) {
      return null;
    }
    return f.contactUserId();
  }

  private boolean isReporterOrParticipant(AccessScope scope, TicketRow t, String address) {
    if (t.reporterEmail() != null && t.reporterEmail().equalsIgnoreCase(address)) {
      return true;
    }
    return participants.listForTicket(scope, t.id()).stream()
        .anyMatch(p -> p.confirmed() && p.email().equalsIgnoreCase(address));
  }

  /** A thread match on a duplicate's source lands on its final target. */
  private long finalTarget(AccessScope scope, long ticketId) {
    long current = ticketId;
    for (int i = 0; i < 10; i++) {
      Optional<TicketRow> t = ticketRows.find(scope, current);
      if (t.isEmpty() || t.get().duplicateOfId() == null) {
        return current;
      }
      current = t.get().duplicateOfId();
    }
    return current;
  }

  private boolean isProbe(
      AccessScope scope, MailboxContext mb, InboundAnalysis.Analysed a, Instant now) {
    if (mb.mailboxId() == null) {
      return false;
    }
    Optional<String> token = a.message().firstHeader(MailboxService.PROBE_HEADER);
    return token.isPresent()
        && token.get().length() <= 100
        && state.markProbeSeen(
            scope, mb.mailboxId(), MailboxService.sha256(token.get().strip()), now);
  }

  // ---------------------------------------------------------------------------------------------
  // Acting

  private record Acted(String outcome, String reason, Long ticketId, boolean newTicket) {}

  private Acted act(
      AccessScope scope,
      Decision decision,
      InboundAnalysis analysis,
      Facts f,
      String messageId,
      Instant now,
      boolean restore,
      List<Deferred> deferred) {
    InboundAnalysis.Analysed a = analysis instanceof InboundAnalysis.Analysed x ? x : null;
    String sender = a == null || a.message().from() == null ? null : a.message().from().address();
    String auth = a == null ? null : authSummary(a.authentication());
    return switch (decision) {
      case Decision.Block b -> new Acted("BLOCKED", b.rule().kind().name(), null, false);
      case Decision.Triage t -> {
        long id =
            createTicket(scope, null, null, sender, null, a, messageId, auth, t.reason(), deferred);
        yield new Acted("TRIAGE", t.reason().name(), id, true);
      }
      case Decision.Create c -> {
        Long reporter = c.reporterUserId();
        if (reporter == null && c.createContact() && sender != null) {
          reporter = people.insertContact(scope, sender, contactName(a), c.organisationId(), now);
        }
        long id =
            createTicket(
                scope,
                c.organisationId(),
                reporter,
                sender,
                c.requestTypeId(),
                a,
                messageId,
                auth,
                null,
                deferred);
        if (!f.automated() && !restore) {
          composer.confirmation(scope, id);
        }
        yield new Acted("CREATED", null, id, true);
      }
      case Decision.ClientReply c -> {
        String body = replyBody(a);
        boolean later = inboundAttachments.hasFiles(a, body, true);
        InboundAttachments.Stored files =
            later
                ? InboundAttachments.Stored.none(InboundAttachments.placeholderBody(body))
                : InboundAttachments.Stored.none(body);
        TicketService.ReplyResult result =
            tickets.addClientReply(
                scope,
                c.ticketId(),
                new ClientReply(
                    ownContact(f), sender, files.bodyHtml(), true, messageId, auth, files.ids()));
        if (later) {
          deferred.add(new Deferred(result.ticketId(), result.commentId(), null, null, true));
        }
        TicketRow target = ticketRows.find(scope, result.ticketId()).orElseThrow();
        addParticipants(scope, target, a, sender);
        if (result.followUpCreated()) {
          if (f.automated()) {
            composer.suppressAutomaticMail(scope, result.ticketId(), "automated sender");
          } else {
            composer.confirmation(scope, result.ticketId());
          }
        }
        yield new Acted("CLIENT_REPLY", null, result.ticketId(), result.followUpCreated());
      }
      case Decision.AutoReplyEntry e -> {
        notes.addSystemEntry(
            scope,
            e.ticketId(),
            "EMAIL_AUTO_REPLY",
            "Automatic email from " + sender + ": " + a.message().subject());
        yield new Acted("SYSTEM_ENTRY", null, e.ticketId(), false);
      }
      case Decision.InternalNote n -> {
        AccessScope supporter = supporterScope(n.supporterUserId());
        // The supporter's own scope owns the files (stored by the worker); the note makes them
        // internal.
        String body = replyBody(a);
        boolean later = inboundAttachments.hasFiles(a, body, true);
        long noteId =
            notes.addNoteByEmail(
                supporter,
                n.ticketId(),
                later ? InboundAttachments.placeholderBody(body) : body,
                List.of(),
                messageId,
                auth);
        if (later) {
          deferred.add(new Deferred(n.ticketId(), null, noteId, n.supporterUserId(), true));
        }
        auditSupporterReply(n.supporterUserId(), n.ticketId(), "note", a.authentication());
        yield new Acted("NOTE", null, n.ticketId(), false);
      }
      case Decision.PublicReply p -> {
        AccessScope supporter = supporterScope(p.supporterUserId());
        InboundAttachments.Stored files =
            inboundAttachments.store(supporter, a, replyBody(a), true);
        tickets.addPublicReplyByEmail(
            supporter, p.ticketId(), files.bodyHtml(), files.ids(), messageId, auth);
        noteNotAttached(scope, p.ticketId(), files);
        auditSupporterReply(p.supporterUserId(), p.ticketId(), "public", a.authentication());
        yield new Acted("PUBLIC_REPLY", null, p.ticketId(), false);
      }
      case Decision.Fold fold -> {
        if (f.automated()) {
          notes.addSystemEntry(
              scope,
              fold.ticketId(),
              "EMAIL_FOLDED",
              "More than 20 emails this hour from " + sender + ": " + a.message().subject());
          yield new Acted("FOLDED", null, fold.ticketId(), false);
        }
        // A client comment like any client email: on a Closed ticket it opens a follow-up.
        // That follow-up counts as this sender's newest ticket, so the next folded
        // mails land on it instead of opening a follow-up each. No confirmation for folded mail.
        String body = newTicketBody(a);
        boolean later = inboundAttachments.hasFiles(a, body, false);
        TicketService.ReplyResult result =
            tickets.addClientReply(
                scope,
                fold.ticketId(),
                new ClientReply(
                    null,
                    sender,
                    later ? InboundAttachments.placeholderBody(body) : body,
                    true,
                    messageId,
                    auth,
                    List.of()));
        if (later) {
          deferred.add(new Deferred(result.ticketId(), result.commentId(), null, null, false));
        }
        if (result.followUpCreated()) {
          TicketRow target = ticketRows.find(scope, result.ticketId()).orElseThrow();
          addParticipants(scope, target, a, sender);
        }
        yield new Acted("FOLDED", null, result.ticketId(), result.followUpCreated());
      }
    };
  }

  private long createTicket(
      AccessScope scope,
      Long organisationId,
      Long reporterUserId,
      String sender,
      Long requestTypeId,
      InboundAnalysis.Analysed a,
      String messageId,
      String auth,
      TriageReason reason,
      List<Deferred> deferred) {
    String subject;
    InboundAttachments.Stored files;
    boolean later = false;
    if (a == null) {
      subject = "Email could not be read";
      files =
          InboundAttachments.Stored.none(
              "<p>This email could not be read automatically. The original is kept for 90"
                  + " days.</p>");
    } else {
      subject = subject(a.message().subject());
      String body = newTicketBody(a);
      later = inboundAttachments.hasFiles(a, body, false);
      files =
          InboundAttachments.Stored.none(later ? InboundAttachments.placeholderBody(body) : body);
    }
    long typeId = requestTypeId != null ? requestTypeId : defaultRequestType(scope);
    long id =
        tickets
            .create(
                scope,
                new NewTicket(
                    organisationId,
                    reporterUserId,
                    reporterUserId == null ? sender : null,
                    typeId,
                    null,
                    null,
                    null,
                    TicketSource.EMAIL,
                    subject,
                    files.bodyHtml(),
                    files.ids(),
                    null,
                    null,
                    messageId,
                    auth))
            .ticketId();
    if (reason != null) {
      notes.addSystemEntry(scope, id, "EMAIL_TRIAGE", "Email triage: " + reason.label());
    }
    if (a != null) {
      if (a.message().headersTruncated()) {
        notes.addSystemEntry(scope, id, "EMAIL_HEADERS_TRUNCATED", "Email headers truncated");
      }
      if (later) {
        // The description is the ticket's first public comment.
        long commentId = publicComments.listForTicket(scope, id).get(0).id();
        deferred.add(new Deferred(id, commentId, null, null, false));
      }
      if (a.automation().automated()) {
        composer.suppressAutomaticMail(scope, id, "automated sender");
      }
      TicketRow t = ticketRows.find(scope, id).orElseThrow();
      addParticipants(scope, t, a, sender);
    }
    return id;
  }

  /**
   * CC handling: the To/Cc addresses of a client's email become participants. A verified email
   * (DMARC pass from a trusted authserv-id) adds them confirmed, so they get the confirmation and
   * the replies like the sender, and confirms an address that was already waiting; an unverified
   * email adds them unconfirmed (no mail until an agent confirms), so a forged email can never put
   * an outsider on a client's ticket. Never added: our own support addresses (and their plus
   * addresses such as {@code +internal}), staff and deactivated accounts, contacts of another
   * organisation (silently, nothing about them is written), reserved test domains, the sender and
   * the reporter. At most {@value #MAX_PARTICIPANTS} participants per ticket; addresses over the
   * limit are listed in a hidden system entry. Every addition is a history line with the client as
   * actor.
   */
  private void addParticipants(
      AccessScope scope, TicketRow t, InboundAnalysis.Analysed a, String sender) {
    boolean verified = a.authentication().dmarcPass();
    Long org = t.organisationId();
    Instant now = clock.instant();
    Map<String, ParticipantRow> current = new HashMap<>();
    List<ParticipantRow> rows = participants.listForTicket(scope, t.id());
    for (ParticipantRow p : rows) {
      if (p.email() != null) {
        current.put(p.email().toLowerCase(Locale.ROOT), p);
      }
    }
    int count = rows.size();
    List<InboundAddress> candidates = new ArrayList<>(a.message().to());
    candidates.addAll(a.message().cc());
    Set<String> seen = new LinkedHashSet<>();
    List<String> overLimit = new ArrayList<>();
    boolean more = false;
    for (InboundAddress addr : candidates) {
      String email = addr.address();
      if (!seen.add(email)
          || email.length() > 254
          || email.equalsIgnoreCase(sender)
          || (t.reporterEmail() != null && email.equalsIgnoreCase(t.reporterEmail()))
          || ReservedDomains.isReserved(email)) {
        continue;
      }
      ParticipantRow existing = current.get(email);
      if (existing != null && (existing.confirmed() || !verified)) {
        continue; // already there, nothing changes
      }
      if (seen.size() > MAX_ADDRESSES_CHECKED) {
        // Bounded work per mail (address headers may hold hundreds of entries).
        more = count >= MAX_PARTICIPANTS;
        break;
      }
      if (addresses.isOurs(scope, email)) {
        continue;
      }
      Eligible eligible = eligible(scope, t, email);
      if (eligible == null) {
        continue;
      }
      if (existing != null) {
        participants.confirmFromEmail(scope, org, t.id(), existing.userId(), email, now);
      } else if (count >= MAX_PARTICIPANTS) {
        if (overLimit.size() < MAX_LISTED_OVER_LIMIT) {
          overLimit.add(email);
        } else {
          more = true;
        }
      } else if (participants.add(scope, org, t.id(), eligible.userId(), email, verified, now)) {
        count++;
      }
    }
    if (!overLimit.isEmpty()) {
      notes.addSystemEntry(
          scope,
          t.id(),
          "EMAIL_CC_LIMIT",
          "Not added (limit): " + String.join(", ", overLimit) + (more ? " and more" : ""));
    }
  }

  /**
   * A To/Cc address that may join: {@code userId} the contact's account, or null (address only).
   */
  private record Eligible(Long userId) {}

  /**
   * Whether {@code email} may be a participant of {@code t} (the CC rules), or null = no (staff, a
   * deactivated account, the reporter's own account, or a contact of another organisation, which is
   * never revealed - also every saved contact while the ticket is in triage, as before).
   */
  private Eligible eligible(AccessScope scope, TicketRow t, String email) {
    Account account = people.byEmail(scope, email).orElse(null);
    if (account == null) {
      return new Eligible(null);
    }
    if (!account.contact()
        || Long.valueOf(account.userId()).equals(t.reporterUserId())
        || t.organisationId() == null
        || !t.organisationId().equals(account.organisationId())) {
      return null;
    }
    return new Eligible(account.userId());
  }

  /** A hidden system entry listing the files of the email that were refused. */
  private void noteNotAttached(AccessScope scope, long ticketId, InboundAttachments.Stored files) {
    String note = InboundAttachments.notAttachedNote(files);
    if (note != null) {
      notes.addSystemEntry(scope, ticketId, "EMAIL_ATTACHMENTS", note);
    }
  }

  private AccessScope supporterScope(long userId) {
    return principals
        .read(userId)
        .filter(p -> p.status() == UserStatus.ACTIVE)
        .map(PrincipalAccessReader.PrincipalAccess::toScope)
        .orElseThrow(AccessScope::notFound);
  }

  private void auditSupporterReply(
      long supporterUserId, long ticketId, String method, AuthenticationResults auth) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("ticket", ticketId);
    d.put("method", method);
    d.put("dmarc", auth.dmarc().name().toLowerCase(Locale.ROOT));
    d.put("spf", auth.spf().name().toLowerCase(Locale.ROOT));
    d.put("dkim", auth.dkim().name().toLowerCase(Locale.ROOT));
    audit.record(MailAuditEvent.SUPPORTER_EMAIL_REPLY, supporterUserId, null, null, d);
  }

  private long defaultRequestType(AccessScope scope) {
    return requestTypes
        .findByName(scope, DEFAULT_REQUEST_TYPE)
        .or(() -> requestTypes.list(scope).stream().findFirst())
        .orElseThrow()
        .id();
  }

  private MailboxContext contextFor(AccessScope scope, Long mailboxId) {
    if (mailboxId != null) {
      Optional<MailboxRepository.MailboxRow> m = mailboxes.find(scope, mailboxId);
      if (m.isPresent()) {
        return new MailboxContext(
            m.get().id(), m.get().address(), MailboxService.trustedIds(m.get()));
      }
    }
    String support = addresses.support(scope).orElse("support@unconfigured.invalid");
    return new MailboxContext(null, support, Set.of());
  }

  private void advance(AccessScope scope, MailboxContext mb, Received r) {
    if (mb.mailboxId() != null && r.uidValidity() != null && r.uid() != null) {
      mailboxes.advanceUid(scope, mb.mailboxId(), r.uidValidity(), r.uid());
    }
  }

  private long log(
      AccessScope scope,
      MailboxContext mb,
      Received r,
      String sha,
      String messageId,
      InboundAnalysis analysis,
      String outcome,
      String reason,
      Long ticketId,
      boolean newTicket,
      BlockListMatcher.BlockMatch block,
      Instant now) {
    Optional<Long> id =
        inbound.insert(
            scope,
            row(mb, r, sha, messageId, analysis, outcome, reason, ticketId, newTicket, block, now)
                .withRaw(r.raw()));
    if (id.isEmpty()) {
      // ON CONFLICT DO NOTHING: another poller committed this Message-ID or UID first. Undo
      // everything this transaction did (ticket, comment, outbox) rather than keep a second copy.
      throw new DuplicateMailException();
    }
    return id.get();
  }

  /** A DUPLICATE log row: no raw copy, nothing else written. */
  private void logDuplicate(
      AccessScope scope,
      MailboxContext mb,
      Received r,
      String sha,
      String messageId,
      InboundAnalysis analysis,
      Instant now) {
    inbound.insert(
        scope, row(mb, r, sha, messageId, analysis, "DUPLICATE", null, null, false, null, now));
  }

  private NewInboundMail row(
      MailboxContext mb,
      Received r,
      String sha,
      String messageId,
      InboundAnalysis analysis,
      String outcome,
      String reason,
      Long ticketId,
      boolean newTicket,
      BlockListMatcher.BlockMatch block,
      Instant now) {
    InboundAnalysis.Analysed a = analysis instanceof InboundAnalysis.Analysed x ? x : null;
    InboundMessage m = a == null ? null : a.message();
    String from = m == null || m.from() == null ? null : cut(m.from().address(), 320);
    String subject = m == null ? null : cut(m.subject(), 250);
    if (m == null && r.tooLarge()) {
      // Over 30 MB: sender and subject from the headers of the truncated copy (Blocked mail log).
      HeaderPeek peek = HeaderPeek.of(r.raw());
      from = peek.from() == null ? null : cut(peek.from().address(), 320);
      subject = cut(peek.subject(), 250);
    }
    return new NewInboundMail(
        mb.mailboxId(),
        r.uidValidity(),
        r.uid(),
        messageId,
        sha,
        r.size(),
        null,
        now.plus(block != null ? BLOCKED_RETENTION : RAW_RETENTION),
        from,
        subject,
        now,
        outcome,
        reason,
        ticketId,
        newTicket,
        a != null && a.authentication().dmarcPass(),
        block == null ? null : block.kind().name(),
        block == null ? null : cut(block.rule(), 320));
  }

  // ---------------------------------------------------------------------------------------------
  // Content helpers

  static String messageIdOf(InboundAnalysis analysis, String sha) {
    if (analysis instanceof InboundAnalysis.Analysed a) {
      String id = a.message().messageId();
      if (id != null && !id.isBlank() && id.length() <= 998) {
        return id.strip();
      }
    }
    return "<" + sha + "@no-message-id.invalid>";
  }

  /** The new mail's content: the whole (sanitised) message, forwards included. */
  public static String newTicketBody(InboundAnalysis.Analysed a) {
    String html = a.safeHtml().html();
    if (html != null && !html.isBlank() && html.length() <= MAX_BODY_HTML) {
      return html;
    }
    String text = a.message().textBody();
    if (text == null || text.isBlank()) {
      text = a.replyText();
    }
    return textToHtml(text);
  }

  /** A reply's content: quoted history stripped (the full mail stays in the raw .eml). */
  public static String replyBody(InboundAnalysis.Analysed a) {
    String html = a.safeReplyHtml().html();
    if (html != null && !html.isBlank() && html.length() <= MAX_BODY_HTML) {
      return html;
    }
    return textToHtml(a.replyText());
  }

  static String textToHtml(String text) {
    if (text == null || text.isBlank()) {
      return "<p>(no text)</p>";
    }
    String t = text.strip();
    String note = "";
    if (t.length() > MAX_BODY_TEXT) {
      t = t.substring(0, MAX_BODY_TEXT);
      note = "<p>[Message shortened - the full email is kept for 90 days.]</p>";
    }
    StringBuilder sb = new StringBuilder();
    for (String para : t.split("\\R\\s*\\R")) {
      sb.append("<p>")
          .append(HtmlUtils.htmlEscape(para.strip(), "UTF-8").replaceAll("\\R", "<br>"))
          .append("</p>");
    }
    return sb + note;
  }

  static String subject(String raw) {
    String s = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").strip();
    if (s.isEmpty()) {
      return "(no subject)";
    }
    return s.length() > 250 ? s.substring(0, 250) : s;
  }

  static String contactName(InboundAnalysis.Analysed a) {
    InboundAddress from = a.message().from();
    String name = from.displayName();
    if (name == null || name.isBlank()) {
      name = from.localPart();
    }
    name = name.replaceAll("\\p{Cntrl}", " ").strip();
    return name.length() > 200 ? name.substring(0, 200) : name;
  }

  static String authSummary(AuthenticationResults r) {
    return "authenticated="
        + r.authenticated()
        + "; dmarc="
        + r.dmarc().name().toLowerCase(Locale.ROOT)
        + "; spf="
        + r.spf().name().toLowerCase(Locale.ROOT)
        + "; dkim="
        + r.dkim().name().toLowerCase(Locale.ROOT);
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  static String sha256(byte[] raw) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
