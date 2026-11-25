package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.Impact;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.Urgency;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ticket}. Every read applies the scope in SQL ({@link ScopeSql#organisationOrTriage}):
 * another organisation's ticket looks exactly like a missing one; triage tickets (no organisation)
 * are visible to supporters and system jobs only. Writes check the scope first and apply it again
 * in their own WHERE clause, so a stale or wrong {@code TicketRow} cannot write to a ticket the
 * scope does not see (404, as for a missing ticket).
 */
@Repository
public class TicketRepository {

  /** One ticket row. {@code organisationId} is null while in triage. */
  public record TicketRow(
      long id,
      Long organisationId,
      Long reporterUserId,
      String reporterEmail,
      Long assigneeUserId,
      long requestTypeId,
      Priority priority,
      Impact impact,
      Urgency urgency,
      TicketStatus status,
      TicketSource source,
      String subject,
      String externalId,
      Long duplicateOfId,
      Long followUpOfId,
      Long createdByUserId,
      Instant createdAt,
      Instant updatedAt,
      Instant statusChangedAt) {}

  /** The values of a new ticket. */
  public record NewTicketRow(
      Long organisationId,
      Long reporterUserId,
      String reporterEmail,
      Long assigneeUserId,
      long requestTypeId,
      Priority priority,
      Impact impact,
      Urgency urgency,
      TicketSource source,
      String subject,
      String externalId,
      Long followUpOfId,
      Long createdByUserId) {}

  /** Queue sort orders. */
  public enum QueueSort {
    /** Newest first (id descending), the default. */
    NEWEST,
    OLDEST,
    /** Last updated first. */
    UPDATED,
    /** P1 first, then newest. */
    PRIORITY,
    /**
     * The first response most at stake first - breached and still unanswered (longest overdue
     * first), then running by due time (soonest first), then paused, then the rest (answered, no
     * target, no SLA yet), each newest first.
     */
    FIRST_RESPONSE
  }

  /**
   * Queue filters; null fields do not filter. {@code search} matches the subject or reporter email
   * (case-insensitive substring); {@code searchTicketId} is the ticket a typed reference resolved
   * to (or null). {@code offset} is ignored when {@code beforeId} is set.
   */
  public record QueueFilter(
      TicketStatus status,
      Long organisationId,
      boolean triageOnly,
      Long assigneeUserId,
      boolean unassignedOnly,
      boolean includeClosed,
      boolean breachingOnly,
      Long beforeId,
      int limit,
      String search,
      Long searchTicketId,
      Priority priority,
      boolean excludeTriage,
      QueueSort sort,
      int offset) {

    /** The default filter (no search, priority or sort; newest first). */
    public QueueFilter(
        TicketStatus status,
        Long organisationId,
        boolean triageOnly,
        Long assigneeUserId,
        boolean unassignedOnly,
        boolean includeClosed,
        boolean breachingOnly,
        Long beforeId,
        int limit) {
      this(
          status,
          organisationId,
          triageOnly,
          assigneeUserId,
          unassignedOnly,
          includeClosed,
          breachingOnly,
          beforeId,
          limit,
          null,
          null,
          null,
          false,
          QueueSort.NEWEST,
          0);
    }
  }

  private static final String COLUMNS =
      "id, organisation_id, reporter_user_id, reporter_email, assignee_user_id, request_type_id,"
          + " priority, impact, urgency, status, source, subject, external_id, duplicate_of_id,"
          + " follow_up_of_id, created_by_user_id, created_at, updated_at, status_changed_at";

  private final JdbcClient jdbc;

  public TicketRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts a ticket (status New) and returns its id. */
  public long insert(AccessScope scope, NewTicketRow t, Instant now) {
    requireWritable(scope, t.organisationId());
    return jdbc.sql(
            "INSERT INTO ticket (organisation_id, reporter_user_id, reporter_email,"
                + " assignee_user_id, request_type_id, priority, impact, urgency, status, source,"
                + " subject, external_id, follow_up_of_id, created_by_user_id, created_at,"
                + " updated_at, status_changed_at) VALUES (:org, :reporter, :reporterEmail,"
                + " :assignee, :type, :priority, :impact, :urgency, 'NEW', :source, :subject,"
                + " :externalId, :followUpOf, :createdBy, :now, :now, :now) RETURNING id")
        .param("org", t.organisationId())
        .param("reporter", t.reporterUserId())
        .param("reporterEmail", t.reporterEmail())
        .param("assignee", t.assigneeUserId())
        .param("type", t.requestTypeId())
        .param("priority", t.priority().name())
        .param("impact", t.impact() == null ? null : t.impact().name())
        .param("urgency", t.urgency() == null ? null : t.urgency().name())
        .param("source", t.source().name())
        .param("subject", t.subject())
        .param("externalId", t.externalId())
        .param("followUpOf", t.followUpOfId())
        .param("createdBy", t.createdByUserId())
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  /** One ticket; empty when missing or not visible. */
  public Optional<TicketRow> find(AccessScope scope, long ticketId) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT "
                        + COLUMNS
                        + " FROM ticket WHERE id = :id AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id"))
                .param("id", ticketId),
            scope)
        .query(TicketRepository::map)
        .optional();
  }

  /** Locks the ticket row for the rest of the transaction; empty when missing or not visible. */
  public Optional<TicketRow> lock(AccessScope scope, long ticketId) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT "
                        + COLUMNS
                        + " FROM ticket WHERE id = :id AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id")
                        + " FOR UPDATE")
                .param("id", ticketId),
            scope)
        .query(TicketRepository::map)
        .optional();
  }

  /**
   * The agent queue, filtered, sorted and scoped. The CSV export uses this same query, so the file
   * and the screen cannot differ.
   */
  public List<TicketRow> list(AccessScope scope, QueueFilter f) {
    Where w = where(scope, f, true);
    StringBuilder sql =
        new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ticket WHERE ").append(w.sql);
    QueueSort sort = f.sort() == null ? QueueSort.NEWEST : f.sort();
    sql.append(
        switch (sort) {
          case NEWEST -> " ORDER BY id DESC";
          case OLDEST -> " ORDER BY id ASC";
          case UPDATED -> " ORDER BY updated_at DESC, id DESC";
          case PRIORITY -> " ORDER BY priority ASC, id DESC";
          case FIRST_RESPONSE ->
              " ORDER BY CASE WHEN first_response_state = 'BREACHED' AND first_responded_at IS NULL"
                  + " THEN 0 WHEN first_response_state = 'RUNNING' THEN 1"
                  + " WHEN first_response_state = 'PAUSED' THEN 2 ELSE 3 END,"
                  + " CASE WHEN first_response_state IN ('BREACHED', 'RUNNING')"
                  + " AND first_responded_at IS NULL THEN first_response_due_at END ASC NULLS LAST,"
                  + " id DESC";
        });
    sql.append(" LIMIT :fLimit");
    w.names.add("fLimit");
    w.values.add(f.limit());
    if (f.beforeId() == null && f.offset() > 0) {
      sql.append(" OFFSET :fOffset");
      w.names.add("fOffset");
      w.values.add(f.offset());
    }
    return ScopeSql.bind(w.spec(jdbc, sql.toString()), scope).query(TicketRepository::map).list();
  }

  /** How many tickets match the filter (paging and cursor ignored). */
  public long count(AccessScope scope, QueueFilter f) {
    Where w = where(scope, f, false);
    return ScopeSql.bind(w.spec(jdbc, "SELECT count(*) FROM ticket WHERE " + w.sql), scope)
        .query(Long.class)
        .single();
  }

  private record Where(String sql, List<String> names, List<Object> values) {
    JdbcClient.StatementSpec spec(JdbcClient jdbc, String sql) {
      JdbcClient.StatementSpec spec = jdbc.sql(sql);
      for (int i = 0; i < names.size(); i++) {
        spec = spec.param(names.get(i), values.get(i));
      }
      return spec;
    }
  }

  private static Where where(AccessScope scope, QueueFilter f, boolean withCursor) {
    StringBuilder sql = new StringBuilder(ScopeSql.organisationOrTriage(scope, "organisation_id"));
    List<String> names = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    if (f.triageOnly()) {
      sql.append(" AND organisation_id IS NULL");
    } else if (f.organisationId() != null) {
      sql.append(" AND organisation_id = :fOrg");
      names.add("fOrg");
      values.add(f.organisationId());
    } else if (f.excludeTriage()) {
      sql.append(" AND organisation_id IS NOT NULL");
    }
    if (f.status() != null) {
      sql.append(" AND status = :fStatus");
      names.add("fStatus");
      values.add(f.status().name());
    } else if (!f.includeClosed()) {
      sql.append(" AND status <> 'CLOSED'");
    }
    if (f.priority() != null) {
      sql.append(" AND priority = :fPriority");
      names.add("fPriority");
      values.add(f.priority().name());
    }
    if (f.unassignedOnly()) {
      sql.append(" AND assignee_user_id IS NULL");
    } else if (f.assigneeUserId() != null) {
      sql.append(" AND assignee_user_id = :fAssignee");
      names.add("fAssignee");
      values.add(f.assigneeUserId());
    }
    if (f.breachingOnly()) {
      // "Breaching" view: about to breach (80%) or breached.
      sql.append(" AND sla_warning <> 'NONE'");
    }
    if (f.search() != null && !f.search().isBlank()) {
      sql.append(
          " AND (subject ILIKE :fSearch ESCAPE '\\' OR reporter_email ILIKE :fSearch ESCAPE"
              + " '\\'");
      names.add("fSearch");
      values.add("%" + escapeLike(f.search().strip()) + "%");
      if (f.searchTicketId() != null) {
        sql.append(" OR id = :fSearchId");
        names.add("fSearchId");
        values.add(f.searchTicketId());
      }
      sql.append(")");
    }
    if (withCursor && f.beforeId() != null) {
      sql.append(" AND id < :fBefore");
      names.add("fBefore");
      values.add(f.beforeId());
    }
    return new Where(sql.toString(), names, values);
  }

  /** Escapes LIKE wildcards so a typed % or _ matches itself. */
  static String escapeLike(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** Sets the reporter to a saved contact (triage "remember" tick). */
  public void updateReporter(AccessScope scope, TicketRow t, long reporterUserId, Instant now) {
    requireWritable(scope, t.organisationId());
    update(scope, t, "reporter_user_id = :v, updated_at = :now", reporterUserId, now);
  }

  /** Visible tickets among {@code ids} (for links), in id order. */
  public List<TicketRow> findAll(AccessScope scope, List<Long> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT "
                        + COLUMNS
                        + " FROM ticket WHERE id IN (:ids) AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id")
                        + " ORDER BY id")
                .param("ids", ids),
            scope)
        .query(TicketRepository::map)
        .list();
  }

  /**
   * Sets the status. Any status other than Closed also clears the close reason and note (they
   * describe the current close only); a close sets them with {@link #updateCloseReason}.
   */
  public void updateStatus(AccessScope scope, TicketRow t, TicketStatus status, Instant now) {
    requireWritable(scope, t.organisationId());
    update(
        scope,
        t,
        status == TicketStatus.CLOSED
            ? "status = :v, status_changed_at = :now, updated_at = :now"
            : "status = :v, status_changed_at = :now, updated_at = :now, close_reason = NULL,"
                + " close_note = NULL",
        status.name(),
        now);
  }

  /** A ticket's current close reason (CloseReason name) and Other note. */
  public record CloseInfo(String reason, String note) {}

  /**
   * The current close reason of each of {@code ticketIds} that has one, scoped like every read
   * (agent read model and export only).
   */
  public Map<Long, CloseInfo> closeReasons(AccessScope scope, List<Long> ticketIds) {
    Map<Long, CloseInfo> out = new HashMap<>();
    if (ticketIds.isEmpty()) {
      return out;
    }
    ScopeSql.bind(
            jdbc.sql(
                    "SELECT id, close_reason, close_note FROM ticket WHERE id IN (:ids) AND"
                        + " close_reason IS NOT NULL AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id"))
                .param("ids", ticketIds),
            scope)
        .query(
            (rs, n) ->
                out.put(
                    rs.getLong("id"),
                    new CloseInfo(rs.getString("close_reason"), rs.getString("close_note"))))
        .list();
    return out;
  }

  /** The reason (CloseReason name) and Other note of the ticket's current close. */
  public void updateCloseReason(AccessScope scope, TicketRow t, String reason, String note) {
    requireWritable(scope, t.organisationId());
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "UPDATE ticket SET close_reason = :reason, close_note = :note WHERE id ="
                            + " :id AND "
                            + ScopeSql.organisationOrTriage(scope, "organisation_id"))
                    .param("reason", reason)
                    .param("note", note)
                    .param("id", t.id()),
                scope)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
  }

  public void updatePriority(AccessScope scope, TicketRow t, Priority priority, Instant now) {
    requireWritable(scope, t.organisationId());
    update(scope, t, "priority = :v, updated_at = :now", priority.name(), now);
  }

  public void updateRequestType(AccessScope scope, TicketRow t, long requestTypeId, Instant now) {
    requireWritable(scope, t.organisationId());
    update(scope, t, "request_type_id = :v, updated_at = :now", requestTypeId, now);
  }

  public void updateAssignee(AccessScope scope, TicketRow t, Long assigneeUserId, Instant now) {
    requireWritable(scope, t.organisationId());
    update(scope, t, "assignee_user_id = :v, updated_at = :now", assigneeUserId, now);
  }

  /** Moves the ticket to {@code organisationId} (triage assignment or move). */
  public void updateOrganisation(AccessScope scope, TicketRow t, long organisationId, Instant now) {
    requireWritable(scope, t.organisationId());
    scope.require(organisationId);
    update(scope, t, "organisation_id = :v, updated_at = :now", organisationId, now);
  }

  /**
   * Closes {@code t} as a duplicate of {@code targetId} in one statement (the check needs both).
   */
  public void markDuplicate(AccessScope scope, TicketRow t, long targetId, Instant now) {
    requireWritable(scope, t.organisationId());
    update(
        scope,
        t,
        "duplicate_of_id = :v, status = 'CLOSED', status_changed_at = :now, updated_at = :now,"
            + " close_reason = 'DUPLICATE', close_note = NULL",
        targetId,
        now);
  }

  /**
   * Tickets already marked as duplicates of {@code oldTargetId} now point at {@code newTargetId} (a
   * chain is kept flat). Both are in {@code organisationId}.
   */
  public int repointDuplicates(
      AccessScope scope, long organisationId, long oldTargetId, long newTargetId, Instant now) {
    scope.require(organisationId);
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "UPDATE ticket SET duplicate_of_id = :newTarget, updated_at = :now WHERE"
                            + " duplicate_of_id = :oldTarget AND organisation_id = :org AND "
                            + ScopeSql.organisation(scope, "organisation_id")
                            + " AND "
                            + ScopeSql.ticketInOrganisation(scope, ":newTarget", "org"))
                    .param("newTarget", newTargetId)
                    .param("oldTarget", oldTargetId)
                    .param("org", organisationId)
                    .param("now", Timestamp.from(now)),
                scope)
            .update();
    if (n == 0 && !ScopeSql.isTicketInOrganisation(jdbc, scope, newTargetId, organisationId)) {
      throw AccessScope.notFound();
    }
    return n;
  }

  /** Sets updated_at (a comment was added); 404 when the ticket is missing or not visible. */
  public void touch(AccessScope scope, TicketRow t, Instant now) {
    requireWritable(scope, t.organisationId());
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "UPDATE ticket SET updated_at = :now WHERE id = :id AND "
                            + ScopeSql.organisationOrTriage(scope, "organisation_id"))
                    .param("now", Timestamp.from(now))
                    .param("id", t.id()),
                scope)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
  }

  private void update(AccessScope scope, TicketRow t, String set, Object value, Instant now) {
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "UPDATE ticket SET "
                            + set
                            + " WHERE id = :id AND "
                            + ScopeSql.organisationOrTriage(scope, "organisation_id"))
                    .param("v", value)
                    .param("now", Timestamp.from(now))
                    .param("id", t.id()),
                scope)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
  }

  private static void requireWritable(AccessScope scope, Long organisationId) {
    if (organisationId == null) {
      scope.requireSupporterOrSystem();
    } else {
      scope.require(organisationId);
    }
  }

  private static TicketRow map(ResultSet rs, int row) throws SQLException {
    String impact = rs.getString("impact");
    String urgency = rs.getString("urgency");
    return new TicketRow(
        rs.getLong("id"),
        rs.getObject("organisation_id", Long.class),
        rs.getObject("reporter_user_id", Long.class),
        rs.getString("reporter_email"),
        rs.getObject("assignee_user_id", Long.class),
        rs.getLong("request_type_id"),
        Priority.valueOf(rs.getString("priority")),
        impact == null ? null : Impact.valueOf(impact),
        urgency == null ? null : Urgency.valueOf(urgency),
        TicketStatus.valueOf(rs.getString("status")),
        TicketSource.valueOf(rs.getString("source")),
        rs.getString("subject"),
        rs.getString("external_id"),
        rs.getObject("duplicate_of_id", Long.class),
        rs.getObject("follow_up_of_id", Long.class),
        rs.getObject("created_by_user_id", Long.class),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("status_changed_at").toInstant());
  }
}
