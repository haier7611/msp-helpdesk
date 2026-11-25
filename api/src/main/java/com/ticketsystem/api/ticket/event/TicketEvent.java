package com.ticketsystem.api.ticket.event;

import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;

/**
 * Domain events for the parts that react to tickets: client and supporter email (by the client
 * email matrix) and SLA clocks and notifications. Published inside the ticket transaction through
 * Spring's {@code ApplicationEventPublisher}; consumers listen with
 * {@code @TransactionalEventListener} (default phase AFTER_COMMIT), so they only ever see changes
 * that committed. Events carry ids and enums only - never comment bodies, names or addresses - so a
 * consumer has to read content back through an {@code AccessScope} (and the read model that fits
 * its audience).
 *
 * <p>Client email matrix mapping: {@link TicketCreated} (except API alerts), {@link
 * PublicReplyAdded}, and {@link StatusChanged} to RESOLVED. Nothing else is client-facing; in
 * particular {@link MarkedDuplicate} and its {@link StatusChanged} (trigger MARK_DUPLICATE, to
 * CLOSED) send nothing to the client (by design). For API tickets ({@code source = API}) - their
 * {@link TicketCreated}, their {@link StatusChanged} with trigger ALERT_RESOLVED or ALERT_REPEAT,
 * and {@link AlertCommentAdded} - the client is emailed only when the organisation's {@code
 * api_alert_client_emails} opt-in is on (default off).
 */
public sealed interface TicketEvent {

  long ticketId();

  /**
   * A ticket was created (any source). {@code organisationId} is null for triage; {@code
   * followUpOfId} is set for a follow-up (F4).
   */
  record TicketCreated(
      long ticketId, Long organisationId, TicketSource source, Long actorUserId, Long followUpOfId)
      implements TicketEvent {}

  /** A supporter's public reply (agent UI, or by email). */
  record PublicReplyAdded(long ticketId, long commentId, Long authorUserId)
      implements TicketEvent {}

  /** A client reply (by email). */
  record ClientReplyAdded(long ticketId, long commentId, boolean receivedByEmail)
      implements TicketEvent {}

  /**
   * A repeat of an API alert was added to the ticket as a public comment authored by the API key.
   * Not client-facing: no client email unless the organisation opted in.
   */
  record AlertCommentAdded(long ticketId, long commentId, long apiKeyId) implements TicketEvent {}

  /** An internal note (supporters only; never client-facing). */
  record InternalNoteAdded(long ticketId, long noteId, Long authorUserId) implements TicketEvent {}

  record StatusChanged(
      long ticketId, TicketStatus from, TicketStatus to, Trigger trigger, Long actorUserId)
      implements TicketEvent {}

  /** The SLA clock recalculates due times from creation under the new targets. */
  record PriorityChanged(long ticketId, Priority from, Priority to, Long actorUserId)
      implements TicketEvent {}

  record AssigneeChanged(long ticketId, Long fromUserId, Long toUserId, Long actorUserId)
      implements TicketEvent {}

  /** Triage assignment ({@code fromOrganisationId} null) or a move between organisations. */
  record OrganisationChanged(
      long ticketId, Long fromOrganisationId, long toOrganisationId, Long actorUserId)
      implements TicketEvent {}

  /** {@code ticketId} (the source, now Closed) is a duplicate of {@code targetTicketId}. */
  record MarkedDuplicate(long ticketId, long targetTicketId, Long actorUserId)
      implements TicketEvent {}

  record TicketsLinked(long ticketId, long otherTicketId, Long actorUserId)
      implements TicketEvent {}
}
