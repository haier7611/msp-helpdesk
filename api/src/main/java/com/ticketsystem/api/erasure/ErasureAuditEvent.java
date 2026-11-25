package com.ticketsystem.api.erasure;

import com.ticketsystem.api.audit.AuditEvent;

/**
 * User erasure (pseudonymisation).
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
public enum ErasureAuditEvent implements AuditEvent {
  /** An admin erased (pseudonymised) a user's personal data; the user is the target. */
  USER_ERASED,
  /**
   * Written by the {@code scrub_audit_email} database function after an erase removed the person's
   * address from audit details ("rows" = events changed); the user is the target.
   */
  AUDIT_EMAIL_SCRUBBED
}
