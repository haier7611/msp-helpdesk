package com.ticketsystem.api.sla.automation;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * SLA automation rules. Detail keys: the rule id, the changed field names, the ticket id, the
 * trigger and the action code - never the rule's note text or any ticket content.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({"rule", "changed", "status", "ticket", "trigger", "action"})
public enum SlaAutomationAuditEvent implements AuditEvent {
  /** An admin created a rule ("rule"). */
  SLA_AUTOMATION_RULE_CREATED,
  /** An admin changed a rule ("rule", "changed" lists the field names). */
  SLA_AUTOMATION_RULE_UPDATED,
  /** An admin turned a rule on or off ("rule", "status" ENABLED or DISABLED). */
  SLA_AUTOMATION_RULE_TOGGLED,
  /** An admin deleted a rule ("rule"); its timeline lines and audit events stay. */
  SLA_AUTOMATION_RULE_DELETED,
  /** An admin changed the order the rules run in. */
  SLA_AUTOMATION_RULES_REORDERED,
  /**
   * A rule performed one action on a ticket ("rule", "ticket", "trigger", "action" the action type,
   * "status" the outcome). Actor: none (the system).
   */
  SLA_AUTOMATION_ACTION
}
