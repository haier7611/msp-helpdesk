package com.ticketsystem.api.contact;

import com.ticketsystem.api.audit.AuditEvent;

/**
 * Contacts created by supporters (organisation in the details, the contact as target user).
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
public enum ContactAuditEvent implements AuditEvent {
  CONTACT_CREATED
}
