package com.ticketsystem.api.notification;

/**
 * What a notification is about (My settings - Notifications, SLA warnings). Stored by name; append
 * new values, never rename. The P1 kinds go to every supporter of the organisation and ignore
 * preferences (v1 sends email + in-app to everyone).
 */
public enum NotificationKind {
  TICKET_ASSIGNED,
  CLIENT_REPLIED,
  /** Reserved for internal-note mentions (the mention syntax arrives with the composer). */
  MENTIONED,
  INTERNAL_NOTE_ADDED,
  SLA_AT_RISK,
  SLA_BREACHED,
  P1_CREATED,
  P1_AT_RISK,
  /**
   * An admin-defined SLA automation rule notified chosen agents or the organisation's agents
   * ({@code detail} = the trigger name). Follows the agent's "SLA warning" choices.
   */
  SLA_AUTOMATION;

  /** P1 alerts are always sent, in-app and by email, whatever the preferences say. */
  public boolean mandatory() {
    return this == P1_CREATED || this == P1_AT_RISK;
  }
}
