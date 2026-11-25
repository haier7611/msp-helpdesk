package com.ticketsystem.api.mail;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A claimed outbox row, ready to send. {@code attempts} already counts this attempt. */
record OutboxMessage(
    long id,
    String messageId,
    MailKind kind,
    Long ticketId,
    List<String> to,
    List<String> cc,
    String fromName,
    String replyTo,
    String subject,
    String htmlBody,
    String textBody,
    Map<String, String> extraHeaders,
    boolean sensitive,
    Instant expiresAt,
    int attempts,
    List<MailPart> parts) {

  OutboxMessage {
    parts = parts == null ? List.of() : List.copyOf(parts);
  }

  OutboxMessage withParts(List<MailPart> newParts) {
    return new OutboxMessage(
        id,
        messageId,
        kind,
        ticketId,
        to,
        cc,
        fromName,
        replyTo,
        subject,
        htmlBody,
        textBody,
        extraHeaders,
        sensitive,
        expiresAt,
        attempts,
        newParts);
  }

  /** The same message to other recipients (the reserved-domain guard). */
  OutboxMessage withRecipients(List<String> newTo, List<String> newCc) {
    return new OutboxMessage(
        id,
        messageId,
        kind,
        ticketId,
        newTo,
        newCc,
        fromName,
        replyTo,
        subject,
        htmlBody,
        textBody,
        extraHeaders,
        sensitive,
        expiresAt,
        attempts,
        parts);
  }

  /** Id and attempt only: the rest can hold tokens and personal data. */
  @Override
  public String toString() {
    return "OutboxMessage[id=" + id + ", attempts=" + attempts + "]";
  }
}
