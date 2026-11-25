package com.ticketsystem.api.sla;

/** Where one SLA target stands at the evaluation instant. */
public enum TargetState {
  /** Not met yet, not breached, the clock is running. */
  RUNNING,
  /** Not met yet, not breached, the clock is paused (a Pending status). */
  PAUSED,
  /**
   * First response only: the ticket is Resolved or Closed without a supporter's public reply and
   * the target was not exceeded. Neither met nor breached; a reopen continues the clock.
   */
  STOPPED,
  /** Achieved within the target (first public reply, or the latest Resolved). */
  MET,
  /** More time used than the target allows. Final: used time never goes down. */
  BREACHED
}
