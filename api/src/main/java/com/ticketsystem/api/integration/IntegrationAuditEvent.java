package com.ticketsystem.api.integration;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * Integrations API keys. Detail keys: the api_key row id (never the key or its hash) and its
 * visible key id; "key_id" is also the data key a rotation re-encrypted to.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"api_key", "key_id"})
public enum IntegrationAuditEvent implements AuditEvent {
  API_KEY_CREATED,
  API_KEY_REVOKED
}
