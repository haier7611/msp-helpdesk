package com.ticketsystem.api.sla.ticket;

import java.time.Instant;

/**
 * The SLA panel on the agent ticket page. Internal only: agent and admin read models, never a
 * customer response. Computed server-side at {@code asOf} by the same engine and inputs as the
 * stored values the queue column and reports use, so the page only counts down between refreshes
 * and never does business-hours maths itself.
 *
 * @param asOf the server instant the values are for (the page ticks from here)
 * @param clock TWENTY_FOUR_SEVEN or BUSINESS_HOURS
 * @param timezone the organisation's timezone (the calendar the business hours are in)
 * @param priority the priority the targets come from (P1-P4)
 * @param policyLabel "P2 High - business hours (Europe/London)" or "P1 Critical - 24/7"
 * @param pendingOnVendorPauses the policy's "Pending on vendor pauses SLA" flag
 * @param businessDayMinutes minutes in one day on the priority's clock (540 for the default
 *     business hours, 1,440 for 24/7), so "2d 3h" means the same days the targets are set in
 * @param firstResponse the first-response row
 * @param resolution the resolution row
 */
public record SlaPanel(
    Instant asOf,
    String clock,
    String timezone,
    String priority,
    String policyLabel,
    boolean pendingOnVendorPauses,
    long businessDayMinutes,
    Target firstResponse,
    Target resolution) {

  /** Where a target stands, as the panel shows it. */
  public enum State {
    /** Counting down now. */
    RUNNING,
    /** Pending on client (always) or Pending on vendor (when the policy pauses it). */
    PAUSED,
    /** Would be running, but the organisation's business hours are closed (never for P1). */
    STOPPED_OUTSIDE_HOURS,
    /** Achieved within the target. */
    MET,
    /** More time used than the target allows; kept after resolution. */
    BREACHED,
    /**
     * Nothing to show: first response on a ticket resolved without a public reply, or a ticket
     * closed as a duplicate (left out of every SLA measure).
     */
    NOT_APPLICABLE
  }

  /** Why a paused target is paused. */
  public enum PausedReason {
    CLIENT,
    VENDOR
  }

  /**
   * One target row.
   *
   * @param state where the target stands
   * @param targetMinutes the target, in the priority's clock
   * @param dueAt when the target is, was or will be reached if the clock carries on (null while
   *     paused or stopped before being reached)
   * @param warnAt when 80% of the target is or will be used (null when not running or paused)
   * @param remainingBusinessMinutes minutes left on the priority's clock, rounded up (RUNNING,
   *     PAUSED, STOPPED_OUTSIDE_HOURS only)
   * @param usedMinutes minutes used on the priority's clock, rounded down
   * @param metAt the first public reply or the latest Resolved (MET only)
   * @param breachedByMinutes minutes over the target, rounded up (BREACHED only)
   * @param nextClockStart when the business hours open again (STOPPED_OUTSIDE_HOURS only)
   * @param clockStopsAt when the current business window closes (RUNNING on business hours only);
   *     the page stops counting there and asks the server again
   * @param pausedReason CLIENT or VENDOR (PAUSED only)
   */
  public record Target(
      State state,
      long targetMinutes,
      Instant dueAt,
      Instant warnAt,
      Long remainingBusinessMinutes,
      long usedMinutes,
      Instant metAt,
      Long breachedByMinutes,
      Instant nextClockStart,
      Instant clockStopsAt,
      PausedReason pausedReason) {}
}
