package com.ticketsystem.api.sla;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * An SLA policy: targets for every priority and the "Pending on vendor pauses SLA" flag. Every new
 * organisation starts with {@link #defaults()}; the admin can edit it per organisation and per
 * request type (the edited copies are stored elsewhere).
 *
 * <p>Rules enforced here:
 *
 * <ul>
 *   <li>Every priority has targets.
 *   <li>P1 is always on the 24/7 clock; a policy with P1 on business hours is refused.
 * </ul>
 */
public record SlaPolicy(Map<Priority, PriorityTargets> targets, boolean pendingOnVendorPauses) {

  public SlaPolicy {
    Objects.requireNonNull(targets, "targets");
    EnumMap<Priority, PriorityTargets> copy = new EnumMap<>(Priority.class);
    targets.forEach((p, t) -> copy.put(Objects.requireNonNull(p), Objects.requireNonNull(t)));
    for (Priority priority : Priority.values()) {
      if (!copy.containsKey(priority)) {
        throw new IllegalArgumentException("an SLA policy needs targets for " + priority);
      }
    }
    if (copy.get(Priority.P1).clock() != ClockType.TWENTY_FOUR_SEVEN) {
      throw new IllegalArgumentException("P1 always runs on the 24/7 clock (ADR-017)");
    }
    targets = Collections.unmodifiableMap(copy);
  }

  /**
   * The default policy: P1 30 min / 4 h on 24/7; P2 1 h / 8 business hours; P3 4 business hours / 3
   * business days; P4 1 business day / 10 business days. Pending on vendor keeps the clock running
   * (the default).
   */
  public static SlaPolicy defaults() {
    EnumMap<Priority, PriorityTargets> targets = new EnumMap<>(Priority.class);
    targets.put(
        Priority.P1,
        new PriorityTargets(
            SlaTarget.minutes(30), SlaTarget.hours(4), ClockType.TWENTY_FOUR_SEVEN));
    targets.put(
        Priority.P2,
        new PriorityTargets(SlaTarget.hours(1), SlaTarget.hours(8), ClockType.BUSINESS_HOURS));
    targets.put(
        Priority.P3,
        new PriorityTargets(SlaTarget.hours(4), SlaTarget.days(3), ClockType.BUSINESS_HOURS));
    targets.put(
        Priority.P4,
        new PriorityTargets(SlaTarget.days(1), SlaTarget.days(10), ClockType.BUSINESS_HOURS));
    return new SlaPolicy(targets, false);
  }

  /** A copy of this policy with a different "Pending on vendor pauses SLA" flag. */
  public SlaPolicy withPendingOnVendorPauses(boolean pauses) {
    return new SlaPolicy(targets, pauses);
  }

  /** The targets for {@code priority}. */
  public PriorityTargets targetsFor(Priority priority) {
    return targets.get(Objects.requireNonNull(priority, "priority"));
  }
}
