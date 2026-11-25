package com.ticketsystem.api.sla;

import static com.ticketsystem.api.sla.SlaFixtures.LONDON;
import static com.ticketsystem.api.sla.SlaFixtures.STANDARD;
import static com.ticketsystem.api.sla.SlaFixtures.at;
import static com.ticketsystem.api.sla.SlaFixtures.local;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-042: is the clock counting now, when does it start again, when does it stop. */
class BusinessCalendarOpeningTest {

  @Test
  void openFromOpeningIncludedToClosingExcluded() {
    assertThat(STANDARD.isOpenAt(at("2026-11-23T08:29"))).isFalse();
    assertThat(STANDARD.isOpenAt(at("2026-11-23T08:30"))).isTrue();
    assertThat(STANDARD.isOpenAt(at("2026-11-23T17:29"))).isTrue();
    assertThat(STANDARD.isOpenAt(at("2026-11-23T17:30"))).isFalse();
    assertThat(STANDARD.isOpenAt(at("2026-11-21T12:00"))).as("Saturday").isFalse();
    assertThat(STANDARD.isOpenAt(at("2026-12-25T12:00"))).as("holiday").isFalse();
  }

  @Test
  void nextOpeningSkipsEveningsWeekendsAndHolidays() {
    Instant open = at("2026-11-23T10:00");
    assertThat(STANDARD.nextOpening(open)).isEqualTo(open);
    assertThat(local(STANDARD.nextOpening(at("2026-11-23T07:00")))).isEqualTo("2026-11-23T08:30");
    assertThat(local(STANDARD.nextOpening(at("2026-11-23T17:30")))).isEqualTo("2026-11-24T08:30");
    assertThat(local(STANDARD.nextOpening(at("2026-11-27T18:00")))).isEqualTo("2026-11-30T08:30");
    // Thu 24 Dec evening: Fri 25 holiday, weekend, Mon 28 holiday -> Tue 29.
    assertThat(local(STANDARD.nextOpening(at("2026-12-24T18:00")))).isEqualTo("2026-12-29T08:30");
  }

  @Test
  void closingAfterIsTheCurrentWindowCloseOrNull() {
    assertThat(local(STANDARD.closingAfter(at("2026-11-23T10:00")))).isEqualTo("2026-11-23T17:30");
    assertThat(STANDARD.closingAfter(at("2026-11-23T18:00"))).isNull();
    assertThat(STANDARD.closingAfter(at("2026-12-25T10:00"))).isNull();
  }

  @Test
  void aWindowToMidnightClosesAtMidnight() {
    Map<DayOfWeek, DailyHours> hours = new EnumMap<>(DayOfWeek.class);
    hours.put(DayOfWeek.MONDAY, DailyHours.of("20:00", "00:00"));
    BusinessCalendar late = BusinessCalendar.of(LONDON, hours, List.of());
    assertThat(local(late.closingAfter(at("2026-11-23T23:59")))).isEqualTo("2026-11-24T00:00");
    assertThat(local(late.nextOpening(at("2026-11-24T00:00")))).isEqualTo("2026-11-30T20:00");
  }

  @Test
  void theTwentyFourSevenClockIsAlwaysOpenAndNeverCloses() {
    BusinessCalendar always = BusinessCalendar.twentyFourSeven(LONDON);
    Instant christmas = at("2026-12-25T03:00");
    assertThat(always.isOpenAt(christmas)).isTrue();
    assertThat(always.nextOpening(christmas)).isEqualTo(christmas);
    assertThat(always.closingAfter(christmas)).isNull();
  }
}
