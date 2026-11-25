package com.ticketsystem.api.sla.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import com.ticketsystem.api.sla.ClockType;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.SlaWarning;
import com.ticketsystem.api.sla.TargetState;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SLA columns of {@code ticket} and the facts they are calculated from (status history, the
 * first supporter public reply), plus the work lists of the SLA jobs. SLA data is internal: every
 * method needs a supporter or SYSTEM scope (customers and API keys get 404 or nothing), and reads
 * apply the scope in SQL. Triage tickets (no organisation) are included for supporters and SYSTEM
 * (every supporter can see triage): they run the default SLA. The Pending and Resolved timers still
 * skip them (per-organisation settings).
 */
@Repository
public class TicketSlaRepository {

  /** A ticket with the SLA values stored on it. */
  public record SlaTicketRow(
      long id,
      Long organisationId,
      long requestTypeId,
      Priority priority,
      TicketStatus status,
      TicketSource source,
      Long assigneeUserId,
      Long duplicateOfId,
      Instant createdAt,
      Instant updatedAt,
      Instant statusChangedAt,
      Instant firstResponseDueAt,
      Instant resolutionDueAt,
      Instant firstResponseAtRiskNotifiedAt,
      Instant firstResponseBreachNotifiedAt,
      Instant resolutionAtRiskNotifiedAt,
      Instant resolutionBreachNotifiedAt,
      Instant p1AlertedAt,
      Instant slaCalculatedAt,
      Instant pendingReminderSentAt) {}

  /** Values written by a recalculation. */
  public record SlaValues(
      ClockType clock,
      boolean policyFallback,
      long firstResponseTargetMinutes,
      Instant firstResponseDueAt,
      Instant firstResponseWarnAt,
      Instant firstRespondedAt,
      long firstResponseUsedMinutes,
      TargetState firstResponseState,
      long resolutionTargetMinutes,
      Instant resolutionDueAt,
      Instant resolutionWarnAt,
      Instant resolvedAt,
      long resolutionUsedMinutes,
      TargetState resolutionState,
      long pausedMinutes,
      SlaWarning.Level warning,
      Instant calculatedAt,
      Instant nextCheckAt,
      Instant firstResponseAtRiskNotifiedAt,
      Instant firstResponseBreachNotifiedAt,
      Instant resolutionAtRiskNotifiedAt,
      Instant resolutionBreachNotifiedAt,
      Instant p1AlertedAt) {}

  /** A status change from the ticket history. */
  public record StatusEvent(TicketStatus status, Instant at) {}

  /** The SLA fields as the agent read model shows them (internal only). */
  public record SlaFields(
      ClockType clock,
      Long firstResponseTargetMinutes,
      Instant firstResponseDueAt,
      Instant firstRespondedAt,
      Long firstResponseUsedMinutes,
      TargetState firstResponseState,
      Long resolutionTargetMinutes,
      Instant resolutionDueAt,
      Instant resolvedAt,
      Long resolutionUsedMinutes,
      TargetState resolutionState,
      Long pausedMinutes,
      SlaWarning.Level warning,
      Instant calculatedAt) {}

  /** A ticket waiting on a timer (Pending on client or Resolved). */
  public record TimerRow(
      long id,
      long organisationId,
      TicketStatus status,
      TicketSource source,
      Instant statusChangedAt,
      Instant pendingReminderSentAt) {}

  private static final String COLUMNS =
      "id, organisation_id, request_type_id, priority, status, source, assignee_user_id,"
          + " duplicate_of_id, created_at, updated_at, status_changed_at, first_response_due_at,"
          + " resolution_due_at, first_response_at_risk_notified_at,"
          + " first_response_breach_notified_at, resolution_at_risk_notified_at,"
          + " resolution_breach_notified_at, p1_alerted_at, sla_calculated_at,"
          + " pending_reminder_sent_at";

  private final JdbcClient jdbc;

