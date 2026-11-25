package com.ticketsystem.api.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Email normalisation: one canonical form (trimmed, lower case) for storage and lookup. */
public final class Emails {

  public static final int MAX_LENGTH = 320;

  // Deliberately simple: one @, something either side, a dot in the domain, no spaces or control
  // characters. Deliverability is proven by the invitation link, not by this check.
  private static final Pattern SHAPE =
      Pattern.compile("^[^@\\s\\p{Cntrl}]+@[^@\\s\\p{Cntrl}]+\\.[^@\\s\\p{Cntrl}]+$");

  private Emails() {}

  /** The canonical form, or empty when the value is not a plausible email address. */
  public static Optional<String> normalise(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String v = raw.trim().toLowerCase(Locale.ROOT);
    if (v.isEmpty() || v.length() > MAX_LENGTH || !SHAPE.matcher(v).matches()) {
      return Optional.empty();
    }
    return Optional.of(v);
  }

  /** The part before the @ (used by the password policy). */
  public static String localPart(String email) {
    int at = email.indexOf('@');
    return at < 0 ? email : email.substring(0, at);
  }
}
