package com.ticketsystem.api.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.mail.inbound.BlockListMatcher.BlockMatch;
import com.ticketsystem.api.mail.inbound.PlusAddress.MailboxTarget;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PlusAddress, BlockListMatcher, AutomatedMailDetector and SubjectPrefixes. */
class SmallComponentsTest {

  private static InboundMessage message(
      String from, List<String> to, List<String> cc, Map<String, List<String>> headers) {
    return new InboundMessage(
        from == null ? null : new InboundAddress(from, null),
        to.stream().map(a -> new InboundAddress(a, null)).toList(),
        cc.stream().map(a -> new InboundAddress(a, null)).toList(),
        List.of(),
        "s",
        null,
        List.of(),
        List.of(),
        null,
        "t",
        null,
        List.of(),
        headers,
        false);
  }

  // ---- PlusAddress ----

  private final PlusAddress plus = new PlusAddress("Support@AcmeMSP.example");

  @Test
  void plusAddressTargets() {
    assertThat(
            plus.detect(
                message("a@b.example", List.of("support@acmemsp.example"), List.of(), Map.of())))
        .isEqualTo(MailboxTarget.SUPPORT);
    assertThat(
            plus.detect(
                message(
                    "a@b.example",
                    List.of("support@acmemsp.example"),
                    List.of("SUPPORT+INTERNAL@acmemsp.example"),
                    Map.of())))
        .isEqualTo(MailboxTarget.INTERNAL);
    assertThat(
            plus.detect(
                message(
                    "a@b.example", List.of("support+other@acmemsp.example"), List.of(), Map.of())))
        .isEqualTo(MailboxTarget.SUPPORT);
    assertThat(
            plus.detect(
                message("a@b.example", List.of("someone@acmemsp.example"), List.of(), Map.of())))
        .isEqualTo(MailboxTarget.NOT_ADDRESSED);
  }

  @Test
  void plusAddressLookAlikesAreNotInternal() {
    for (String to :
        new String[] {
          "support+internal@acmemsp.example.attacker.example",
          "support+internal@attacker.example",
          "xsupport+internal@acmemsp.example",
          "support+internalx@acmemsp.example",
          "support+internal+x@acmemsp.example"
        }) {
      assertThat(plus.detect(message("a@b.example", List.of(to), List.of(), Map.of())))
          .as(to)
          .isNotEqualTo(MailboxTarget.INTERNAL);
    }
  }

  @Test
  void plusAddressReadsDeliveryHeaders() {
    assertThat(
            plus.detect(
                message(
                    "a@b.example",
                    List.of(),
                    List.of(),
                    Map.of("x-original-to", List.of("support+internal@acmemsp.example")))))
        .isEqualTo(MailboxTarget.INTERNAL);
  }

