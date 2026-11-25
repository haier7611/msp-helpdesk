package com.ticketsystem.api.qa;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * The QA round's host commands. Counts only ("rows"), never account details.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"rows", "kind"})
public enum QaAuditEvent implements AuditEvent {
  /** ticketsys-qa-seed ran ("rows" = rows created this run, "kind" = seed or reset_passwords). */
  QA_SEED,
  /** ticketsys-qa-wipe ran ("rows" = rows removed, "kind" = wipe). */
  QA_WIPE
}
