package com.ticketsystem.api.sla;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A clock that counts only business time: weekly hours per weekday, a holiday list and a timezone
 * (default business hours: Monday-Friday 08:30-17:30 in the organisation's timezone, with a holiday
 * list per organisation), or the 24/7 variant that counts every minute (P1).
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>Business time is real elapsed time inside each day's window. Each day's window is built as
 *       a {@link ZonedDateTime} in the calendar's zone, so a DST change moves the window with local
 *       time and a window spanning the change is its real length (DST-safe).
 *   <li>A holiday (a local date in the calendar's zone) has no business hours, whatever its
 *       weekday. Holidays are ignored by the 24/7 calendar.
 *   <li>A day without hours (a weekday not in the weekly hours) counts nothing.
 *   <li>A business day is one full set of business hours. For targets given in days this is {@link
 *       #businessDayMinutes()}: the longest weekday's nominal hours (540 minutes for the default
 *       08:30-17:30), or 1,440 minutes for the 24/7 calendar.
 * </ul>
 *
 * <p>Immutable and thread-safe.
 */
public final class BusinessCalendar {

  /** Default business hours, 08:30-17:30. */
  public static final DailyHours DEFAULT_HOURS = DailyHours.of("08:30", "17:30");

  /** Searching further than this many days for business time is treated as a bad calendar. */
  private static final int MAX_SEARCH_DAYS = 366 * 100;

  private final ZoneId zone;
  private final Map<DayOfWeek, DailyHours> weeklyHours;
  private final Set<LocalDate> holidays;
  private final boolean twentyFourSeven;

  private BusinessCalendar(
      ZoneId zone,
      Map<DayOfWeek, DailyHours> weeklyHours,
      Collection<LocalDate> holidays,
      boolean twentyFourSeven) {
    this.zone = Objects.requireNonNull(zone, "zone");
    EnumMap<DayOfWeek, DailyHours> hours = new EnumMap<>(DayOfWeek.class);
    Objects.requireNonNull(weeklyHours, "weeklyHours")
        .forEach((day, h) -> hours.put(Objects.requireNonNull(day), Objects.requireNonNull(h)));
    if (!twentyFourSeven && hours.isEmpty()) {
      throw new IllegalArgumentException("a business calendar needs hours on at least one weekday");
    }
    this.weeklyHours = Collections.unmodifiableMap(hours);
    TreeSet<LocalDate> days = new TreeSet<>();
    Objects.requireNonNull(holidays, "holidays").forEach(d -> days.add(Objects.requireNonNull(d)));
    this.holidays = Collections.unmodifiableSet(days);
    this.twentyFourSeven = twentyFourSeven;
  }

  /**
   * A business-hours calendar.
   *
   * @param zone the organisation's timezone
   * @param weeklyHours hours per weekday; a weekday that is absent has no business hours
   * @param holidays local dates (in {@code zone}) with no business hours
   */
  public static BusinessCalendar of(
      ZoneId zone, Map<DayOfWeek, DailyHours> weeklyHours, Collection<LocalDate> holidays) {
    return new BusinessCalendar(zone, weeklyHours, holidays, false);
  }

  /** The default calendar: Monday-Friday 08:30-17:30 in {@code zone}. */
  public static BusinessCalendar standard(ZoneId zone, Collection<LocalDate> holidays) {
    EnumMap<DayOfWeek, DailyHours> hours = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek day : DayOfWeek.values()) {
      if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
        hours.put(day, DEFAULT_HOURS);
      }
    }
    return of(zone, hours, holidays);
  }

  /**
   * The 24/7 clock (P1): every minute counts, holidays are ignored. The zone is kept only so
   * results can be shown in the organisation's local time.
   */
  public static BusinessCalendar twentyFourSeven(ZoneId zone) {
    return new BusinessCalendar(zone, Map.of(), Set.of(), true);
  }

  public ZoneId zone() {
    return zone;
  }

  /** Hours per weekday (unmodifiable); empty for the 24/7 calendar. */
  public Map<DayOfWeek, DailyHours> weeklyHours() {
    return weeklyHours;
  }

  /** Holidays in date order (unmodifiable); empty for the 24/7 calendar. */
  public Set<LocalDate> holidays() {
    return holidays;
  }

  public boolean isTwentyFourSeven() {
    return twentyFourSeven;
  }

  /**
   * Minutes in one business day (a business day is one full set of business hours): the longest
   * weekday's nominal hours, or 1,440 for the 24/7 calendar.
   */
  public long businessDayMinutes() {
    if (twentyFourSeven) {
      return 24 * 60;
    }
    return weeklyHours.values().stream().mapToLong(DailyHours::nominalMinutes).max().orElseThrow();
  }

  /**
   * The earliest instant at which {@code minutes} of business time have passed since {@code start}.
   * Adding zero returns {@code start} unchanged; otherwise the result is always inside a business
   * window or exactly at its close (so Friday 08:30 + 9 h is Friday 17:30, not Monday).
   */
  public Instant addBusinessMinutes(Instant start, long minutes) {
    if (minutes < 0) {
      throw new IllegalArgumentException("minutes must not be negative: " + minutes);
    }
    return addBusinessTime(start, Duration.ofMinutes(minutes));
  }

  /** Whole minutes of business time between {@code a} and {@code b} (rounded down). */
  public long businessMinutesBetween(Instant a, Instant b) {
    return businessTimeBetween(a, b).toMinutes();
  }

  /** As {@link #addBusinessMinutes}, to the precision of the {@link Duration}. */
  public Instant addBusinessTime(Instant start, Duration amount) {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(amount, "amount");
    if (amount.isNegative()) {
      throw new IllegalArgumentException("amount must not be negative: " + amount);
    }
    if (twentyFourSeven || amount.isZero()) {
      return start.plus(amount);
    }
    Duration remaining = amount;
    LocalDate day = start.atZone(zone).toLocalDate();
    for (int i = 0; i < MAX_SEARCH_DAYS; i++, day = day.plusDays(1)) {
      Optional<Window> window = windowOn(day);
      if (window.isEmpty()) {
        continue;
      }
      Instant begin = later(window.get().open(), start);
      Instant close = window.get().close();
      if (begin.isBefore(close)) {
        Duration available = Duration.between(begin, close);
        if (remaining.compareTo(available) <= 0) {
          return begin.plus(remaining);
        }
        remaining = remaining.minus(available);
      }
    }
    throw new ArithmeticException(
        "no business time found within " + MAX_SEARCH_DAYS + " days of " + start);
  }

  /**
   * Business time between {@code a} and {@code b}: the overlap of {@code [a, b]} with the business
   * windows. {@code b} must not be before {@code a}.
   */
  public Duration businessTimeBetween(Instant a, Instant b) {
    Objects.requireNonNull(a, "a");
    Objects.requireNonNull(b, "b");
    if (b.isBefore(a)) {
      throw new IllegalArgumentException("end " + b + " is before start " + a);
    }
    if (twentyFourSeven) {
      return Duration.between(a, b);
    }
    Duration total = Duration.ZERO;
    LocalDate last = b.atZone(zone).toLocalDate();
    for (LocalDate day = a.atZone(zone).toLocalDate(); !day.isAfter(last); day = day.plusDays(1)) {
      Optional<Window> window = windowOn(day);
      if (window.isEmpty()) {
        continue;
      }
      Instant begin = later(window.get().open(), a);
      Instant end = earlier(window.get().close(), b);
      if (begin.isBefore(end)) {
        total = total.plus(Duration.between(begin, end));
      }
    }
    return total;
  }

  /**
   * Whether business time counts at {@code at}: inside a window, opening instant included, closing
   * instant excluded. Always true for the 24/7 calendar (P1 always runs).
   */
  public boolean isOpenAt(Instant at) {
    Objects.requireNonNull(at, "at");
    if (twentyFourSeven) {
      return true;
    }
    Optional<Window> window = windowOn(at.atZone(zone).toLocalDate());
    return window.isPresent()
        && !at.isBefore(window.get().open())
        && at.isBefore(window.get().close());
  }

  /**
   * The instant business time next starts counting at or after {@code at}: {@code at} itself when
   * the calendar is open, otherwise the next window's opening (holidays and days without hours
   * skipped). The SLA panel shows it as "Clock stopped until Mon 08:30".
   */
  public Instant nextOpening(Instant at) {
    Objects.requireNonNull(at, "at");
    if (twentyFourSeven) {
      return at;
    }
    LocalDate day = at.atZone(zone).toLocalDate();
    for (int i = 0; i < MAX_SEARCH_DAYS; i++, day = day.plusDays(1)) {
      Optional<Window> window = windowOn(day);
      if (window.isPresent() && at.isBefore(window.get().close())) {
        return later(window.get().open(), at);
      }
    }
    throw new ArithmeticException(
        "no business time found within " + MAX_SEARCH_DAYS + " days of " + at);
  }

  /**
   * The close of the window {@code at} is in, or {@code null} when the calendar is closed at {@code
   * at} or is the 24/7 calendar (which never closes). The ticket page stops its countdown there and
   * asks the server again.
   */
  public Instant closingAfter(Instant at) {
    if (twentyFourSeven || !isOpenAt(at)) {
      return null;
    }
    return windowOn(at.atZone(zone).toLocalDate()).map(Window::close).orElse(null);
  }

  private Optional<Window> windowOn(LocalDate day) {
    if (holidays.contains(day)) {
      return Optional.empty();
    }
    DailyHours hours = weeklyHours.get(day.getDayOfWeek());
    if (hours == null) {
      return Optional.empty();
    }
    // ZonedDateTime.of moves a local time in a DST gap forward and picks the earlier offset in an
    // overlap, so the window always follows local wall-clock time.
    Instant open = ZonedDateTime.of(day, hours.start(), zone).toInstant();
    Instant close =
        hours.endsAtEndOfDay()
            ? day.plusDays(1).atStartOfDay(zone).toInstant()
            : ZonedDateTime.of(day, hours.end(), zone).toInstant();
    if (!close.isAfter(open)) {
      return Optional.empty();
    }
    return Optional.of(new Window(open, close));
  }

  private static Instant later(Instant x, Instant y) {
    return x.isAfter(y) ? x : y;
  }

  private static Instant earlier(Instant x, Instant y) {
    return x.isBefore(y) ? x : y;
  }

  private record Window(Instant open, Instant close) {}

  @Override
  public String toString() {
    return twentyFourSeven
        ? "BusinessCalendar[24/7, " + zone + "]"
        : "BusinessCalendar[" + zone + ", " + weeklyHours + ", holidays=" + holidays + "]";
  }
}
