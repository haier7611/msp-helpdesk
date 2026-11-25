package com.ticketsystem.api.sla.automation.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.automation.SlaAutomationRule;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Action;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Conditions;
import com.ticketsystem.api.sla.automation.SlaAutomationTrigger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code sla_automation_rule} and {@code sla_automation_firing}. Rules are installation
 * configuration (not client data): reads need an admin or SYSTEM scope (the engine), writes an
 * admin, firing records SYSTEM. The id checks used by validation read only ids and roles.
 */
@Repository
public class SlaAutomationRepository {

  private static final Logger log = LoggerFactory.getLogger(SlaAutomationRepository.class);

  private static final String COLUMNS =
      "id, name, enabled, sort_order, trigger_type, conditions::text AS conditions,"
          + " actions::text AS actions, created_at, updated_at";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public SlaAutomationRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Every rule in run order (position, then id). Admin or SYSTEM. */
  public List<SlaAutomationRule> list(AccessScope scope) {
    requireAdminOrSystem(scope);
    return jdbc.sql("SELECT " + COLUMNS + " FROM sla_automation_rule ORDER BY sort_order, id")
        .query(this::map)
        .list();
  }

  /** The enabled rules for one trigger, in run order. SYSTEM only (the engine). */
  public List<SlaAutomationRule> enabledFor(AccessScope scope, SlaAutomationTrigger trigger) {
    requireSystem(scope);
    return jdbc
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM sla_automation_rule WHERE enabled AND trigger_type = :trigger ORDER BY"
                + " sort_order, id")
        .param("trigger", trigger.name())
        .query(this::mapOrSkip)
        .list()
        .stream()
        .filter(Objects::nonNull)
        .toList();
  }

  /** As {@link #map}, but an unreadable rule is logged (id only) and skipped, not fatal. */
  private SlaAutomationRule mapOrSkip(ResultSet rs, int row) throws SQLException {
    try {
      return map(rs, row);
    } catch (JacksonException | IllegalArgumentException e) {
      log.warn("ADR-049 automation: rule {} cannot be read; skipped", rs.getLong("id"));
      return null;
    }
  }

  /** True when at least one enabled rule exists (lets the engine skip work cheaply). SYSTEM. */
  public boolean anyEnabled(AccessScope scope) {
    requireSystem(scope);
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sla_automation_rule WHERE enabled)")
        .query(Boolean.class)
        .single();
  }

  public Optional<SlaAutomationRule> find(AccessScope scope, long id) {
    requireAdminOrSystem(scope);
    return jdbc.sql("SELECT " + COLUMNS + " FROM sla_automation_rule WHERE id = :id")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  /**
   * Serialises rule creation and reordering for the rest of the transaction, so two admins adding a
   * rule at once get distinct positions and the rule cap holds. Admin.
   */
  public void lockRuleOrder(AccessScope scope) {
    scope.requireAdmin();
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("sla-automation-rule-order")
        .query((rs, n) -> 1)
        .list();
  }

  public int count(AccessScope scope) {
    scope.requireAdmin();
    return jdbc.sql("SELECT count(*) FROM sla_automation_rule").query(Integer.class).single();
  }

  /** A new rule at the end of the run order. */
  public long insert(
      AccessScope scope,
      String name,
      boolean enabled,
      SlaAutomationTrigger trigger,
      Conditions conditions,
      List<Action> actions,
      Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "INSERT INTO sla_automation_rule (name, enabled, sort_order, trigger_type, conditions,"
                + " actions, created_at, updated_at) VALUES (:name, :enabled, (SELECT"
                + " COALESCE(max(sort_order), 0) + 1 FROM sla_automation_rule), :trigger,"
                + " CAST(:conditions AS jsonb), CAST(:actions AS jsonb), :now, :now) RETURNING id")
        .param("name", name)
        .param("enabled", enabled)
        .param("trigger", trigger.name())
        .param("conditions", json.writeValueAsString(conditions))
        .param("actions", json.writeValueAsString(actions))
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public boolean update(
      AccessScope scope,
      long id,
      String name,
      boolean enabled,
      SlaAutomationTrigger trigger,
      Conditions conditions,
      List<Action> actions,
      Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
                "UPDATE sla_automation_rule SET name = :name, enabled = :enabled, trigger_type ="
                    + " :trigger, conditions = CAST(:conditions AS jsonb), actions = CAST(:actions"
                    + " AS jsonb), updated_at = :now WHERE id = :id")
            .param("name", name)
            .param("enabled", enabled)
            .param("trigger", trigger.name())
            .param("conditions", json.writeValueAsString(conditions))
            .param("actions", json.writeValueAsString(actions))
            .param("now", Timestamp.from(now))
            .param("id", id)
            .update()
        == 1;
  }

  public boolean setEnabled(AccessScope scope, long id, boolean enabled, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
                "UPDATE sla_automation_rule SET enabled = :enabled, updated_at = :now WHERE id ="
                    + " :id")
            .param("enabled", enabled)
            .param("now", Timestamp.from(now))
            .param("id", id)
            .update()
        == 1;
  }

  /** Deletes the rule and its firing records (ON DELETE CASCADE); timeline and audit stay. */
  public boolean delete(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql("DELETE FROM sla_automation_rule WHERE id = :id").param("id", id).update() == 1;
  }

  /** Positions 1..n in the order of {@code ids} (the caller checks it lists every rule once). */
  public void reorder(AccessScope scope, List<Long> ids) {
    scope.requireAdmin();
    for (int i = 0; i < ids.size(); i++) {
      jdbc.sql("UPDATE sla_automation_rule SET sort_order = :pos WHERE id = :id")
          .param("pos", i + 1)
          .param("id", ids.get(i))
          .update();
    }
  }

  /**
   * Records that {@code ruleId} fired for the ticket, target and trigger. False when it had already
   * fired (the unique key): the caller then does nothing. SYSTEM only, in the transaction
   * (savepoint) of the rule's actions, so the record exists exactly when the actions do.
   */
  public boolean recordFiring(
      AccessScope scope, long ruleId, long ticketId, SlaAutomationTrigger trigger, Instant now) {
    requireSystem(scope);
    return jdbc.sql(
                "INSERT INTO sla_automation_firing (rule_id, ticket_id, target, trigger_type,"
                    + " fired_at) VALUES (:rule, :ticket, :target, :trigger, :now) ON CONFLICT ON"
                    + " CONSTRAINT sla_automation_firing_uk DO NOTHING")
            .param("rule", ruleId)
            .param("ticket", ticketId)
            .param("target", trigger.target().name())
            .param("trigger", trigger.name())
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  /** How many times the rule fired (tests and the admin list). Admin or SYSTEM. */
  public long firingCount(AccessScope scope, long ruleId) {
    requireAdminOrSystem(scope);
    return jdbc.sql("SELECT count(*) FROM sla_automation_firing WHERE rule_id = :rule")
        .param("rule", ruleId)
        .query(Long.class)
        .single();
  }

  /** The ids among {@code ids} that are not an existing organisation. Admin. */
  public Set<Long> unknownOrganisations(AccessScope scope, Collection<Long> ids) {
    return missing(scope, "SELECT id FROM organisation WHERE id IN (:ids)", ids);
  }

  /** The ids among {@code ids} that are not an existing request type. Admin. */
  public Set<Long> unknownRequestTypes(AccessScope scope, Collection<Long> ids) {
    return missing(scope, "SELECT id FROM request_type WHERE id IN (:ids)", ids);
  }

  /** The ids among {@code ids} that are not an active agent or admin. Admin. */
  public Set<Long> notActiveSupporters(AccessScope scope, Collection<Long> ids) {
    return missing(
        scope,
        "SELECT id FROM app_user WHERE id IN (:ids) AND status = 'ACTIVE' AND role IN ('AGENT',"
            + " 'ADMIN')",
        ids);
  }

  private Set<Long> missing(AccessScope scope, String sql, Collection<Long> ids) {
    scope.requireAdmin();
    Set<Long> out = new LinkedHashSet<>(ids);
    if (out.isEmpty()) {
      return out;
    }
    out.removeAll(jdbc.sql(sql).param("ids", List.copyOf(out)).query(Long.class).list());
    return out;
  }

  private SlaAutomationRule map(ResultSet rs, int row) throws SQLException {
    return new SlaAutomationRule(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getBoolean("enabled"),
        rs.getInt("sort_order"),
        SlaAutomationTrigger.valueOf(rs.getString("trigger_type")),
        json.readValue(rs.getString("conditions"), Conditions.class),
        Arrays.asList(json.readValue(rs.getString("actions"), Action[].class)),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static void requireAdminOrSystem(AccessScope scope) {
    if (!scope.isSystem()) {
      scope.requireAdmin();
    }
  }

  private static void requireSystem(AccessScope scope) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
  }
}
