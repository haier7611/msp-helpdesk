package com.ticketsystem.api.mail.inbound;

/** Outcome of parsing raw message bytes: a message, or a safe "unparseable" marker for triage. */
public sealed interface ParseResult {

  /** The message parsed. */
  record Parsed(InboundMessage message) implements ParseResult {}

  /**
   * The message could not be parsed safely. The intake keeps the raw {@code .eml} and puts it in
   * Email triage with the reason; nothing is dropped.
   *
   * @param rawSize size of the raw message in bytes
   */
  record Unparseable(Reason reason, long rawSize) implements ParseResult {}

  /** Why a message was not parsed. */
  enum Reason {
    /** No bytes at all. */
    EMPTY,
    /** Larger than {@link InboundLimits#maxMessageBytes()}. */
    TOO_LARGE,
    /** More MIME parts than {@link InboundLimits#maxParts()}. */
    TOO_MANY_PARTS,
    /** Multiparts nested deeper than {@link InboundLimits#maxDepth()}. */
    TOO_DEEP,
    /** No recognisable header (From, To, Subject, Date or Message-ID). */
    NO_HEADERS,
    /** The MIME structure is broken (for example a multipart without its start boundary). */
    MALFORMED
  }
}
