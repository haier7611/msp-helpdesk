package com.ticketsystem.api.auth;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * Core auth: sign-in, sign-out, passwords, links, invitations and setup.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"reason", "role", "method", "purpose", "email", "attempts", "status"})
public enum AuthAuditEvent implements AuditEvent {
  SIGN_IN_SUCCEEDED,
  SIGN_IN_FAILED,
  /** A sign-in attempt refused by the back-off or a rate cap (the "lockout" event). */
  SIGN_IN_THROTTLED,
  SIGN_OUT,
  SESSION_EXPIRED,
  PASSWORD_CHANGED,
  PASSWORD_RESET_REQUESTED,
  PASSWORD_RESET_COMPLETED,
  MAGIC_LINK_REQUESTED,
  INVITATION_CREATED,
  INVITATION_ACCEPTED,
  /**
   * A wrong 6-digit invitation code. No longer recorded since invitation codes were removed; kept
   * so older stored events still resolve.
   */
  INVITATION_CODE_FAILED,
  INVITATION_REVOKED,
  SETUP_COMPLETED,
  SETUP_FAILED
}
