package com.ticketsystem.api.admin;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * Admin configuration. Detail keys: the request type id, the audit-log export's filters (event type
 * name, organisation; the actor search text is never stored), the saved reply id and the client
 * email switches of an organisation - never secrets, never template text or logo bytes.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"request_type", "actor", "action", "saved_reply", "organisation", "changed"})
public enum AdminConfigAuditEvent implements AuditEvent {
  REQUEST_TYPE_CREATED,
  /** "changed" lists the fields (never values); "request_type" is the id. */
  REQUEST_TYPE_UPDATED,
  REQUEST_TYPES_REORDERED,
  /** The company signature template was saved ("changed" lists the fields). */
  SIGNATURE_TEMPLATE_CHANGED,
  /** The company logo was uploaded or removed ("status"). */
  SIGNATURE_LOGO_CHANGED,
  /** Admin - Branding: support name or brand colour ("changed"). */
  BRANDING_CHANGED,
  /** The audit log was exported as CSV (filters, date range and row count). */
  AUDIT_LOG_EXPORTED,
  /** A shared saved reply was created, updated or deleted ("status", "saved_reply" id). */
  SHARED_SAVED_REPLY_CHANGED,
  /**
   * An admin turned client emails on or off for an organisation ("organisation" id, "changed" lists
   * each switch with its new state, e.g. "resolved_notice:off").
   */
  CLIENT_EMAILS_CHANGED
}
