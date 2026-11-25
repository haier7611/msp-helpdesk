package com.ticketsystem.api.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A test clock that starts at the real time and can be moved forward. */
public class MutableClock extends Clock {

  private volatile Instant now = Instant.now();

  public void reset() {
    now = Instant.now();
  }

  /** Moves the clock to {@code instant} (WP-09 SLA tests replay hand-calculated timelines). */
  public void set(Instant instant) {
    now = instant;
  }

  public void advance(Duration d) {
    now = now.plus(d);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
