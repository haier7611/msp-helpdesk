package com.ticketsystem.api.auth.throttle;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-agent cap on public replies and internal notes sent from the agent UI (Bucket4j, in memory,
 * like {@link RateLimiter}): one bucket per supporter with a short and a long window from {@link
 * AgentMessageLimits}; over either cap a {@link ThrottledException} (429 {@code too_many_requests}
 * with Retry-After). Time comes from the application {@link Clock}, so tests move it instead of
 * sleeping. Replies by email arrive through the poller and are not counted.
 */
@Component
@EnableConfigurationProperties(AgentMessageLimits.class)
public class AgentMessageLimiter {

  private final AgentMessageLimits limits;
  private final TimeMeter time;
  private final Clock clock;
  private final Map<Long, Entry> buckets = new ConcurrentHashMap<>();

  /** A supporter's bucket and when it was last used (for the sweep). */
  private static final class Entry {
    final Bucket bucket;
    volatile long lastUsedMillis;

    Entry(Bucket bucket) {
      this.bucket = bucket;
    }
  }

  public AgentMessageLimiter(AgentMessageLimits limits, Clock clock) {
    this.limits = limits;
    this.clock = clock;
    this.time =
        new TimeMeter() {
          @Override
          public long currentTimeNanos() {
            return TimeUnit.MILLISECONDS.toNanos(clock.millis());
          }

          @Override
          public boolean isWallClockBased() {
            return true;
          }
        };
  }

  /** Counts one message for {@code supporterId} or throws {@link ThrottledException}. */
  public void consume(long supporterId) {
    Entry entry = buckets.computeIfAbsent(supporterId, ignored -> new Entry(newBucket()));
    entry.lastUsedMillis = clock.millis();
    ConsumptionProbe probe = entry.bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      throw new ThrottledException(
          TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
    }
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .withCustomTimePrecision(time)
        .addLimit(
            Bandwidth.builder()
                .capacity(limits.shortMax())
                .refillIntervally(limits.shortMax(), limits.shortPeriod())
                .build())
        .addLimit(
            Bandwidth.builder()
                .capacity(limits.longMax())
                .refillIntervally(limits.longMax(), limits.longPeriod())
                .build())
        .build();
  }

  /**
   * Drops buckets unused for longer than both windows (they would be full again), so memory follows
   * recent use.
   */
  @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
  void sweep() {
    long idle = Math.max(limits.shortPeriod().toMillis(), limits.longPeriod().toMillis());
    long now = clock.millis();
    buckets.values().removeIf(e -> now - e.lastUsedMillis > idle);
  }

  /** Test hook: forget all buckets. */
  public void reset() {
    buckets.clear();
  }
}
