package com.ticketsystem.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketTransitions;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;
import java.util.HashSet;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/** WP-08 unit tests: key format, hashing, parsing, and the two alert status triggers. */
class ApiKeysTest {

  @Test
  void generatedKeysHaveTheFormatAndAreUnique() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      ApiKeys.Generated g = ApiKeys.generate();
      assertThat(g.fullKey()).matches("tsk_[a-z0-9]{8}_[A-Za-z0-9_-]{43}");
      assertThat(g.fullKey()).startsWith(g.displayPrefix() + "_");
      assertThat(g.hash()).hasSize(32).isEqualTo(ApiKeys.hash(g.fullKey()));
      assertThat(ApiKeys.keyIdOf(g.fullKey())).contains(g.keyId());
      assertThat(g.toString()).doesNotContain(g.fullKey().substring(13));
      assertThat(seen.add(g.fullKey())).isTrue();
    }
  }

  @Test
  void malformedKeysAreNotLookedUp() {
    assertThat(ApiKeys.keyIdOf(null)).isEmpty();
    assertThat(ApiKeys.keyIdOf("tsk_ABCDEFGH_" + "a".repeat(43))).isEmpty();
    assertThat(ApiKeys.keyIdOf("tsk_abcdefgh_" + "a".repeat(42))).isEmpty();
    assertThat(ApiKeys.keyIdOf("xyz_abcdefgh_" + "a".repeat(43))).isEmpty();
    assertThat(ApiKeys.keyIdOf("tsk_abcdefgh_" + "a".repeat(43) + "\n")).isEmpty();
  }

  @Test
  void matchingIsByTheWholeKey() {
    ApiKeys.Generated g = ApiKeys.generate();
    assertThat(ApiKeys.matches(g.hash(), g.fullKey())).isTrue();
    assertThat(ApiKeys.matches(g.hash(), g.fullKey().substring(0, 55) + "x")).isFalse();
  }

  @Test
  void alertRepeatReopensEveryOpenStatusExceptNewAndAlertResolvedResolvesAnyOpenStatus() {
    for (TicketStatus s : TicketStatus.values()) {
      if (s == TicketStatus.CLOSED) {
        Assertions.assertThatThrownBy(() -> TicketTransitions.next(s, Trigger.ALERT_REPEAT, null));
        Assertions.assertThatThrownBy(
            () -> TicketTransitions.next(s, Trigger.ALERT_RESOLVED, null));
        continue;
      }
      assertThat(TicketTransitions.next(s, Trigger.ALERT_REPEAT, null))
          .isEqualTo(s == TicketStatus.NEW ? s : TicketStatus.OPEN);
      assertThat(TicketTransitions.next(s, Trigger.ALERT_RESOLVED, null))
          .isEqualTo(TicketStatus.RESOLVED);
    }
  }

  @Test
  void theCommentTitleIsEscaped() {
    assertThat(IntegrationTicketService.commentHtml("<b>x</b> & y", null))
        .isEqualTo("<p><strong>&lt;b&gt;x&lt;/b&gt; &amp; y</strong></p>");
  }
}
