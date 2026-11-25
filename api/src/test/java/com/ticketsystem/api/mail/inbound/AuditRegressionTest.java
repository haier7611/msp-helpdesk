package com.ticketsystem.api.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the PR #6 audit: the two denial-of-service inputs at full size, each with a
 * time bound the old code missed by far (26.5 s and 13.5 s).
 */
class AuditRegressionTest {

  // The two original attacks took 13.5 s and 26.5 s before the fix, so 5 s still proves the
  // protection; 1 s failed at random on a busy CI host.
  private static final Duration ATTACK_LIMIT = Duration.ofSeconds(5);

  private static byte[] email(String extraHeaders, String body) {
    return ("From: Dana Molar <dana.molar@acmedental.example>\r\n"
            + "To: support@acmemsp.example\r\n"
            + "Message-ID: <dos@acmedental.example>\r\n"
            + extraHeaders
            + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n"
            + body)
        .getBytes(StandardCharsets.UTF_8);
  }

  /** M1: Subject of 560,000 encoded words (about 9.5 MB) - quadratic RFC 2047 decoding. */
  @Test
  void hugeEncodedSubjectIsCappedAndStillBecomesAMessage() {
    StringBuilder subject = new StringBuilder("Subject: ");
    for (int i = 0; i < 560_000; i++) {
      subject.append("=?utf-8?B?QUFB?= ");
    }
    subject.append("\r\n");
    byte[] raw = email(subject.toString(), "Hello.");
    assertThat(raw.length).isGreaterThan(9_000_000).isLessThan(30 * 1024 * 1024);

    InboundAnalysis result = assertTimeout(ATTACK_LIMIT, () -> Fixtures.ENGINE.analyse(raw, null));

    assertThat(result).isInstanceOf(InboundAnalysis.Analysed.class);
    var a = (InboundAnalysis.Analysed) result;
    assertThat(a.message().headersTruncated()).isTrue();
    assertThat(a.message().subject()).isNotEmpty().startsWith("AAA");
    assertThat(a.message().subject().length())
        .isLessThanOrEqualTo(InboundMessageParser.MAX_SUBJECT_CHARS);
    assertThat(a.message().from().address()).isEqualTo("dana.molar@acmedental.example");
    assertThat(a.replyText()).isEqualTo("Hello.");
  }

  /** M1: one oversized address header and a header block over 256 KB. */
  @Test
  void oversizedAddressHeaderAndHeaderBlockAreCapped() {
    StringBuilder headers = new StringBuilder("Cc: ");
    for (int i = 0; i < 100_000; i++) {
      headers.append("=?utf-8?B?QUFB?= <u").append(i).append("@acmedental.example>, ");
    }
    headers.append("\r\n");
    for (int i = 0; i < 400; i++) {
      headers.append("X-Junk-").append(i).append(": ").append("x".repeat(1000)).append("\r\n");
    }
    byte[] raw = email(headers.toString(), "Body.");

    var a =
        (InboundAnalysis.Analysed)
            assertTimeout(ATTACK_LIMIT, () -> Fixtures.ENGINE.analyse(raw, null));

    assertThat(a.message().headersTruncated()).isTrue();
    assertThat(a.message().cc()).isNotEmpty().hasSizeLessThan(2_000);
    assertThat(a.message().headers().values().stream().mapToLong(List::size).sum()).isLessThan(300);
    assertThat(a.message().from()).isNotNull();
  }

  @Test
  void normalMailIsNotFlaggedAsTruncated() {
    assertThat(Fixtures.analysed("01-outlook-desktop-reply.eml").message().headersTruncated())
        .isFalse();
  }

  /** M2: 20,000 lines of "On " + "a wrote " x 95 (about 15 MB) - regex backtracking. */
  @Test
  void attributionBacktrackingAttackIsFast() {
    String line = "On " + "a wrote ".repeat(95);
    String text = (line + "\n").repeat(20_000);
    assertThat(text.length()).isGreaterThan(15_000_000);

    String out = assertTimeout(ATTACK_LIMIT, () -> QuoteStripper.stripText(text));
    assertThat(out).isSameAs(text);
  }

  /** M2 under the body cap: the per-line scan itself must be linear. */
  @Test
  void attributionScanIsLinearUnderTheCap() {
    String noColon = "On " + "a wrote ".repeat(60);
    String withColon = noColon.strip() + " x:";
    StringBuilder sb = new StringBuilder("New text.\n");
    while (sb.length() < QuoteStripper.MAX_TEXT_CHARS - 1_000) {
      sb.append(noColon).append('\n').append("> ").append(withColon).append('\n');
    }
    String text = sb.toString();

    String out = assertTimeout(ATTACK_LIMIT, () -> QuoteStripper.stripText(text));
    assertThat(out).startsWith("New text.");
    String html = "<p>" + text.replace("\n", "</p><p>") + "</p>";
    assertTimeout(ATTACK_LIMIT, () -> QuoteStripper.stripHtml(html));
  }

  @Test
  void attributionScanStillRecognisesRealLines() {
    assertThat(QuoteStripper.isAttribution("On Tue, 17 Nov 2026, Sam <s@acmemsp.example> wrote:"))
        .isTrue();
    assertThat(QuoteStripper.isAttribution("Am 17.11.2026 schrieb Sam <s@acmemsp.example>:"))
        .isTrue();
    assertThat(QuoteStripper.isAttribution("Le 17 nov. 2026, Sam a  écrit :")).isTrue();
    assertThat(QuoteStripper.isAttribution("On wrote:")).isFalse();
    assertThat(QuoteStripper.isAttribution("Sam wrote:")).isFalse();
    assertThat(QuoteStripper.isAttribution("On Tuesday Sam wrote")).isFalse();
    assertThat(QuoteStripper.isAttribution("On " + "x".repeat(600) + " wrote:")).isFalse();
  }

  /** Low 1: a cid: pointing at a non-image part is not turned into an image. */
  @Test
  void cidOfANonImagePartIsRemoved() {
    var attachments =
        List.of(
            new InboundAttachment("x.html", "text/html", "x@acmedental.example", true, new byte[1]),
            new InboundAttachment("x.png", "image/png", "x@acmedental.example", true, new byte[1]));
    assertThat(
            HtmlToSafeHtml.sanitise("<img src=\"cid:x@acmedental.example\">", attachments).html())
        .isEqualTo("<img src=\"attachment:1\" />");
    assertThat(
            HtmlToSafeHtml.sanitise(
                    "<img src=\"cid:x@acmedental.example\">", List.of(attachments.get(0)))
                .html())
        .isEqualTo(HtmlToSafeHtml.IMAGE_REMOVED_PLACEHOLDER);
  }

  /** Low 5: the blocked-image list is capped. */
  @Test
  void blockedRemoteImageListIsCapped() {
    String html = "<img src=\"https://t.example/p.gif\">".repeat(5_000);
    var result = HtmlToSafeHtml.sanitise(html, List.of());
    assertThat(result.blockedRemoteImages()).hasSize(HtmlToSafeHtml.MAX_BLOCKED_IMAGES);
  }
}
