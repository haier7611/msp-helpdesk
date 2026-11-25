package com.ticketsystem.api.sla;

import static com.ticketsystem.api.sla.SlaFixtures.STANDARD;
import static com.ticketsystem.api.sla.SlaFixtures.at;
import static com.ticketsystem.api.sla.SlaFixtures.history;
import static com.ticketsystem.api.sla.SlaFixtures.local;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SlaCalculatorTest {

  private static SlaInput input(Priority priority, String historySpec, String responded) {
    List<StatusChange> changes = history(historySpec);
    return new SlaInput(
        changes.get(0).at(),
        priority,
        SlaPolicy.defaults(),
        STANDARD,
        changes,
        responded == null ? null : at(responded));
  }

  @Test
  void priorityChangeRecalculatesFromCreation() {
    SlaInput p3 = input(Priority.P3, "NEW@2026-11-23T09:00", null);
    SlaInput p2 = p3.withPriority(Priority.P2);
    Instant asOf = at("2026-11-23T09:45");

    SlaResult before = SlaCalculator.calculate(p3, asOf);
    SlaResult after = SlaCalculator.calculate(p2, asOf);

    assertThat(local(before.firstResponse().dueAt())).isEqualTo("2026-11-23T13:00");
    assertThat(local(after.firstResponse().dueAt())).isEqualTo("2026-11-23T10:00");
    assertThat(local(after.resolution().dueAt())).isEqualTo("2026-11-23T17:00");
    assertThat(after.firstResponse().usedMinutes()).isEqualTo(45);
    // Raising to P1 switches to the 24/7 clock, still from creation.
    SlaResult p1 = SlaCalculator.calculate(p3.withPriority(Priority.P1), asOf);
    assertThat(p1.clock()).isEqualTo(ClockType.TWENTY_FOUR_SEVEN);
    assertThat(local(p1.firstResponse().dueAt())).isEqualTo("2026-11-23T09:30");
    assertThat(p1.firstResponse().state()).isEqualTo(TargetState.BREACHED);
  }

  @Test
  void usingExactlyTheTargetIsStillWithinIt() {
    SlaInput p2 = input(Priority.P2, "NEW@2026-11-23T09:00", "2026-11-23T10:00");
    SlaResult atDue = SlaCalculator.calculate(p2, at("2026-11-23T10:00"));
    assertThat(atDue.firstResponse().state()).isEqualTo(TargetState.MET);
    assertThat(atDue.firstResponse().usedPercent()).isEqualTo(100);

    SlaInput late = input(Priority.P2, "NEW@2026-11-23T09:00", null);
    Instant oneSecondLate = at("2026-11-23T10:00").plusSeconds(1);
    assertThat(SlaCalculator.calculate(late, at("2026-11-23T10:00")).firstResponse().state())
        .isEqualTo(TargetState.RUNNING);
    assertThat(SlaCalculator.calculate(late, oneSecondLate).firstResponse().state())
        .isEqualTo(TargetState.BREACHED);
  }

  @Test
  void resolvingExactlyAtTheDueTimeIsMet() {
    SlaInput p2 = input(Priority.P2, "NEW@2026-11-23T09:00, RESOLVED@2026-11-23T17:00", null);
    SlaResult result = SlaCalculator.calculate(p2, at("2026-11-24T09:00"));
    assertThat(result.resolution().state()).isEqualTo(TargetState.MET);
    assertThat(result.resolution().usedMinutes()).isEqualTo(480);
    assertThat(local(result.resolution().dueAt())).isEqualTo("2026-11-23T17:00");
  }

  @Test
  void breachIsFinalEvenAfterResolving() {
    SlaInput p2 = input(Priority.P2, "NEW@2026-11-23T09:00, RESOLVED@2026-11-24T09:00", null);
    SlaResult result = SlaCalculator.calculate(p2, at("2026-11-25T09:00"));
    assertThat(result.resolution().state()).isEqualTo(TargetState.BREACHED);
    assertThat(result.resolution().breached()).isTrue();
    assertThat(result.resolution().met()).isFalse();
    assertThat(result.resolution().remaining()).isEqualTo(Duration.ZERO);
  }

  @Test
  void changesAndRepliesAfterAsOfAreIgnored() {
    SlaInput p3 =
        input(
            Priority.P3,
            "NEW@2026-11-23T09:00, PENDING_ON_CLIENT@2026-11-23T11:00",
            "2026-11-23T11:00");
    SlaResult early = SlaCalculator.calculate(p3, at("2026-11-23T10:00"));
    assertThat(early.firstResponse().state()).isEqualTo(TargetState.RUNNING);
    assertThat(early.resolution().state()).isEqualTo(TargetState.RUNNING);
    assertThat(early.pausedMinutes()).isZero();
    assertThat(early.resolution().usedMinutes()).isEqualTo(60);
  }

  @Test
  void aStatusChangeAtTheEvaluationInstantApplies() {
    // WP-09 recalculates right when a status changes: the new status must count at once.
    SlaInput p3 =
        input(Priority.P3, "NEW@2026-11-23T09:00, PENDING_ON_CLIENT@2026-11-23T11:00", null);
    SlaResult result = SlaCalculator.calculate(p3, at("2026-11-23T11:00"));
    assertThat(result.resolution().state()).isEqualTo(TargetState.PAUSED);
    assertThat(result.resolution().dueAt()).isNull();
    assertThat(result.resolution().due()).isEmpty();
    assertThat(result.firstResponse().state()).isEqualTo(TargetState.PAUSED);
  }

  @Test
  void aReplyAtCreationIsMetWithNothingUsed() {
    SlaInput p3 = input(Priority.P3, "NEW@2026-11-23T09:00", "2026-11-23T09:00");
    SlaResult result = SlaCalculator.calculate(p3, at("2026-11-23T09:00"));
    assertThat(result.firstResponse().state()).isEqualTo(TargetState.MET);
    assertThat(result.firstResponse().usedMinutes()).isZero();
    assertThat(local(result.firstResponse().dueAt())).isEqualTo("2026-11-23T13:00");
  }

  @Test
  void pendingOnVendorRunsByDefaultAndPausesWhenThePolicySaysSo() {
    List<StatusChange> changes =
        history("NEW@2026-11-23T09:00, PENDING_ON_VENDOR@2026-11-23T10:00");
    Instant asOf = at("2026-11-23T12:00");
    SlaInput running =
        new SlaInput(
            changes.get(0).at(), Priority.P3, SlaPolicy.defaults(), STANDARD, changes, null);
    SlaInput pausing =
        new SlaInput(
            changes.get(0).at(),
            Priority.P3,
            SlaPolicy.defaults().withPendingOnVendorPauses(true),
            STANDARD,
            changes,
            null);
    assertThat(SlaCalculator.calculate(running, asOf).resolution().state())
        .isEqualTo(TargetState.RUNNING);
    assertThat(SlaCalculator.calculate(running, asOf).pausedMinutes()).isZero();
    assertThat(SlaCalculator.calculate(pausing, asOf).resolution().state())
        .isEqualTo(TargetState.PAUSED);
    assertThat(SlaCalculator.calculate(pausing, asOf).pausedMinutes()).isEqualTo(120);
  }

  @Test
  void pausedTimeIsMeasuredOnThePrioritysClock() {
    // Pending on client Fri 17:00 -> Mon 09:00: 60 business minutes, but 64 h on the P1 clock.
    String spec = "NEW@2026-11-20T16:00, PENDING_ON_CLIENT@2026-11-20T17:00, OPEN@2026-11-23T09:00";
    Instant asOf = at("2026-11-23T09:00");
    assertThat(SlaCalculator.calculate(input(Priority.P3, spec, null), asOf).pausedMinutes())
        .isEqualTo(60);
    assertThat(SlaCalculator.calculate(input(Priority.P1, spec, null), asOf).pausedMinutes())
        .isEqualTo(64 * 60);
  }

  @Test
  void aPauseNeverBringsTheDueTimeForward() {
    // Property: inserting a Pending on client spell into a running ticket moves the resolution due
    // time later by at least nothing, and never earlier.
    Random random = new Random(20261118L);
    for (int i = 0; i < 500; i++) {
      Instant created = at("2026-11-02T00:00").plusSeconds(random.nextInt(60 * 24 * 60 * 60));
      Instant pauseStart = created.plusSeconds(random.nextInt(5 * 24 * 60 * 60));
      Instant pauseEnd = pauseStart.plusSeconds(1 + random.nextInt(5 * 24 * 60 * 60));
      Instant asOf = pauseEnd.plusSeconds(random.nextInt(24 * 60 * 60));
      Priority priority = Priority.values()[random.nextInt(4)];

      List<StatusChange> plain = List.of(new StatusChange(created, TicketStatus.OPEN));
      List<StatusChange> paused = new ArrayList<>(plain);
      paused.add(new StatusChange(pauseStart, TicketStatus.PENDING_ON_CLIENT));
      paused.add(new StatusChange(pauseEnd, TicketStatus.OPEN));

      SlaResult a = SlaCalculator.calculate(sample(created, priority, plain), asOf);
      SlaResult b = SlaCalculator.calculate(sample(created, priority, paused), asOf);
      assertThat(b.resolution().dueAt())
          .as("case %d: created %s pause %s-%s %s", i, created, pauseStart, pauseEnd, priority)
          .isAfterOrEqualTo(a.resolution().dueAt());
      assertThat(b.resolution().used()).isLessThanOrEqualTo(a.resolution().used());
      assertThat(b.resolution().used().plus(b.paused())).isEqualTo(a.resolution().used());
    }
  }

  private static SlaInput sample(Instant created, Priority priority, List<StatusChange> changes) {
    return new SlaInput(created, priority, SlaPolicy.defaults(), STANDARD, changes, null);
  }

  @Test
  void refusesInconsistentInput() {
    Instant created = at("2026-11-23T09:00");
    List<StatusChange> ok = List.of(new StatusChange(created, TicketStatus.NEW));
    SlaPolicy policy = SlaPolicy.defaults();

    assertThatThrownBy(() -> new SlaInput(created, Priority.P3, policy, STANDARD, List.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least");
    assertThatThrownBy(
            () ->
                new SlaInput(
                    created,
                    Priority.P3,
                    policy,
                    STANDARD,
                    List.of(new StatusChange(created.plusSeconds(1), TicketStatus.NEW)),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("createdAt");
    assertThatThrownBy(
            () ->
                new SlaInput(
                    created,
                    Priority.P3,
                    policy,
                    STANDARD,
                    List.of(
                        new StatusChange(created, TicketStatus.NEW),
                        new StatusChange(created.plusSeconds(60), TicketStatus.OPEN),
                        new StatusChange(created.plusSeconds(30), TicketStatus.RESOLVED)),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("time order");
    assertThatThrownBy(
            () -> new SlaInput(created, Priority.P3, policy, STANDARD, ok, created.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    SlaInput valid = new SlaInput(created, Priority.P3, policy, STANDARD, ok, null);
    assertThatThrownBy(() -> SlaCalculator.calculate(valid, created.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void historyIsCopied() {
    Instant created = at("2026-11-23T09:00");
    List<StatusChange> changes = new ArrayList<>();
    changes.add(new StatusChange(created, TicketStatus.NEW));
    SlaInput in = new SlaInput(created, Priority.P3, SlaPolicy.defaults(), STANDARD, changes, null);
    changes.add(new StatusChange(created.plusSeconds(60), TicketStatus.RESOLVED));
    assertThat(in.history()).hasSize(1);
  }
}
