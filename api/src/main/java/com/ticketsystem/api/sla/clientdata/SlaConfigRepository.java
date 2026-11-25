package com.ticketsystem.api.sla.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.ClockType;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.SlaTarget;
import com.ticketsystem.api.sla.config.SlaConfigTypes.HolidayRow;
import com.ticketsystem.api.sla.config.SlaConfigTypes.HoursRow;
import com.ticketsystem.api.sla.config.SlaConfigTypes.SettingsRow;
import com.ticketsystem.api.sla.config.SlaConfigTypes.TargetRow;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SLA configuration per organisation: timer settings and default assignee, weekly business hours,
 * holidays, SLA policies (organisation default plus request-type overrides) and the request-type
 * default assignee. Internal only: reads need a supporter or SYSTEM scope that sees the
 * organisation (404 otherwise, customers and API keys included); writes need an admin (or SYSTEM
 * for seeding and deactivation clean-up).
 */
@Repository
public class SlaConfigRepository {

  /** A policy: {@code requestTypeId} null = the organisation default. */
  public record PolicyRow(
      long id,
      long organisationId,
      Long requestTypeId,
      boolean pendingOnVendorPauses,
      Map<Priority, TargetRow> targets) {}

  private final JdbcClient jdbc;

  public SlaConfigRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ---------------------------------------------------------------------------------------------
  // Settings

  public Optional<SettingsRow> settings(AccessScope scope, long organisationId) {
    requireRead(scope, organisationId);
    return jdbc.sql(
            "SELECT pending_reminder_enabled, pending_reminder_business_days,"
                + " auto_resolve_enabled, auto_resolve_business_days, auto_close_enabled,"
                + " auto_close_calendar_days, api_alert_client_emails, default_assignee_user_id"
                + " FROM organisation_sla_settings WHERE organisation_id = :org")
        .param("org", organisationId)
        .query(
            (rs, n) ->
                new SettingsRow(
                    rs.getBoolean("pending_reminder_enabled"),
                    rs.getInt("pending_reminder_business_days"),
                    rs.getBoolean("auto_resolve_enabled"),
                    rs.getInt("auto_resolve_business_days"),
                    rs.getBoolean("auto_close_enabled"),
                    rs.getInt("auto_close_calendar_days"),
                    rs.getBoolean("api_alert_client_emails"),
                    rs.getObject("default_assignee_user_id", Long.class)))
        .optional();
  }

  /** Inserts or replaces the timer settings (the default assignee is kept). */
  public void saveTimers(AccessScope scope, long organisationId, SettingsRow s, Instant now) {
    requireWrite(scope, organisationId);
    jdbc.sql(
            "INSERT INTO organisation_sla_settings (organisation_id, pending_reminder_enabled,"
                + " pending_reminder_business_days, auto_resolve_enabled,"
                + " auto_resolve_business_days, auto_close_enabled, auto_close_calendar_days,"
                + " api_alert_client_emails, updated_at) VALUES (:org, :re, :rd, :ae, :ad, :ce,"
                + " :cd, :api, :now) ON CONFLICT (organisation_id) DO UPDATE SET"
                + " pending_reminder_enabled = EXCLUDED.pending_reminder_enabled,"
                + " pending_reminder_business_days = EXCLUDED.pending_reminder_business_days,"
                + " auto_resolve_enabled = EXCLUDED.auto_resolve_enabled,"
                + " auto_resolve_business_days = EXCLUDED.auto_resolve_business_days,"
                + " auto_close_enabled = EXCLUDED.auto_close_enabled, auto_close_calendar_days ="
                + " EXCLUDED.auto_close_calendar_days, api_alert_client_emails ="
                + " EXCLUDED.api_alert_client_emails, updated_at = EXCLUDED.updated_at")
        .param("org", organisationId)
        .param("re", s.pendingReminderEnabled())
        .param("rd", s.pendingReminderBusinessDays())
        .param("ae", s.autoResolveEnabled())
        .param("ad", s.autoResolveBusinessDays())
        .param("ce", s.autoCloseEnabled())
        .param("cd", s.autoCloseCalendarDays())
        .param("api", s.apiAlertClientEmails())
        .param("now", Timestamp.from(now))
        .update();
  }

  public void setDefaultAssignee(AccessScope scope, long organisationId, Long userId, Instant now) {
    requireWrite(scope, organisationId);
    jdbc.sql(
            "INSERT INTO organisation_sla_settings (organisation_id, default_assignee_user_id,"
                + " updated_at) VALUES (:org, :user, :now) ON CONFLICT (organisation_id) DO UPDATE"
                + " SET default_assignee_user_id = EXCLUDED.default_assignee_user_id, updated_at"
                + " = EXCLUDED.updated_at")
        .param("org", organisationId)
        .param("user", userId)
        .param("now", Timestamp.from(now))
        .update();
  }

