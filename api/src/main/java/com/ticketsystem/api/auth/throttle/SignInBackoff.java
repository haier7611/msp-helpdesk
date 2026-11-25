package com.ticketsystem.api.auth.throttle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Exponential back-off on failed sign-ins per account and client IP (C15): from the 3rd failure the
 * next attempt must wait 1 s, then 2 s, 4 s ... capped at 15 minutes; a success resets it. There is
 * no hard lock, and because the key includes the client IP an attacker slows only themselves - the
 * real user signing in from another address is not affected. Requests inside the delay get 429 with
 * Retry-After; no thread sleeps.
 *
 * <p>There is also an account-wide back-off across all addresses: from the 11th failure on one
 * account from any address, 1 s doubling to 15 minutes. It is skipped for requests from a browser
 * the account's owner has signed in on before (a known device cookie), so a distributed attacker
 * slows themselves without locking the real user out. The same keys cover TOTP and recovery-code
 * checks ({@code mfa:<user id>}).
 *
 * <p>In memory (single api instance, technical choice): a restart forgets the counters, and a
 * second instance would need shared storage (the {@code login_throttle} table that was proposed).
 */
@Component
public class SignInBackoff {

  static final int FREE_FAILURES = 2;
  static final Duration FIRST_DELAY = Duration.ofSeconds(1);
  static final Duration MAX_DELAY = Duration.ofMinutes(15);
  static final Duration FORGET_AFTER = Duration.ofHours(1);
  static final int ACCOUNT_FREE_FAILURES = 10;
  private static final String ACCOUNT_WIDE = "*";

  private record State(int failures, Instant nextAllowedAt, Instant lastFailureAt) {}

  private final Map<String, State> states = new ConcurrentHashMap<>();
  private final Clock clock;

  public SignInBackoff(Clock clock) {
    this.clock = clock;
  }

  /** Throws {@link ThrottledException} while the (account, address) key is inside its delay. */
  public void check(String account, String clientIp) {
    checkKey(key(account, clientIp));
  }

  /**
   * As {@link #check(String, String)}, plus the account-wide delay unless {@code knownDevice} (the
   * request comes from a browser the account's owner has signed in on before).
   */
  public void check(String account, String clientIp, boolean knownDevice) {
    check(account, clientIp);
    if (!knownDevice) {
      checkKey(key(account, ACCOUNT_WIDE));
    }
  }

  private void checkKey(String key) {
    State s = states.get(key);
    Instant now = clock.instant();
    if (s != null && s.nextAllowedAt() != null && now.isBefore(s.nextAllowedAt())) {
      long millis = Duration.between(now, s.nextAllowedAt()).toMillis();
      throw new ThrottledException((millis + 999) / 1000);
    }
  }

  /** Records a failure for (account, address); returns its failure count so far. */
  public int recordFailure(String account, String clientIp) {
    return recordKey(key(account, clientIp), FREE_FAILURES);
  }

  /**
   * Records a failure for (account, address) and - unless {@code knownDevice} - for the account
   * across all addresses. Returns the higher of the two counts.
   */
  public int recordFailure(String account, String clientIp, boolean knownDevice) {
    int pair = recordFailure(account, clientIp);
    if (knownDevice) {
      return pair;
    }
    return Math.max(pair, recordKey(key(account, ACCOUNT_WIDE), ACCOUNT_FREE_FAILURES));
  }

  private int recordKey(String key, int free) {
    Instant now = clock.instant();
    State s =
        states.compute(
            key,
            (k, old) -> {
              int failures = old == null ? 1 : old.failures() + 1;
              Instant next = failures > free ? now.plus(delayFor(failures, free)) : null;
              return new State(failures, next, now);
            });
    return s.failures();
  }

  /** A success clears the (account, address) delay and the account-wide one. */
  public void recordSuccess(String account, String clientIp) {
    states.remove(key(account, clientIp));
    states.remove(key(account, ACCOUNT_WIDE));
  }

  /** 1 s after the 3rd failure, doubling, capped at 15 minutes. */
  static Duration delayFor(int failures) {
    return delayFor(failures, FREE_FAILURES);
  }

  /** 1 s after the first failure beyond {@code free}, doubling, capped at 15 minutes. */
  static Duration delayFor(int failures, int free) {
    int exponent = failures - free - 1;
    if (exponent >= 20) {
      return MAX_DELAY;
    }
    Duration d = FIRST_DELAY.multipliedBy(1L << Math.max(0, exponent));
    return d.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : d;
  }

  private static String key(String account, String clientIp) {
    return (account == null ? "" : account) + "|" + (clientIp == null ? "" : clientIp);
  }

  @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
  void sweep() {
    Instant cutoff = clock.instant().minus(MAX_DELAY).minus(FORGET_AFTER);
    states.values().removeIf(s -> s.lastFailureAt().isBefore(cutoff));
  }

  /** Test hook. */
  public void reset() {
    states.clear();
  }
}
