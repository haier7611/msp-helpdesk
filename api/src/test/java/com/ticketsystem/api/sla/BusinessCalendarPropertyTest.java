package com.ticketsystem.api.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property-based tests for {@link BusinessCalendar} (WP-09a acceptance: "addBusinessMinutes and
 * businessMinutesBetween are inverse; results are monotonic in the input minutes").
 *
 * <p>Written as seeded random generators in plain JUnit rather than with jqwik, so no new test
 * dependency is needed: every run checks the same {@value #CASES} cases per calendar, and a failure
 * message names the inputs so it can be turned into a fixture.
 */
class BusinessCalendarPropertyTest {

  private static final int CASES = 2_000;
  private static final long SEED = 20261118L;

  /** 2026-01-01 .. 2028-12-31, covering both DST changes, holidays and a leap year. */
  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

  private static final long SPAN_SECONDS = Duration.ofDays(3 * 365 + 1).toSeconds();

  static Stream<Arguments> calendars() {
    List<LocalDate> holidays =
        Stream.concat(
                SlaFixtures.HOLIDAYS.stream(),
                Stream.of(
                    LocalDate.parse("2027-04-02"),
                    LocalDate.parse("2027-12-27"),
                    LocalDate.parse("2028-02-29")))
            .toList();
    return Stream.of(
        Arguments.of("standard London", BusinessCalendar.standard(SlaFixtures.LONDON, holidays)),
        Arguments.of("no Wednesday", SlaFixtures.NO_WEDNESDAY),
        Arguments.of("short Friday", SlaFixtures.SHORT_FRIDAY),
        Arguments.of(
            "standard New York",
            BusinessCalendar.standard(ZoneId.of("America/New_York"), holidays)),
        Arguments.of("24/7 London", BusinessCalendar.twentyFourSeven(SlaFixtures.LONDON)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("calendars")
  void betweenUndoesAdd(String name, BusinessCalendar calendar) {
    Random random = new Random(SEED);
    for (int i = 0; i < CASES; i++) {
      Instant start = randomInstant(random);
      long minutes = random.nextInt(20_000);
      Instant end = calendar.addBusinessMinutes(start, minutes);
      assertThat(calendar.businessMinutesBetween(start, end))
          .as("between(%s, add(%s, %d))", start, start, minutes)
          .isEqualTo(minutes);
      assertThat(calendar.businessTimeBetween(start, end))
          .as("exact between(%s, add(%s, %d))", start, start, minutes)
          .isEqualTo(Duration.ofMinutes(minutes));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("calendars")
  void addUndoesBetweenUpToTheEarliestInstant(String name, BusinessCalendar calendar) {
    Random random = new Random(SEED + 1);
    for (int i = 0; i < CASES; i++) {
      Instant a = randomInstant(random);
      Instant b = a.plusSeconds(random.nextInt(60 * 24 * 60 * 60));
      Duration between = calendar.businessTimeBetween(a, b);
      Instant back = calendar.addBusinessTime(a, between);
      // add returns the earliest instant with that much business time, so never after b, and
      // there is no business time between that instant and b.
      assertThat(back).as("add(%s, between(%s, %s))", a, a, b).isBeforeOrEqualTo(b);
      assertThat(calendar.businessTimeBetween(back, b)).isEqualTo(Duration.ZERO);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("calendars")
  void addIsStrictlyMonotonicInMinutes(String name, BusinessCalendar calendar) {
    Random random = new Random(SEED + 2);
    for (int i = 0; i < CASES; i++) {
      Instant start = randomInstant(random);
      long m1 = random.nextInt(10_000);
      long m2 = m1 + 1 + random.nextInt(5_000);
      assertThat(calendar.addBusinessMinutes(start, m1))
          .as("add(%s, %d) < add(%s, %d)", start, m1, start, m2)
          .isBefore(calendar.addBusinessMinutes(start, m2));
      assertThat(calendar.addBusinessMinutes(start, m1)).isAfterOrEqualTo(start);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("calendars")
  void betweenIsMonotonicAndAdditive(String name, BusinessCalendar calendar) {
    Random random = new Random(SEED + 3);
    for (int i = 0; i < CASES; i++) {
      Instant a = randomInstant(random);
      Instant b = a.plusSeconds(random.nextInt(30 * 24 * 60 * 60));
      Instant c = b.plusSeconds(random.nextInt(30 * 24 * 60 * 60));
      Duration ab = calendar.businessTimeBetween(a, b);
      Duration bc = calendar.businessTimeBetween(b, c);
      Duration ac = calendar.businessTimeBetween(a, c);
      assertThat(ab.plus(bc)).as("between(%s,%s) + between(%s,%s)", a, b, b, c).isEqualTo(ac);
      assertThat(ac).isGreaterThanOrEqualTo(ab);
      assertThat(ab).isLessThanOrEqualTo(Duration.between(a, b));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("calendars")
  void addIsAdditive(String name, BusinessCalendar calendar) {
    Random random = new Random(SEED + 4);
    for (int i = 0; i < CASES; i++) {
      Instant start = randomInstant(random);
      long m1 = 1 + random.nextInt(5_000);
      long m2 = 1 + random.nextInt(5_000);
      assertThat(calendar.addBusinessMinutes(calendar.addBusinessMinutes(start, m1), m2))
          .as("add(add(%s, %d), %d)", start, m1, m2)
          .isEqualTo(calendar.addBusinessMinutes(start, m1 + m2));
    }
  }

  private static Instant randomInstant(Random random) {
    // Half on whole minutes (as most tickets are compared), half with seconds.
    long seconds = (long) (random.nextDouble() * SPAN_SECONDS);
    if (random.nextBoolean()) {
      seconds -= seconds % 60;
    }
    return FROM.plusSeconds(seconds);
  }
}
