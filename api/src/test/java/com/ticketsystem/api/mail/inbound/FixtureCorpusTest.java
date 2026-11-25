package com.ticketsystem.api.mail.inbound;

import static com.ticketsystem.api.mail.inbound.Fixtures.analysed;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.mail.inbound.AuthenticationResults.Result;
import com.ticketsystem.api.mail.inbound.AutomatedMailFlags.Signal;
import com.ticketsystem.api.mail.inbound.PlusAddress.MailboxTarget;
import java.io.File;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every synthetic fixture email through the whole engine, with the expected result (WP-07a
 * acceptance: at least 25 fixtures parse to the expected results).
 */
class FixtureCorpusTest {

  private static final String OUTBOX_ID = "<5f0c2a9e-1111-4c1e-9a52-0c3c1d6b7a01@acmemsp.example>";

  private static List<String> refs(InboundAnalysis.Analysed a) {
    return a.referenceCandidates().stream().map(c -> c.prefix() + "/" + c.number()).toList();
  }

  @Test
  void theCorpusHasAtLeast25FixturesAndNoneThrows() throws URISyntaxException {
    File dir = new File(Objects.requireNonNull(getClass().getResource("/mail/inbound")).toURI());
    String[] names = dir.list((d, n) -> n.endsWith(".eml"));
    assertThat(names).hasSizeGreaterThanOrEqualTo(25);
    Arrays.sort(names);
    for (String name : names) {
      InboundAnalysis a = Fixtures.ENGINE.analyse(Fixtures.bytes(name), Fixtures.BLOCK_LIST);
      assertThat(a).as(name).isNotNull();
    }
  }

  @Test
  void outlookDesktopReply() {
    var a = analysed("01-outlook-desktop-reply.eml");
    InboundMessage m = a.message();
    assertThat(m.from().address()).isEqualTo("dana.molar@acmedental.example");
    assertThat(m.from().displayName()).isEqualTo("Dana Molar");
    assertThat(m.to()).extracting(InboundAddress::address).containsExactly(Fixtures.SUPPORT);
    assertThat(m.subject()).isEqualTo("RE: [ACD-042] Printer offline in reception");
    assertThat(m.messageId()).isEqualTo("<AM0PR01MB0001@eurprd01.acmedental.example>");
    assertThat(m.inReplyTo()).containsExactly(OUTBOX_ID);
    assertThat(m.references()).containsExactly("<first-0001@acmedental.example>", OUTBOX_ID);
    assertThat(m.threadMessageIds()).containsExactly(OUTBOX_ID, "<first-0001@acmedental.example>");
    assertThat(m.date()).isEqualTo(Instant.parse("2026-11-17T03:15:00Z"));
    assertThat(refs(a)).containsExactly("ACD/42");
    assertThat(a.authentication().dmarcPass()).isTrue();
    assertThat(a.automation().automated()).isFalse();
    assertThat(a.mailboxTarget()).isEqualTo(MailboxTarget.SUPPORT);
    assertThat(a.blocked()).isFalse();
    assertThat(a.replyText())
        .isEqualTo("Hi Sam,\n\nThe printer is still offline after the restart.\n\nThanks,\nDana");
    assertThat(a.safeReplyHtml().html())
        .contains("The printer is still offline")
        .doesNotContain("Sent:")
        .doesNotContain("Please restart")
        .doesNotContain("style")
        .doesNotContain("class");
    assertThat(a.safeHtml().html()).contains("Please restart the printer");
  }

  @Test
  void outlookWebReply() {
    var a = analysed("02-outlook-web-reply.eml");
    assertThat(refs(a)).containsExactly("ACD/42");
    assertThat(a.replyText()).isEqualTo("Still broken, sorry.");
    assertThat(a.safeReplyHtml().html()).contains("Still broken, sorry.").doesNotContain("From:");
    assertThat(HtmlText.toPlainText(a.safeReplyHtml().html())).isEqualTo("Still broken, sorry.");
  }

  @Test
  void gmailReply() {
    var a = analysed("03-gmail-reply.eml");
    assertThat(a.message().messageId()).isEqualTo("<CAF=abc123+gm@mail.acmedental.example>");
    assertThat(refs(a)).containsExactly("ACD/42");
    assertThat(a.replyText()).isEqualTo("Fixed now, thank you!\n\nRiley");
    assertThat(HtmlText.toPlainText(a.safeReplyHtml().html()))
        .isEqualTo("Fixed now, thank you!\n\nRiley");
  }

