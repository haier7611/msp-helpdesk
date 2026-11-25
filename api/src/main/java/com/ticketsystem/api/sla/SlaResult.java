package com.ticketsystem.api.sla;

import java.time.Duration;
import java.util.Objects;

/**
 * Everything stored and reported for a ticket's SLA at one instant (due times, total paused
 * minutes, met/breached for both targets). Internal only - never shown to customers.
 *
 * @param priority the priority the targets were taken from
 * @param clock the clock both targets and the paused time are measured on
 * @param firstResponse the first-response target
 * @param resolution the resolution target
 * @param paused time spent in a pausing status (Pending on client, and Pending on vendor when the
 *     policy pauses it), measured on {@code clock}; Resolved and Closed time is not paused time
 */
public record SlaResult(
    Priority priority,
    ClockType clock,
    TargetResult firstResponse,
    TargetResult resolution,
    Duration paused) {

  public SlaResult {
    Objects.requireNonNull(priority, "priority");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(firstResponse, "firstResponse");
    Objects.requireNonNull(resolution, "resolution");
    Objects.requireNonNull(paused, "paused");
  }

  /** Whole paused minutes (rounded down), as stored on the ticket. */
  public long pausedMinutes() {
    return paused.toMinutes();
  }
}
