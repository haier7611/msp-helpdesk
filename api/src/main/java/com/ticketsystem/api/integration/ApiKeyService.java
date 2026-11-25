package com.ticketsystem.api.integration;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.integration.clientdata.ApiKeyRepository;
import com.ticketsystem.api.integration.clientdata.ApiKeyRepository.ApiKeyRow;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin management of integrations API keys: create a key bound to one organisation (shown once),
 * list keys without secrets, revoke. Creation needs step-up (the controller) and both changes are
 * audit-logged without the key.
 */
@Service
public class ApiKeyService {

  static final int MAX_NAME = 100;

  /** A key as listed: never the secret. */
  public record ApiKeyView(
      long id,
      String name,
      String prefix,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      Instant revokedAt,
      String status) {}

  /** The answer to a create: the only time the full key is ever returned. */
  public record CreatedKey(ApiKeyView key, String secret) {
    @Override
    public String toString() {
      return "CreatedKey[id=" + key.id() + "]";
    }
  }

  private final ApiKeyRepository keys;
  private final OrganisationRepository organisations;
  private final AuditService audit;
  private final Clock clock;

  public ApiKeyService(
      ApiKeyRepository keys,
      OrganisationRepository organisations,
      AuditService audit,
      Clock clock) {
    this.keys = keys;
    this.organisations = organisations;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  public CreatedKey create(
      AccessScope scope, long organisationId, String name, Instant expiresAt, String clientIp) {
    scope.requireAdmin();
    organisations.find(scope, organisationId).orElseThrow(AccessScope::notFound);
    String clean = name == null ? "" : name.strip().replaceAll("\\p{Cntrl}", " ");
    if (clean.isEmpty() || clean.length() > MAX_NAME) {
      throw ApiException.invalidRequest("Give the key a name of up to 100 characters.");
    }
    Instant now = clock.instant();
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      throw ApiException.invalidRequest("The expiry date must be in the future.");
    }
    ApiKeys.Generated g = ApiKeys.generate();
    long id = keys.insert(scope, organisationId, clean, g.keyId(), g.hash(), expiresAt, now);
    audit.record(
        IntegrationAuditEvent.API_KEY_CREATED,
        scope.principalId().orElse(null),
        null,
        clientIp,
        Map.of("organisation", organisationId, "api_key", id, "key_id", g.keyId()));
    ApiKeyRow row = keys.find(scope, organisationId, id).orElseThrow();
    return new CreatedKey(view(row, now), g.fullKey());
  }

  @Transactional(readOnly = true)
  public List<ApiKeyView> list(AccessScope scope, long organisationId) {
    scope.requireAdmin();
    organisations.find(scope, organisationId).orElseThrow(AccessScope::notFound);
    Instant now = clock.instant();
    return keys.list(scope, organisationId).stream().map(r -> view(r, now)).toList();
  }

  /** Revokes a key at once (its open tickets stay as they are). Repeating it changes nothing. */
  @Transactional
  public ApiKeyView revoke(AccessScope scope, long organisationId, long keyId, String clientIp) {
    scope.requireAdmin();
    ApiKeyRow row = keys.find(scope, organisationId, keyId).orElseThrow(AccessScope::notFound);
    Instant now = clock.instant();
    if (keys.revoke(scope, organisationId, keyId, now)) {
      audit.record(
          IntegrationAuditEvent.API_KEY_REVOKED,
          scope.principalId().orElse(null),
          null,
          clientIp,
          Map.of("organisation", organisationId, "api_key", keyId, "key_id", row.keyId()));
    }
    return view(keys.find(scope, organisationId, keyId).orElseThrow(), now);
  }

  private static ApiKeyView view(ApiKeyRow r, Instant now) {
    String status =
        r.revokedAt() != null
            ? "REVOKED"
            : r.expiresAt() != null && !r.expiresAt().isAfter(now) ? "EXPIRED" : "ACTIVE";
    return new ApiKeyView(
        r.id(),
        r.name(),
        ApiKeys.PREFIX + r.keyId(),
        r.createdAt(),
        r.expiresAt(),
        r.lastUsedAt(),
        r.revokedAt(),
        status);
  }
}
