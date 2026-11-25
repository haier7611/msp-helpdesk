package com.ticketsystem.api.auth.mfa;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code mfa_exemption}: at most one row per engineer; the row is the exemption. Not client-owned
 * data (no AccessScope): every method is keyed by the target user id, and only {@link
 * MfaExemptionService} calls it.
 */
@Repository
public class MfaExemptionRepository {

  /** One exemption. {@code reason} is shown to admins only and never written to the audit log. */
  public record Row(
      long userId, String reason, Instant endsAt, Long grantedByUserId, Instant grantedAt) {
    @Override
    public String toString() {
      return "MfaExemption[userId=" + userId + ", endsAt=" + endsAt + "]";
    }
  }

  private final JdbcClient jdbc;

  public MfaExemptionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Row> find(long userId) {
    return jdbc.sql(
            "SELECT user_id, reason, ends_at, granted_by_user_id, granted_at FROM mfa_exemption"
                + " WHERE user_id = ?")
        .param(userId)
        .query(
            (rs, n) ->
                new Row(
                    rs.getLong("user_id"),
                    rs.getString("reason"),
                    rs.getTimestamp("ends_at").toInstant(),
                    rs.getObject("granted_by_user_id", Long.class),
                    rs.getTimestamp("granted_at").toInstant()))
        .optional();
  }

  /** Inserts or replaces the user's exemption; true when one already existed. */
  public boolean upsert(
      long userId, String reason, Instant endsAt, long grantedByUserId, Instant grantedAt) {
    boolean existed = find(userId).isPresent();
    jdbc.sql(
            "INSERT INTO mfa_exemption (user_id, reason, ends_at, granted_by_user_id, granted_at)"
                + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id) DO UPDATE SET reason ="
                + " EXCLUDED.reason, ends_at = EXCLUDED.ends_at, granted_by_user_id ="
                + " EXCLUDED.granted_by_user_id, granted_at = EXCLUDED.granted_at")
        .params(userId, reason, ts(endsAt), grantedByUserId, ts(grantedAt))
        .update();
    return existed;
  }

  /** Deletes the user's exemption; true when there was one. */
  public boolean delete(long userId) {
    return jdbc.sql("DELETE FROM mfa_exemption WHERE user_id = ?").param(userId).update() == 1;
  }

  /**
   * Deletes the user's exemption only if it has ended by {@code now}; true for the one caller that
   * deleted it (so the "expired" audit event is written exactly once).
   */
  public boolean deleteIfEnded(long userId, Instant now) {
    return jdbc.sql("DELETE FROM mfa_exemption WHERE user_id = ? AND ends_at <= ?")
            .params(userId, ts(now))
            .update()
        == 1;
  }

  /**
   * Up to {@code limit} exemptions that have ended by {@code now} (user id and end time only), for
   * the nightly sweep that removes them when the engineer never signs in again.
   */
  public List<Row> ended(Instant now, int limit) {
    return jdbc.sql(
            "SELECT user_id, ends_at FROM mfa_exemption WHERE ends_at <= ? ORDER BY user_id"
                + " LIMIT ?")
        .params(ts(now), limit)
        .query(
            (rs, n) ->
                new Row(
                    rs.getLong("user_id"),
                    null,
                    rs.getTimestamp("ends_at").toInstant(),
                    null,
                    null))
        .list();
  }

  private static Timestamp ts(Instant instant) {
    return Timestamp.from(instant);
  }
}