  @Test
  void plusAddressNeedsAPlainSupportAddress() {
    assertThatThrownBy(() -> new PlusAddress("support+x@acmemsp.example"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PlusAddress("nodomain"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- BlockListMatcher ----

  private final BlockListMatcher block =
      new BlockListMatcher(
          List.of("Promo@Spam.example", "scam@other.example", "bad", ""),
          List.of(
              "spam.example", "@junk.example", "*.wild.example", "example", "Blocked.Example."));

  @Test
  void blockExactAddressWinsOverDomain() {
    assertThat(block.match("promo@spam.example"))
        .contains(new BlockMatch(BlockMatch.Kind.ADDRESS, "promo@spam.example"));
    assertThat(block.match("scam@OTHER.example"))
        .contains(new BlockMatch(BlockMatch.Kind.ADDRESS, "scam@other.example"));
    assertThat(block.match("other@other.example")).isEmpty();
  }

  @Test
  void blockDomainAndSubdomains() {
    assertThat(block.match("x@spam.example"))
        .contains(new BlockMatch(BlockMatch.Kind.DOMAIN, "spam.example"));
    assertThat(block.match("x@mx.eu.spam.example"))
        .contains(new BlockMatch(BlockMatch.Kind.DOMAIN, "spam.example"));
    assertThat(block.match("x@junk.example")).isPresent();
    assertThat(block.match("x@a.wild.example")).isPresent();
    assertThat(block.match("x@blocked.example.")).isPresent();
  }

  @Test
  void blockLookAlikesAndBareTldsDoNotMatch() {
    assertThat(block.match("x@notspam.example")).isEmpty();
    assertThat(block.match("x@spam.example.org")).isEmpty();
    assertThat(block.match("spam.example@good.example")).isEmpty();
    // "example" alone was ignored as a rule: it would block a whole top-level domain.
    assertThat(block.match("x@good.example")).isEmpty();
    assertThat(block.match((String) null)).isEmpty();
    assertThat(block.match("no-at-sign")).isEmpty();
    assertThat(block.match((InboundAddress) null)).isEmpty();
    assertThat(BlockListMatcher.none().match("x@spam.example")).isEmpty();
  }

  @Test
  void blockIdnFormsMatchEachOther() {
    var idn = new BlockListMatcher(Set.of(), Set.of("bücher.example"));
    assertThat(idn.match("x@xn--bcher-kva.example")).isPresent();
    assertThat(idn.match("x@BÜCHER.example")).isPresent();
  }

  // ---- AutomatedMailDetector ----

  @ParameterizedTest
  @ValueSource(
      strings = {
        "noreply",
        "no-reply",
        "No_Reply",
        "do-not-reply",
        "donotreply",
        "dont-reply",
        "MAILER-DAEMON",
        "postmaster",
        "bounces",
        "bounce+abc",
        "noreply123"
      })
  void noReplyLocalParts(String local) {
    assertThat(AutomatedMailDetector.isNoReply(local)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"dana.molar", "reply", "norep", "bouncer", "replynow", "support"})
  void ordinaryLocalParts(String local) {
    assertThat(AutomatedMailDetector.isNoReply(local)).isFalse();
  }

  @Test
  void autoSubmittedNoIsAPersonAndValuesAreCaseInsensitive() {
    assertThat(
            AutomatedMailDetector.detect(
                    message(
                        "a@b.example",
                        List.of(),
                        List.of(),
                        Map.of("auto-submitted", List.of("No"))))
                .automated())
        .isFalse();
    var flags =
        AutomatedMailDetector.detect(
            message(
                "a@b.example",
                List.of(),
                List.of(),
                Map.of("auto-submitted", List.of("Auto-Replied (vacation)"))));
    assertThat(flags.autoReply()).isTrue();
    assertThat(
            AutomatedMailDetector.detect(
                    message(
                        "a@b.example",
                        List.of(),
                        List.of(),
                        Map.of("x-auto-response-suppress", List.of("None"))))
                .automated())
        .isFalse();
    assertThat(
            AutomatedMailDetector.detect(
                    message(
                        "a@b.example",
                        List.of(),
                        List.of(),
                        Map.of("precedence", List.of("auto_reply"))))
                .autoReply())
        .isTrue();
    assertThat(
            AutomatedMailDetector.detect(message(null, List.of(), List.of(), Map.of())).automated())
        .isFalse();
  }

  // ---- SubjectPrefixes ----

  @Test
  void forwardIsTheOutermostPrefixOnly() {
    assertThat(SubjectPrefixes.isForward("FW: RE: x")).isTrue();
    assertThat(SubjectPrefixes.isForward("Fwd: x")).isTrue();
    assertThat(SubjectPrefixes.isForward("WG: x")).isTrue();
    assertThat(SubjectPrefixes.isForward("RE: FW: x")).isFalse();
    assertThat(SubjectPrefixes.isForward("Forward planning")).isFalse();
    assertThat(SubjectPrefixes.isForward(null)).isFalse();
  }
}
