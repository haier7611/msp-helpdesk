package com.ticketsystem.api.sla;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * SLA settings and the SLA report export. Detail keys: the date range and row count, never ticket
 * content.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"from", "to", "rows"})
public enum SlaAuditEvent implements AuditEvent {
  /** An admin changed SLA policies, calendar, holidays, timers or default assignees. */
  SLA_SETTINGS_CHANGED,
  /** The SLA report CSV was exported (F7: every export is audit-logged). */
  SLA_REPORT_EXPORTED
}
