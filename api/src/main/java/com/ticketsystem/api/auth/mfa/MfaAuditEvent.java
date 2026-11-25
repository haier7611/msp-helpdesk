package com.ticketsystem.api.auth.mfa;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * MFA, step-up, devices, sessions and security_version. Detail keys: the device label (from the
 * User-Agent, display only) and counts of codes, sessions and devices affected by a change.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"device", "remaining", "sessions", "devices"})
public enum MfaAuditEvent implements AuditEvent {
  MFA_ENROLMENT_STARTED,
  MFA_ENROLLED,
  /** A wrong TOTP or recovery code at sign-in or step-up ("method" says which). */
  MFA_FAILED,
  /** An MFA check refused by the back-off. */
  MFA_THROTTLED,
  MFA_SIGN_IN_SUCCEEDED,
  RECOVERY_CODE_USED,
  RECOVERY_CODES_REGENERATED,
  STEP_UP_SUCCEEDED,
  MFA_RESET,
  NEW_DEVICE_SIGN_IN,
  TRUSTED_DEVICE_ADDED,
  TRUSTED_DEVICE_REMOVED,
  /** A user signed out one of their own sessions, or all others. */
  SESSION_REVOKED,
  /** security_version bumped; "reason" names the trigger, other sessions were deleted. */
  SECURITY_VERSION_BUMPED,
  /** A request arrived on a session whose security_version, role or status no longer matches. */
  SESSION_INVALIDATED
}
