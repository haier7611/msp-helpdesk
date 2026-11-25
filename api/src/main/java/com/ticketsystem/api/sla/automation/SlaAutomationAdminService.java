package com.ticketsystem.api.sla.automation;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.admin.PlainText;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Action;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.ActionType;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Conditions;
import com.ticketsystem.api.sla.automation.clientdata.SlaAutomationRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin - SLA automation: list, create, edit, turn on or off, reorder and delete rules. Admins only
 * (the {@code /api/v1/admin} gate, checked again here); no step-up (not a security setting). Every
 * change is audit-logged with the rule id and changed field names, never the note text.
 *
 * <p>Validation: a name, a known trigger, at least one action and at most {@link #MAX_ACTIONS};
 * organisation, request type and agent ids must exist (agents must be active agents or admins); the
 * note text is 1 to {@link #NOTE_MAX} characters of plain text. Whether a chosen agent can work a
 * ticket's organisation is checked when the rule runs, not here (access can change).
 */
@Service
public class SlaAutomationAdminService {

  static final int NAME_MAX = 100;
  static final int NOTE_MAX = 1000;
  static final int MAX_RULES = 100;
  static final int MAX_ACTIONS = 10;
  static final int MAX_IDS = 200;

  /** Control and bidirectional override characters; line breaks are allowed in a note. */
  private static final Pattern NOTE_FORBIDDEN =
      Pattern.compile("[\\p{Cc}&&[^\\n\\r\\t]]|[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F]");

  /** What an admin sends for a rule (create and update). */
  public record RuleInput(
      String name, Boolean enabled, String trigger, Conditions conditions, List<Action> actions) {}

  private final SlaAutomationRepository rules;
  private final AuditService audit;
  private final Clock clock;

  public SlaAutomationAdminService(SlaAutomationRepository rules, AuditService audit, Clock clock) {
    this.rules = rules;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<SlaAutomationRule> list(AccessScope scope) {
    scope.requireAdmin();
    return rules.list(scope);
  }

  @Transactional
  public SlaAutomationRule create(AccessScope scope, RuleInput in, String clientIp) {
    scope.requireAdmin();
    Valid v = validate(scope, in);
    rules.lockRuleOrder(scope);
    if (rules.count(scope) >= MAX_RULES) {
      throw ApiException.invalidRequest("There can be at most " + MAX_RULES + " rules.");
    }
    long id =
        rules.insert(scope, v.name, v.enabled, v.trigger, v.conditions, v.actions, clock.instant());
    record(scope, SlaAutomationAuditEvent.SLA_AUTOMATION_RULE_CREATED, id, null, null, clientIp);
    return rules.find(scope, id).orElseThrow();
  }

  @Transactional
  public SlaAutomationRule update(AccessScope scope, long id, RuleInput in, String clientIp) {
    scope.requireAdmin();
    SlaAutomationRule old = rules.find(scope, id).orElseThrow(AccessScope::notFound);
    Valid v = validate(scope, in);
    List<String> changed = new ArrayList<>();
    if (!old.name().equals(v.name)) changed.add("name");
    if (old.enabled() != v.enabled) changed.add("enabled");
    if (old.trigger() != v.trigger) changed.add("trigger");
    if (!old.conditions().equals(v.conditions)) changed.add("conditions");
    if (!old.actions().equals(v.actions)) changed.add("actions");
    if (!changed.isEmpty()) {
      rules.update(
          scope, id, v.name, v.enabled, v.trigger, v.conditions, v.actions, clock.instant());
      record(
          scope,
          SlaAutomationAuditEvent.SLA_AUTOMATION_RULE_UPDATED,
          id,
          String.join(",", changed),
          null,
          clientIp);
    }
    return rules.find(scope, id).orElseThrow();
  }

  @Transactional
  public SlaAutomationRule setEnabled(
      AccessScope scope, long id, boolean enabled, String clientIp) {
    scope.requireAdmin();
    SlaAutomationRule old = rules.find(scope, id).orElseThrow(AccessScope::notFound);
    if (old.enabled() != enabled) {
      rules.setEnabled(scope, id, enabled, clock.instant());
      record(
          scope,
          SlaAutomationAuditEvent.SLA_AUTOMATION_RULE_TOGGLED,
          id,
          null,
          enabled ? "ENABLED" : "DISABLED",
          clientIp);
    }
    return rules.find(scope, id).orElseThrow();
  }

  @Transactional
  public void delete(AccessScope scope, long id, String clientIp) {
    scope.requireAdmin();
    if (!rules.delete(scope, id)) {
      throw AccessScope.notFound();
    }
    record(scope, SlaAutomationAuditEvent.SLA_AUTOMATION_RULE_DELETED, id, null, null, clientIp);
  }

  /** Sets the run order; {@code ids} must list every rule exactly once. */
  @Transactional
  public List<SlaAutomationRule> reorder(AccessScope scope, List<Long> ids, String clientIp) {
    scope.requireAdmin();
    rules.lockRuleOrder(scope);
    Set<Long> expected = new HashSet<>();
    rules.list(scope).forEach(r -> expected.add(r.id()));
    if (ids == null || ids.size() != expected.size() || !new HashSet<>(ids).equals(expected)) {
      throw ApiException.invalidRequest("List every rule exactly once.");
    }
    rules.reorder(scope, ids);
    record(
        scope, SlaAutomationAuditEvent.SLA_AUTOMATION_RULES_REORDERED, null, null, null, clientIp);
    return rules.list(scope);
  }

  // ---------------------------------------------------------------------------------------------

  private record Valid(
      String name,
      boolean enabled,
      SlaAutomationTrigger trigger,
      Conditions conditions,
      List<Action> actions) {}

  private Valid validate(AccessScope scope, RuleInput in) {
    if (in == null) {
      throw ApiException.invalidRequest("Send the rule.");
    }
    String name = PlainText.required(in.name(), "a name", NAME_MAX);
    SlaAutomationTrigger trigger = trigger(in.trigger());
    Conditions conditions = conditions(scope, in.conditions());
    List<Action> actions = actions(scope, in.actions());
    boolean enabled = in.enabled() == null || in.enabled();
    return new Valid(name, enabled, trigger, conditions, actions);
  }

  private static SlaAutomationTrigger trigger(String raw) {
    try {
      return SlaAutomationTrigger.valueOf(raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidRequest("Choose when the rule runs (a trigger).");
    }
  }

  private Conditions conditions(AccessScope scope, Conditions in) {
    if (in == null) {
      return Conditions.ANY;
    }
    List<Long> organisations = distinct(in.organisationIds(), "organisations");
    List<Long> types = distinct(in.requestTypeIds(), "request types");
    if (in.priorities().stream().anyMatch(Objects::isNull)) {
      throw ApiException.invalidRequest("Choose priorities from P1 to P4.");
    }
    List<Priority> priorities = List.copyOf(new LinkedHashSet<>(in.priorities()));
    if (!rules.unknownOrganisations(scope, organisations).isEmpty()) {
      throw ApiException.invalidRequest("One of the chosen organisations does not exist.");
    }
    if (!rules.unknownRequestTypes(scope, types).isEmpty()) {
      throw ApiException.invalidRequest("One of the chosen request types does not exist.");
    }
    return new Conditions(in.includeTriage(), organisations, priorities, types);
  }

  private List<Action> actions(AccessScope scope, List<Action> in) {
    if (in == null || in.isEmpty()) {
      throw ApiException.invalidRequest("Add at least one action.");
    }
    if (in.size() > MAX_ACTIONS) {
      throw ApiException.invalidRequest("A rule can have at most " + MAX_ACTIONS + " actions.");
    }
    List<Action> out = new ArrayList<>();
    Set<Long> agents = new LinkedHashSet<>();
    for (Action a : in) {
      if (a == null || a.type() == null) {
        throw ApiException.invalidRequest("Choose what each action does.");
      }
      out.add(
          switch (a.type()) {
            case NOTIFY_AGENTS -> {
              List<Long> ids = distinct(a.agentIds(), "agents");
              if (ids.isEmpty()) {
                throw ApiException.invalidRequest("Choose at least one agent to notify.");
              }
              agents.addAll(ids);
              yield new Action(ActionType.NOTIFY_AGENTS, ids, null, null);
            }
            case ASSIGN -> {
              if (a.agentId() == null) {
                throw ApiException.invalidRequest("Choose the agent to assign the ticket to.");
              }
              agents.add(a.agentId());
              yield new Action(ActionType.ASSIGN, List.of(), a.agentId(), null);
            }
            case ADD_NOTE -> new Action(ActionType.ADD_NOTE, List.of(), null, note(a.text()));
            case NOTIFY_ORGANISATION, RAISE_PRIORITY -> new Action(a.type(), List.of(), null, null);
          });
    }
    if (!rules.notActiveSupporters(scope, agents).isEmpty()) {
      throw ApiException.invalidRequest(
          "One of the chosen agents does not exist or is deactivated.");
    }
    return List.copyOf(out);
  }

  private static String note(String raw) {
    String v = raw == null ? "" : raw.strip();
    if (v.isEmpty() || v.length() > NOTE_MAX) {
      throw ApiException.invalidRequest(
          "Enter the internal note text, 1 to " + NOTE_MAX + " characters.");
    }
    if (NOTE_FORBIDDEN.matcher(v).find()) {
      throw ApiException.invalidRequest("The internal note must be plain text.");
    }
    return v;
  }

  private static List<Long> distinct(List<Long> ids, String what) {
    if (ids == null) {
      return List.of();
    }
    if (ids.stream().anyMatch(Objects::isNull) || ids.size() > MAX_IDS) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "invalid_request", "The list of " + what + " is not valid.");
    }
    return List.copyOf(new LinkedHashSet<>(ids));
  }

  private void record(
      AccessScope scope,
      SlaAutomationAuditEvent type,
      Long ruleId,
      String changed,
      String status,
      String clientIp) {
    Map<String, Object> d = new LinkedHashMap<>();
    if (ruleId != null) d.put("rule", ruleId);
    if (changed != null) d.put("changed", changed);
    if (status != null) d.put("status", status);
    audit.record(type, scope.principalId().orElse(null), null, clientIp, d);
  }
}
