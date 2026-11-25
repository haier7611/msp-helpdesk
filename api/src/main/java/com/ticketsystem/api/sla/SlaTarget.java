package com.ticketsystem.api.sla;

import java.util.Objects;

/**
 * One SLA target, such as "30 min", "8 business hours" or "3 business days". Whether hours are
 * business hours or clock hours depends on the priority's {@link ClockType}; days are converted
 * with {@link BusinessCalendar#businessDayMinutes()} (one full set of business hours, or 24 h on
 * the 24/7 clock).
 */
public record SlaTarget(long amount, Unit unit) {

  /** The unit a target is written in. */
  public enum Unit {
    MINUTES,
    HOURS,
    DAYS
  }

  public SlaTarget {
    Objects.requireNonNull(unit, "unit");
    if (amount <= 0) {
      throw new IllegalArgumentException("an SLA target must be positive: " + amount + " " + unit);
    }
  }

  public static SlaTarget minutes(long minutes) {
    return new SlaTarget(minutes, Unit.MINUTES);
  }

  public static SlaTarget hours(long hours) {
    return new SlaTarget(hours, Unit.HOURS);
  }

  /** Business days on a business-hours clock, 24 h days on the 24/7 clock. */
  public static SlaTarget days(long days) {
    return new SlaTarget(days, Unit.DAYS);
  }

  /** The target in minutes of the given clock. */
  public long toMinutes(BusinessCalendar clock) {
    return switch (unit) {
      case MINUTES -> amount;
      case HOURS -> Math.multiplyExact(amount, 60L);
      case DAYS -> Math.multiplyExact(amount, clock.businessDayMinutes());
    };
  }
}
