package com.ticketsystem.api.auth.entra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code external_identity}: linked Microsoft accounts as (tid, oid). Not client-owned data (no
 * AccessScope): every method is keyed by the user id or by the (tid, oid) pair from a validated ID
 * token, never by email.
 */
@Repository
public class ExternalIdentityRepository {

  /** A user's link. */
  public record Link(
      long userId, UUID tenantId, UUID objectId, Instant linkedAt, Instant lastSignInAt) {}

  private static final String COLUMNS = "user_id, tenant_id, object_id, linked_at, last_sign_in_at";

  private final JdbcClient jdbc;

  public ExternalIdentityRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Link> findByIdentity(UUID tenantId, UUID objectId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM external_identity WHERE tenant_id = ? AND object_id = ?")
        .params(tenantId, objectId)
        .query(ExternalIdentityRepository::map)
        .optional();
  }

  public Optional<Link> findByUser(long userId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM external_identity WHERE user_id = ?")
        .param(userId)
        .query(ExternalIdentityRepository::map)
        .optional();
  }

  /**
   * Links; false when the user already has a link or the (tid, oid) belongs to someone else (the
   * unique constraints decide, so two racing links cannot both win).
   */
  public boolean insert(long userId, UUID tenantId, UUID objectId, Instant now) {
    return jdbc.sql(
                "INSERT INTO external_identity (user_id, tenant_id, object_id, linked_at)"
                    + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")
            .params(userId, tenantId, objectId, Timestamp.from(now))
            .update()
        == 1;
  }

  public boolean deleteByUser(long userId) {
    return jdbc.sql("DELETE FROM external_identity WHERE user_id = ?").param(userId).update() > 0;
  }

  public void touchSignIn(long userId, Instant now) {
    jdbc.sql("UPDATE external_identity SET last_sign_in_at = ? WHERE user_id = ?")
        .params(Timestamp.from(now), userId)
        .update();
  }

  private static Link map(ResultSet rs, int n) throws SQLException {
    Timestamp last = rs.getTimestamp("last_sign_in_at");
    return new Link(
        rs.getLong("user_id"),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("object_id", UUID.class),
        rs.getTimestamp("linked_at").toInstant(),
        last == null ? null : last.toInstant());
  }
}
