package com.ticketsystem.api.sla.ticket;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.BusinessCalendar;
import com.ticketsystem.api.sla.ClockType;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.SlaFields;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.sla.ticket.SlaPanel.State;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "First response" column of the agent queue. For each row it turns the stored SLA values (the
 * same ones the queue's SLA chip reads) into the business time left now, the time over a breached
 * target, or the final state - so the page never does business-hours maths.
 *
 * <p>Read-only: it stores nothing and sends nothing. It reads only the business calendars of the
 * organisations the rows belong to, through the organisation-scoped {@link SlaConfigService}, one
 * lookup per organisation per page; triage rows use the triage default calendar from the same
 * service ({@link SlaConfigService#calendar} with no organisation). States use the {@link
 * SlaPanel.State} names, so the queue and the ticket page's SLA card speak the same words.
 */
@Service
public class QueueTimerService {

  /**
   * The first-response timer of one queue row (internal; agent read model only).
   *
   * @param state RUNNING, STOPPED_OUTSIDE_HOURS, PAUSED, MET, BREACHED or NOT_APPLICABLE
   * @param remainingMinutes business minutes left, rounded up (RUNNING, STOPPED_OUTSIDE_HOURS and
   *     PAUSED); null when it cannot be worked out
   * @param breachedByMinutes how far over the target, rounded up (BREACHED only)
   * @param dueAt when the target is or was due (null while paused)
   * @param businessDayMinutes one day on the priority's clock, for "1d 2h" (0 when unknown)
   * @param asOf the server instant the values are for
   */
  public record FirstResponseTimer(
      String state,
      Long remainingMinutes,
      Long breachedByMinutes,
      Instant dueAt,
      long businessDayMinutes,
      Instant asOf) {}

  /** One queue row: its organisation (null for triage), whether it is a duplicate, its SLA. */
  public record TimerInput(long ticketId, Long organisationId, boolean duplicate, SlaFields sla) {}

  private final SlaConfigService config;
  private final Clock clock;

  public QueueTimerService(SlaConfigService config, Clock clock) {
    this.config = config;
    this.clock = clock;
  }

  /** The timers by ticket id; rows without SLA values (not calculated yet) have no entry. */
  @Transactional(readOnly = true)
  public Map<Long, FirstResponseTimer> firstResponse(
      AccessScope scope, Collection<TimerInput> rows) {
    Instant now = clock.instant();
    Map<Long, Optional<BusinessCalendar>> calendars = new HashMap<>();
    Map<Long, FirstResponseTimer> out = new HashMap<>();
    for (TimerInput row : rows) {
      if (row.sla() == null) {
        continue;
      }
      // A triage row (no organisation) gets the default calendar from SlaConfigService, the same
      // one its SLA was calculated on; HashMap keys it under null.
      Optional<BusinessCalendar> calendar =
          calendars.computeIfAbsent(
              row.organisationId(), org -> Optional.of(config.calendar(scope, org)));
      out.put(row.ticketId(), timer(row, calendar, now));
    }
    return out;
  }

  /**
   * The timer of one row at {@code now}. {@code calendar} is the row's business calendar (the
   * organisation's, or the triage default); when empty, business-hours time left is unknown (24/7
   * still works).
   */
  static FirstResponseTimer timer(
      TimerInput row, Optional<BusinessCalendar> calendar, Instant now) {
    SlaFields f = row.sla();
    Optional<BusinessCalendar> clockCal =
        f.clock() == ClockType.TWENTY_FOUR_SEVEN
            ? Optional.of(
                BusinessCalendar.twentyFourSeven(
                    calendar.map(BusinessCalendar::zone).orElse(ZoneOffset.UTC)))
            : calendar;
    long dayMinutes = clockCal.map(BusinessCalendar::businessDayMinutes).orElse(0L);
    long target = nz(f.firstResponseTargetMinutes());
    long used = nz(f.firstResponseUsedMinutes());
    Instant due = f.firstResponseDueAt();
    if (row.duplicate()) {
      return new FirstResponseTimer(State.NOT_APPLICABLE.name(), null, null, null, 0, now);
    }
    return switch (f.firstResponseState()) {
      case STOPPED -> new FirstResponseTimer(State.NOT_APPLICABLE.name(), null, null, null, 0, now);
      case MET -> new FirstResponseTimer(State.MET.name(), null, null, due, dayMinutes, now);
      case PAUSED ->
          new FirstResponseTimer(
              State.PAUSED.name(), Math.max(0, target - used), null, null, dayMinutes, now);
      case BREACHED -> {
        // Answered late, or no due time: the stored minutes are final. Still unanswered: the time
        // since the due time on the priority's clock (the stored minutes stop at the breach check).
        Long over;
        if (f.firstRespondedAt() != null || due == null) {
          over = Math.max(0, used - target);
        } else if (clockCal.isPresent()) {
          over = minutesUp(clockCal.get(), due, now);
        } else {
          over = null;
        }
        yield new FirstResponseTimer(State.BREACHED.name(), null, over, due, dayMinutes, now);
      }
      case RUNNING -> {
        Long left;
        if (due == null) {
          left = Math.max(0, target - used);
        } else if (clockCal.isPresent()) {
          left = minutesUp(clockCal.get(), now, due);
        } else {
          left = null;
        }
        boolean open = clockCal.map(c -> c.isOpenAt(now)).orElse(true);
        yield new FirstResponseTimer(
            (open ? State.RUNNING : State.STOPPED_OUTSIDE_HOURS).name(),
            left,
            null,
            due,
            dayMinutes,
            now);
      }
    };
  }

  /** Business minutes from {@code a} to {@code b}, rounded up; 0 when {@code b} is not later. */
  private static long minutesUp(BusinessCalendar cal, Instant a, Instant b) {
    if (!b.isAfter(a)) {
      return 0;
    }
    return SlaPanelService.ceilMinutes(cal.businessTimeBetween(a, b));
  }

  private static long nz(Long v) {
    return v == null ? 0 : v;
  }
}
