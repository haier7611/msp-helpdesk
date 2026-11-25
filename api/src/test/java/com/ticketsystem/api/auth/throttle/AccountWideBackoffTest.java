package com.ticketsystem.api.auth.throttle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The account-wide back-off across addresses (WP-02c, audit PR #7 L2). */
class AccountWideBackoffTest {

  @Test
  void elevenFailuresFromElevenAddressesSlowTheAccountForUnknownBrowsersOnly() {
    MutableClock clock = new MutableClock();
    SignInBackoff b = new SignInBackoff(clock);
    for (int i = 1; i <= 10; i++) {
      b.check("sam@example.test", "203.0.113." + i, false);
      b.recordFailure("sam@example.test", "203.0.113." + i, false);
    }
    // Ten free failures across addresses: nobody is slowed yet.
    assertThatCode(() -> b.check("sam@example.test", "203.0.113.99", false))
        .doesNotThrowAnyException();
    assertThat(b.recordFailure("sam@example.test", "203.0.113.11", false)).isEqualTo(11);

    // A fresh address without the owner's device cookie must now wait 1 s...
    assertThatThrownBy(() -> b.check("sam@example.test", "203.0.113.200", false))
        .isInstanceOfSatisfying(
            ThrottledException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
    // ...but the owner on a known device is not affected.
    assertThatCode(() -> b.check("sam@example.test", "198.51.100.5", true))
        .doesNotThrowAnyException();
    // Other accounts are not affected.
    assertThatCode(() -> b.check("other@example.test", "203.0.113.200", false))
        .doesNotThrowAnyException();

    clock.advance(Duration.ofSeconds(1));
    b.recordFailure("sam@example.test", "203.0.113.12", false);
    assertThatThrownBy(() -> b.check("sam@example.test", "203.0.113.201", false))
        .isInstanceOfSatisfying(
            ThrottledException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(2));

    // A success (from anywhere) clears it.
    b.recordSuccess("sam@example.test", "198.51.100.5");
    assertThatCode(() -> b.check("sam@example.test", "203.0.113.202", false))
        .doesNotThrowAnyException();
  }

  @Test
  void knownDeviceFailuresDoNotCountAccountWide() {
    SignInBackoff b = new SignInBackoff(new MutableClock());
    for (int i = 0; i < 12; i++) {
      b.recordFailure("mfa:9", "203.0.113." + i, true);
    }
    assertThatCode(() -> b.check("mfa:9", "203.0.113.200", false)).doesNotThrowAnyException();
  }

  @Test
  void accountWideDelayIsCappedAtFifteenMinutes() {
    assertThat(SignInBackoff.delayFor(11, SignInBackoff.ACCOUNT_FREE_FAILURES))
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(SignInBackoff.delayFor(14, SignInBackoff.ACCOUNT_FREE_FAILURES))
        .isEqualTo(Duration.ofSeconds(8));
    assertThat(SignInBackoff.delayFor(500, SignInBackoff.ACCOUNT_FREE_FAILURES))
        .isEqualTo(Duration.ofMinutes(15));
  }
}