  // ---------------------------------------------------------------------------------------------
  // Calendar

  public List<HoursRow> businessHours(AccessScope scope, long organisationId) {
    requireRead(scope, organisationId);
    return jdbc.sql(
            "SELECT day_of_week, opens_at, closes_at FROM organisation_business_hours WHERE"
                + " organisation_id = :org ORDER BY day_of_week")
        .param("org", organisationId)
        .query(
            (rs, n) ->
                new HoursRow(
                    rs.getInt("day_of_week"),
                    rs.getTime("opens_at").toLocalTime(),
                    rs.getTime("closes_at").toLocalTime()))
        .list();
  }

  public void replaceBusinessHours(AccessScope scope, long organisationId, List<HoursRow> hours) {
    requireWrite(scope, organisationId);
    jdbc.sql("DELETE FROM organisation_business_hours WHERE organisation_id = :org")
        .param("org", organisationId)
        .update();
    for (HoursRow h : hours) {
      jdbc.sql(
              "INSERT INTO organisation_business_hours (organisation_id, day_of_week, opens_at,"
                  + " closes_at) VALUES (:org, :day, :opens, :closes)")
          .param("org", organisationId)
          .param("day", h.dayOfWeek())
          .param("opens", Time.valueOf(h.opens()))
          .param("closes", Time.valueOf(h.closes()))
          .update();
    }
  }

  public List<HolidayRow> holidays(AccessScope scope, long organisationId) {
    requireRead(scope, organisationId);
    return jdbc.sql(
            "SELECT holiday_date, name FROM organisation_holiday WHERE organisation_id = :org"
                + " ORDER BY holiday_date")
        .param("org", organisationId)
        .query(
            (rs, n) ->
                new HolidayRow(rs.getDate("holiday_date").toLocalDate(), rs.getString("name")))
        .list();
  }

