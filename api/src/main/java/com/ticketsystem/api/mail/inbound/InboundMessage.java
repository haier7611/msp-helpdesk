package com.ticketsystem.api.mail.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed inbound email. Everything here is sender-controlled and untrusted; the HTML body is raw
 * and must go through {@link HtmlToSafeHtml} before it is stored or shown.
 *
 * @param from first mailbox of the From header, or null when missing or unparseable
 * @param subject decoded subject on one line, never null
 * @param messageId Message-ID with angle brackets (as the outbox stores them), or null
 * @param inReplyTo message ids from In-Reply-To, with angle brackets
 * @param references message ids from References, with angle brackets, oldest first
 * @param date the Date header, or null when missing or unparseable
 * @param textBody plain text body (text/plain parts joined), or null
 * @param htmlBody raw HTML body, or null
 * @param headers every header, names in lower case, values unfolded, in message order
 * @param headersTruncated a header was longer than its cap (Subject 2 KB, address headers 32 KB,
 *     others 8 KB) or the header block passed 256 KB: values were cut and later headers dropped.
 *     The message still becomes a ticket; triage shows the flag in triage.
 */
public record InboundMessage(
    InboundAddress from,
    List<InboundAddress> to,
    List<InboundAddress> cc,
    List<InboundAddress> replyTo,
    String subject,
    String messageId,
    List<String> inReplyTo,
    List<String> references,
    Instant date,
    String textBody,
    String htmlBody,
    List<InboundAttachment> attachments,
    Map<String, List<String>> headers,
    boolean headersTruncated) {

  public InboundMessage {
    to = List.copyOf(to);
    cc = List.copyOf(cc);
    replyTo = List.copyOf(replyTo);
    inReplyTo = List.copyOf(inReplyTo);
    references = List.copyOf(references);
    attachments = List.copyOf(attachments);
    headers = Map.copyOf(headers);
  }

  /** All values of a header (case-insensitive name), in message order. */
  public List<String> header(String name) {
    return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
  }

  /** The first value of a header, if present. */
  public Optional<String> firstHeader(String name) {
    List<String> values = header(name);
    return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
  }

  /** In-Reply-To then References, without duplicates: the ids looked up in the outbox. */
  public List<String> threadMessageIds() {
    java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(inReplyTo);
    for (int i = references.size() - 1; i >= 0; i--) {
      ids.add(references.get(i));
    }
    return List.copyOf(ids);
  }

  @Override
  public String toString() {
    // No addresses, subject or body: inbound mail is personal data and must not reach logs.
    return "InboundMessage[attachments=" + attachments.size() + "]";
  }
}
