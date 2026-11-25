package com.ticketsystem.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.throttle.SignInBackoff;
import com.ticketsystem.api.auth.throttle.ThrottledException;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests: tokens, password policy, back-off, email normalisation, link building. */
class AuthUnitTest {

  private static final PasswordPolicy POLICY = new PasswordPolicy(new BreachedPasswordList());

  @Test
  void tokensAre256BitUrlSafeAndUnique() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      String t = SecureTokens.newToken();
      assertThat(t).hasSize(43).matches("[A-Za-z0-9_-]{43}");
      assertThat(SecureTokens.looksLikeToken(t)).isTrue();
      assertThat(seen.add(t)).isTrue();
    }
    assertThat(SecureTokens.looksLikeToken("short")).isFalse();
    assertThat(SecureTokens.looksLikeToken(null)).isFalse();
  }

  @Test
  void hashesAreSha256HexAndComparedExactly() {
    assertThat(SecureTokens.sha256Hex("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(SecureTokens.constantTimeEquals("abc", "abc")).isTrue();
    assertThat(SecureTokens.constantTimeEquals("abc", "abd")).isFalse();
    assertThat(SecureTokens.constantTimeEquals("abc", null)).isFalse();
  }

  @Test
  void passwordPolicyRules() {
    String email = "robin.example@example.test";
    assertThat(POLICY.check("Short-1", email)).contains(PasswordPolicy.Violation.TOO_SHORT);
    assertThat(POLICY.check("x".repeat(129), email)).contains(PasswordPolicy.Violation.TOO_LONG);
    assertThat(POLICY.check("Robin.Example-2026!", email))
        .contains(PasswordPolicy.Violation.CONTAINS_EMAIL);
    for (String breached :
        new String[] {
          "password1234", "PASSWORDPASSWORD", "qwertyuiop123", "correcthorsebatterystaple"
        }) {
      assertThat(POLICY.check(breached, email))
          .as(breached)
          .contains(PasswordPolicy.Violation.BREACHED);
    }
    assertThat(POLICY.check("Violet-Tractor-Sundial-42", email)).isEmpty();
    assertThat(POLICY.check(null, email)).contains(PasswordPolicy.Violation.TOO_SHORT);
  }

  @Test
  void breachedListIsLoadedFromTheBundledResource() {
    assertThat(new BreachedPasswordList().size()).isGreaterThan(70_000);
  }

  @Test
  void backoffStartsAfterTwoFreeFailuresDoublesAndCaps() {
    assertThat(SignInBackoff.class).isNotNull();
    MutableClock clock = new MutableClock();
    SignInBackoff b = new SignInBackoff(clock);
    b.recordFailure("a@example.test", "192.0.2.1");
    b.recordFailure("a@example.test", "192.0.2.1");
    b.check("a@example.test", "192.0.2.1"); // two failures: no delay yet
    b.recordFailure("a@example.test", "192.0.2.1");
    assertThatThrownBy(() -> b.check("a@example.test", "192.0.2.1"))
        .isInstanceOfSatisfying(
            ThrottledException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
    // Another address or another account is unaffected.
    b.check("a@example.test", "192.0.2.2");
    b.check("b@example.test", "192.0.2.1");
    // Success resets.
    b.recordSuccess("a@example.test", "192.0.2.1");
    b.check("a@example.test", "192.0.2.1");
    // Cap at 15 minutes.
    for (int i = 0; i < 40; i++) {
      b.recordFailure("c@example.test", "192.0.2.3");
    }
    assertThatThrownBy(() -> b.check("c@example.test", "192.0.2.3"))
        .isInstanceOfSatisfying(
            ThrottledException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(900));
    clock.advance(Duration.ofMinutes(15));
    b.check("c@example.test", "192.0.2.3");
  }

  @Test
  void emailsAreNormalisedOrRefused() {
    assertThat(Emails.normalise("  Robin@Example.TEST ")).contains("robin@example.test");
    assertThat(Emails.normalise("no-at-sign")).isEmpty();
    assertThat(Emails.normalise("a@b")).isEmpty();
    assertThat(Emails.normalise("a b@example.test")).isEmpty();
    assertThat(Emails.normalise("a@example.test\r\nBcc: x@y.z")).isEmpty();
    assertThat(Emails.normalise(null)).isEmpty();
  }

  @Test
  void linksCarryTheTokenInTheFragment() {
    AuthProperties props =
        new AuthProperties(
            URI.create("https://support.example.test/"), true, null, null, null, null, null);
    assertThat(props.link("/reset-password", "TOKEN"))
        .isEqualTo("https://support.example.test/reset-password#token=TOKEN");
    assertThat(props.supporterInvitationValidity()).isEqualTo(Duration.ofHours(48));
    assertThat(props.customerInvitationValidity()).isEqualTo(Duration.ofDays(7));
    assertThat(props.passwordResetValidity()).isEqualTo(Duration.ofMinutes(15));
    assertThat(props.sessionAbsoluteTimeout()).isEqualTo(Duration.ofHours(24));
    assertThat(AuthProperties.describe(Duration.ofHours(48))).isEqualTo("48 hours");
    assertThat(AuthProperties.describe(Duration.ofDays(7))).isEqualTo("7 days");
    assertThat(AuthProperties.describe(Duration.ofMinutes(15))).isEqualTo("15 minutes");
    assertThatThrownBy(
            () ->
                new AuthProperties(
                    URI.create("javascript:alert(1)"), true, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
