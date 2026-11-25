package com.ticketsystem.api.auth.mfa;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * The per-engineer two-step sign-in exemption. Detail keys: {@code exempt_until} (the end time,
 * ISO-8601 UTC), {@code replaced} (a grant that changed an existing exemption) and {@code ended_by}
 * ({@code admin}, {@code role_change} or {@code deactivation} on a revoke). The admin's free-text
 * reason is deliberately <b>not</b> an allowed key: it may describe a person's circumstances, and
 * the audit log is append-only and kept for years, so it stays in {@code mfa_exemption} where it is
 * deleted with the exemption.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"exempt_until", "replaced", "ended_by"})
public enum MfaExemptionAuditEvent implements AuditEvent {
  /** An admin exempted an engineer (or changed the reason or end date of an exemption). */
  MFA_EXEMPTION_GRANTED,
  /** The exemption was ended early: by an admin, or because of a role change or deactivation. */
  MFA_EXEMPTION_REVOKED,
  /** The end date passed; written once, when sign-in or a request first notices it. */
  MFA_EXEMPTION_EXPIRED
}
