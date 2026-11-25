package com.ticketsystem.api.mail.inbound;

import jakarta.mail.internet.MimeUtility;

/** Small text helpers shared by the inbound parser. */
final class InboundText {

  private InboundText() {}

  /** Replaces line breaks, tabs and other control characters with spaces and trims. */
  static String singleLine(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      sb.append(Character.isISOControl(c) ? ' ' : c);
    }
    return sb.toString().strip().replaceAll(" {2,}", " ");
  }

  /** Decodes RFC 2047 encoded words, returning the input unchanged if it cannot be decoded. */
  static String decodeWords(String value) {
    if (value == null) {
      return null;
    }
    try {
      return MimeUtility.decodeText(MimeUtility.unfold(value));
    } catch (Exception e) {
      return MimeUtility.unfold(value);
    }
  }
}
