package com.ticketsystem.api.sla.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.notification.TicketNotificationService;
import com.ticketsystem.api.sla.ticket.TicketSlaService;
import com.ticketsystem.api.ticket.assignment.AutoAssignmentService;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.event.TicketEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to ticket events after they commit (notification dispatch listens to domain events after
 * commit): auto-assignment of a new ticket, then SLA recalculation (which also sends the P1 alert),
 * and the per-event supporter notifications. Each step runs in its own transaction under a SYSTEM
 * scope - this is background work on behalf of the system, not of the user who caused the event,
 * like the minute job it shares the code with. A failure is logged (ids only) and never undoes the
 * ticket change; the minute job catches up on anything missed (auto-assignment and stale SLA values
 * are both on its work list).
 */
@Component
public class TicketEventReactor {

  private static final Logger log = LoggerFactory.getLogger(TicketEventReactor.class);

  private final AutoAssignmentService autoAssignment;
  private final TicketSlaService sla;
  private final TicketNotificationService notifications;
  private final TicketRepository tickets;

  public TicketEventReactor(
      AutoAssignmentService autoAssignment,
      TicketSlaService sla,
      TicketNotificationService notifications,
      TicketRepository tickets) {
    this.autoAssignment = autoAssignment;
    this.sla = sla;
    this.notifications = notifications;
    this.tickets = tickets;
  }

  @TransactionalEventListener
  public void on(TicketEvent event) {
    AccessScope system = AccessScope.system("ticket event " + event.getClass().getSimpleName());
    long id = event.ticketId();
    try {
      switch (event) {
        case TicketEvent.TicketCreated c -> {
          // Created already assigned (agent "New ticket"): tell the assignee unless it is the
          // creator. An auto-assignment below notifies through its own AssigneeChanged event.
          tickets
              .find(system, id)
              .filter(t -> t.assigneeUserId() != null)
              .ifPresent(
                  t -> notifications.assigned(system, id, t.assigneeUserId(), c.actorUserId()));
          autoAssignment.applyInNewTransaction(system, id);
          sla.recalculateInNewTransaction(system, id);
        }
        case TicketEvent.PriorityChanged p -> sla.recalculateAfterPriorityChange(system, id);
        case TicketEvent.StatusChanged s -> sla.recalculateInNewTransaction(system, id);
        case TicketEvent.PublicReplyAdded r -> sla.recalculateInNewTransaction(system, id);
        case TicketEvent.OrganisationChanged o ->
            sla.recalculateAfterOrganisationChange(system, id);
        case TicketEvent.ClientReplyAdded c ->
            notifications.clientReplied(system, id, c.commentId());
        case TicketEvent.InternalNoteAdded n ->
            notifications.internalNoteAdded(system, id, n.noteId(), n.authorUserId());
        case TicketEvent.AssigneeChanged a ->
            notifications.assigned(system, id, a.toUserId(), a.actorUserId());
        // A monitoring comment on an alert; a repeat on a Resolved ticket also arrives as a
        // StatusChanged (ALERT_REPEAT), which recalculates. Alerts notify supporters nowhere else.
        case TicketEvent.AlertCommentAdded a -> {}
        case TicketEvent.MarkedDuplicate d -> {}
        case TicketEvent.TicketsLinked l -> {}
      }
    } catch (RuntimeException e) {
      log.warn(
          "WP-09 reaction to {} on ticket {} failed ({}); the minute job will catch up",
          event.getClass().getSimpleName(),
          id,
          e.getClass().getSimpleName());
    }
  }
}
