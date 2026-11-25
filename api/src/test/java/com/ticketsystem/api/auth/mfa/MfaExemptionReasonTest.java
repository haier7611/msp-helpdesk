package com.ticketsystem.api.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.ApiException;
import org.junit.jupiter.api.Test;

/** WP-15: the exemption reason is one line of 1-200 characters, trimmed. */
class MfaExemptionReasonTest {

  @Test
  void aReasonIsTrimmedAndKept() {
    assertThat(MfaExemptionService.validReason("  New phone on Friday  "))
        .isEqualTo("New phone on Friday");
    assertThat(MfaExemptionService.validReason("x".repeat(200))).hasSize(200);
    // 200 characters that are each two UTF-16 units still fit (Postgres counts characters).
    assertThat(MfaExemptionService.validReason("📱".repeat(200))).isNotEmpty();
  }

  @Test
  void emptyTooLongAndMultiLineReasonsAreRefused() {
    for (String bad : new String[] {null, "", "   ", "x".repeat(201), "a\nb", "a\tb", "a\u0000b"}) {
      assertThatThrownBy(() -> MfaExemptionService.validReason(bad))
          .isInstanceOf(ApiException.class)
          .extracting(e -> ((ApiException) e).code())
          .isEqualTo("invalid_request");
    }
  }
}
