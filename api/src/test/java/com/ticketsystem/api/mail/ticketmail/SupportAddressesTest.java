package com.ticketsystem.api.mail.ticketmail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The plus-addressed internal mailbox used as Reply-To of agent-only mail (spec 6, WP-07b). */
class SupportAddressesTest {

  @Test
  void theInternalAddressIsThePlusInternalMailbox() {
    assertThat(SupportAddresses.internalOf("Support@MSP.example"))
        .isEqualTo("support+internal@msp.example");
    assertThat(SupportAddresses.internalOf("no-at-sign")).isNull();
  }

  @Test
  void aPlusTaggedAddressStillGivesTheInternalAddressOfItsMailbox() {
    // AUD L2 (PR #19): a plus-tagged sender address must not drop the internal Reply-To.
    assertThat(SupportAddresses.internalOf("support+x@msp.example"))
        .isEqualTo("support+internal@msp.example");
    assertThat(SupportAddresses.internalOf("Support+Tickets@MSP.example"))
        .isEqualTo("support+internal@msp.example");
    assertThat(SupportAddresses.internalOf("support+internal@msp.example"))
        .isEqualTo("support+internal@msp.example");
    assertThat(SupportAddresses.internalOf("+x@msp.example")).isNull();
  }
}
