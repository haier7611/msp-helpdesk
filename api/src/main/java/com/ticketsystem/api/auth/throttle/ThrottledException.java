package com.ticketsystem.api.auth.throttle;

/** A request refused by a rate cap or the sign-in back-off; answered 429 with Retry-After. */
public class ThrottledException extends RuntimeException {

  private final long retryAfterSeconds;

  public ThrottledException(long retryAfterSeconds) {
    super("too many requests", null, false, false);
    this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
