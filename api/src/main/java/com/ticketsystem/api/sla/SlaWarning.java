package com.ticketsystem.api.sla;

import java.util.Objects;

/**
 * The queue warning for one target ("about to breach" at 80% of the target used, and "breached").
 * The every-minute job uses this to flag the queue and notify.
 */
public final class SlaWarning {

  /** "About to breach" threshold: 80% of the target used. */
  public static final int AT_RISK_PERCENT = 80;

  private SlaWarning() {}

  /** The warning to show for a target. */
  public enum Level {
    /** Nothing to flag (below 80%, met, or stopped). */
    NONE,
    /** At least 80% of the target used, not yet breached, still open (running or paused). */
    AT_RISK,
    /** The target is breached. */
    BREACHED
  }

  /**
   * The warning level for {@code result}. Paused targets at or above 80% stay "about to breach",
   * because the clock resumes as soon as the client replies.
   */
  public static Level levelOf(TargetResult result) {
    Objects.requireNonNull(result, "result");
    return switch (result.state()) {
      case BREACHED -> Level.BREACHED;
      case MET, STOPPED -> Level.NONE;
      case RUNNING, PAUSED -> isAtRisk(result) ? Level.AT_RISK : Level.NONE;
    };
  }

  private static boolean isAtRisk(TargetResult result) {
    // Compared exactly (used * 100 >= target * 80) so 79.99% is not rounded up to 80%.
    return result.used().toMillis() * 100 >= result.target().toMillis() * AT_RISK_PERCENT;
  }
}
