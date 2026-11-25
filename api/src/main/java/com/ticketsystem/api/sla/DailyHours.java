package com.ticketsystem.api.sla;

import java.time.LocalTime;
import java.util.Objects;

/**
 * One weekday's business hours, as local wall-clock times in the calendar's timezone. A window
 * never crosses midnight; an {@code end} of {@code 00:00} means the end of the day (midnight that
 * night), so {@code 00:00-00:00} is the whole day.
 */
public record DailyHours(LocalTime start, LocalTime end) {

  public DailyHours {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
    if (!endsAtMidnight(end) && !end.isAfter(start)) {
      throw new IllegalArgumentException(
          "business hours must end after they start (no overnight windows): " + start + "-" + end);
    }
  }

  public static DailyHours of(String start, String end) {
    return new DailyHours(LocalTime.parse(start), LocalTime.parse(end));
  }

  /** True when the window runs to midnight at the end of the day. */
  public boolean endsAtEndOfDay() {
    return endsAtMidnight(end);
  }

  /** Nominal length in minutes, on a day without a DST change. */
  public long nominalMinutes() {
    long endMinute = endsAtEndOfDay() ? 24 * 60 : end.toSecondOfDay() / 60;
    return endMinute - start.toSecondOfDay() / 60;
  }

  private static boolean endsAtMidnight(LocalTime time) {
    return time.equals(LocalTime.MIDNIGHT);
  }
}
