package com.ticketsystem.api.signature;

import com.ticketsystem.api.auth.ApiException;
import java.util.regex.Pattern;

/**
 * Validation of the supporter's fixed signature fields: plain text, trimmed, one line each. Any
 * printable character is allowed - HTML is never interpreted, every value is escaped when a
 * signature is rendered - but control characters and line breaks are refused so a field cannot add
 * lines or headers anywhere it is reused (email "From" name).
 */
public final class ProfileFields {

  public static final int NAME_MAX = 80;
  public static final int TITLE_MAX = 80;
  public static final int PHONE_MAX = 30;
  public static final int LINE_MAX = 100;

  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");
  private static final Pattern PHONE = Pattern.compile("[0-9 +()\\-]*");

  private ProfileFields() {}

  /** Display name: 1-80 characters. */
  public static String displayName(String raw) {
    String v = clean(raw, "Display name");
    if (v.isEmpty() || v.length() > NAME_MAX) {
      throw ApiException.invalidRequest(
          "Enter a display name of 1 to " + NAME_MAX + " characters.");
    }
    return v;
  }

  /** Job title: 0-80 characters. */
  public static String jobTitle(String raw) {
    return optional(raw, "Job title", TITLE_MAX);
  }

  /** Phone or mobile: 0-30 of digits, spaces and {@code + ( ) -}. */
  public static String phone(String raw, String label) {
    String v = optional(raw, label, PHONE_MAX);
    if (!PHONE.matcher(v).matches()) {
      throw ApiException.invalidRequest(
          label + " can only contain digits, spaces and + ( ) - characters.");
    }
    return v;
  }

  /** The one free-text line: 0-100 characters. */
  public static String freeLine(String raw) {
    return optional(raw, "Extra line", LINE_MAX);
  }

  private static String optional(String raw, String label, int max) {
    String v = clean(raw, label);
    if (v.length() > max) {
      throw ApiException.invalidRequest(label + " can be at most " + max + " characters.");
    }
    return v;
  }

  private static String clean(String raw, String label) {
    String v = raw == null ? "" : raw.strip();
    if (CONTROL.matcher(v).find()) {
      throw ApiException.invalidRequest(label + " must be a single line of plain text.");
    }
    return v;
  }
}
