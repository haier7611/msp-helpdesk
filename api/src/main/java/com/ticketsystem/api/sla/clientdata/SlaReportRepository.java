package com.ticketsystem.api.sla.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TargetState;
import com.ticketsystem.api.sla.TicketStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SLA report's ticket rows: the stored measured values, never recomputed, so history does not
 * drift when a policy changes. Supporters only (agents and admins); the scope is applied in SQL, so
 * a restricted agent reports only on their organisations. Triage tickets (no organisation: they run
 * the default SLA) are included like any other - every supporter can see triage - unless an
 * organisation filter is set.
 */
@Repository
public class SlaReportRepository {

  /** Filters; null = no filter. */
  public record Filter(
      Long organisationId, Long assigneeUserId, Long requestTypeId, Priority priority) {}

  public record ReportRow(
      long id,
      Long organisationId,
      Long assigneeUserId,
      long requestTypeId,
      Priority priority,
      TicketStatus status,
      String subject,
      Long duplicateOfId,
      Instant createdAt,
      Instant firstRespondedAt,
      Instant firstResponseDueAt,
      Long firstResponseTargetMinutes,
      Long firstResponseUsedMinutes,
      TargetState firstResponseState,
      Instant resolvedAt,
      Instant resolutionDueAt,
      Long resolutionTargetMinutes,
      Long resolutionUsedMinutes,
      TargetState resolutionState,
      Long pausedMinutes,
      String clock) {}

  private final JdbcClient jdbc;

  public SlaReportRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Tickets created in [from, to), resolved in [from, to), or still open (created before {@code
   * to}); oldest first, at most {@code limit}.
   */
  public List<ReportRow> rows(AccessScope scope, Filter f, Instant from, Instant to, int limit) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    StringBuilder sql =
        new StringBuilder(
                "SELECT id, organisation_id, assignee_user_id, request_type_id, priority, status,"
                    + " subject, duplicate_of_id, created_at, first_responded_at,"
                    + " first_response_due_at, first_response_target_minutes,"
                    + " first_response_used_minutes, first_response_state, resolved_at,"
                    + " resolution_due_at, resolution_target_minutes, resolution_used_minutes,"
                    + " resolution_state, sla_paused_minutes, sla_clock FROM ticket WHERE"
                    + " sla_calculated_at IS NOT NULL AND ((created_at >= :from AND created_at <"
                    + " :to) OR (resolved_at >= :from AND resolved_at < :to) OR (status NOT IN"
                    + " ('RESOLVED', 'CLOSED') AND created_at < :to)) AND ")
            .append(ScopeSql.organisationOrTriage(scope, "organisation_id"));
    if (f.organisationId() != null) {
      sql.append(" AND organisation_id = :fOrg");
    }
    if (f.assigneeUserId() != null) {
      sql.append(" AND assignee_user_id = :fAssignee");
    }
    if (f.requestTypeId() != null) {
      sql.append(" AND request_type_id = :fType");
    }
    if (f.priority() != null) {
      sql.append(" AND priority = :fPriority");
    }
    sql.append(" ORDER BY created_at, id LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString())
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .param("limit", limit);
    if (f.organisationId() != null) {
      spec = spec.param("fOrg", f.organisationId());
    }
    if (f.assigneeUserId() != null) {
      spec = spec.param("fAssignee", f.assigneeUserId());
    }
    if (f.requestTypeId() != null) {
      spec = spec.param("fType", f.requestTypeId());
    }
    if (f.priority() != null) {
      spec = spec.param("fPriority", f.priority().name());
    }
    return ScopeSql.bind(spec, scope).query(SlaReportRepository::map).list();
  }

  private static Instant instant(ResultSet rs, String c) throws SQLException {
    Timestamp t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  private static TargetState state(ResultSet rs, String c) throws SQLException {
    String s = rs.getString(c);
    return s == null ? null : TargetState.valueOf(s);
  }

  private static ReportRow map(ResultSet rs, int n) throws SQLException {
    return new ReportRow(
        rs.getLong("id"),
        rs.getObject("organisation_id", Long.class),
        rs.getObject("assignee_user_id", Long.class),
        rs.getLong("request_type_id"),
        Priority.valueOf(rs.getString("priority")),
        TicketStatus.valueOf(rs.getString("status")),
        rs.getString("subject"),
        rs.getObject("duplicate_of_id", Long.class),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs, "first_responded_at"),
        instant(rs, "first_response_due_at"),
        rs.getObject("first_response_target_minutes", Long.class),
        rs.getObject("first_response_used_minutes", Long.class),
        state(rs, "first_response_state"),
        instant(rs, "resolved_at"),
        instant(rs, "resolution_due_at"),
        rs.getObject("resolution_target_minutes", Long.class),
        rs.getObject("resolution_used_minutes", Long.class),
        state(rs, "resolution_state"),
        rs.getObject("sla_paused_minutes", Long.class),
        rs.getString("sla_clock"));
  }
}