  public TicketSlaRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Locks the ticket row for the rest of the transaction; empty when not visible. */
  public Optional<SlaTicketRow> lock(AccessScope scope, long ticketId) {
    requireSupporterOrSystem(scope);
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT "
                        + COLUMNS
                        + " FROM ticket WHERE id = :id AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id")
                        + " FOR UPDATE")
                .param("id", ticketId),
            scope)
        .query(TicketSlaRepository::map)
        .optional();
  }

  /** Reads the ticket row without locking it (for the SLA panel); empty when not visible. */
  public Optional<SlaTicketRow> find(AccessScope scope, long ticketId) {
    requireSupporterOrSystem(scope);
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT "
                        + COLUMNS
                        + " FROM ticket WHERE id = :id AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id"))
                .param("id", ticketId),
            scope)
        .query(TicketSlaRepository::map)
        .optional();
  }

  /**
   * The per-target SLA state the last recalculation stored (null before the first one), with its
   * 80% instant and when it ran, so the next recalculation can tell what changed.
   */
  public record StoredState(
      TargetState firstResponseState,
      Instant firstResponseWarnAt,
      TargetState resolutionState,
      Instant resolutionWarnAt,
      Instant calculatedAt) {}

  /** The stored states of the ticket (read in the recalculation's transaction). */
  public Optional<StoredState> storedState(AccessScope scope, long ticketId) {
    requireSupporterOrSystem(scope);
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT first_response_state, first_response_warn_at, resolution_state,"
                        + " resolution_warn_at, sla_calculated_at FROM ticket WHERE id = :id AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id"))
                .param("id", ticketId),
            scope)
        .query(
            (rs, n) ->
                new StoredState(
                    state(rs.getString("first_response_state")),
                    instant(rs, "first_response_warn_at"),
                    state(rs.getString("resolution_state")),
                    instant(rs, "resolution_warn_at"),
                    instant(rs, "sla_calculated_at")))
        .optional();
  }

  private static TargetState state(String value) {
    return value == null ? null : TargetState.valueOf(value);
  }

  /** Status changes, oldest first (the creation status is not in the history). */
  public List<StatusEvent> statusChanges(AccessScope scope, long ticketId) {
    requireSupporterOrSystem(scope);
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT e.new_value, e.created_at FROM ticket_event e JOIN ticket t ON t.id ="
                        + " e.ticket_id WHERE e.ticket_id = :t AND e.type = 'STATUS' AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id")
                        + " ORDER BY e.id")
                .param("t", ticketId),
            scope)
        .query(
            (rs, n) ->
                new StatusEvent(
                    TicketStatus.valueOf(rs.getString("new_value")),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /**
   * The first public reply by a supporter (the only thing that meets first response; client
   * replies, internal notes and automatic emails never count). The description of a ticket an agent
   * opened (stored as the agent's message at the creation instant) is the request itself, not a
   * response, so it does not count either.
   */
  public Optional<Instant> firstSupporterReply(AccessScope scope, long ticketId) {
    requireSupporterOrSystem(scope);
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT min(c.created_at) FROM public_comment c JOIN ticket t ON t.id ="
                        + " c.ticket_id WHERE c.ticket_id = :t AND c.author_kind = 'SUPPORTER' AND"
                        + " NOT (t.source = 'AGENT' AND c.created_at = t.created_at AND c.id ="
                        + " (SELECT min(f.id) FROM public_comment f WHERE f.ticket_id = t.id)) AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id"))
                .param("t", ticketId),
            scope)
        .query((rs, n) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant))
        .optional()
        .flatMap(o -> o);
  }

  /**
   * Writes a recalculation (never touches updated_at, which belongs to the ticket workflow). {@code
   * organisationId} is the one the row was locked with (null for triage); a ticket moved since then
   * matches nothing and fails.
   */
  public void save(AccessScope scope, long ticketId, Long organisationId, SlaValues v) {
    requireSupporterOrSystem(scope);
    if (organisationId != null) {
      scope.require(organisationId);
    } else if (!scope.canSeeTriage()) {
      throw AccessScope.notFound();
    }
    int n =
        jdbc.sql(
                "UPDATE ticket SET sla_clock = :clock, sla_policy_fallback = :fallback,"
                    + " first_response_target_minutes = :frTarget, first_response_due_at = :frDue,"
                    + " first_response_warn_at = :frWarn, first_responded_at = :responded,"
                    + " first_response_used_minutes = :frUsed, first_response_state = :frState,"
                    + " resolution_target_minutes = :resTarget, resolution_due_at = :resDue,"
                    + " resolution_warn_at = :resWarn, resolved_at = :resolved,"
                    + " resolution_used_minutes = :resUsed, resolution_state = :resState,"
                    + " sla_paused_minutes = :paused, sla_warning = :warning, sla_calculated_at ="
                    + " :calculated, sla_next_check_at = :next, first_response_at_risk_notified_at"
                    + " = :frRisk, first_response_breach_notified_at = :frBreach,"
                    + " resolution_at_risk_notified_at = :resRisk, resolution_breach_notified_at ="
                    + " :resBreach, p1_alerted_at = :p1 WHERE id = :id AND organisation_id IS NOT"
                    + " DISTINCT FROM CAST(:org AS bigint)")
            .param("clock", v.clock().name())
            .param("fallback", v.policyFallback())
            .param("frTarget", v.firstResponseTargetMinutes())
            .param("frDue", ts(v.firstResponseDueAt()))
            .param("frWarn", ts(v.firstResponseWarnAt()))
            .param("responded", ts(v.firstRespondedAt()))
            .param("frUsed", v.firstResponseUsedMinutes())
            .param("frState", v.firstResponseState().name())
            .param("resTarget", v.resolutionTargetMinutes())
            .param("resDue", ts(v.resolutionDueAt()))
            .param("resWarn", ts(v.resolutionWarnAt()))
            .param("resolved", ts(v.resolvedAt()))
            .param("resUsed", v.resolutionUsedMinutes())
            .param("resState", v.resolutionState().name())
            .param("paused", v.pausedMinutes())
            .param("warning", v.warning().name())
            .param("calculated", ts(v.calculatedAt()))
            .param("next", ts(v.nextCheckAt()))
            .param("frRisk", ts(v.firstResponseAtRiskNotifiedAt()))
            .param("frBreach", ts(v.firstResponseBreachNotifiedAt()))
            .param("resRisk", ts(v.resolutionAtRiskNotifiedAt()))
            .param("resBreach", ts(v.resolutionBreachNotifiedAt()))
            .param("p1", ts(v.p1AlertedAt()))
            .param("id", ticketId)
            .param("org", organisationId)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
  }

  /** SLA fields for the agent read model (triage included); never-calculated tickets are absent. */
  public Map<Long, SlaFields> fieldsFor(AccessScope scope, Collection<Long> ticketIds) {
    Map<Long, SlaFields> out = new HashMap<>();
    if (ticketIds.isEmpty() || (!scope.isSupporter() && !scope.isSystem())) {
      return out;
    }
    ScopeSql.bind(
            jdbc.sql(
                    "SELECT id, sla_clock, first_response_target_minutes, first_response_due_at,"
                        + " first_responded_at, first_response_used_minutes, first_response_state,"
                        + " resolution_target_minutes, resolution_due_at, resolved_at,"
                        + " resolution_used_minutes, resolution_state, sla_paused_minutes,"
                        + " sla_warning, sla_calculated_at FROM ticket WHERE id IN (:ids) AND"
                        + " sla_calculated_at IS NOT NULL AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id"))
                .param("ids", ticketIds),
            scope)
        .query(
            rs -> {
              out.put(
                  rs.getLong("id"),
                  new SlaFields(
                      ClockType.valueOf(rs.getString("sla_clock")),
                      rs.getObject("first_response_target_minutes", Long.class),
                      instant(rs, "first_response_due_at"),
                      instant(rs, "first_responded_at"),
                      rs.getObject("first_response_used_minutes", Long.class),
                      TargetState.valueOf(rs.getString("first_response_state")),
                      rs.getObject("resolution_target_minutes", Long.class),
                      instant(rs, "resolution_due_at"),
                      instant(rs, "resolved_at"),
                      rs.getObject("resolution_used_minutes", Long.class),
                      TargetState.valueOf(rs.getString("resolution_state")),
                      rs.getObject("sla_paused_minutes", Long.class),
                      SlaWarning.Level.valueOf(rs.getString("sla_warning")),
                      instant(rs, "sla_calculated_at")));
            });
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Job work lists (SYSTEM scope)

  /**
   * The "needs (re)calculating" half of the work list. Its WHERE clause is the predicate of the
   * partial index {@code ticket_sla_work_ix} (V98), written identically so the planner can use it;
   * change both together ({@code TicketSlaWorkListIndexIntegrationTest} checks the plan).
   */
  static final String STALE_WORK_LIST_SQL =
      "SELECT id FROM ticket WHERE (status <> 'CLOSED' AND (sla_calculated_at IS NULL OR"
          + " sla_calculated_at <= updated_at)) OR (organisation_id IS NULL AND"
          + " sla_calculated_at IS NULL) ORDER BY id LIMIT :limit";

  /**
   * Tickets the minute job must (re)calculate: open and never calculated or changed since the last
   * calculation, a never-calculated triage ticket even when Closed (so triage tickets filed before
   * they had an SLA get their values once), or one whose next 80%/breach instant has come. Oldest
   * first.
   */
  public List<Long> needingRecalculation(AccessScope scope, Instant now, int limit) {
    requireSystem(scope);
    Set<Long> ids = new LinkedHashSet<>();
    ids.addAll(jdbc.sql(STALE_WORK_LIST_SQL).param("limit", limit).query(Long.class).list());
    ids.addAll(
        jdbc.sql(
                "SELECT id FROM ticket WHERE sla_next_check_at IS NOT NULL AND status <> 'CLOSED'"
                    + " AND sla_next_check_at <= :now ORDER BY sla_next_check_at LIMIT :limit")
            .param("now", Timestamp.from(now))
            .param("limit", limit)
            .query(Long.class)
            .list());
    return new ArrayList<>(ids);
  }

  /** New tickets not yet checked for auto-assignment. */
  public List<Long> awaitingAutoAssignment(AccessScope scope, int limit) {
    requireSystem(scope);
    return jdbc.sql(
            "SELECT id FROM ticket WHERE auto_assign_checked_at IS NULL ORDER BY id LIMIT :limit")
        .param("limit", limit)
        .query(Long.class)
        .list();
  }

  /** Marks auto-assignment as done for the ticket; false when it already was (idempotent). */
  public boolean claimAutoAssignment(AccessScope scope, long ticketId, Instant now) {
    requireSystem(scope);
    return jdbc.sql(
                "UPDATE ticket SET auto_assign_checked_at = :now WHERE id = :id AND"
                    + " auto_assign_checked_at IS NULL")
            .param("now", Timestamp.from(now))
            .param("id", ticketId)
            .update()
        == 1;
  }

  /** Tickets in {@code status} since before {@code changedBefore}, oldest first. */
  public List<TimerRow> inStatusSince(
      AccessScope scope, TicketStatus status, Instant changedBefore, int limit) {
    requireSystem(scope);
    return jdbc.sql(
            "SELECT id, organisation_id, status, source, status_changed_at,"
                + " pending_reminder_sent_at FROM ticket WHERE organisation_id IS NOT NULL AND"
                + " status = :status AND status_changed_at <= :before ORDER BY status_changed_at,"
                + " id LIMIT :limit")
        .param("status", status.name())
        .param("before", Timestamp.from(changedBefore))
        .param("limit", limit)
        .query(
            (rs, n) ->
                new TimerRow(
                    rs.getLong("id"),
                    rs.getLong("organisation_id"),
                    TicketStatus.valueOf(rs.getString("status")),
                    TicketSource.valueOf(rs.getString("source")),
                    rs.getTimestamp("status_changed_at").toInstant(),
                    instant(rs, "pending_reminder_sent_at")))
        .list();
  }

  public void markReminderSent(AccessScope scope, long ticketId, long organisationId, Instant now) {
    requireSystem(scope);
    jdbc.sql(
            "UPDATE ticket SET pending_reminder_sent_at = :now WHERE id = :id AND organisation_id"
                + " = :org")
        .param("now", Timestamp.from(now))
        .param("id", ticketId)
        .param("org", organisationId)
        .update();
  }

  /**
   * The client addresses of a ticket for the client email matrix: the reporter and confirmed
   * participants (external CCs wait for an agent's confirmation). Lower case, no duplicates.
   */
  public List<String> clientRecipients(AccessScope scope, long ticketId) {
    requireSystem(scope);
    Set<String> out = new LinkedHashSet<>();
    jdbc.sql(
            "SELECT lower(reporter_email) AS email FROM ticket WHERE id = :t AND reporter_email IS"
                + " NOT NULL UNION ALL SELECT p.email FROM ticket_participant p WHERE p.ticket_id ="
                + " :t AND p.confirmed")
        .param("t", ticketId)
        .query((rs, n) -> rs.getString("email"))
        .list()
        .forEach(out::add);
    return new ArrayList<>(out);
  }

  private static void requireSupporterOrSystem(AccessScope scope) {
    if (!scope.isSupporter() && !scope.isSystem()) {
      throw AccessScope.notFound();
    }
  }

  private static void requireSystem(AccessScope scope) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static SlaTicketRow map(ResultSet rs, int row) throws SQLException {
    return new SlaTicketRow(
        rs.getLong("id"),
        rs.getObject("organisation_id", Long.class),
        rs.getLong("request_type_id"),
        Priority.valueOf(rs.getString("priority")),
        TicketStatus.valueOf(rs.getString("status")),
        TicketSource.valueOf(rs.getString("source")),
        rs.getObject("assignee_user_id", Long.class),
        rs.getObject("duplicate_of_id", Long.class),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("status_changed_at").toInstant(),
        instant(rs, "first_response_due_at"),
        instant(rs, "resolution_due_at"),
        instant(rs, "first_response_at_risk_notified_at"),
        instant(rs, "first_response_breach_notified_at"),
        instant(rs, "resolution_at_risk_notified_at"),
        instant(rs, "resolution_breach_notified_at"),
        instant(rs, "p1_alerted_at"),
        instant(rs, "sla_calculated_at"),
        instant(rs, "pending_reminder_sent_at"));
  }
}
