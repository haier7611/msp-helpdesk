package com.ticketsystem.api.ticket;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.mail.ticketmail.SupportAddresses;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.CcRecipient;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ParticipantReason;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository.ParticipantRow;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository.Account;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The CC an agent chooses for a public reply in the composer ("To: reporter CC: ... Edit"). The
 * reply request carries the final CC list; every rule is checked here, on the server, inside the
 * reply's transaction and under the ticket lock:
 *
 * <ul>
 *   <li>valid, plain addresses only (no display names, no header characters); the reporter is
 *       dropped from CC (they are already in To);
 *   <li>at most {@value #MAX_CC} addresses on a reply, and at most {@value #MAX_CC} participants on
 *       a ticket when the agent adds someone who stays;
 *   <li>a contact of the ticket's organisation or an address on one of its verified domains is
 *       added at once; any other address needs the agent's explicit confirmation ({@code
 *       confirmExternal}), which counts as the external-CC confirmation;
 *   <li>an agent or admin account, a deactivated contact of the organisation or one of our own
 *       support mailboxes is refused with a message that names no organisation; a contact of
 *       another organisation is treated exactly like any outside address (see {@link
 *       #accountKind}), so an agent cannot learn that an address is registered with another client;
 *   <li>"Keep on future replies" on: removed participants leave the ticket and added ones join it;
 *       off: the participants stay as they are and the change applies to this reply only (stored
 *       with the reply). Every change is a history line with the agent as actor.
 * </ul>
 *
 * Reserved test domains are never sent through a real mailbox: that guard sits in the outbox worker
 * and applies to To and CC alike, so nothing extra is needed here.
 */
@Service
public class ReplyCcService {

  public static final int MAX_CC = 10;

  /**
   * Plain {@code local@domain} only: stricter than {@code Emails.normalise} so no quote, comma,
   * angle bracket, semicolon or whitespace can reach a mail header.
   */
  private static final Pattern PLAIN_ADDRESS =
      Pattern.compile(
          "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
              + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");

  /** The reply request's CC choice; {@code keepOnFutureReplies} null = on (the default). */
  public record ReplyCc(
      List<String> recipients, Boolean keepOnFutureReplies, List<String> confirmExternal) {}

  /** One address of the final CC list and how it was classified. */
  record Target(String email, Long userId, Kind kind, boolean participantUnconfirmed) {}

  enum Kind {
    /** Already a confirmed participant. */
    EXISTING,
    /** A contact of the organisation or an address on its verified domains. */
    SAME_ORGANISATION,
    /** Outside the organisation, confirmed by the agent in this request. */
    EXTERNAL
  }

  /** A checked choice, applied once the reply is stored. */
  public record Plan(boolean keep, List<Target> targets, List<ParticipantRow> leftOut) {}

  private final TicketParticipantRepository participants;
  private final TicketPeopleRepository people;
  private final PublicCommentRepository comments;
  private final SupportAddresses addresses;

  public ReplyCcService(
      TicketParticipantRepository participants,
      TicketPeopleRepository people,
      PublicCommentRepository comments,
      SupportAddresses addresses) {
    this.participants = participants;
    this.people = people;
    this.comments = comments;
    this.addresses = addresses;
  }

  /** Checks the choice against every rule; throws a 400 with a plain message when one fails. */
  public Plan plan(AccessScope scope, TicketRow t, ReplyCc cc) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    if (t.organisationId() == null) {
      throw ApiException.invalidRequest(
          "Choose the organisation for this ticket before changing who is in CC.");
    }
    long org = t.organisationId();
    scope.require(org);
    List<String> raw = cc.recipients() == null ? List.of() : cc.recipients();
    if (raw.size() > MAX_CC * 3) {
      throw limit();
    }
    Set<String> confirmed = new LinkedHashSet<>();
    if (cc.confirmExternal() != null) {
      if (cc.confirmExternal().size() > MAX_CC * 3) {
        throw limit();
      }
      for (String a : cc.confirmExternal()) {
        confirmed.add(normalise(a));
      }
    }
    String reporter = reporterEmail(scope, t);
    Set<String> wanted = new LinkedHashSet<>();
    for (String a : raw) {
      String email = normalise(a);
      if (!email.equals(reporter)) {
        wanted.add(email);
      }
    }
    if (wanted.size() > MAX_CC) {
      throw limit();
    }

    Map<String, ParticipantRow> current = new LinkedHashMap<>();
    for (ParticipantRow p : participants.listForTicket(scope, t.id())) {
      if (p.email() != null) {
        current.put(p.email().toLowerCase(Locale.ROOT), p);
      }
    }
    String orgName = null;
    List<Target> targets = new ArrayList<>();
    for (String email : wanted) {
      ParticipantRow existing = current.get(email);
      if (existing != null && existing.confirmed()) {
        targets.add(new Target(email, existing.userId(), Kind.EXISTING, false));
        continue;
      }
      boolean unconfirmed = existing != null;
      if (addresses.isOurs(scope, email)) {
        throw notAllowed(email);
      }
      Account account = people.accountByEmail(scope, org, email).orElse(null);
      AccountKind accountKind = accountKind(account, org);
      if (accountKind == AccountKind.CONTACT) {
        targets.add(new Target(email, account.userId(), Kind.SAME_ORGANISATION, unconfirmed));
      } else if (accountKind != AccountKind.NONE) {
        throw notAllowed(email);
      } else if (people.isVerifiedDomainOf(scope, org, domain(email))) {
        targets.add(new Target(email, null, Kind.SAME_ORGANISATION, unconfirmed));
      } else if (confirmed.contains(email)) {
        targets.add(new Target(email, null, Kind.EXTERNAL, unconfirmed));
      } else {
        if (orgName == null) {
          orgName = people.organisationName(scope, org).orElse("this organisation");
        }
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "external_cc_unconfirmed",
            email + " is not from " + orgName + ". Confirm to add them anyway.");
      }
    }

    List<ParticipantRow> leftOut = new ArrayList<>();
    for (Map.Entry<String, ParticipantRow> e : current.entrySet()) {
      if (e.getValue().confirmed() && !wanted.contains(e.getKey())) {
        leftOut.add(e.getValue());
      }
    }
    boolean keep = cc.keepOnFutureReplies() == null || cc.keepOnFutureReplies();
    if (keep) {
      long joining =
          targets.stream()
              .filter(x -> x.kind() != Kind.EXISTING && !x.participantUnconfirmed())
              .count();
      if (joining > 0 && current.size() - leftOut.size() + joining > MAX_CC) {
        throw limit();
      }
    }
    return new Plan(keep, List.copyOf(targets), List.copyOf(leftOut));
  }

  /**
   * The answer of the composer's early CC check (spec "CC on a reply"): a reason code only, never a
   * name or organisation, so an agent learns no more than a refused reply already tells them.
   */
  public enum CcCheck {
    /** Already in CC, a contact of the organisation or on its verified domains. */
    ALLOWED,
    /**
     * Outside the organisation, including another organisation's contact (so the answer does not
     * reveal an account): allowed once the agent confirms ("Add anyway").
     */
    EXTERNAL,
    /** An agent or admin account: never a client CC. */
    STAFF,
    /** A deactivated contact or a support mailbox: refused (cc_not_allowed at send time). */
    NOT_ALLOWED
  }

  /**
   * Classifies one address for ticket {@code t} with the same rules as {@link #plan}, without
   * changing anything; an invalid address is a 400 like at send time.
   */
  public CcCheck check(AccessScope scope, TicketRow t, String raw) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    if (t.organisationId() == null) {
      throw ApiException.invalidRequest(
          "Choose the organisation for this ticket before changing who is in CC.");
    }
    long org = t.organisationId();
    scope.require(org);
    String email = normalise(raw);
    for (ParticipantRow p : participants.listForTicket(scope, t.id())) {
      if (p.confirmed() && email.equalsIgnoreCase(p.email())) {
        return CcCheck.ALLOWED;
      }
    }
    if (addresses.isOurs(scope, email)) {
      return CcCheck.NOT_ALLOWED;
    }
    Account account = people.accountByEmail(scope, org, email).orElse(null);
    switch (accountKind(account, org)) {
      case STAFF -> {
        return CcCheck.STAFF;
      }
      case CONTACT -> {
        return CcCheck.ALLOWED;
      }
      case REFUSED -> {
        return CcCheck.NOT_ALLOWED;
      }
      case NONE -> {
        // no account, or one the agent may not learn about: decided by the address alone
      }
    }
    return people.isVerifiedDomainOf(scope, org, domain(email))
        ? CcCheck.ALLOWED
        : CcCheck.EXTERNAL;
  }

  /** Applies a checked plan to reply {@code commentId}: participants, history, the reply's CC. */
  public void apply(AccessScope scope, TicketRow t, long commentId, Plan plan, Instant now) {
    long org = t.organisationId();
    List<CcRecipient> sent = new ArrayList<>();
    for (ParticipantRow p : plan.leftOut()) {
      if (plan.keep()) {
        participants.remove(scope, org, t.id(), p.email(), now, ParticipantReason.AGENT_REMOVED);
      } else {
        participants.recordReplyOnly(
            scope, org, t.id(), false, p.userId(), p.email(), ParticipantReason.REPLY_ONLY, now);
      }
    }
    for (Target x : plan.targets()) {
      boolean external = x.kind() == Kind.EXTERNAL;
      if (x.kind() == Kind.EXISTING) {
        sent.add(new CcRecipient(x.email(), x.userId(), false));
      } else if (plan.keep()) {
        if (x.participantUnconfirmed()) {
          participants.confirm(scope, org, t.id(), x.email(), now);
        } else {
          participants.add(
              scope,
              org,
              t.id(),
              x.userId(),
              x.email(),
              true,
              now,
              external ? ParticipantReason.AGENT_ADDED_EXTERNAL : ParticipantReason.AGENT_ADDED);
        }
        sent.add(new CcRecipient(x.email(), x.userId(), false));
      } else {
        participants.recordReplyOnly(
            scope,
            org,
            t.id(),
            true,
            x.userId(),
            x.email(),
            external ? ParticipantReason.REPLY_ONLY_EXTERNAL : ParticipantReason.REPLY_ONLY,
            now);
        sent.add(new CcRecipient(x.email(), x.userId(), true));
      }
    }
    comments.saveChosenCc(scope, org, commentId, sent);
  }

  /** How an address that belongs to an account is treated as a CC of a ticket. */
  private enum AccountKind {
    /** No account, or one the agent may not learn about: treated like any other address. */
    NONE,
    /** An active or invited contact of the ticket's organisation: added at once. */
    CONTACT,
    /** An agent or admin: never a client CC. */
    STAFF,
    /** A deactivated contact of the ticket's organisation: refused. */
    REFUSED
  }

  /**
   * Account-existence disclosure: a customer account of another organisation, in any status, is
   * answered exactly like an address with no account - outside the organisation unless on one of
   * its verified domains, allowed once the agent confirms it - so neither the CC check nor the
   * reply tells an agent that an address is registered with another client. Such an address is then
   * a normal outside participant of this ticket: stored by address only (never linked to the
   * account), emailed like any confirmed outside CC, and its email replies thread onto this ticket
   * as an outside participant's. Staff accounts stay refused (agents already see the staff list),
   * and so does a deactivated contact of the ticket's own organisation (the agent can see that
   * organisation's contacts).
   */
  private static AccountKind accountKind(Account account, long org) {
    if (account == null) {
      return AccountKind.NONE;
    }
    if ("AGENT".equals(account.role()) || "ADMIN".equals(account.role())) {
      return AccountKind.STAFF;
    }
    if (!"CUSTOMER".equals(account.role()) || !Long.valueOf(org).equals(account.organisationId())) {
      return AccountKind.NONE;
    }
    return "ACTIVE".equals(account.status()) || "INVITED".equals(account.status())
        ? AccountKind.CONTACT
        : AccountKind.REFUSED;
  }

  private String reporterEmail(AccessScope scope, TicketRow t) {
    if (t.reporterUserId() != null) {
      return people
          .customerOf(scope, t.reporterUserId(), t.organisationId())
          .map(c -> c.email().toLowerCase(Locale.ROOT))
          .orElse(null);
    }
    return t.reporterEmail() == null ? null : t.reporterEmail().toLowerCase(Locale.ROOT);
  }

  static String normalise(String raw) {
    String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    if (v.length() > 254 || !PLAIN_ADDRESS.matcher(v).matches()) {
      String shown = v.length() > 80 ? v.substring(0, 80) + "..." : v;
      throw ApiException.invalidRequest(
          (shown.isEmpty() ? "An empty value" : shown) + " is not a valid email address.");
    }
    return v;
  }

  private static String domain(String email) {
    return email.substring(email.lastIndexOf('@') + 1);
  }

  private static ApiException limit() {
    return new ApiException(
        HttpStatus.BAD_REQUEST,
        "cc_limit",
        "A ticket can have at most " + MAX_CC + " people in CC. Remove someone first.");
  }

  private static ApiException notAllowed(String email) {
    return new ApiException(
        HttpStatus.BAD_REQUEST,
        "cc_not_allowed",
        email + " cannot be copied on replies to this ticket.");
  }
}
