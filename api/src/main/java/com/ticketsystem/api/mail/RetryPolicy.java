package com.ticketsystem.api.mail;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Backoff for temporary send failures (a technical choice): by default wait 1, 2, 4, 8, 16, 32
 * minutes after attempts 1-6, then give up after attempt 7.
 */
record RetryPolicy(List<Duration> delays) {

  RetryPolicy {
    delays = List.copyOf(delays);
  }

  /** Attempt 1 plus one more per delay. */
  int maxAttempts() {
    return delays.size() + 1;
  }

  /**
   * How long to wait after a temporary failure on attempt {@code attempt} (1-based), or empty when
   * that was the last attempt and the row should fail.
   */
  Optional<Duration> delayAfter(int attempt) {
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt is 1-based");
    }
    return attempt <= delays.size() ? Optional.of(delays.get(attempt - 1)) : Optional.empty();
  }
}
