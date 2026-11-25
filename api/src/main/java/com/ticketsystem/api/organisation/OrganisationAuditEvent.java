package com.ticketsystem.api.organisation;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * Organisations and access. Detail keys: the organisation id, ticket prefix and verified domain -
 * never secrets.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({
  "organisation",
  "prefix",
  "old_prefix",
  "domain",
  "all_organisations",
  "organisations"
})
public enum OrganisationAuditEvent implements AuditEvent {
  ORGANISATION_CREATED,
  ORGANISATION_UPDATED,
  ORGANISATION_DELETED,
  ORGANISATION_PREFIX_CHANGED,
  ORGANISATION_DOMAIN_ADDED,
  ORGANISATION_DOMAIN_REMOVED,
  USER_ORGANISATION_CHANGED,
  SUPPORTER_ACCESS_CHANGED
}
