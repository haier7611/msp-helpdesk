package com.ticketsystem.api.supporter;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * Manage supporters and My settings. Detail keys: the previous role on a role change, open tickets
 * moved on deactivation, the supporter tickets were reassigned to (user id), which profile fields
 * changed (names).
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"old_role", "tickets", "reassigned_to", "fields"})
public enum SupporterAuditEvent implements AuditEvent {
  /** An admin edited a supporter's name or job title. */
  SUPPORTER_UPDATED,
  /** An admin changed a supporter's role ("role" = new, "old_role" = previous). */
  SUPPORTER_ROLE_CHANGED,
  /** An admin deactivated a supporter ("tickets" = open tickets reassigned). */
  SUPPORTER_DEACTIVATED,
  SUPPORTER_REACTIVATED,
  /** A supporter saved their own profile (signature fields). */
  PROFILE_UPDATED,
  /** A supporter uploaded or removed their profile photo ("status" = uploaded/removed). */
  PROFILE_PHOTO_CHANGED
}
