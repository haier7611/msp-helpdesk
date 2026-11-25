package com.ticketsystem.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The mapping to WP-11a's stored event names. (The internal Reply-To is {@code
 * SupportAddresses.internalOf}, tested in {@code SupportAddressesTest}, WP-07b.)
 */
class SupporterMailerTest {

  @Test
  void kindsMapToWp11aEventNames() {
    assertThat(SupporterPreferences.eventName(NotificationKind.SLA_AT_RISK))
        .isEqualTo("SLA_WARNING");
    assertThat(SupporterPreferences.eventName(NotificationKind.SLA_BREACHED))
        .isEqualTo("SLA_BREACHED");
    assertThat(SupporterPreferences.eventName(NotificationKind.P1_CREATED)).isNull();
    assertThat(NotificationKind.P1_CREATED.mandatory()).isTrue();
    assertThat(NotificationKind.SLA_AT_RISK.mandatory()).isFalse();
  }
}
