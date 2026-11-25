package com.ticketsystem.api.mail.inbound;

import java.util.List;

/** What {@link InboundMailEngine} found out about one raw email. */
public sealed interface InboundAnalysis {

  /**
   * The email could not be parsed safely: the intake keeps the raw {@code .eml} and creates an
   * Email triage ticket marked "unparseable" (every email becomes a ticket).
   */
  record Unparseable(ParseResult.Reason reason, long rawSize) implements InboundAnalysis {}

  /**
   * A parsed email.
   *
   * @param referenceCandidates bracketed references in the subject, for the reference module
   * @param authentication our receiving server's verdict; {@code dmarcPass()} is the C10 guard
   * @param automation loop-safety flags (no automatic replies when automated)
   * @param mailboxTarget {@code support@} or {@code support+internal@}
   * @param blockMatch the block-list rule that matched the sender, or null
   * @param replyText the new text with quoted history removed (forwards are kept whole), plain
   *     text; the full body stays in {@code message}
   * @param safeHtml the whole HTML body, sanitised (empty when there is no HTML part)
   * @param safeReplyHtml the HTML body with quoted history removed, sanitised
   */
  record Analysed(
      InboundMessage message,
      List<ReferenceCandidate> referenceCandidates,
      AuthenticationResults authentication,
      AutomatedMailFlags automation,
      PlusAddress.MailboxTarget mailboxTarget,
      BlockListMatcher.BlockMatch blockMatch,
      String replyText,
      HtmlToSafeHtml.SafeHtml safeHtml,
      HtmlToSafeHtml.SafeHtml safeReplyHtml)
      implements InboundAnalysis {

    public Analysed {
      referenceCandidates = List.copyOf(referenceCandidates);
    }

    public boolean blocked() {
      return blockMatch != null;
    }

    @Override
    public String toString() {
      return "InboundAnalysis.Analysed[references="
          + referenceCandidates.size()
          + ", dmarcPass="
          + authentication.dmarcPass()
          + ", automated="
          + automation.automated()
          + ", target="
          + mailboxTarget
          + ", blocked="
          + blocked()
          + "]";
    }
  }
}