  @Test
  void appleMailReply() {
    var a = analysed("04-apple-mail-reply.eml");
    assertThat(a.replyText()).isEqualTo("It works again.\n\nSent from my iPhone");
    assertThat(HtmlText.toPlainText(a.safeReplyHtml().html()))
        .isEqualTo("It works again.\n\nSent from my iPhone");
  }

  @Test
  void plainTextInterleavedReplyKeepsTheAnswers() {
    var a = analysed("05-plain-text-interleaved.eml");
    assertThat(a.message().htmlBody()).isNull();
    assertThat(a.safeHtml().html()).isEmpty();
    assertThat(a.replyText()).isEqualTo("Yes, twice.\nNo, it is off.\n\nDana");
  }

  @Test
  void replyWithoutReferenceStillCarriesThreadIds() {
    var a = analysed("06-reply-no-reference.eml");
    assertThat(a.referenceCandidates()).isEmpty();
    assertThat(a.message().threadMessageIds()).startsWith(OUTBOX_ID);
    assertThat(a.replyText()).isEqualTo("Any update on this?");
  }

  @Test
  void gmailForwardIsKeptWhole() {
    var a = analysed("07-gmail-forward.eml");
    assertThat(a.replyText()).contains("Please look at this.").contains("since 7am");
    assertThat(a.safeReplyHtml().html()).contains("since 7am");
  }

  @Test
  void outlookForwardIsKeptWhole() {
    var a = analysed("08-outlook-forward.eml");
    assertThat(a.replyText()).contains("FYI").contains("rejects your login");
  }

  @Test
  void autoReplyIsFlagged() {
    var a = analysed("09-auto-reply.eml");
    assertThat(refs(a)).containsExactly("ACD/42");
    assertThat(a.automation().autoReply()).isTrue();
    assertThat(a.automation().suppressAutomaticReplies()).isTrue();
    assertThat(a.automation().signals()).contains(Signal.AUTO_SUBMITTED, Signal.AUTO_REPLY_HEADER);
  }

  @Test
  void outlookOutOfOfficeIsFlagged() {
    var a = analysed("10-outlook-ooo.eml");
    assertThat(a.automation().signals())
        .containsExactlyInAnyOrder(Signal.AUTO_SUBMITTED, Signal.AUTO_RESPONSE_SUPPRESS);
    assertThat(a.automation().suppressAutomaticReplies()).isTrue();
    assertThat(a.automation().autoReply()).isFalse();
  }

  @Test
  void newsletterIsBulkAndItsTrackingPixelIsBlocked() {
    var a = analysed("11-newsletter.eml");
    assertThat(a.automation().bulk()).isTrue();
    assertThat(a.automation().signals())
        .containsExactlyInAnyOrder(Signal.LIST_HEADERS, Signal.PRECEDENCE_BULK);
    assertThat(a.safeHtml().blockedRemoteImages())
        .containsExactly("https://track.dentalsupplies.example/open.gif?u=1");
    assertThat(a.safeHtml().html())
        .contains(HtmlToSafeHtml.REMOTE_IMAGE_PLACEHOLDER)
        .doesNotContain("<img");
  }

  @Test
  void noReplyAlertIsFlaggedButStillParsed() {
    var a = analysed("12-noreply-alert.eml");
    assertThat(a.automation().signals()).containsExactly(Signal.NO_REPLY_SENDER);
    assertThat(a.referenceCandidates()).isEmpty();
    assertThat(a.replyText()).startsWith("Job 'Nightly' failed");
  }

  @Test
  void newMailWithDmarcPass() {
    var a = analysed("13-dmarc-pass-new.eml");
    assertThat(a.authentication())
        .isEqualTo(new AuthenticationResults(true, Result.PASS, Result.PASS, Result.PASS));
    assertThat(a.referenceCandidates()).isEmpty();
  }

  @Test
  void dmarcFail() {
    var a = analysed("14-dmarc-fail.eml");
    assertThat(a.authentication().authenticated()).isTrue();
    assertThat(a.authentication().dmarc()).isEqualTo(Result.FAIL);
    assertThat(a.authentication().spf()).isEqualTo(Result.FAIL);
    assertThat(a.authentication().dkim()).isEqualTo(Result.NONE);
    assertThat(a.authentication().dmarcPass()).isFalse();
  }

  @Test
  void passFromAnUntrustedServerIsNotAuthenticated() {
    var a = analysed("15-untrusted-authserv.eml");
    assertThat(a.authentication()).isEqualTo(AuthenticationResults.NOT_AUTHENTICATED);
  }

