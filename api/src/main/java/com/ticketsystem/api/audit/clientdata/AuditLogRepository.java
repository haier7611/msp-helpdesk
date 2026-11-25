package com.ticketsystem.api.audit.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the audit log for the admin viewer (C14). The log is MSP-wide security history, so only
 * admins read it; the application's database role can only insert and read {@code audit_event}
 * (V5), so nothing here can change it. Filters: date range, actor (part of their email address),
 * event type and organisation (the {@code organisation} detail).
 */
@Repository
public class AuditLogRepository {

  public record Filter(
      Instant from, Instant to, String actorEmail, String type, Long organisationId) {}

  public record EventRow(
      long id,
      Instant occurredAt,
      String type,
      Long actorUserId,
      String actorName,
      String actorEmail,
      Long targetUserId,
      String targetName,
      String targetEmail,
      String clientIp,
      String detailsJson) {}

  private final JdbcClient jdbc;

  public AuditLogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Newest first; {@code beforeId} continues a page; at most {@code limit} rows. */
  public List<EventRow> find(AccessScope scope, Filter f, Long beforeId, int limit) {
    scope.requireAdmin();
    StringBuilder sql =
        new StringBuilder(
            "SELECT e.id, e.occurred_at, e.type, e.actor_user_id, a.name AS actor_name,"
                + " a.email AS actor_email, e.target_user_id, t.name AS target_name,"
                + " t.email AS target_email, e.client_ip, e.details::text AS details"
                + " FROM audit_event e"
                + " LEFT JOIN app_user a ON a.id = e.actor_user_id"
                + " LEFT JOIN app_user t ON t.id = e.target_user_id"
                + " WHERE e.occurred_at >= :from AND e.occurred_at < :to");
    if (f.actorEmail() != null) {
      sql.append(" AND lower(a.email) LIKE :actor ESCAPE '\\'");
    }
    if (f.type() != null) {
      sql.append(" AND e.type = :type");
    }
    if (f.organisationId() != null) {
      sql.append(" AND e.details ->> 'organisation' = :organisation");
    }
    if (beforeId != null) {
      sql.append(" AND e.id < :before");
    }
    sql.append(" ORDER BY e.id DESC LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString())
            .param("from", Timestamp.from(f.from()))
            .param("to", Timestamp.from(f.to()))
            .param("limit", limit);
    if (f.actorEmail() != null) {
      spec = spec.param("actor", "%" + likeEscape(f.actorEmail()) + "%");
    }
    if (f.type() != null) {
      spec = spec.param("type", f.type());
    }
    if (f.organisationId() != null) {
      spec = spec.param("organisation", Long.toString(f.organisationId()));
    }
    if (beforeId != null) {
      spec = spec.param("before", beforeId);
    }
    return spec.query(
            (rs, n) -> {
              long actor = rs.getLong("actor_user_id");
              boolean noActor = rs.wasNull();
              long target = rs.getLong("target_user_id");
              boolean noTarget = rs.wasNull();
              return new EventRow(
                  rs.getLong("id"),
                  rs.getTimestamp("occurred_at").toInstant(),
                  rs.getString("type"),
                  noActor ? null : actor,
                  rs.getString("actor_name"),
                  rs.getString("actor_email"),
                  noTarget ? null : target,
                  rs.getString("target_name"),
                  rs.getString("target_email"),
                  rs.getString("client_ip"),
                  rs.getString("details"));
            })
        .list();
  }

  static String likeEscape(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
