package com.ticketsystem.api.sla;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome for one target (first response or resolution) at the evaluation instant.
 *
 * @param target the target, in the priority's clock
 * @param used time counted against the target so far (running time only, in the priority's clock)
 * @param dueAt the instant the target is, was or will be reached if the clock carries on in its
 *     current state; {@code null} while the clock is paused or stopped and the target is not yet
 *     reached (callers show "paused" and recompute on the next status change)
 * @param state where the target stands
 */
public record TargetResult(Duration target, Duration used, Instant dueAt, TargetState state) {

  public TargetResult {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(used, "used");
    Objects.requireNonNull(state, "state");
  }

  public Optional<Instant> due() {
    return Optional.ofNullable(dueAt);
  }

  public long targetMinutes() {
    return target.toMinutes();
  }

  /** Whole minutes used (rounded down). */
  public long usedMinutes() {
    return used.toMinutes();
  }

  /** Time left before breach, never negative. */
  public Duration remaining() {
    Duration left = target.minus(used);
    return left.isNegative() ? Duration.ZERO : left;
  }

  /** Percentage of the target used, rounded down (may exceed 100 once breached). */
  public long usedPercent() {
    return used.toMillis() * 100 / target.toMillis();
  }

  public boolean met() {
    return state == TargetState.MET;
  }

  public boolean breached() {
    return state == TargetState.BREACHED;
  }
}