  @Test
  void aForgedTrustedHeaderBelowOursIsIgnored() {
    var a = analysed("16-forged-header-below.eml");
    assertThat(a.authentication().dmarc()).isEqualTo(Result.FAIL);
    assertThat(a.authentication().dmarcPass()).isFalse();
  }

  @Test
  void missingAuthenticationResultsIsNotAuthenticated() {
    var a = analysed("17-no-auth-results.eml");
    assertThat(a.authentication().authenticated()).isFalse();
    assertThat(a.authentication().dmarcPass()).isFalse();
  }

  @Test
  void dmarcPassForAnotherDomainIsAFail() {
    var a = analysed("18-dmarc-header-from-mismatch.eml");
    assertThat(a.authentication().dmarc()).isEqualTo(Result.FAIL);
    assertThat(a.authentication().dmarcPass()).isFalse();
  }

  @Test
  void plusAddressedInternalReply() {
    var a = analysed("19-plus-internal-reply.eml");
    assertThat(a.mailboxTarget()).isEqualTo(MailboxTarget.INTERNAL);
    assertThat(refs(a)).containsExactly("ACD/42");
    assertThat(a.replyText())
        .isEqualTo("Checked the print server, the spooler had stopped. Restarted.");
  }

  @Test
  void deliveredToShowsTheInternalAddress() {
    var a = analysed("20-plus-delivered-to.eml");
    assertThat(a.mailboxTarget()).isEqualTo(MailboxTarget.INTERNAL);
  }

  @Test
  void inlineImageInMultipartRelatedBecomesAnAttachmentPlaceholder() {
    var a = analysed("21-inline-image-related.eml");
    List<InboundAttachment> att = a.message().attachments();
    assertThat(att).hasSize(2);
    assertThat(att.get(0).contentType()).isEqualTo("image/png");
    assertThat(att.get(0).fileName()).isEqualTo("image001.png");
    assertThat(att.get(0).contentId()).isEqualTo("image001.png@01DB0000.00000000");
    assertThat(att.get(0).inline()).isTrue();
    assertThat(att.get(0).size()).isEqualTo(69);
    assertThat(att.get(1).fileName()).isEqualTo("error-log.pdf");
    assertThat(att.get(1).contentType()).isEqualTo("application/pdf");
    assertThat(att.get(1).inline()).isFalse();
    assertThat(att.get(1).contentId()).isNull();
    assertThat(new String(att.get(1).content())).startsWith("%PDF-1.4");
    assertThat(a.message().textBody()).startsWith("See the screenshot.");
    assertThat(a.safeHtml().html()).contains("<img src=\"attachment:0\" alt=\"error.png\" />");
    assertThat(a.safeHtml().blockedRemoteImages()).isEmpty();
  }

  @Test
  void appleInlineImageWithTwoTextParts() {
    var a = analysed("22-apple-inline-image.eml");
    List<InboundAttachment> att = a.message().attachments();
    assertThat(att).hasSize(1);
    assertThat(att.get(0).fileName()).isEqualTo("IMG_0042.jpeg");
    assertThat(att.get(0).inline()).isTrue();
    assertThat(att.get(0).contentId()).isEqualTo("8A1C6F2E-0022@acmedental.example");
    assertThat(a.message().textBody())
        .contains("Here is the cabling under the desk.")
        .contains("Sent from my iPhone");
  }

  @Test
  void hostileHtmlIsDefused() {
    var a = analysed("23-hostile-html.eml");
    String html = a.safeHtml().html();
    HtmlToSafeHtmlTest.assertNothingDangerous(html);
    assertThat(html).contains("Please see the invoice.").contains("Thanks");
    assertThat(html)
        .contains("<a href=\"https://acmemsp.example/help\" rel=\"noopener noreferrer nofollow\">");
    assertThat(a.safeHtml().blockedRemoteImages())
        .containsExactly("https://evil.example/pixel.gif", "https://evil.example/pr.gif");
  }

  @Test
  void encodedSubjectAndLatin1QuotedPrintableGermanReply() {
    var a = analysed("24-encoded-subject-german.eml");
    assertThat(a.message().from().displayName()).isEqualTo("Jörg Müller");
    assertThat(a.message().subject())
        .isEqualTo("AW: [rlw-7] Drucker funktioniert nicht – dringend");
    assertThat(refs(a)).containsExactly("RLW/7");
    assertThat(a.replyText()).isEqualTo("Der Drucker funktioniert immer noch nicht. Grüße");
  }

