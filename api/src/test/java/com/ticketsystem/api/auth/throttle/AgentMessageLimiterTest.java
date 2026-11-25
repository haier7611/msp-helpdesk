package com.ticketsystem.api.auth.throttle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The per-agent message cap: short and long window together, per agent, on the app clock. */
class AgentMessageLimiterTest {

  private final MutableClock clock = new MutableClock();

  private AgentMessageLimiter limiter(int shortMax, int longMax) {
    return new AgentMessageLimiter(
        new AgentMessageLimits(shortMax, Duration.ofMinutes(1), longMax, Duration.ofHours(1)),
        clock);
  }

  private static void sendN(AgentMessageLimiter l, long agent, int n) {
    for (int i = 0; i < n; i++) {
      l.consume(agent);
    }
  }

  @Test
  void defaultsAre30AMinuteAnd300AnHour() {
    AgentMessageLimits d = new AgentMessageLimits(null, null, null, null);
    assertThat(d.shortMax()).isEqualTo(30);
    assertThat(d.shortPeriod()).isEqualTo(Duration.ofMinutes(1));
    assertThat(d.longMax()).isEqualTo(300);
    assertThat(d.longPeriod()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void invalidSettingsAreRefusedAtStartup() {
    assertThatThrownBy(() -> new AgentMessageLimits(0, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgentMessageLimits(null, Duration.ZERO, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgentMessageLimits(null, null, null, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theShortWindowRefusesTheNextMessageUntilItResets() {
    AgentMessageLimiter l = limiter(3, 100);
    sendN(l, 1, 3);
    assertThatThrownBy(() -> l.consume(1))
        .isInstanceOfSatisfying(
            ThrottledException.class, e -> assertThat(e.retryAfterSeconds()).isBetween(1L, 61L));
    l.consume(2); // another agent is not affected
    clock.advance(Duration.ofSeconds(61));
    sendN(l, 1, 3);
  }

  @Test
  void theLongWindowCapsASteadyStream() {
    AgentMessageLimiter l = limiter(3, 7);
    sendN(l, 1, 3);
    clock.advance(Duration.ofSeconds(61));
    sendN(l, 1, 3);
    clock.advance(Duration.ofSeconds(61));
    sendN(l, 1, 1); // 7 in about two minutes: the hourly cap is reached
    clock.advance(Duration.ofSeconds(61));
    assertThatThrownBy(() -> l.consume(1))
        .isInstanceOfSatisfying(
            ThrottledException.class, e -> assertThat(e.retryAfterSeconds()).isGreaterThan(60L));
    clock.advance(Duration.ofHours(1));
    sendN(l, 1, 3);
  }

  @Test
  void resetForgetsEveryCount() {
    AgentMessageLimiter l = limiter(1, 1);
    l.consume(1);
    l.reset();
    l.consume(1);
  }
}
