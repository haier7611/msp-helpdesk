package com.ticketsystem.api.mail.inbound;

/**
 * Safety caps for parsing untrusted mail.
 *
 * @param maxMessageBytes raw size cap; above it the message is not parsed at all
 * @param maxParts most MIME parts walked (counting multipart containers)
 * @param maxDepth deepest multipart nesting walked
 */
public record InboundLimits(long maxMessageBytes, int maxParts, int maxDepth) {

  /**
   * 30 MB raw (one 20 MB attachment, the attachment limit, is about 27 MB once base64 encoded), 500
   * parts, 20 levels.
   */
  public static final InboundLimits DEFAULT = new InboundLimits(30L * 1024 * 1024, 500, 20);

  public InboundLimits {
    if (maxMessageBytes < 1 || maxParts < 1 || maxDepth < 1) {
      throw new IllegalArgumentException("inbound limits must be positive");
    }
  }
}
