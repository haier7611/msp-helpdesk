package com.ticketsystem.api.mail;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * An extra MIME part of an outbound email: an inline image shown through {@code cid:<contentId>}
 * (the signature logo, inline images of a public reply) or an attached file. Validated on
 * construction so nothing that could inject headers reaches the outbox.
 *
 * @param contentId Content-ID without angle brackets; required for inline parts
 * @param filename file name shown to the recipient
 * @param contentType MIME type such as {@code image/png}
 * @param inline true for an inline image, false for an attachment
 */
public record MailPart(
    String contentId, String filename, String contentType, boolean inline, byte[] content) {

  /** Largest single part (the attachment limit). */
  public static final int MAX_BYTES = 20 * 1024 * 1024;

  private static final Pattern CONTENT_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");
  private static final Pattern CONTENT_TYPE =
      Pattern.compile("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,58}");

  public MailPart {
    if (contentId != null && !CONTENT_ID.matcher(contentId).matches()) {
      throw new IllegalArgumentException("invalid content id");
    }
    if (inline && contentId == null) {
      throw new IllegalArgumentException("an inline part needs a content id");
    }
    if (filename == null || filename.isBlank() || filename.length() > 255) {
      throw new IllegalArgumentException("filename must be 1-255 characters");
    }
    // Control characters, quotes, slashes and bidi overrides (e.g. "invoice" + U+202E +
    // "fdp.exe"
    // must not pose as a PDF) are replaced.
    filename =
        filename
            .replaceAll("[\\p{Cntrl}\"\\\\/\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "_")
            .strip();
    if (contentType == null || !CONTENT_TYPE.matcher(contentType).matches()) {
      throw new IllegalArgumentException("invalid content type");
    }
    if (content == null || content.length > MAX_BYTES) {
      throw new IllegalArgumentException("part content missing or too large");
    }
    content = content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  int size() {
    return content.length;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof MailPart p
        && java.util.Objects.equals(contentId, p.contentId)
        && filename.equals(p.filename)
        && contentType.equals(p.contentType)
        && inline == p.inline
        && Arrays.equals(content, p.content);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(contentId, filename, contentType, inline)
        + 31 * Arrays.hashCode(content);
  }

  /** No file name or content: they can be personal data. */
  @Override
  public String toString() {
    return "MailPart[type=" + contentType + ", inline=" + inline + ", size=" + content.length + "]";
  }
}
