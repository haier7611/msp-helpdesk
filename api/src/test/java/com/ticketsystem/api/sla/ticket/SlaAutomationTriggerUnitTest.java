package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.SlaWarning.Level;
import com.ticketsystem.api.sla.TargetResult;
import com.ticketsystem.api.sla.TargetState;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Conditions;
import com.ticketsystem.api.sla.automation.SlaAutomationTrigger;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.SlaTicketRow;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.StoredState;
import com.ticketsystem.api.ticket.TicketSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-049: which stored-state changes are triggers (at risk only from below 80%, breached and met
 * once each), and how rule conditions match (AND, empty = any, the triage marker).
 */
class SlaAutomationTriggerUnitTest {

  private static final Instant T0 = Instant.parse("2026-11-23T09:00:00Z");

  private static SlaTicketRow row(TicketStatus status) {
    return new SlaTicketRow(
        1,
        1L,
        1,
        Priority.P3,
        status,
        TicketSource.AGENT,
        null,
        null,
        T0,
        T0,
        T0,
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

  private static TargetResult result(TargetState state) {
    return new TargetResult(Duration.ofHours(4), Duration.ofHours(1), T0, state);
  }

  private static StoredState stored(TargetState fr, Instant frWarn, TargetState res) {
    return new StoredState(fr, frWarn, res, null, T0);
  }

  private static List<SlaAutomationTrigger> changes(
      StoredState before, TargetState fr, Level frLevel, TargetState res, Level resLevel) {
    return TicketSlaService.stateChanges(
        row(TicketStatus.OPEN), before, result(fr), frLevel, result(res), resLevel);
  }

  @Test
  void atRiskOnlyFromBelowEightyPercent() {
    StoredState below = stored(TargetState.RUNNING, T0.plusSeconds(600), TargetState.RUNNING);
    assertThat(changes(below, TargetState.RUNNING, Level.AT_RISK, TargetState.RUNNING, Level.NONE))
        .containsExactly(SlaAutomationTrigger.FIRST_RESPONSE_AT_RISK);
    // Already at risk when last stored (its 80% instant had come): no new trigger.
    StoredState atRisk = stored(TargetState.RUNNING, T0, TargetState.RUNNING);
    assertThat(changes(atRisk, TargetState.RUNNING, Level.AT_RISK, TargetState.RUNNING, Level.NONE))
        .isEmpty();
  }

  @Test
  void straightPastEightyPercentIsABreachOnly() {
    StoredState below = stored(TargetState.RUNNING, T0.plusSeconds(600), TargetState.RUNNING);
    assertThat(
            changes(below, TargetState.BREACHED, Level.BREACHED, TargetState.RUNNING, Level.NONE))
        .containsExactly(SlaAutomationTrigger.FIRST_RESPONSE_BREACHED);
    StoredState breached = stored(TargetState.BREACHED, null, TargetState.RUNNING);
    assertThat(
            changes(
                breached, TargetState.BREACHED, Level.BREACHED, TargetState.RUNNING, Level.NONE))
        .isEmpty();
  }

  @Test
  void metOnceWhenItBecomesMet() {
    StoredState running = stored(TargetState.MET, null, TargetState.RUNNING);
    assertThat(changes(running, TargetState.MET, Level.NONE, TargetState.MET, Level.NONE))
        .containsExactly(SlaAutomationTrigger.RESOLUTION_MET);
  }

  @Test
  void aFinishedTicketsFirstCalculationTriggersNothing() {
    StoredState never = new StoredState(null, null, null, null, null);
    assertThat(
            TicketSlaService.stateChanges(
                row(TicketStatus.CLOSED),
                never,
                result(TargetState.MET),
                Level.NONE,
                result(TargetState.MET),
                Level.NONE))
        .isEmpty();
  }

  @Test
  void conditionsAreAndedAndEmptyMeansAny() {
    assertThat(Conditions.ANY.matches(null, Priority.P4, 3)).isTrue();
    assertThat(Conditions.ANY.matches(9L, Priority.P1, 3)).isTrue();
    Conditions triage = new Conditions(true, List.of(), List.of(), List.of());
    assertThat(triage.matches(null, Priority.P3, 3)).isTrue();
    assertThat(triage.matches(9L, Priority.P3, 3)).isFalse();
    Conditions narrow = new Conditions(false, List.of(9L), List.of(Priority.P1), List.of(3L));
    assertThat(narrow.matches(9L, Priority.P1, 3)).isTrue();
    assertThat(narrow.matches(9L, Priority.P2, 3)).isFalse();
    assertThat(narrow.matches(9L, Priority.P1, 4)).isFalse();
    assertThat(narrow.matches(8L, Priority.P1, 3)).isFalse();
    assertThat(narrow.matches(null, Priority.P1, 3)).isFalse();
  }
}
