package com.ticketsystem.api.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private final RetryPolicy policy = new RetryPolicy(MailSettings.DEFAULT_RETRY_DELAYS);

  @Test
  void backoffIsOneTwoFourEightSixteenThirtyTwoMinutes() {
    assertThat(policy.delayAfter(1)).contains(Duration.ofMinutes(1));
    assertThat(policy.delayAfter(2)).contains(Duration.ofMinutes(2));
    assertThat(policy.delayAfter(3)).contains(Duration.ofMinutes(4));
    assertThat(policy.delayAfter(4)).contains(Duration.ofMinutes(8));
    assertThat(policy.delayAfter(5)).contains(Duration.ofMinutes(16));
    assertThat(policy.delayAfter(6)).contains(Duration.ofMinutes(32));
  }

  @Test
  void theSeventhAttemptIsTheLast() {
    assertThat(policy.maxAttempts()).isEqualTo(7);
    assertThat(policy.delayAfter(7)).isEmpty();
    assertThat(policy.delayAfter(8)).isEmpty();
  }

  @Test
  void attemptsAreOneBased() {
    assertThatThrownBy(() -> policy.delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
