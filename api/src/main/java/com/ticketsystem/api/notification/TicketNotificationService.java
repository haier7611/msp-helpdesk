package com.ticketsystem.api.notification;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.notification.NotificationService.Notice;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The event-driven notifications of My settings - Notifications (ticket assigned to me, client
 * replied on my ticket, internal note added on my ticket). Called after commit of the ticket event,
 * each in its own transaction, with a SYSTEM scope. Nobody is notified about their own action.
 * Triage tickets (no organisation) notify nobody, except that a triage ticket's assignee hears when
 * the client replied.
 */
@Service
public class TicketNotificationService {

  private final TicketRepository tickets;
  private final NotificationService notifications;

  public TicketNotificationService(TicketRepository tickets, NotificationService notifications) {
    this.tickets = tickets;
    this.notifications = notifications;
  }

  /** A ticket was assigned to {@code toUserId} by {@code actorUserId} (null = the system). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int assigned(AccessScope scope, long ticketId, Long toUserId, Long actorUserId) {
    if (toUserId == null || toUserId.equals(actorUserId)) {
      return 0;
    }
    return ticket(scope, ticketId)
        .filter(t -> toUserId.equals(t.assigneeUserId()))
        .map(
            t ->
                notifications.notifyUsers(
                    scope,
                    List.of(toUserId),
                    new Notice(
                        NotificationKind.TICKET_ASSIGNED, null, t.id(), t.organisationId(), null)))
        .orElse(0);
  }

  /** A client replied: the assignee hears about it, on a triage ticket too. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int clientReplied(AccessScope scope, long ticketId, long commentId) {
    return toAssignee(
        scope,
        tickets.find(scope, ticketId),
        null,
        NotificationKind.CLIENT_REPLIED,
        "client-reply:" + commentId);
  }

  /** An internal note was added: the assignee hears about it unless they wrote it. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int internalNoteAdded(AccessScope scope, long ticketId, long noteId, Long authorUserId) {
    return toAssignee(
        scope,
        ticket(scope, ticketId),
        authorUserId,
        NotificationKind.INTERNAL_NOTE_ADDED,
        "note:" + noteId);
  }

  private int toAssignee(
      AccessScope scope,
      Optional<TicketRow> t,
      Long actor,
      NotificationKind kind,
      String dedupeKey) {
    if (t.isEmpty()
        || t.get().assigneeUserId() == null
        || Objects.equals(t.get().assigneeUserId(), actor)) {
      return 0;
    }
    return notifications.notifyUsers(
        scope,
        List.of(t.get().assigneeUserId()),
        new Notice(kind, null, t.get().id(), t.get().organisationId(), dedupeKey));
  }

  private Optional<TicketRow> ticket(AccessScope scope, long ticketId) {
    return tickets.find(scope, ticketId).filter(t -> t.organisationId() != null);
  }
}
