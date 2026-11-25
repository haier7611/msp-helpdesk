package com.ticketsystem.api.sla;

import static com.ticketsystem.api.sla.SlaFixtures.STANDARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlaPolicyAndWarningTest {

  private static final BusinessCalendar CLOCK =
      BusinessCalendar.twentyFourSeven(SlaFixtures.LONDON);

  @Test
  void defaultsMatchTheAdr017Table() {
    SlaPolicy policy = SlaPolicy.defaults();
    assertThat(policy.pendingOnVendorPauses()).isFalse();

    PriorityTargets p1 = policy.targetsFor(Priority.P1);
    assertThat(p1.clock()).isEqualTo(ClockType.TWENTY_FOUR_SEVEN);
    assertThat(p1.firstResponse().toMinutes(CLOCK)).isEqualTo(30);
    assertThat(p1.resolution().toMinutes(CLOCK)).isEqualTo(240);

    PriorityTargets p2 = policy.targetsFor(Priority.P2);
    assertThat(p2.clock()).isEqualTo(ClockType.BUSINESS_HOURS);
    assertThat(p2.firstResponse().toMinutes(STANDARD)).isEqualTo(60);
    assertThat(p2.resolution().toMinutes(STANDARD)).isEqualTo(480);

    PriorityTargets p3 = policy.targetsFor(Priority.P3);
    assertThat(p3.clock()).isEqualTo(ClockType.BUSINESS_HOURS);
    assertThat(p3.firstResponse().toMinutes(STANDARD)).isEqualTo(240);
    assertThat(p3.resolution().toMinutes(STANDARD)).isEqualTo(3 * 540);

    PriorityTargets p4 = policy.targetsFor(Priority.P4);
    assertThat(p4.clock()).isEqualTo(ClockType.BUSINESS_HOURS);
    assertThat(p4.firstResponse().toMinutes(STANDARD)).isEqualTo(540);
    assertThat(p4.resolution().toMinutes(STANDARD)).isEqualTo(10 * 540);
  }

  @Test
  void p1MustStayOnTheTwentyFourSevenClock() {
    Map<Priority, PriorityTargets> targets = new EnumMap<>(SlaPolicy.defaults().targets());
    targets.put(
        Priority.P1,
        new PriorityTargets(SlaTarget.minutes(30), SlaTarget.hours(4), ClockType.BUSINESS_HOURS));
    assertThatThrownBy(() -> new SlaPolicy(targets, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("24/7");
  }

  @Test
  void anAdminCanAdjustTargetsAndTheFlag() {
    Map<Priority, PriorityTargets> targets = new EnumMap<>(SlaPolicy.defaults().targets());
    targets.put(
        Priority.P3,
        new PriorityTargets(SlaTarget.hours(2), SlaTarget.days(2), ClockType.TWENTY_FOUR_SEVEN));
    SlaPolicy custom = new SlaPolicy(targets, true);
    assertThat(custom.pendingOnVendorPauses()).isTrue();
    assertThat(custom.targetsFor(Priority.P3).resolution().toMinutes(CLOCK)).isEqualTo(2 * 1440);
    assertThatThrownBy(() -> custom.targets().remove(Priority.P1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void everyPriorityNeedsTargetsAndTargetsMustBePositive() {
    Map<Priority, PriorityTargets> targets = new EnumMap<>(SlaPolicy.defaults().targets());
    targets.remove(Priority.P4);
    assertThatThrownBy(() -> new SlaPolicy(targets, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("P4");
    assertThatThrownBy(() -> SlaTarget.minutes(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SlaTarget.days(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void statusClockEffectsMatchTheSpecTable() {
    assertThat(TicketStatus.NEW.clockEffect(false)).isEqualTo(TicketStatus.ClockEffect.RUNNING);
    assertThat(TicketStatus.OPEN.clockEffect(true)).isEqualTo(TicketStatus.ClockEffect.RUNNING);
    assertThat(TicketStatus.PENDING_ON_CLIENT.clockEffect(false))
        .isEqualTo(TicketStatus.ClockEffect.PAUSED);
    assertThat(TicketStatus.PENDING_ON_VENDOR.clockEffect(false))
        .isEqualTo(TicketStatus.ClockEffect.RUNNING);
    assertThat(TicketStatus.PENDING_ON_VENDOR.clockEffect(true))
        .isEqualTo(TicketStatus.ClockEffect.PAUSED);
    assertThat(TicketStatus.RESOLVED.clockEffect(true)).isEqualTo(TicketStatus.ClockEffect.STOPPED);
    assertThat(TicketStatus.CLOSED.clockEffect(false)).isEqualTo(TicketStatus.ClockEffect.STOPPED);
  }

  private static TargetResult result(Duration used, TargetState state) {
    return new TargetResult(Duration.ofMinutes(100), used, Instant.EPOCH, state);
  }

  @Test
  void warningAtEightyPercent() {
    Duration justUnder = Duration.ofMinutes(80).minusMillis(1);
    assertThat(SlaWarning.levelOf(result(justUnder, TargetState.RUNNING)))
        .isEqualTo(SlaWarning.Level.NONE);
    assertThat(SlaWarning.levelOf(result(Duration.ofMinutes(80), TargetState.RUNNING)))
        .isEqualTo(SlaWarning.Level.AT_RISK);
    assertThat(SlaWarning.levelOf(result(Duration.ofMinutes(90), TargetState.PAUSED)))
        .isEqualTo(SlaWarning.Level.AT_RISK);
    assertThat(SlaWarning.levelOf(result(Duration.ofMinutes(101), TargetState.BREACHED)))
        .isEqualTo(SlaWarning.Level.BREACHED);
    assertThat(SlaWarning.levelOf(result(Duration.ofMinutes(95), TargetState.MET)))
        .isEqualTo(SlaWarning.Level.NONE);
    assertThat(SlaWarning.levelOf(result(Duration.ofMinutes(95), TargetState.STOPPED)))
        .isEqualTo(SlaWarning.Level.NONE);
    assertThat(result(justUnder, TargetState.RUNNING).usedPercent()).isEqualTo(79);
  }

  @Test
  void warningFromARealCalculation() {
    // P2 created Mon 09:00: at 09:48 the 60-minute first response is 80% used.
    var changes = SlaFixtures.history("NEW@2026-11-23T09:00");
    SlaInput in =
        new SlaInput(
            changes.get(0).at(), Priority.P2, SlaPolicy.defaults(), STANDARD, changes, null);
    TargetResult at47 =
        SlaCalculator.calculate(in, SlaFixtures.at("2026-11-23T09:47")).firstResponse();
    TargetResult at48 =
        SlaCalculator.calculate(in, SlaFixtures.at("2026-11-23T09:48")).firstResponse();
    assertThat(SlaWarning.levelOf(at47)).isEqualTo(SlaWarning.Level.NONE);
    assertThat(SlaWarning.levelOf(at48)).isEqualTo(SlaWarning.Level.AT_RISK);
    assertThat(at48.usedPercent()).isEqualTo(80);
  }
}
