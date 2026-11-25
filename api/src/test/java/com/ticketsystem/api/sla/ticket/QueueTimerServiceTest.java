package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.BusinessCalendar;
import com.ticketsystem.api.sla.ClockType;
import com.ticketsystem.api.sla.SlaWarning;
import com.ticketsystem.api.sla.TargetState;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.SlaFields;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.sla.ticket.QueueTimerService.FirstResponseTimer;
import com.ticketsystem.api.sla.ticket.QueueTimerService.TimerInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** ADR-047: the queue timer's edge cases without a database. */
class QueueTimerServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z"); // Monday, London
  private static final BusinessCalendar LONDON =
      BusinessCalendar.standard(ZoneId.of("Europe/London"), List.of());

  private static SlaFields fields(
      ClockType clock, TargetState state, Instant due, Instant respondedAt, long used) {
    return new SlaFields(
        clock,
        240L,
        due,
        respondedAt,
        used,
        state,
        1620L,
        null,
        null,
        0L,
        TargetState.RUNNING,
        0L,
        SlaWarning.Level.NONE,
        NOW);
  }

  private static FirstResponseTimer timer(
      Long org, boolean duplicate, SlaFields f, Optional<BusinessCalendar> cal) {
    return QueueTimerService.timer(new TimerInput(1, org, duplicate, f), cal, NOW);
  }

  @Test
  void duplicatesAndStoppedTargetsAreNotApplicable() {
    SlaFields running =
        fields(ClockType.BUSINESS_HOURS, TargetState.RUNNING, NOW.plusSeconds(3600), null, 0);
    assertThat(timer(1L, true, running, Optional.of(LONDON)).state()).isEqualTo("NOT_APPLICABLE");
    SlaFields stopped = fields(ClockType.BUSINESS_HOURS, TargetState.STOPPED, null, null, 0);
    FirstResponseTimer t = timer(1L, false, stopped, Optional.of(LONDON));
    assertThat(t.state()).isEqualTo("NOT_APPLICABLE");
    assertThat(t.remainingMinutes()).isNull();
  }

  @Test
  void timeLeftIsRoundedUpAndNeverNegative() {
    SlaFields soon =
        fields(ClockType.BUSINESS_HOURS, TargetState.RUNNING, NOW.plusSeconds(61), null, 0);
    assertThat(timer(1L, false, soon, Optional.of(LONDON)).remainingMinutes()).isEqualTo(2);
    // The minute job has not marked it breached yet: zero left, not a negative number.
    SlaFields passed =
        fields(ClockType.BUSINESS_HOURS, TargetState.RUNNING, NOW.minusSeconds(30), null, 0);
    assertThat(timer(1L, false, passed, Optional.of(LONDON)).remainingMinutes()).isZero();
  }

  @Test
  void withoutACalendarBusinessHoursTimeIsUnknownBut247StillCounts() {
    SlaFields business =
        fields(ClockType.BUSINESS_HOURS, TargetState.RUNNING, NOW.plusSeconds(600), null, 0);
    FirstResponseTimer t = timer(null, false, business, Optional.empty());
    assertThat(t.state()).isEqualTo("RUNNING");
    assertThat(t.remainingMinutes()).isNull();
    assertThat(t.businessDayMinutes()).isZero();

    SlaFields allDay =
        fields(ClockType.TWENTY_FOUR_SEVEN, TargetState.RUNNING, NOW.plusSeconds(600), null, 0);
    FirstResponseTimer p1 = timer(null, false, allDay, Optional.empty());
    assertThat(p1.remainingMinutes()).isEqualTo(10);
    assertThat(p1.businessDayMinutes()).isEqualTo(1440);
  }

  @Test
  void breachedAndAnsweredUsesTheStoredMinutes() {
    SlaFields answered =
        fields(
            ClockType.BUSINESS_HOURS,
            TargetState.BREACHED,
            NOW.minusSeconds(3600),
            NOW.minusSeconds(600),
            290);
    assertThat(timer(1L, false, answered, Optional.of(LONDON)).breachedByMinutes()).isEqualTo(50);
    SlaFields open =
        fields(ClockType.BUSINESS_HOURS, TargetState.BREACHED, NOW.minusSeconds(3600), null, 240);
    assertThat(timer(1L, false, open, Optional.of(LONDON)).breachedByMinutes()).isEqualTo(60);
  }

  @Test
  void aTriageRowUsesTheCalendarSlaConfigServiceGivesForNoOrganisation() {
    // ADR-047 Decision 2: the triage default calendar comes from SlaConfigService (asked with a
    // null organisation), once per page, never a second copy of the defaults here.
    SlaConfigService config = Mockito.mock(SlaConfigService.class);
    AccessScope scope = AccessScope.admin(1);
    Mockito.when(config.calendar(scope, null)).thenReturn(LONDON);
    QueueTimerService service = new QueueTimerService(config, Clock.fixed(NOW, ZoneOffset.UTC));
    SlaFields business =
        fields(ClockType.BUSINESS_HOURS, TargetState.RUNNING, NOW.plusSeconds(600), null, 0);

    Map<Long, FirstResponseTimer> timers =
        service.firstResponse(
            scope,
            List.of(
                new TimerInput(1, null, false, business),
                new TimerInput(2, null, false, business)));

    assertThat(timers.get(1L).remainingMinutes()).isEqualTo(10);
    assertThat(timers.get(1L).businessDayMinutes()).isEqualTo(540);
    assertThat(timers.get(2L).remainingMinutes()).isEqualTo(10);
    Mockito.verify(config, Mockito.times(1)).calendar(scope, null);
  }
}
