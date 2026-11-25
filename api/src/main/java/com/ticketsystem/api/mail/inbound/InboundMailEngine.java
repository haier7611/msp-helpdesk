package com.ticketsystem.api.mail.inbound;

import java.util.Set;

/**
 * Reads one raw inbound email and works out everything the intake needs to decide what to do with
 * it: reference candidates, thread ids, our server's authentication verdict, loop-safety flags,
 * which support address it was sent to, the block-list match, the new reply text and safe HTML.
 * Pure logic: no IMAP, database or ticket access, no logging of message content. Never throws for
 * any input bytes.
 */
public final class InboundMailEngine {

  private final InboundMessageParser parser;
  private final AuthenticationResultsParser authParser;
  private final PlusAddress plusAddress;

  /**
   * @param supportAddress the mailbox address, e.g. {@code support@acmemsp.example}
   * @param trustedAuthServIds authserv-ids of our own receiving server (see {@link
   *     AuthenticationResultsParser})
   */
  public InboundMailEngine(
      String supportAddress, Set<String> trustedAuthServIds, InboundLimits limits) {
    this.parser = new InboundMessageParser(limits);
    this.authParser = new AuthenticationResultsParser(trustedAuthServIds);
    this.plusAddress = new PlusAddress(supportAddress);
  }

  /**
   * @param raw the {@code .eml} bytes
   * @param blockList the current admin block list
   */
  public InboundAnalysis analyse(byte[] raw, BlockListMatcher blockList) {
    ParseResult result = parser.parse(raw);
    if (result instanceof ParseResult.Unparseable u) {
      return new InboundAnalysis.Unparseable(u.reason(), u.rawSize());
    }
    InboundMessage message = ((ParseResult.Parsed) result).message();
    try {
      return analyseParsed(message, blockList);
    } catch (RuntimeException | StackOverflowError e) {
      return new InboundAnalysis.Unparseable(ParseResult.Reason.MALFORMED, raw.length);
    }
  }

  private InboundAnalysis analyseParsed(InboundMessage message, BlockListMatcher blockList) {
    boolean forward = SubjectPrefixes.isForward(message.subject());

    String replyText;
    String replyHtmlSource = message.htmlBody();
    if (message.htmlBody() != null && !forward) {
      replyHtmlSource = QuoteStripper.stripHtml(message.htmlBody());
    }
    if (message.textBody() != null && !message.textBody().isBlank()) {
      replyText =
          forward ? message.textBody().strip() : QuoteStripper.stripText(message.textBody());
    } else if (replyHtmlSource != null) {
      replyText = HtmlText.toPlainText(replyHtmlSource);
    } else {
      replyText = "";
    }

    HtmlToSafeHtml.SafeHtml fullHtml =
        HtmlToSafeHtml.sanitise(message.htmlBody(), message.attachments());
    HtmlToSafeHtml.SafeHtml replyHtml =
        replyHtmlSource == message.htmlBody()
            ? fullHtml
            : HtmlToSafeHtml.sanitise(replyHtmlSource, message.attachments());

    return new InboundAnalysis.Analysed(
        message,
        ReferenceExtractor.extract(message.subject()),
        authParser.parse(message),
        AutomatedMailDetector.detect(message),
        plusAddress.detect(message),
        (blockList == null ? BlockListMatcher.none() : blockList)
            .match(message.from())
            .orElse(null),
        replyText == null ? "" : replyText,
        fullHtml,
        replyHtml);
  }
}
