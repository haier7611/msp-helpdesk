package com.ticketsystem.api.erasure.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Personal-data erasure. Admin scope only. The pseudonymisation itself is the {@code ts_erase_user}
 * database function (migration V82), so the admin action and the restore drill's erasure-log replay
 * do exactly the same thing.
 */
@Repository
public class ErasureRepository {

  /** The fields the erase guards need, read with a row lock. */
  public record Target(long id, String role, String status, boolean erased) {}

  private final JdbcClient jdbc;

  public ErasureRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Target> lock(AccessScope scope, long userId) {
    requireAdmin(scope);
    return jdbc.sql(
            "SELECT id, role, status, erased_at IS NOT NULL AS erased FROM app_user WHERE id = :id"
                + " FOR UPDATE")
        .param("id", userId)
        .query(
            (rs, n) ->
                new Target(
                    rs.getLong("id"),
                    rs.getString("role"),
                    rs.getString("status"),
                    rs.getBoolean("erased")))
        .optional();
  }

  /** Pseudonymises the user and writes the erasure log row, in the caller's transaction. */
  public void erase(AccessScope scope, long userId, long actorUserId, Instant at) {
    requireAdmin(scope);
    Boolean done =
        jdbc.sql("SELECT ts_erase_user(:id, :at)")
            .param("id", userId)
            .param("at", Timestamp.from(at))
            .query(Boolean.class)
            .single();
    if (!Boolean.TRUE.equals(done)) {
      throw AccessScope.notFound();
    }
    jdbc.sql(
            "INSERT INTO user_erasure (user_id, actor_user_id, erased_at) VALUES (:id, :actor,"
                + " :at)")
        .param("id", userId)
        .param("actor", actorUserId)
        .param("at", Timestamp.from(at))
        .update();
  }

  private static void requireAdmin(AccessScope scope) {
    if (!scope.isAdmin()) {
      throw AccessScope.notFound();
    }
  }
}
