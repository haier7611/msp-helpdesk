package com.ticketsystem.api.retention;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * The nightly retention job. Detail keys: counts only (with "kind" and "rows"), never content.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys("attachments")
public enum RetentionAuditEvent implements AuditEvent {
  /** The nightly retention job removed rows ("kind", "rows", "attachments"). */
  RETENTION_RUN
}
