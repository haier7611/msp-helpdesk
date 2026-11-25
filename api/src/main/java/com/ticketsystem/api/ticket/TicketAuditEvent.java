package com.ticketsystem.api.ticket;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * A ticket moved to another organisation or out of triage (audit-logged); queue CSV export (row
 * count and filters, never the search text); an admin reopened a closed ticket (ticket and
 * organisation ids; the reason is on the agent timeline, never here). Detail keys: the ticket id
 * and the organisation it left - never content.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"ticket", "old_organisation"})
public enum TicketAuditEvent implements AuditEvent {
  TICKET_ORGANISATION_CHANGED,
  QUEUE_EXPORTED,
  TICKET_REOPENED
}
