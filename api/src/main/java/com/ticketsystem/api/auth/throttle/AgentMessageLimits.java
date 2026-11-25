package com.ticketsystem.api.auth.throttle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-agent caps on public replies and internal notes from the agent UI ({@code
 * app.rate-limits.agent-messages.*}). Both windows apply together to one shared count per agent: a
 * short window stops a runaway script or a stolen session quickly, a long window caps a steady
 * stream.
 *
 * @param shortMax messages allowed in {@code shortPeriod} (default 30)
 * @param shortPeriod the short window (default 1 minute)
 * @param longMax messages allowed in {@code longPeriod} (default 300)
 * @param longPeriod the long window (default 1 hour)
 */
@ConfigurationProperties("app.rate-limits.agent-messages")
public record AgentMessageLimits(
    Integer shortMax, Duration shortPeriod, Integer longMax, Duration longPeriod) {

  public AgentMessageLimits {
    shortMax = shortMax == null ? 30 : shortMax;
    shortPeriod = shortPeriod == null ? Duration.ofMinutes(1) : shortPeriod;
    longMax = longMax == null ? 300 : longMax;
    longPeriod = longPeriod == null ? Duration.ofHours(1) : longPeriod;
    if (shortMax < 1 || longMax < 1) {
      throw new IllegalArgumentException(
          "app.rate-limits.agent-messages short-max and long-max must be at least 1");
    }
    if (shortPeriod.isNegative()
        || shortPeriod.isZero()
        || longPeriod.isNegative()
        || longPeriod.isZero()) {
      throw new IllegalArgumentException(
          "app.rate-limits.agent-messages short-period and long-period must be positive");
    }
  }
}
