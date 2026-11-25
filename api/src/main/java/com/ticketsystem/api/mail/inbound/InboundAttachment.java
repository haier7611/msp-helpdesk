package com.ticketsystem.api.mail.inbound;

import java.util.Arrays;

/**
 * A non-body MIME part of an inbound message (file attachment, inline image, attached message).
 *
 * @param fileName decoded file name with any path removed, or null when the part has none
 * @param contentType base MIME type in lower case as declared by the sender (not trusted: the
 *     attachment pipeline detects the real type before storing)
 * @param contentId the Content-ID without angle brackets, or null
 * @param inline true for {@code Content-Disposition: inline}, or no disposition plus a Content-ID
 */
public record InboundAttachment(
    String fileName, String contentType, String contentId, boolean inline, byte[] content) {

  public InboundAttachment {
    content = content.clone();
  }

  /** Decoded size in bytes. */
  public long size() {
    return content.length;
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof InboundAttachment a
        && java.util.Objects.equals(fileName, a.fileName)
        && contentType.equals(a.contentType)
        && java.util.Objects.equals(contentId, a.contentId)
        && inline == a.inline
        && Arrays.equals(content, a.content);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(fileName, contentType, contentId, inline)
        + 31 * Arrays.hashCode(content);
  }

  @Override
  public String toString() {
    return "InboundAttachment[type="
        + contentType
        + ", size="
        + content.length
        + ", inline="
        + inline
        + "]";
  }
}
