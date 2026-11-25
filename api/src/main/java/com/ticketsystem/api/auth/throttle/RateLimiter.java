package com.ticketsystem.api.auth.throttle;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flat rate caps per client IP and per email on the auth endpoints (Bucket4j, in memory - one api
 * instance). The growing per-account delay is {@link SignInBackoff}. Buckets idle for longer than
 * their refill period are dropped by a scheduled sweep, so memory stays bounded by recent traffic.
 */
@Component
public class RateLimiter {

  /** What is being limited, with its cap. */
  public enum Scope {
    /** Sign-in attempts per client IP. */
    SIGN_IN_IP(30, Duration.ofMinutes(1)),
    /** Reset and magic-link requests per client IP. */
    LINK_REQUEST_IP(10, Duration.ofMinutes(10)),
    /** Reset and magic-link mails per email address (excess requests are silently not mailed). */
    LINK_REQUEST_EMAIL(3, Duration.ofMinutes(15)),
    /** Token and code checks (accept invitation, reset confirm, magic link consume) per IP. */
    TOKEN_IP(20, Duration.ofMinutes(1)),
    /** First-admin setup attempts per IP. */
    SETUP_IP(5, Duration.ofMinutes(10)),
    /** Mailbox test connections per mailbox (each opens an IMAP login and queues a probe mail). */
    MAILBOX_TEST_MAILBOX(1, Duration.ofSeconds(60)),
    /** Mailbox test connections per admin. */
    MAILBOX_TEST_ADMIN(10, Duration.ofHours(1)),
    /** Integrations API calls per key: a hard cap of 60 a minute. */
    API_KEY(60, Duration.ofMinutes(1)),
    /** Integrations API calls with a missing or invalid key, per client IP. */
    API_KEY_INVALID_IP(30, Duration.ofMinutes(1)),
    /** CC checks from the composer per supporter (limits address probing). */
    CC_CHECK_AGENT(60, Duration.ofMinutes(1));

    final long capacity;
    final Duration period;

    Scope(long capacity, Duration period) {
      this.capacity = capacity;
      this.period = period;
    }
  }

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /** Takes one token or throws {@link ThrottledException} with the wait in seconds. */
  public void consume(Scope scope, String key) {
    ConsumptionProbe probe = bucket(scope, key).tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      throw new ThrottledException(
          TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
    }
  }

  /** Takes one token; false when the cap is reached (for silent limits). */
  public boolean tryConsume(Scope scope, String key) {
    return bucket(scope, key).tryConsume(1);
  }

  private Bucket bucket(Scope scope, String key) {
    String k = scope.name() + "|" + (key == null ? "" : key);
    return buckets.computeIfAbsent(
        k,
        ignored ->
            Bucket.builder()
                .addLimit(
                    Bandwidth.builder()
                        .capacity(scope.capacity)
                        .refillIntervally(scope.capacity, scope.period)
                        .build())
                .build());
  }

  /** Drops buckets that are full again (nothing to remember). */
  @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
  void sweep() {
    buckets
        .entrySet()
        .removeIf(
            e -> {
              Scope scope = Scope.valueOf(e.getKey().substring(0, e.getKey().indexOf('|')));
              return e.getValue().getAvailableTokens() >= scope.capacity;
            });
  }

  /** Test hook: forget all buckets. */
  public void reset() {
    buckets.clear();
  }
}
