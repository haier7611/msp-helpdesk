package com.ticketsystem.api.sla.automation;

import com.ticketsystem.api.sla.Priority;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One SLA automation rule as stored and as the admin API returns it - a trigger, optional
 * conditions (AND-ed; an empty list means "any") and one or more actions, run in {@code position}
 * order. Ids and codes only, plus the admin's fixed note text.
 */
public record SlaAutomationRule(
    long id,
    String name,
    boolean enabled,
    int position,
    SlaAutomationTrigger trigger,
    Conditions conditions,
    List<Action> actions,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * Which tickets the rule applies to. {@code includeTriage} is the "Email triage" choice in the
   * organisation list (tickets with no organisation). Organisations: none chosen and no triage =
   * any ticket; otherwise the ticket's organisation must be one of them (or triage when chosen).
   */
  public record Conditions(
      Boolean includeTriage,
      List<Long> organisationIds,
      List<Priority> priorities,
      List<Long> requestTypeIds) {

    public static final Conditions ANY = new Conditions(false, List.of(), List.of(), List.of());

    public Conditions {
      includeTriage = Boolean.TRUE.equals(includeTriage);
      organisationIds = copy(organisationIds);
      priorities = copy(priorities);
      requestTypeIds = copy(requestTypeIds);
    }

    /** True when a ticket with these values meets every condition. */
    public boolean matches(Long organisationId, Priority priority, long requestTypeId) {
      boolean anyOrganisation = !includeTriage && organisationIds.isEmpty();
      boolean organisationOk =
          anyOrganisation
              || (organisationId == null
                  ? includeTriage
                  : organisationIds.contains(organisationId));
      boolean priorityOk = priorities.isEmpty() || priorities.contains(priority);
      boolean typeOk = requestTypeIds.isEmpty() || requestTypeIds.contains(requestTypeId);
      return organisationOk && priorityOk && typeOk;
    }
  }

  /** What a rule can do (v1). Stored by name: append, never rename. */
  public enum ActionType {
    /** Email + bell to the chosen agents who can work the ticket's organisation. */
    NOTIFY_AGENTS,
    /** Email + bell to every active agent who can work the ticket's organisation. */
    NOTIFY_ORGANISATION,
    /** Assign to the chosen agent, only when the ticket is unassigned. */
    ASSIGN,
    /** Raise the priority one level, never above P1. */
    RAISE_PRIORITY,
    /** Add an internal note with the rule's fixed text. */
    ADD_NOTE
  }

  /**
   * One action. {@code agentIds} for NOTIFY_AGENTS, {@code agentId} for ASSIGN, {@code text} for
   * ADD_NOTE; the other fields are empty.
   */
  public record Action(ActionType type, List<Long> agentIds, Long agentId, String text) {

    public Action {
      agentIds = copy(agentIds);
    }
  }

  /** An unmodifiable copy that keeps null elements, so validation can refuse them with a 400. */
  static <T> List<T> copy(List<T> in) {
    return in == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(in));
  }
}
