package com.ticketsystem.api.sla.ticket;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.SystemEmail;
import com.ticketsystem.api.organisation.clientemail.ClientEmail;
import com.ticketsystem.api.organisation.clientemail.ClientEmailSwitches;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.sla.BusinessCalendar;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.SlaTicketRow;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.sla.config.SlaConfigTypes.SettingsRow;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Pending timers, per organisation (each can be switched off; an admin can turn the reminder
 * email and the Closed notice off per organisation - the auto-close still runs and a timeline line
 * says the email was not sent):
 *
 * <ul>
 *   <li><b>Reminder</b>: a ticket in Pending on client for N business days (default 3) gets one
 *       reminder email to the client per Pending spell.
 *   <li><b>Auto-resolve</b>: after M business days (default 7) in Pending on client it is set to
 *       Resolved (trigger AUTO_RESOLVE; the ticket mail listener sends the "Resolved" email from
 *       that event).
 *   <li><b>Auto-close</b>: after K calendar days (default 5) in Resolved it is Closed (AUTO_CLOSE)
 *       and the client gets the "Closed" notice.
 * </ul>
 *
 * Business days are measured as business time on the organisation's calendar (one business day =
 * the longest weekday's hours), from the moment the ticket entered the status. Pending on vendor is
 * never reminded or auto-resolved. API alert tickets email the client only when the organisation
 * opted in; automated senders never get one. Each ticket is handled in its own transaction with its
 * row locked, and every condition is checked again under the lock, so a repeat or concurrent run
 * changes nothing.
 */
@Service
public class PendingTimersService {

  /** What a timer did (for tests and logs). */
  public enum Action {
    NONE,
    REMINDED,
    REMINDED_WITHOUT_EMAIL,
    /** The reminder was due but the organisation turned "Pending reminder" off. */
    REMINDER_TURNED_OFF,
    AUTO_RESOLVED,
    AUTO_CLOSED
  }

  private final TicketSlaRepository slaTickets;
  private final TicketRepository tickets;
  private final TicketService ticketService;
  private final SlaConfigService config;
  private final ClientNoticeMailer mailer;
  private final ReferenceService references;
  private final TicketHistoryRepository history;
  private final ClientEmailSwitches switches;
  private final Clock clock;

  public PendingTimersService(
      TicketSlaRepository slaTickets,
      TicketRepository tickets,
      TicketService ticketService,
      SlaConfigService config,
      ClientNoticeMailer mailer,
      ReferenceService references,
      TicketHistoryRepository history,
      ClientEmailSwitches switches,
      Clock clock) {
    this.slaTickets = slaTickets;
    this.tickets = tickets;
    this.ticketService = ticketService;
    this.config = config;
    this.mailer = mailer;
    this.references = references;
    this.history = history;
    this.switches = switches;
    this.clock = clock;
  }

