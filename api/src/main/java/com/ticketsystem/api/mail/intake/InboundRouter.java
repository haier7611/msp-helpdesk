package com.ticketsystem.api.mail.intake;

import com.ticketsystem.api.mail.inbound.BlockListMatcher.BlockMatch;
import com.ticketsystem.api.mail.inbound.PlusAddress.MailboxTarget;
import com.ticketsystem.api.sla.TicketStatus;

/**
 * Decides what happens to one inbound email from facts gathered by {@link InboundMailProcessor}
 * (C9-C11). Pure logic: no database, no mail.
 *
 * <p>Order: block list (also for mail too large to parse) -> unparseable -> matched ticket
 * (reference, then thread headers) with the sender and DMARC guards -> agent-only address ->
 * per-sender cap -> sender rule -> known contact -> verified domain -> Email triage. Every path
 * ends in a ticket or a comment except a block.
 */
public final class InboundRouter {

  /** New mails from one sender in an hour that may each open a ticket. */
  public static final int NEW_TICKETS_PER_HOUR = 20;

  private InboundRouter() {}

  /** The admin's "Supporter replies by email" setting. */
  public enum ReplyMode {
    OFF,
    INTERNAL_ONLY,
    NOTES_AND_PUBLIC
  }

  /** Why a mail went to Email triage; the label is shown to supporters. */
  public enum TriageReason {
    NOT_AUTHENTICATED("Sender not authenticated"),
    NOT_A_PARTICIPANT("Not a participant of the ticket"),
    REFERENCE_NOT_FOUND("Reference not found"),
    REFERENCE_OTHER_ORGANISATION("Reference belongs to another organisation"),
    UNKNOWN_SENDER("Unknown sender"),
    NOT_A_SUPPORTER("Sent to the internal address by someone who is not an agent"),
    NO_TICKET_FOR_INTERNAL_REPLY("Internal reply without a ticket reference"),
    SUPPORTER_REPLIES_OFF("Agent replies by email are turned off"),
    SUPPORTER_PUBLIC_REPLIES_OFF("Agent email replies to clients are not allowed"),
    TICKET_CLOSED("Agent reply to a closed ticket"),
    UNPARSEABLE("Email could not be read"),
    PROCESSING_FAILED("Email could not be processed");

    private final String label;

    TriageReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** How the sender relates to the installation. */
  public enum SenderKind {
    /** An active agent or admin. */
    SUPPORTER,
    /** A saved contact (active or invited customer). */
    CONTACT,
    /** Anyone else. */
    UNKNOWN
  }

  /**
   * The ticket a mail refers to.
   *
   * @param organisationId null while the ticket is in triage
   * @param internalThread matched through In-Reply-To/References of an agent-only notification
   * @param senderIsReporterOrParticipant the sender is the reporter, a confirmed participant
   * @param supporterHasAccess the sender is an active supporter who may work this organisation
   */
  public record TicketMatch(
      long ticketId,
      Long organisationId,
      TicketStatus status,
      boolean internalThread,
      boolean senderIsReporterOrParticipant,
      boolean supporterHasAccess) {}

  /** A sender rule that applies. */
  public record RuleMatch(long organisationId, long requestTypeId) {}

  /**
   * One sender's new-ticket mails in the last hour, within one authentication class (DMARC pass, or
   * not). The two classes never share a cap or a fold-in target.
   *
   * @param latestTicket the newest ticket opened by this class of mail; only looked up once the cap
   *     is reached
   * @param latestInTriage that ticket is still in Email triage
   */
  public record SenderHistory(int newTicketsLastHour, Long latestTicket, boolean latestInTriage) {
    public static final SenderHistory NONE = new SenderHistory(0, null, false);
  }

  /**
   * Everything the decision needs.
   *
   * @param referenceNotFound the subject had reference candidates but none resolved
   * @param contactOrganisationId the sender's organisation when they are a saved contact
   * @param authenticatedHistory the sender's DMARC-passing new-ticket mail this hour
   * @param unauthenticatedHistory the sender's other new-ticket mail this hour
   * @param domainOrganisationId the organisation whose verified domain is the sender's domain
   */
  public record Facts(
      boolean unparseable,
      BlockMatch block,
      MailboxTarget target,
      boolean dmarcPass,
      boolean automated,
      TicketMatch match,
      boolean referenceNotFound,
      SenderKind senderKind,
      Long supporterUserId,
      Long contactUserId,
      Long contactOrganisationId,
      ReplyMode mode,
      SenderHistory authenticatedHistory,
      SenderHistory unauthenticatedHistory,
      RuleMatch rule,
      Long domainOrganisationId) {

    /** The history of this mail's own authentication class. */
    SenderHistory ownClass() {
      SenderHistory h = dmarcPass ? authenticatedHistory : unauthenticatedHistory;
      return h == null ? SenderHistory.NONE : h;
    }
  }

  /** The outcome. */
  public sealed interface Decision {
    record Block(BlockMatch rule) implements Decision {}

    record Triage(TriageReason reason) implements Decision {}

    /** A client reply on the ticket (a Closed ticket gets a follow-up, F4). */
    record ClientReply(long ticketId) implements Decision {}

    /** An auto-reply or bulk mail on a matched ticket: a hidden system entry only. */
    record AutoReplyEntry(long ticketId) implements Decision {}