  @Test
  void extraBracketsAndLookAlikes() {
    var a = analysed("25-extra-brackets-lookalikes.eml");
    assertThat(refs(a)).containsExactly("NWD/1");
  }

  @Test
  void multipleReferencesInOrderWithoutDuplicates() {
    var a = analysed("26-multiple-references.eml");
    assertThat(refs(a)).containsExactly("ACD/42", "ACD/43");
  }

  @Test
  void garbageIsUnparseable() {
    InboundAnalysis a =
        Fixtures.ENGINE.analyse(Fixtures.bytes("27-malformed-no-headers.eml"), null);
    assertThat(a).isInstanceOf(InboundAnalysis.Unparseable.class);
    assertThat(((InboundAnalysis.Unparseable) a).reason()).isEqualTo(ParseResult.Reason.NO_HEADERS);
  }

  @Test
  void multipartWithoutStartBoundaryIsUnparseable() {
    InboundAnalysis a =
        Fixtures.ENGINE.analyse(Fixtures.bytes("28-malformed-broken-multipart.eml"), null);
    assertThat(a)
        .isEqualTo(
            new InboundAnalysis.Unparseable(
                ParseResult.Reason.MALFORMED,
                Fixtures.bytes("28-malformed-broken-multipart.eml").length));
  }

  @Test
  void unknownCharsetAndTransferEncodingAreReadLeniently() {
    var a = analysed("29-unknown-charset.eml");
    assertThat(a.message().textBody()).contains("Plain words survive.");
  }

  @Test
  void blockedSenderSubdomainMatchesTheDomainRule() {
    var a = analysed("30-blocked-sender.eml");
    assertThat(a.blockMatch())
        .isEqualTo(
            new BlockListMatcher.BlockMatch(
                BlockListMatcher.BlockMatch.Kind.DOMAIN, "spam.example"));
    assertThat(a.message().textBody()).isNull();
    assertThat(a.replyText()).isEqualTo("Claim your prize now!");
  }

  @Test
  void bounceIsAnAutoReply() {
    var a = analysed("31-bounce-dsn.eml");
    assertThat(a.automation().autoReply()).isTrue();
    assertThat(a.automation().signals())
        .contains(Signal.NULL_RETURN_PATH, Signal.DELIVERY_REPORT, Signal.NO_REPLY_SENDER);
    assertThat(a.message().attachments())
        .extracting(InboundAttachment::contentType)
        .containsExactly("message/delivery-status");
  }

  @Test
  void microsoft365HeaderWithoutAuthservIdNeedsExplicitTrust() {
    byte[] raw = Fixtures.bytes("32-m365-no-authserv-id.eml");
    var defaultEngine = (InboundAnalysis.Analysed) Fixtures.ENGINE.analyse(raw, null);
    assertThat(defaultEngine.authentication().authenticated()).isFalse();

    var m365 =
        new InboundMailEngine(
            Fixtures.SUPPORT,
            Set.of(AuthenticationResultsParser.NO_AUTHSERV_ID),
            InboundLimits.DEFAULT);
    var a = (InboundAnalysis.Analysed) m365.analyse(raw, null);
    assertThat(a.authentication())
        .isEqualTo(new AuthenticationResults(true, Result.PASS, Result.PASS, Result.PASS));
  }

  @Test
  void htmlOnlyReplyGetsPlainTextFromTheStrippedHtml() {
    var a = analysed("33-html-only-reply.eml");
    assertThat(a.message().textBody()).isNull();
    assertThat(a.replyText()).isEqualTo("Thanks & regards – it's working now.");
    assertThat(a.safeReplyHtml().html()).doesNotContain("Old text").doesNotContain("<hr");
  }

  @Test
  void attachedMessageIsAnAttachmentNotParsedInto() {
    var a = analysed("34-attached-message.eml");
    assertThat(a.message().textBody()).isEqualTo("Is this phishing?");
    assertThat(a.message().attachments()).hasSize(1);
    InboundAttachment att = a.message().attachments().get(0);
    assertThat(att.contentType()).isEqualTo("message/rfc822");
    assertThat(att.fileName()).isEqualTo("attached-message.eml");
    assertThat(new String(att.content())).contains("Verify your account");
    assertThat(a.message().subject()).isEqualTo("Suspicious email, please check");
  }

  @Test
  void toStringCarriesNoPersonalData() {
    var a = analysed("01-outlook-desktop-reply.eml");
    assertThat(a.toString()).doesNotContain("dana").doesNotContain("Printer");
    assertThat(a.message().toString()).doesNotContain("dana").doesNotContain("Printer");
  }
}