  /** Pending-on-client reminder and auto-resolve for one ticket. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Action processPending(AccessScope scope, long ticketId) {
    requireSystem(scope);
    Optional<SlaTicketRow> found = slaTickets.lock(scope, ticketId);
    // Triage tickets have no organisation timer settings (they get an SLA only).
    if (found.isEmpty()
        || found.get().organisationId() == null
        || found.get().status() != TicketStatus.PENDING_ON_CLIENT) {
      return Action.NONE;
    }
    SlaTicketRow t = found.get();
    SettingsRow s = config.settings(scope, t.organisationId());
    Instant now = clock.instant();
    if (now.isBefore(t.statusChangedAt())) {
      return Action.NONE;
    }
    BusinessCalendar calendar = config.calendar(scope, t.organisationId());
    long dayMinutes = calendar.businessDayMinutes();
    long waited = calendar.businessMinutesBetween(t.statusChangedAt(), now);
    if (s.autoResolveEnabled() && waited >= s.autoResolveBusinessDays() * dayMinutes) {
      ticketService.applySystemTrigger(scope, t.id(), Trigger.AUTO_RESOLVE);
      return Action.AUTO_RESOLVED;
    }
    boolean remindedThisSpell =
        t.pendingReminderSentAt() != null
            && !t.pendingReminderSentAt().isBefore(t.statusChangedAt());
    if (!remindedThisSpell && waited >= s.pendingReminderBusinessDays() * dayMinutes) {
      // The spell's reminder moment is marked either way, so a switched-off reminder writes its
      // timeline line once per Pending spell, like a sent one.
      slaTickets.markReminderSent(scope, t.id(), t.organisationId(), now);
      if (!s.pendingReminderEnabled()) {
        // "Pending reminder" off (the same stored switch as before, V61).
        if (clientEmailAllowed(t.source(), s) && wouldEmail(scope, t.id())) {
          switches.recordSkipped(scope, t.organisationId(), t.id(), ClientEmail.PENDING_REMINDER);
        }
        return Action.REMINDER_TURNED_OFF;
      }
      boolean emailed =
          clientEmailAllowed(t.source(), s)
              && sendClientNotice(scope, t.id(), SystemEmail.CLIENT_PENDING_REMINDER);
      history.record(
          scope,
          t.organisationId(),
          t.id(),
          HistoryType.CLIENT_REMINDER_SENT,
          null,
          null,
          emailed ? "emailed" : "no client email",
          now);
      return emailed ? Action.REMINDED : Action.REMINDED_WITHOUT_EMAIL;
    }
    return Action.NONE;
  }

  /** Resolved -> Closed after the organisation's calendar days, with the Closed notice. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Action processResolved(AccessScope scope, long ticketId) {
    requireSystem(scope);
    Optional<SlaTicketRow> found = slaTickets.lock(scope, ticketId);
    if (found.isEmpty()
        || found.get().organisationId() == null
        || found.get().status() != TicketStatus.RESOLVED) {
      return Action.NONE;
    }
    SlaTicketRow t = found.get();
    SettingsRow s = config.settings(scope, t.organisationId());
    Instant now = clock.instant();
    if (!s.autoCloseEnabled()
        || now.isBefore(t.statusChangedAt().plus(Duration.ofDays(s.autoCloseCalendarDays())))) {
      return Action.NONE;
    }
    ticketService.applySystemTrigger(scope, t.id(), Trigger.AUTO_CLOSE);
    if (clientEmailAllowed(t.source(), s)) {
      if (switches.isOn(scope, t.organisationId(), ClientEmail.CLOSED_NOTICE)) {
        sendClientNotice(scope, t.id(), SystemEmail.CLIENT_TICKET_CLOSED);
      } else if (wouldEmail(scope, t.id())) {
        // "Closed notice" off; the ticket still closes.
        switches.recordSkipped(scope, t.organisationId(), t.id(), ClientEmail.CLOSED_NOTICE);
      }
    }
    return Action.AUTO_CLOSED;
  }

  /**
   * Candidates for {@link #processPending}: every ticket in Pending on client (one business day can
   * pass in less than a calendar day, so there is no cheaper pre-filter).
   */
  @Transactional(readOnly = true)
  public List<Long> pendingCandidates(AccessScope scope, int limit) {
    return slaTickets
        .inStatusSince(scope, TicketStatus.PENDING_ON_CLIENT, clock.instant(), limit)
        .stream()
        .map(r -> r.id())
        .toList();
  }

  /** Candidates for {@link #processResolved}: in Resolved for at least one day. */
  @Transactional(readOnly = true)
  public List<Long> resolvedCandidates(AccessScope scope, int limit) {
    return slaTickets
        .inStatusSince(
            scope, TicketStatus.RESOLVED, clock.instant().minus(Duration.ofDays(1)), limit)
        .stream()
        .map(r -> r.id())
        .toList();
  }

  /** API alert tickets email the client only when the organisation opted in. */
  static boolean clientEmailAllowed(TicketSource source, SettingsRow s) {
    return source != TicketSource.API || s.apiAlertClientEmails();
  }

  /**
   * Would a client notice really be queued (a deliverable recipient, no bulk/automated sender
   * suppression)? The "not sent - turned off" line is written only then.
   */
  private boolean wouldEmail(AccessScope scope, long ticketId) {
    return mailer.wouldSend(scope, slaTickets.clientRecipients(scope, ticketId), ticketId);
  }

  private boolean sendClientNotice(AccessScope scope, long ticketId, SystemEmail template) {
    String subject = tickets.find(scope, ticketId).map(r -> r.subject()).orElse(null);
    TicketReference ref = references.displayReferences(scope, List.of(ticketId)).get(ticketId);
    return mailer.send(
        scope,
        template,
        slaTickets.clientRecipients(scope, ticketId),
        ticketId,
        ref == null ? null : ref.toString(),
        subject);
  }

  private static void requireSystem(AccessScope scope) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
  }
}
