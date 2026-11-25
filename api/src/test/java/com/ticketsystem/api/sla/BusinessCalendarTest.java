package com.ticketsystem.api.sla;

import static com.ticketsystem.api.sla.SlaFixtures.LONDON;
import static com.ticketsystem.api.sla.SlaFixtures.STANDARD;
import static com.ticketsystem.api.sla.SlaFixtures.at;
import static com.ticketsystem.api.sla.SlaFixtures.local;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

  @Test
  void addingZeroReturnsTheStartEvenOutsideHours() {
    Instant saturdayNight = at("2026-11-21T23:00");
    assertThat(STANDARD.addBusinessMinutes(saturdayNight, 0)).isEqualTo(saturdayNight);
  }

  @Test
  void aFullDayEndsAtCloseNotNextOpening() {
    assertThat(local(STANDARD.addBusinessMinutes(at("2026-11-20T08:30"), 540)))
        .isEqualTo("2026-11-20T17:30");
    assertThat(local(STANDARD.addBusinessMinutes(at("2026-11-20T08:30"), 541)))
        .isEqualTo("2026-11-23T08:31");
  }

  @Test
  void betweenCountsOnlyOpenTime() {
    // Fri 17:00 -> Mon 09:00 = 30 + 30.
    assertThat(STANDARD.businessMinutesBetween(at("2026-11-20T17:00"), at("2026-11-23T09:00")))
        .isEqualTo(60);
    // A whole weekend counts nothing.
    assertThat(STANDARD.businessMinutesBetween(at("2026-11-20T17:30"), at("2026-11-23T08:30")))
        .isZero();
    // A holiday counts nothing.
    assertThat(STANDARD.businessMinutesBetween(at("2026-12-25T00:00"), at("2026-12-26T00:00")))
        .isZero();
  }

  @Test
  void betweenRoundsPartMinutesDown() {
    Instant start = at("2026-11-23T09:00");
    assertThat(STANDARD.businessMinutesBetween(start, start.plusSeconds(119))).isEqualTo(1);
    assertThat(STANDARD.businessTimeBetween(start, start.plusSeconds(119)))
        .isEqualTo(Duration.ofSeconds(119));
  }

  @Test
  void secondsArePreservedWhenAdding() {
    Instant start = at("2026-11-23T17:29").plusSeconds(30);
    // 30 s left on Monday, then 59 min 30 s on Tuesday.
    assertThat(STANDARD.addBusinessMinutes(start, 60))
        .isEqualTo(at("2026-11-24T09:29").plusSeconds(30));
  }

  @Test
  void businessHoursFollowLocalTimeAcrossDst() {
    // Week before the change: open 08:30 BST = 07:30 UTC; after: 08:30 GMT = 08:30 UTC.
    assertThat(STANDARD.addBusinessMinutes(at("2026-10-23T17:30"), 1))
        .isEqualTo(Instant.parse("2026-10-26T08:31:00Z"));
    assertThat(STANDARD.businessMinutesBetween(at("2026-10-23T08:30"), at("2026-10-26T17:30")))
        .isEqualTo(1080);
  }

  @Test
  void aWindowOverTheDstChangeIsItsRealLength() {
    // 00:00-04:00 every day. On 28 Mar 2027 the 01:00-02:00 hour does not exist (3 real hours);
    // on 25 Oct 2026 01:00-02:00 happens twice (5 real hours).
    Map<DayOfWeek, DailyHours> hours = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek day : DayOfWeek.values()) {
      hours.put(day, DailyHours.of("00:00", "04:00"));
    }
    BusinessCalendar night = BusinessCalendar.of(LONDON, hours, List.of());
    assertThat(night.businessMinutesBetween(at("2027-03-28T00:00"), at("2027-03-28T12:00")))
        .isEqualTo(180);
    assertThat(night.businessMinutesBetween(at("2026-10-25T00:00"), at("2026-10-25T12:00")))
        .isEqualTo(300);
    assertThat(night.businessMinutesBetween(at("2026-10-24T00:00"), at("2026-10-24T12:00")))
        .isEqualTo(240);
  }

  @Test
  void aWindowCanRunToMidnight() {
    Map<DayOfWeek, DailyHours> hours = new EnumMap<>(DayOfWeek.class);
    hours.put(DayOfWeek.MONDAY, DailyHours.of("20:00", "00:00"));
    BusinessCalendar evenings = BusinessCalendar.of(LONDON, hours, List.of());
    assertThat(evenings.businessDayMinutes()).isEqualTo(240);
    assertThat(local(evenings.addBusinessMinutes(at("2026-11-23T12:00"), 240)))
        .isEqualTo("2026-11-24T00:00");
    assertThat(local(evenings.addBusinessMinutes(at("2026-11-23T12:00"), 241)))
        .isEqualTo("2026-11-30T20:01");
  }

  @Test
  void twentyFourSevenCountsEveryMinuteAndIgnoresHolidays() {
    BusinessCalendar clock = BusinessCalendar.twentyFourSeven(LONDON);
    assertThat(clock.businessMinutesBetween(at("2026-12-24T12:00"), at("2026-12-26T12:00")))
        .isEqualTo(2880);
    // Real minutes over DST end: 00:30 BST -> 03:30 GMT is 4 h.
    assertThat(clock.businessMinutesBetween(at("2026-10-25T00:30"), at("2026-10-25T03:30")))
        .isEqualTo(240);
    assertThat(clock.businessDayMinutes()).isEqualTo(1440);
    assertThat(clock.isTwentyFourSeven()).isTrue();
    assertThat(clock.holidays()).isEmpty();
  }

  @Test
  void businessDayIsOneFullSetOfHours() {
    assertThat(STANDARD.businessDayMinutes()).isEqualTo(540);
    assertThat(SlaFixtures.SHORT_FRIDAY.businessDayMinutes()).isEqualTo(540);
  }

  @Test
  void otherTimezonesWork() {
    BusinessCalendar hcm = BusinessCalendar.standard(ZoneId.of("Asia/Ho_Chi_Minh"), List.of());
    // Sat 23:00 in Ho Chi Minh City: 4 business hours from Mon 08:30 local = 12:30 +07:00.
    assertThat(hcm.addBusinessMinutes(Instant.parse("2026-11-21T16:00:00Z"), 240))
        .isEqualTo(Instant.parse("2026-11-23T05:30:00Z"));
  }

  @Test
  void refusesBadInput() {
    assertThatThrownBy(() -> STANDARD.addBusinessMinutes(at("2026-11-23T09:00"), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> STANDARD.businessMinutesBetween(at("2026-11-23T10:00"), at("2026-11-23T09:00")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("before");
    assertThatThrownBy(() -> BusinessCalendar.of(LONDON, Map.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one weekday");
    assertThatThrownBy(() -> DailyHours.of("17:30", "08:30"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DailyHours.of("09:00", "09:00"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCalendarThatIsAllHolidaysGivesUpInsteadOfLoopingForever() {
    Map<DayOfWeek, DailyHours> hours = new EnumMap<>(DayOfWeek.class);
    hours.put(DayOfWeek.MONDAY, BusinessCalendar.DEFAULT_HOURS);
    // Every Monday for the next 101 years is a holiday.
    LocalDate first = LocalDate.parse("2026-11-23");
    List<LocalDate> mondays =
        java.util.stream.Stream.iterate(first, d -> d.plusWeeks(1)).limit(53 * 101).toList();
    BusinessCalendar never = BusinessCalendar.of(LONDON, hours, mondays);
    assertThatThrownBy(() -> never.addBusinessMinutes(at("2026-11-23T09:00"), 1))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void holidaysAndHoursAreDefensiveCopies() {
    assertThatThrownBy(() -> STANDARD.holidays().add(LocalDate.parse("2027-01-02")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> STANDARD.weeklyHours().remove(DayOfWeek.MONDAY))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
