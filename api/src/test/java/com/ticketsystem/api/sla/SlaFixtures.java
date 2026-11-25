package com.ticketsystem.api.sla;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Shared calendars and helpers for the SLA tests. All times are Europe/London local times. */
final class SlaFixtures {

  /** Test zone with DST (spec AC: "DST change (Europe/London test zone)"). */
  static final ZoneId LONDON = ZoneId.of("Europe/London");

  /** Synthetic holiday list: Christmas Day, a substitute Boxing Day and New Year's Day. */
  static final List<LocalDate> HOLIDAYS =
      List.of(
          LocalDate.parse("2026-12-25"),
          LocalDate.parse("2026-12-28"),
          LocalDate.parse("2027-01-01"));

  /** Default calendar: Monday-Friday 08:30-17:30, London, with the holidays above. */
  static final BusinessCalendar STANDARD = BusinessCalendar.standard(LONDON, HOLIDAYS);

  /** Monday, Tuesday, Thursday, Friday 08:30-17:30: Wednesday has no hours. */
  static final BusinessCalendar NO_WEDNESDAY = noWednesday();

  /** Monday-Thursday 08:30-17:30 and Friday 08:30-12:30; a business day is still 540 minutes. */
  static final BusinessCalendar SHORT_FRIDAY = shortFriday();

  private SlaFixtures() {}

  static BusinessCalendar calendar(String name) {
    return switch (name) {
      case "STANDARD" -> STANDARD;
      case "NO_WEDNESDAY" -> NO_WEDNESDAY;
      case "SHORT_FRIDAY" -> SHORT_FRIDAY;
      default -> throw new IllegalArgumentException(name);
    };
  }

  /** A London local time such as {@code 2026-11-21T23:00} as an instant. */
  static Instant at(String localDateTime) {
    return ZonedDateTime.of(LocalDateTime.parse(localDateTime), LONDON).toInstant();
  }

  /** London local time of an instant, minute precision, for readable assertions. */
  static String local(Instant instant) {
    return instant == null ? "-" : LocalDateTime.ofInstant(instant, LONDON).toString();
  }

  /** Parses {@code "NEW@2026-11-23T09:00, OPEN@2026-11-23T10:00"} into a status history. */
  static List<StatusChange> history(String spec) {
    List<StatusChange> changes = new ArrayList<>();
    for (String part : spec.split(",")) {
      String[] bits = part.trim().split("@");
      changes.add(new StatusChange(at(bits[1]), TicketStatus.valueOf(bits[0])));
    }
    return changes;
  }

  private static BusinessCalendar noWednesday() {
    Map<DayOfWeek, DailyHours> hours = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek day :
        List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
      hours.put(day, BusinessCalendar.DEFAULT_HOURS);
    }
    return BusinessCalendar.of(LONDON, hours, HOLIDAYS);
  }

  private static BusinessCalendar shortFriday() {
    Map<DayOfWeek, DailyHours> hours = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek day :
        List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)) {
      hours.put(day, BusinessCalendar.DEFAULT_HOURS);
    }
    hours.put(DayOfWeek.FRIDAY, DailyHours.of("08:30", "12:30"));
    return BusinessCalendar.of(LONDON, hours, HOLIDAYS);
  }
}
