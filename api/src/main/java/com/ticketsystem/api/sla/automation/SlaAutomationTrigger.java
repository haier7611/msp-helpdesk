package com.ticketsystem.api.sla.automation;

/**
 * What makes an SLA automation rule fire - a target's stored SLA state changing, as the existing
 * SLA evaluation (minute job and after-commit reactions) detects it. Stored by name in {@code
 * sla_automation_rule.trigger_type} and {@code sla_automation_firing}: append, never rename.
 */
public enum SlaAutomationTrigger {
  FIRST_RESPONSE_AT_RISK(Target.FIRST_RESPONSE, "first response at risk"),
  FIRST_RESPONSE_BREACHED(Target.FIRST_RESPONSE, "first response breached"),
  FIRST_RESPONSE_MET(Target.FIRST_RESPONSE, "first response met"),
  RESOLUTION_AT_RISK(Target.RESOLUTION, "resolution at risk"),
  RESOLUTION_BREACHED(Target.RESOLUTION, "resolution breached"),
  RESOLUTION_MET(Target.RESOLUTION, "resolution met");

  /** The SLA target a trigger belongs to. */
  public enum Target {
    FIRST_RESPONSE,
    RESOLUTION
  }

  /** What happened to the target. */
  public enum Change {
    AT_RISK,
    BREACHED,
    MET
  }

  private final Target target;
  private final String label;

  SlaAutomationTrigger(Target target, String label) {
    this.target = target;
    this.label = label;
  }

  public Target target() {
    return target;
  }

  /** Lower-case words for timeline lines and notifications, e.g. "first response breached". */
  public String label() {
    return label;
  }

  /** The trigger for {@code change} of {@code target}. */
  public static SlaAutomationTrigger of(Target target, Change change) {
    boolean fr = target == Target.FIRST_RESPONSE;
    return switch (change) {
      case AT_RISK -> fr ? FIRST_RESPONSE_AT_RISK : RESOLUTION_AT_RISK;
      case BREACHED -> fr ? FIRST_RESPONSE_BREACHED : RESOLUTION_BREACHED;
      case MET -> fr ? FIRST_RESPONSE_MET : RESOLUTION_MET;
    };
  }
}
