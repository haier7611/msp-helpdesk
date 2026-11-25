package com.ticketsystem.api.crypto;

import com.ticketsystem.api.audit.AuditEvent;

/**
 * data-key rotation (the new key's id goes in "key_id", never the key).
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
public enum CryptoAuditEvent implements AuditEvent {
  /** The data-key rotation command re-encrypted stored secrets ("key_id" = the new key). */
  DATA_KEY_ROTATED
}
