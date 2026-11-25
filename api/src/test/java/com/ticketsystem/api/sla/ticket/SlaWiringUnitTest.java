package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.sla.BusinessCalendar;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.SlaWarning;
import com.ticketsystem.api.sla.StatusChange;
import com.ticketsystem.api.sla.TargetResult;
import com.ticketsystem.api.sla.TargetState;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.SlaTicketRow;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.StatusEvent;
import com.ticketsystem.api.sla.config.SlaConfigTypes.SettingsRow;
import com.ticketsystem.api.ticket.TicketSource;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure helpers of the WP-09 wiring. */
class SlaWiringUnitTest {

  private static final Instant T0 = Instant.parse("2026-11-23T09:00:00Z");

  private static SlaTicketRow row(Instant created) {
    return new SlaTicketRow(
        1,
        1L,
        1,
        Priority.P3,
        TicketStatus.OPEN,
        TicketSource.AGENT,
        null,
        null,
        created,
        created,
        created,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void statusHistoryStartsNewAtCreationAndNeverGoesBackInTime() {
    List<StatusChange> h =
        TicketSlaService.statusHistory(
            row(T0),
            List.of(
                new StatusEvent(TicketStatus.OPEN, T0.minusSeconds(5)),
                new StatusEvent(TicketStatus.PENDING_ON_CLIENT, T0.plusSeconds(60)),
                new StatusEvent(TicketStatus.OPEN, T0.plusSeconds(30))));
    assertThat(h)
        .extracting(StatusChange::status)
        .containsExactly(
            TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING_ON_CLIENT, TicketStatus.OPEN);
    assertThat(h)
        .extracting(StatusChange::at)
        .containsExactly(T0, T0, T0.plusSeconds(60), T0.plusSeconds(60));
  }

  @Test
  void warnAtIsWhen80PercentIsUsedOnTheClock() {
    BusinessCalendar c = BusinessCalendar.twentyFourSeven(ZoneId.of("UTC"));
    TargetResult running =
        new TargetResult(
            Duration.ofMinutes(30),
            Duration.ofMinutes(10),
            T0.plusSeconds(1200),
            TargetState.RUNNING);
    assertThat(TicketSlaService.warnAt(running, c, T0)).isEqualTo(T0.plus(Duration.ofMinutes(14)));
    TargetResult over =
        new TargetResult(Duration.ofMinutes(30), Duration.ofMinutes(25), null, TargetState.PAUSED);
    assertThat(TicketSlaService.warnAt(over, c, T0)).isEqualTo(T0);
    TargetResult pausedLow =
        new TargetResult(Duration.ofMinutes(30), Duration.ofMinutes(5), null, TargetState.PAUSED);
    assertThat(TicketSlaService.warnAt(pausedLow, c, T0)).isNull();
    TargetResult met =
        new TargetResult(Duration.ofMinutes(30), Duration.ofMinutes(5), T0, TargetState.MET);
    assertThat(TicketSlaService.warnAt(met, c, T0)).isNull();
  }

  @Test
  void nextCheckIsTheWarningThenJustAfterTheDueTime() {
    TargetResult r =
        new TargetResult(Duration.ofMinutes(30), Duration.ofMinutes(25), T0, TargetState.RUNNING);
    assertThat(TicketSlaService.nextCheck(r, SlaWarning.Level.NONE, T0.plusSeconds(9)))
        .isEqualTo(T0.plusSeconds(9));
    assertThat(TicketSlaService.nextCheck(r, SlaWarning.Level.AT_RISK, null))
        .isEqualTo(T0.plusSeconds(1));
    assertThat(TicketSlaService.nextCheck(r, SlaWarning.Level.BREACHED, null)).isNull();
  }

  @Test
  void resolvedAtIsTheLastResolvedUnlessReopened() {
    List<StatusChange> h =
        List.of(
            new StatusChange(T0, TicketStatus.NEW),
            new StatusChange(T0.plusSeconds(10), TicketStatus.RESOLVED),
            new StatusChange(T0.plusSeconds(20), TicketStatus.OPEN),
            new StatusChange(T0.plusSeconds(30), TicketStatus.RESOLVED),
            new StatusChange(T0.plusSeconds(40), TicketStatus.CLOSED));
    assertThat(TicketSlaService.resolvedAt(TicketStatus.CLOSED, h)).isEqualTo(T0.plusSeconds(30));
    assertThat(TicketSlaService.resolvedAt(TicketStatus.OPEN, h)).isNull();
    // Closed straight from Open (a duplicate) has no resolved time.
    assertThat(
            TicketSlaService.resolvedAt(
                TicketStatus.CLOSED,
                List.of(
                    new StatusChange(T0, TicketStatus.NEW),
                    new StatusChange(T0.plusSeconds(5), TicketStatus.CLOSED))))
        .isNull();
  }

  @Test
  void apiAlertsEmailTheClientOnlyWhenOptedInAndAutomatedSendersNever() {
    SettingsRow off = SettingsRow.defaults();
    SettingsRow on = new SettingsRow(true, 3, true, 7, true, 5, true, null);
    assertThat(PendingTimersService.clientEmailAllowed(TicketSource.API, off)).isFalse();
    assertThat(PendingTimersService.clientEmailAllowed(TicketSource.API, on)).isTrue();
    assertThat(PendingTimersService.clientEmailAllowed(TicketSource.EMAIL, off)).isTrue();
    assertThat(
            ClientNoticeMailer.deliverable(
                List.of(
                    "pat@acme.example",
                    "no-reply@acme.example",
                    "MAILER-DAEMON@x.example",
                    "bounces+42@x.example",
                    "pat@acme.example")))
        .containsExactly("pat@acme.example");
  }
}
