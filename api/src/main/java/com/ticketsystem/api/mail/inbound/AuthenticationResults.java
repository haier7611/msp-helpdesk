package com.ticketsystem.api.mail.inbound;

/**
 * What our own receiving server said about a message (RFC 8601 {@code Authentication-Results}).
 *
 * @param authenticated true only when a header from a trusted authserv-id was found; false means
 *     "not authenticated" and every result below is {@link Result#NONE}
 * @param dmarc the DMARC result, downgraded to {@link Result#FAIL} when its {@code header.from}
 *     does not match the message's From domain
 * @param spf the SPF result
 * @param dkim {@link Result#PASS} if any DKIM signature passed, otherwise the first DKIM result
 */
public record AuthenticationResults(boolean authenticated, Result dmarc, Result spf, Result dkim) {

  /** No trusted header: treat as not authenticated. */
  public static final AuthenticationResults NOT_AUTHENTICATED =
      new AuthenticationResults(false, Result.NONE, Result.NONE, Result.NONE);

  /** The guard for auto-attach and auto-create. */
  public boolean dmarcPass() {
    return authenticated && dmarc == Result.PASS;
  }

  /** RFC 8601 result values; anything unknown is {@link #OTHER}. */
  public enum Result {
    PASS,
    FAIL,
    SOFTFAIL,
    NEUTRAL,
    NONE,
    TEMPERROR,
    PERMERROR,
    POLICY,
    OTHER
  }
}
