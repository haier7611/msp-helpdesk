package com.ticketsystem.api.integration.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code api_key} management. Admin scope only - every method checks it - and always within one
 * organisation. Rows never leave with their hash.
 */
@Repository
public class ApiKeyRepository {

  /** A key as admins see it: never the secret or its hash. */
  public record ApiKeyRow(
      long id,
      long organisationId,
      String name,
      String keyId,
      Long createdByUserId,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      Instant revokedAt) {}

  private static final String COLUMNS =
      "id, organisation_id, name, key_id, created_by_user_id, created_at, expires_at,"
          + " last_used_at, revoked_at";

  private final JdbcClient jdbc;

  public ApiKeyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long insert(
      AccessScope scope,
      long organisationId,
      String name,
      String keyId,
      byte[] secretHash,
      Instant expiresAt,
      Instant now) {
    scope.requireAdmin();
    scope.require(organisationId);
    return jdbc.sql(
            "INSERT INTO api_key (organisation_id, name, key_id, secret_hash, created_by_user_id,"
                + " created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id")
        .params(
            organisationId,
            name,
            keyId,
            secretHash,
            scope.principalId().orElse(null),
            Timestamp.from(now),
            expiresAt == null ? null : Timestamp.from(expiresAt))
        .query(Long.class)
        .single();
  }

  public List<ApiKeyRow> list(AccessScope scope, long organisationId) {
    scope.requireAdmin();
    scope.require(organisationId);
    return jdbc.sql("SELECT " + COLUMNS + " FROM api_key WHERE organisation_id = ? ORDER BY id")
        .param(organisationId)
        .query(ApiKeyRepository::map)
        .list();
  }

  public Optional<ApiKeyRow> find(AccessScope scope, long organisationId, long id) {
    scope.requireAdmin();
    scope.require(organisationId);
    return jdbc.sql("SELECT " + COLUMNS + " FROM api_key WHERE organisation_id = ? AND id = ?")
        .params(organisationId, id)
        .query(ApiKeyRepository::map)
        .optional();
  }

  /** Revokes a key; false when it was already revoked or does not belong to the organisation. */
  public boolean revoke(AccessScope scope, long organisationId, long id, Instant now) {
    scope.requireAdmin();
    scope.require(organisationId);
    return jdbc.sql(
                "UPDATE api_key SET revoked_at = ?, revoked_by_user_id = ? WHERE organisation_id"
                    + " = ? AND id = ? AND revoked_at IS NULL")
            .params(Timestamp.from(now), scope.principalId().orElse(null), organisationId, id)
            .update()
        == 1;
  }

  private static ApiKeyRow map(ResultSet rs, int n) throws SQLException {
    return new ApiKeyRow(
        rs.getLong("id"),
        rs.getLong("organisation_id"),
        rs.getString("name"),
        rs.getString("key_id"),
        rs.getObject("created_by_user_id", Long.class),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs.getTimestamp("expires_at")),
        instant(rs.getTimestamp("last_used_at")),
        instant(rs.getTimestamp("revoked_at")));
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
