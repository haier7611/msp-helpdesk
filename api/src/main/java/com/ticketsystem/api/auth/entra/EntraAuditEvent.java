package com.ticketsystem.api.auth.entra;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * Microsoft (Entra) sign-in. Detail keys: an Entra tenant id (an organisation identifier, not
 * personal data), the names of changed settings fields (never their values) and an AADSTS / OAuth
 * error code; never object ids or tokens.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"tenant", "changed", "error_code"})
public enum EntraAuditEvent implements AuditEvent {
  /** Admin changed the Microsoft sign-in details ("changed" lists the fields, never values). */
  ENTRA_SETTINGS_CHANGED,
  ENTRA_ENABLED,
  ENTRA_DISABLED,
  ENTRA_TEST_SUCCEEDED,
  ENTRA_TEST_FAILED,
  /** A user linked a Microsoft account (from settings, or customer auto-link at sign-in). */
  MICROSOFT_LINKED,
  MICROSOFT_LINK_FAILED,
  MICROSOFT_UNLINKED,
  /** An organisation's allowed Microsoft tenant was set, changed or cleared. */
  ORGANISATION_ENTRA_TENANT_CHANGED
}
