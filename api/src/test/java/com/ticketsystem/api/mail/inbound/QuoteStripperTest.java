package com.ticketsystem.api.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuoteStripperTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "On Tue, 17 Nov 2026 at 09:12, Acme MSP Support <support@acmemsp.example> wrote:",
        "On 17 Nov 2026, at 09:12, Acme MSP Support <support@acmemsp.example> wrote:",
        "Am 17.11.2026 um 09:12 schrieb Acme MSP Support <support@acmemsp.example>:",
        "Le mar. 17 nov. 2026 à 09:12, Acme MSP Support a écrit :",
        "-----Original Message-----",
        "----- Ursprüngliche Nachricht -----",
      })
  void cutsAtAttributionAndSeparatorLines(String marker) {
    String text = "New answer.\n\n" + marker + "\n> old text\nold text";
    assertThat(QuoteStripper.stripText(text)).isEqualTo("New answer.");
  }

  @Test
  void cutsAtOutlookHeaderBlockWithTheRuleAbove() {
    String text =
        "Answer.\n\n________________________________\nFrom: A <a@acmemsp.example>\n"
            + "Sent: Monday\nTo: B\nSubject: x\n\nOld.";
    assertThat(QuoteStripper.stripText(text)).isEqualTo("Answer.");
  }

  @Test
  void aFromLineWithoutASentLineIsKept() {
    String text = "From: the reception desk\nThe printer is broken.";
    assertThat(QuoteStripper.stripText(text)).isEqualTo(text);
  }

  @Test
  void quotedLinesAreRemovedEvenWhenInterleaved() {
    assertThat(QuoteStripper.stripText("> q1\na1\n>q2\na2")).isEqualTo("a1\na2");
  }

  @Test
  void anAllQuotedMessageIsReturnedUnchanged() {
    String text = "> only quoted\n> text";
    assertThat(QuoteStripper.stripText(text)).isEqualTo(text);
    String onlyAttribution = "On Tue, 17 Nov 2026, Someone wrote:\n> x";
    assertThat(QuoteStripper.stripText(onlyAttribution)).isEqualTo(onlyAttribution);
  }

  @Test
  void nullAndBlank() {
    assertThat(QuoteStripper.stripText(null)).isNull();
    assertThat(QuoteStripper.stripText("  ")).isEqualTo("  ");
    assertThat(QuoteStripper.stripHtml(null)).isNull();
  }

  @Test
  void hugeSingleLineIsHandledQuickly() {
    String text = "On " + "x".repeat(2_000_000) + " wrote:\nreal";
    long start = System.nanoTime();
    QuoteStripper.stripText(text);
    assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
  }

  @Test
  void htmlGmailQuoteIsCut() {
    String html =
        "<div>New</div><div class=\"gmail_quote\"><div class=\"gmail_attr\">On x wrote:</div>"
            + "<blockquote>old</blockquote></div>";
    assertThat(HtmlText.toPlainText(QuoteStripper.stripHtml(html))).isEqualTo("New");
  }

  @Test
  void htmlYahooAndThunderbirdQuotesAreCut() {
    assertThat(
            HtmlText.toPlainText(
                QuoteStripper.stripHtml("<p>New</p><div class=\"yahoo_quoted\">old</div>")))
        .isEqualTo("New");
    assertThat(
            HtmlText.toPlainText(
                QuoteStripper.stripHtml(
                    "<p>New</p><div class=\"moz-cite-prefix\">On x, y wrote:<br></div>"
                        + "<blockquote type=\"cite\">old</blockquote>")))
        .isEqualTo("New");
  }

  @Test
  void htmlCiteBlockquoteWithoutAttributionIsCutAtTheBlockquote() {
    assertThat(
            HtmlText.toPlainText(
                QuoteStripper.stripHtml("<p>New</p><blockquote type=\"cite\">old</blockquote>")))
        .isEqualTo("New");
  }

  @Test
  void htmlPlainBlockquoteIsNotAQuoteMarker() {
    String html = "<p>See this:</p><blockquote>a quote the client typed</blockquote>";
    assertThat(QuoteStripper.stripHtml(html)).isEqualTo(html);
  }

  @Test
  void htmlOriginalMessageSeparatorIsCut() {
    assertThat(
            HtmlText.toPlainText(
                QuoteStripper.stripHtml(
                    "<p>New</p><p>-----Original Message-----<br>From: x<br>Sent: y</p>")))
        .isEqualTo("New");
  }

  @Test
  void htmlThatIsOnlyAQuoteIsReturnedUnchanged() {
    String html = "<div class=\"gmail_quote\">old</div>";
    assertThat(QuoteStripper.stripHtml(html)).isEqualTo(html);
  }
}
