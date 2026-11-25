package com.ticketsystem.api.mail.inbound;

import jakarta.mail.internet.InternetHeaders;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

/**
 * The sender and subject of a mail that is too large to parse (over 30 MB), read from the headers
 * at the start of its truncated copy, so the block list still applies to it. Lenient: anything
 * unreadable gives an empty result, never an exception. The headers are not authenticated - like
 * the block check on parsed mail, a block matches the From address as sent.
 *
 * @param from the first From address, or null
 * @param subject the decoded subject on one line, or null
 */
public record HeaderPeek(InboundAddress from, String subject) {

  /** Most header bytes read (the truncated copy is 256 KB anyway). */
  static final int MAX_HEADER_BYTES = 256 * 1024;

  private static final int MAX_FIELD = 32 * 1024;

  public static HeaderPeek of(byte[] raw) {
    if (raw == null || raw.length == 0) {
      return new HeaderPeek(null, null);
    }
    try {
      InternetHeaders headers =
          new InternetHeaders(
              new ByteArrayInputStream(raw, 0, Math.min(raw.length, MAX_HEADER_BYTES)));
      String fromValue = headers.getHeader("From", ",");
      InboundAddress from = null;
      if (fromValue != null && fromValue.length() <= MAX_FIELD) {
        List<InboundAddress> parsed = InboundAddress.parseHeader(fromValue);
        from = parsed.isEmpty() ? null : parsed.get(0);
      }
      String subjectValue = headers.getHeader("Subject", null);
      String subject = null;
      if (subjectValue != null) {
        String cut = subjectValue.length() > 2048 ? subjectValue.substring(0, 2048) : subjectValue;
        subject = InboundText.singleLine(InboundText.decodeWords(cut));
      }
      return new HeaderPeek(from, subject);
    } catch (Exception | StackOverflowError e) {
      return new HeaderPeek(null, null);
    }
  }

  /** The block-list rule matching the sender, if any. */
  public Optional<BlockListMatcher.BlockMatch> block(BlockListMatcher blockList) {
    return from == null ? Optional.empty() : blockList.match(from);
  }
}
