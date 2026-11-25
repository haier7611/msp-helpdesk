package com.ticketsystem.api.access;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads one API key row by its visible key id so the integrations filter can authenticate a call
 * and build the request's {@link AccessScope#apiKey}. Like {@link PrincipalAccessReader} this is
 * the bootstrap of scoping and therefore cannot take a scope: it reads only the row the presented
 * key names and returns no client data (organisation id, hash, expiry, revocation).
 */
@Component
public class ApiKeyAccessReader {

  /** What authentication needs about a key. */
  public record KeyAccess(
      long id, long organisationId, byte[] secretHash, Instant expiresAt, Instant revokedAt) {

    public boolean usableAt(Instant now) {
      return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public AccessScope toScope() {
      return AccessScope.apiKey(id, organisationId);
    }
  }

  /** "Last used" is written at most once a minute per key, so a busy monitor is not a write. */
  static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

  private final JdbcClient jdbc;

  public ApiKeyAccessReader(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<KeyAccess> read(String keyId) {
    return jdbc.sql(
            "SELECT id, organisation_id, secret_hash, expires_at, revoked_at FROM api_key"
                + " WHERE key_id = ?")
        .param(keyId)
        .query(
            (rs, n) ->
                new KeyAccess(
                    rs.getLong("id"),
                    rs.getLong("organisation_id"),
                    rs.getBytes("secret_hash"),
                    instant(rs.getTimestamp("expires_at")),
                    instant(rs.getTimestamp("revoked_at"))))
        .optional();
  }

  /** Records a successful use of the key. */
  public void markUsed(long id, Instant now) {
    jdbc.sql(
            "UPDATE api_key SET last_used_at = ? WHERE id = ? AND (last_used_at IS NULL"
                + " OR last_used_at < ?)")
        .params(Timestamp.from(now), id, Timestamp.from(now.minus(LAST_USED_RESOLUTION)))
        .update();
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
