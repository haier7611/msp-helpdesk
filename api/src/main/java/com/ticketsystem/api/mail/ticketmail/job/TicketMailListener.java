package com.ticketsystem.api.mail.ticketmail.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.ticketmail.TicketMailComposer;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.event.TicketEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns ticket events into ticket email by the client email matrix, inside the ticket transaction
 * just before it commits (BEFORE_COMMIT), so the outbox row commits or rolls back with the change
 * that caused it.
 *
 * <ul>
 *   <li>Ticket opened by an agent for a client: the agent's message, emailed like a public reply.
 *   <li>Ticket created (portal, follow-up by an agent): confirmation. Email tickets are confirmed
 *       by the inbound processor, which knows about loop safety. API alert tickets get it only when
 *       the organisation opted in (below).
 *   <li>Public reply by a supporter (agent UI or email): the reply.
 *   <li>Set to Resolved (by an agent or the auto-resolve job): Resolved notice.
 *   <li>Closed by the auto-close job: the Closed notice is sent by the {@code
 *       PendingTimersService}, not here (one notice only). Other closes send nothing.
 *   <li>Client replied: the assignee's "client replied" email is the notification service's (it
 *       follows the supporter's stored choices and is agent-only with Reply-To {@code
 *       support+internal@}), so this listener sends no second copy.
 * </ul>
 *
 * API alert tickets (source API): no client mail of any kind (Created, public reply, Resolved)
 * unless the organisation's {@code api_alert_client_emails} opt-in (default off) is on; one source
 * of truth, the same flag the timer notices read.
 *
 * <p>Internal notes, assignment, priority, Pending on vendor and every other event send nothing to
 * the client. Events carry no scope (they can come from a request or a job), so this job reads the
 * ticket with a SYSTEM scope; it lives in a {@code ..job..} package as ArchitectureTest requires. A
 * failure to build an email is logged by ticket id and never fails the ticket change.
 */
@Component
class TicketMailListener {

  private static final Logger log = LoggerFactory.getLogger(TicketMailListener.class);

  private final TicketMailComposer composer;
  private final TicketRepository tickets;
  private final SlaConfigService slaConfig;

  TicketMailListener(
      TicketMailComposer composer, TicketRepository tickets, SlaConfigService slaConfig) {
    this.composer = composer;
    this.tickets = tickets;
    this.slaConfig = slaConfig;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  void on(TicketEvent event) {
    try {
      switch (event) {
        case TicketEvent.TicketCreated c when openedByAnAgent(c) ->
            composer.agentOpened(scope("agent opened ticket mail"), c.ticketId(), c.actorUserId());
        case TicketEvent.TicketCreated c
            when c.source() != TicketSource.EMAIL && clientMailAllowed(c.ticketId()) ->
            composer.confirmation(scope("ticket created mail"), c.ticketId());
        case TicketEvent.PublicReplyAdded r when clientMailAllowed(r.ticketId()) ->
            composer.publicReply(scope("public reply mail"), r.ticketId(), r.commentId());
        case TicketEvent.StatusChanged s
            when s.to() == TicketStatus.RESOLVED && clientMailAllowed(s.ticketId()) ->
            composer.resolved(scope("resolved mail"), s.ticketId());
        default -> {
          // Not in the client email matrix: nothing is sent.
        }
      }
    } catch (RuntimeException e) {
      log.warn(
          "ticket mail for ticket {} not queued ({})",
          event.ticketId(),
          e.getClass().getSimpleName());
    }
  }

  /**
   * A ticket an agent opened for a client (an agent's own "New ticket") is emailed like that
   * agent's public reply. A follow-up an agent starts by hand has no message of the agent's and
   * keeps the confirmation.
   */
  private static boolean openedByAnAgent(TicketEvent.TicketCreated c) {
    return c.source() == TicketSource.AGENT
        && c.followUpOfId() == null
        && c.actorUserId() != null
        && c.organisationId() != null;
  }

  /** API alert tickets email the client only when the organisation opted in. */
  private boolean clientMailAllowed(long ticketId) {
    AccessScope system = scope("client mail opt-in check");
    return tickets
        .find(system, ticketId)
        .map(
            t ->
                t.source() != TicketSource.API
                    || slaConfig.settings(system, t.organisationId()).apiAlertClientEmails())
        .orElse(false);
  }

  private static AccessScope scope(String reason) {
    return AccessScope.system(reason);
  }
}