  public void replaceHolidays(AccessScope scope, long organisationId, List<HolidayRow> holidays) {
    requireWrite(scope, organisationId);
    jdbc.sql("DELETE FROM organisation_holiday WHERE organisation_id = :org")
        .param("org", organisationId)
        .update();
    for (HolidayRow h : holidays) {
      jdbc.sql(
              "INSERT INTO organisation_holiday (organisation_id, holiday_date, name) VALUES"
                  + " (:org, :date, :name)")
          .param("org", organisationId)
          .param("date", Date.valueOf(h.date()))
          .param("name", h.name())
          .update();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Policies

  /** Every policy of the organisation, default first. */
  public List<PolicyRow> policies(AccessScope scope, long organisationId) {
    requireRead(scope, organisationId);
    Map<Long, PolicyRow> byId = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT p.id, p.request_type_id, p.pending_on_vendor_pauses, t.priority,"
                + " t.first_response_amount, t.first_response_unit, t.resolution_amount,"
                + " t.resolution_unit, t.clock FROM sla_policy p LEFT JOIN sla_policy_target t ON"
                + " t.policy_id = p.id WHERE p.organisation_id = :org ORDER BY p.request_type_id"
                + " NULLS FIRST, p.id, t.priority")
        .param("org", organisationId)
        .query(
            rs -> {
              long id = rs.getLong("id");
              PolicyRow p =
                  byId.computeIfAbsent(
                      id,
                      k -> {
                        try {
                          return new PolicyRow(
                              k,
                              organisationId,
                              rs.getObject("request_type_id", Long.class),
                              rs.getBoolean("pending_on_vendor_pauses"),
                              new EnumMap<>(Priority.class));
                        } catch (java.sql.SQLException e) {
                          throw new IllegalStateException(e);
                        }
                      });
              String priority = rs.getString("priority");
              if (priority != null) {
                p.targets()
                    .put(
                        Priority.valueOf(priority),
                        new TargetRow(
                            new SlaTarget(
                                rs.getInt("first_response_amount"),
                                SlaTarget.Unit.valueOf(rs.getString("first_response_unit"))),
                            new SlaTarget(
                                rs.getInt("resolution_amount"),
                                SlaTarget.Unit.valueOf(rs.getString("resolution_unit"))),
                            ClockType.valueOf(rs.getString("clock"))));
              }
            });
    return new ArrayList<>(byId.values());
  }

  /** Inserts or replaces a policy and all its targets; returns its id. */
  public long savePolicy(
      AccessScope scope,
      long organisationId,
      Long requestTypeId,
      boolean pendingOnVendorPauses,
      Map<Priority, TargetRow> targets,
      Instant now) {
    requireWrite(scope, organisationId);
    Long existing =
        jdbc.sql(
                "SELECT id FROM sla_policy WHERE organisation_id = :org AND"
                    + " COALESCE(request_type_id, 0) = COALESCE(CAST(:type AS BIGINT), 0) FOR"
                    + " UPDATE")
            .param("org", organisationId)
            .param("type", requestTypeId)
            .query(Long.class)
            .optional()
            .orElse(null);
    long id;
    if (existing == null) {
      id =
          jdbc.sql(
                  "INSERT INTO sla_policy (organisation_id, request_type_id,"
                      + " pending_on_vendor_pauses, created_at, updated_at) VALUES (:org, :type,"
                      + " :vendor, :now, :now) RETURNING id")
              .param("org", organisationId)
              .param("type", requestTypeId)
              .param("vendor", pendingOnVendorPauses)
              .param("now", Timestamp.from(now))
              .query(Long.class)
              .single();
    } else {
      id = existing;
      jdbc.sql(
              "UPDATE sla_policy SET pending_on_vendor_pauses = :vendor, updated_at = :now WHERE"
                  + " id = :id")
          .param("vendor", pendingOnVendorPauses)
          .param("now", Timestamp.from(now))
          .param("id", id)
          .update();
      jdbc.sql("DELETE FROM sla_policy_target WHERE policy_id = :id").param("id", id).update();
    }
    for (Map.Entry<Priority, TargetRow> e : targets.entrySet()) {
      TargetRow t = e.getValue();
      jdbc.sql(
              "INSERT INTO sla_policy_target (policy_id, priority, first_response_amount,"
                  + " first_response_unit, resolution_amount, resolution_unit, clock) VALUES"
                  + " (:id, :p, :fa, :fu, :ra, :ru, :clock)")
          .param("id", id)
          .param("p", e.getKey().name())
          .param("fa", t.firstResponse().amount())
          .param("fu", t.firstResponse().unit().name())
          .param("ra", t.resolution().amount())
          .param("ru", t.resolution().unit().name())
          .param("clock", t.clock().name())
          .update();
    }
    return id;
  }

  /** Removes a request-type override (the organisation default then applies). */
  public boolean deleteRequestTypePolicy(
      AccessScope scope, long organisationId, long requestTypeId) {
    requireWrite(scope, organisationId);
    return jdbc.sql(
                "DELETE FROM sla_policy WHERE organisation_id = :org AND request_type_id = :type")
            .param("org", organisationId)
            .param("type", requestTypeId)
            .update()
        > 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Default assignees

  /** The request type's default assignee (request types are shared by every organisation). */
  public Optional<Long> requestTypeDefaultAssignee(AccessScope scope, long requestTypeId) {
    scope.requireSupporterOrSystem();
    return jdbc.sql("SELECT default_assignee_user_id FROM request_type WHERE id = :id")
        .param("id", requestTypeId)
        .query((rs, n) -> Optional.ofNullable(rs.getObject("default_assignee_user_id", Long.class)))
        .optional()
        .flatMap(o -> o);
  }

  public boolean setRequestTypeDefaultAssignee(AccessScope scope, long requestTypeId, Long userId) {
    scope.requireAdmin();
    return jdbc.sql("UPDATE request_type SET default_assignee_user_id = :user WHERE id = :id")
            .param("user", userId)
            .param("id", requestTypeId)
            .update()
        == 1;
  }

  /**
   * Clears {@code userId} from every default-assignee setting (deactivation). Admin or SYSTEM;
   * returns how many settings were cleared.
   */
  public int clearDefaultAssignee(AccessScope scope, long userId, Instant now) {
    if (!scope.isAdmin() && !scope.isSystem()) {
      throw AccessScope.notFound();
    }
    int n =
        jdbc.sql(
                "UPDATE organisation_sla_settings SET default_assignee_user_id = NULL, updated_at"
                    + " = :now WHERE default_assignee_user_id = :user")
            .param("user", userId)
            .param("now", Timestamp.from(now))
            .update();
    n +=
        jdbc.sql(
                "UPDATE request_type SET default_assignee_user_id = NULL WHERE"
                    + " default_assignee_user_id = :user")
            .param("user", userId)
            .update();
    return n;
  }

  private static void requireRead(AccessScope scope, long organisationId) {
    if (!scope.isSupporter() && !scope.isSystem()) {
      throw AccessScope.notFound();
    }
    scope.require(organisationId);
  }

  private static void requireWrite(AccessScope scope, long organisationId) {
    if (!scope.isAdmin() && !scope.isSystem()) {
      throw AccessScope.notFound();
    }
    scope.require(organisationId);
  }
}