    record InternalNote(long ticketId, long supporterUserId) implements Decision {}

    record PublicReply(long ticketId, long supporterUserId) implements Decision {}

    /** The sender passed 20 new tickets this hour: added to their latest ticket instead. */
    record Fold(long ticketId) implements Decision {}

    /**
     * A new ticket in {@code organisationId}.
     *
     * @param reporterUserId the saved contact, or null (a contact is created when {@code
     *     createContact})
     * @param requestTypeId from a sender rule, or null for the default type
     */
    record Create(
        long organisationId, Long reporterUserId, boolean createContact, Long requestTypeId)
        implements Decision {}
  }

  public static Decision decide(Facts f) {
    // The block list first: it also applies to mail too large to parse.
    if (f.block() != null) {
      return new Decision.Block(f.block());
    }
    if (f.unparseable()) {
      return new Decision.Triage(TriageReason.UNPARSEABLE);
    }
    boolean internal = f.target() == MailboxTarget.INTERNAL;
    TicketMatch m = f.match();
    if (m != null) {
      return matched(f, m, internal || m.internalThread());
    }
    if (internal) {
      return new Decision.Triage(
          f.senderKind() == SenderKind.SUPPORTER
              ? TriageReason.NO_TICKET_FOR_INTERNAL_REPLY
              : TriageReason.NOT_A_SUPPORTER);
    }
    if (f.referenceNotFound()) {
      return new Decision.Triage(TriageReason.REFERENCE_NOT_FOUND);
    }
    return newMail(f);
  }

  private static Decision matched(Facts f, TicketMatch m, boolean internal) {
    if (f.senderKind() == SenderKind.SUPPORTER && m.supporterHasAccess()) {
      if (!f.dmarcPass()) {
        return new Decision.Triage(TriageReason.NOT_AUTHENTICATED);
      }
      if (f.automated()) {
        // A supporter's out-of-office or other automatic reply is never a note or a public reply:
        // a hidden system entry, like a client's auto-reply.
        return new Decision.AutoReplyEntry(m.ticketId());
      }
      if (f.mode() == ReplyMode.OFF) {
        return new Decision.Triage(TriageReason.SUPPORTER_REPLIES_OFF);
      }
      if (internal) {
        return new Decision.InternalNote(m.ticketId(), f.supporterUserId());
      }
      if (f.mode() != ReplyMode.NOTES_AND_PUBLIC) {
        return new Decision.Triage(TriageReason.SUPPORTER_PUBLIC_REPLIES_OFF);
      }
      if (m.status() == TicketStatus.CLOSED) {
        return new Decision.Triage(TriageReason.TICKET_CLOSED);
      }
      return new Decision.PublicReply(m.ticketId(), f.supporterUserId());
    }
    if (internal) {
      // support+internal@ is routing only, never trust: only an active supporter
      // with access may add a note.
      return new Decision.Triage(TriageReason.NOT_A_SUPPORTER);
    }
    // Another organisation's contact reaches this ticket only as one of its confirmed outside
    // participants (see docs/10-decision-log.md ADR-050); any other ticket of this organisation
    // goes to
    // triage, whatever the account's status.
    if (f.senderKind() == SenderKind.CONTACT
        && m.organisationId() != null
        && !m.organisationId().equals(f.contactOrganisationId())
        && !m.senderIsReporterOrParticipant()) {
      return new Decision.Triage(TriageReason.REFERENCE_OTHER_ORGANISATION);
    }
    if (!m.senderIsReporterOrParticipant()) {
      return new Decision.Triage(TriageReason.NOT_A_PARTICIPANT);
    }
    if (!f.dmarcPass()) {
      return new Decision.Triage(TriageReason.NOT_AUTHENTICATED);
    }
    if (f.automated()) {
      return new Decision.AutoReplyEntry(m.ticketId());
    }
    return new Decision.ClientReply(m.ticketId());
  }

  private static Decision newMail(Facts f) {
    SenderHistory h = f.ownClass();
    if (h.newTicketsLastHour() >= NEW_TICKETS_PER_HOUR
        && h.latestTicket() != null
        && (f.dmarcPass() || h.latestInTriage())) {
      // Counted and targeted within the mail's own authentication class: forged mail
      // never uses up the real sender's cap or redirects their mail into a forged triage ticket,
      // and an unauthenticated mail only folds into a triage ticket (C10).
      return new Decision.Fold(h.latestTicket());
    }
    if (f.rule() != null) {
      return f.dmarcPass()
          ? new Decision.Create(f.rule().organisationId(), null, false, f.rule().requestTypeId())
          : new Decision.Triage(TriageReason.NOT_AUTHENTICATED);
    }
    if (f.senderKind() == SenderKind.CONTACT && f.contactOrganisationId() != null) {
      return f.dmarcPass()
          ? new Decision.Create(f.contactOrganisationId(), f.contactUserId(), false, null)
          : new Decision.Triage(TriageReason.NOT_AUTHENTICATED);
    }
    if (f.senderKind() == SenderKind.UNKNOWN && f.domainOrganisationId() != null) {
      return f.dmarcPass()
          ? new Decision.Create(f.domainOrganisationId(), null, true, null)
          : new Decision.Triage(TriageReason.NOT_AUTHENTICATED);
    }
    return new Decision.Triage(TriageReason.UNKNOWN_SENDER);
  }
}
