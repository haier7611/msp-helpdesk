package com.ticketsystem.api.admin;

import com.ticketsystem.api.auth.ApiException;
import java.util.regex.Pattern;

/**
 * Validation for the one-line plain-text settings an admin types: trimmed, no control characters or
 * line breaks (so a value can never add lines or headers where it is reused, e.g. the email "From"
 * name), and no bidirectional override characters (they make text read differently from what it
 * is). HTML is never interpreted: every value is escaped wherever it is rendered.
 */
public final class PlainText {

  private static final Pattern FORBIDDEN =
      Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F]");

  private PlainText() {}

  /** A required value of 1 to {@code max} characters. */
  public static String required(String raw, String label, int max) {
    String v = clean(raw, label);
    if (v.isEmpty() || v.length() > max) {
      throw ApiException.invalidRequest("Enter " + label + " of 1 to " + max + " characters.");
    }
    return v;
  }

  /** An optional value of 0 to {@code max} characters ("" when empty). */
  public static String optional(String raw, String label, int max) {
    String v = clean(raw, label);
    if (v.length() > max) {
      throw ApiException.invalidRequest(
          capitalise(label) + " can be at most " + max + " characters.");
    }
    return v;
  }

  private static String clean(String raw, String label) {
    String v = raw == null ? "" : raw.strip();
    if (FORBIDDEN.matcher(v).find()) {
      throw ApiException.invalidRequest(
          capitalise(label) + " must be a single line of plain text.");
    }
    return v;
  }

  private static String capitalise(String label) {
    String l = label.startsWith("a ") ? label.substring(2) : label;
    l = l.startsWith("an ") ? l.substring(3) : l;
    return l.isEmpty() ? l : Character.toUpperCase(l.charAt(0)) + l.substring(1);
  }
}
