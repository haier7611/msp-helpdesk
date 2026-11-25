package com.ticketsystem.api.mail.inbound;

import com.ticketsystem.api.mail.inbound.AuthenticationResults.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads {@code Authentication-Results} headers (RFC 8601) and returns the DMARC, SPF and DKIM
 * results stamped by our own receiving server, identified by a configured trusted authserv-id (e.g.
 * the M365 or Gmail host name). Headers with any other authserv-id are ignored, because a sender
 * can write any header it likes.
 *
 * <p>Only the <b>topmost</b> header counts: the receiving server prepends its header above
 * everything the sender put in the message. If the topmost header's authserv-id is not trusted (or
 * it has none and "(none)" is not trusted), the mail is not authenticated, whatever lower headers
 * say - a forged header further down never wins, even when the trusted list is misconfigured. A
 * DMARC pass whose {@code header.from} differs from the message's From domain is treated as a fail.
 *
 * <p>The {@code header.from} comparison is exact (no organisational-domain match). That is the safe
 * side: a relaxed-aligned pass for {@code mail.a.example} on mail From {@code a.example} goes to
 * triage instead of attaching (revisit if it causes real triage).
 */
public final class AuthenticationResultsParser {

  /**
   * Configure this as a trusted authserv-id to accept headers that have none, as Microsoft 365
   * (Exchange Online) writes them. Safe only for a mailbox whose server always stamps its own
   * header on top of every message, as Exchange Online does.
   */
  public static final String NO_AUTHSERV_ID = "(none)";

  private final Set<String> trustedAuthServIds;

  public AuthenticationResultsParser(Set<String> trustedAuthServIds) {
    this.trustedAuthServIds =
        trustedAuthServIds.stream()
            .map(s -> s.strip().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  public AuthenticationResults parse(InboundMessage message) {
    String fromDomain = message.from() == null ? null : message.from().domain();
    return parse(message.header("Authentication-Results"), fromDomain);
  }

  /**
   * @param headerValues every {@code Authentication-Results} value, in message order
   * @param fromDomain the domain of the From address, or null
   */
  public AuthenticationResults parse(List<String> headerValues, String fromDomain) {
    if (headerValues == null || headerValues.isEmpty() || headerValues.get(0) == null) {
      return AuthenticationResults.NOT_AUTHENTICATED;
    }
    // Topmost header only: the one our receiving server prepended.
    List<String> parts = splitTopLevel(stripComments(headerValues.get(0)));
    if (parts.isEmpty()) {
      return AuthenticationResults.NOT_AUTHENTICATED;
    }
    // authserv-id, optionally followed by a version number ("mx.example.com 1"). Exchange
    // Online leaves the authserv-id out and starts straight with "spf=...".
    String first = parts.get(0).strip();
    boolean noId = first.contains("=");
    String authServId = noId ? NO_AUTHSERV_ID : first.split("\\s+")[0].toLowerCase(Locale.ROOT);
    if (!trustedAuthServIds.contains(authServId)) {
      return AuthenticationResults.NOT_AUTHENTICATED;
    }
    return results(noId ? parts : parts.subList(1, parts.size()), fromDomain);
  }

  private static AuthenticationResults results(List<String> resinfos, String fromDomain) {
    Result dmarc = null;
    Result spf = null;
    Result dkim = null;
    for (String resinfo : resinfos) {
      String[] tokens = resinfo.strip().replaceAll("\\s*=\\s*", "=").split("\\s+");
      if (tokens.length == 0 || !tokens[0].contains("=")) {
        continue;
      }
      String method = tokens[0].substring(0, tokens[0].indexOf('=')).toLowerCase(Locale.ROOT);
      int slash = method.indexOf('/');
      if (slash >= 0) {
        method = method.substring(0, slash);
      }
      Result result = result(tokens[0].substring(tokens[0].indexOf('=') + 1));
      switch (method) {
        case "dmarc" -> {
          if (dmarc == null) {
            dmarc = result;
            if (result == Result.PASS && !headerFromMatches(tokens, fromDomain)) {
              dmarc = Result.FAIL;
            }
          }
        }
        case "spf" -> {
          if (spf == null) {
            spf = result;
          }
        }
        case "dkim" -> {
          if (dkim == null || (dkim != Result.PASS && result == Result.PASS)) {
            dkim = result;
          }
        }
        default -> {
          // other methods (arc, iprev, auth, compauth ...) are not used
        }
      }
    }
    return new AuthenticationResults(true, orNone(dmarc), orNone(spf), orNone(dkim));
  }

  private static boolean headerFromMatches(String[] tokens, String fromDomain) {
    for (int i = 1; i < tokens.length; i++) {
      String t = tokens[i];
      if (t.toLowerCase(Locale.ROOT).startsWith("header.from=")) {
        String domain = unquote(t.substring("header.from=".length())).toLowerCase(Locale.ROOT);
        int at = domain.lastIndexOf('@');
        if (at >= 0) {
          domain = domain.substring(at + 1);
        }
        return fromDomain != null && domain.equals(fromDomain.toLowerCase(Locale.ROOT));
      }
    }
    // No header.from property: nothing contradicts the result.
    return fromDomain != null;
  }

  private static Result result(String value) {
    String v = unquote(value).toLowerCase(Locale.ROOT);
    return switch (v) {
      case "pass" -> Result.PASS;
      case "fail", "hardfail" -> Result.FAIL;
      case "softfail" -> Result.SOFTFAIL;
      case "neutral" -> Result.NEUTRAL;
      case "none" -> Result.NONE;
      case "temperror" -> Result.TEMPERROR;
      case "permerror" -> Result.PERMERROR;
      case "policy" -> Result.POLICY;
      default -> Result.OTHER;
    };
  }

  private static Result orNone(Result r) {
    return r == null ? Result.NONE : r;
  }

  private static String unquote(String s) {
    String t = s.strip();
    if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
      return t.substring(1, t.length() - 1);
    }
    return t;
  }

  /** Removes RFC 5322 comments (nested parentheses), keeping quoted strings intact. */
  static String stripComments(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    int depth = 0;
    boolean quoted = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' && i + 1 < value.length()) {
        if (depth == 0) {
          sb.append(c).append(value.charAt(i + 1));
        }
        i++;
        continue;
      }
      if (depth == 0 && c == '"') {
        quoted = !quoted;
        sb.append(c);
      } else if (!quoted && c == '(') {
        depth++;
        sb.append(' ');
      } else if (!quoted && c == ')' && depth > 0) {
        depth--;
      } else if (depth == 0) {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** Splits on semicolons outside quoted strings. */
  private static List<String> splitTopLevel(String value) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      }
      if (c == ';' && !quoted) {
        parts.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    parts.add(current.toString());
    parts.removeIf(String::isBlank);
    return parts;
  }
}
