package com.ticketsystem.api.mail.inbound;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reply and forward prefixes at the start of a subject ({@code RE:}, {@code FW:}, ...). */
final class SubjectPrefixes {

  private static final Pattern PREFIX =
      Pattern.compile("^\\s*\\[?\\s*([A-Za-z]{1,6})\\s*(\\[\\d+]|\\(\\d+\\))?\\s*:\\s*");
  private static final Set<String> FORWARD =
      Set.of("fw", "fwd", "wg", "tr", "vs", "vb", "rv", "enc", "doorst");

  private SubjectPrefixes() {}

  /** True when the outermost prefix is a forward ({@code FW: RE: x} yes, {@code RE: FW: x} no). */
  static boolean isForward(String subject) {
    if (subject == null) {
      return false;
    }
    Matcher m = PREFIX.matcher(subject);
    return m.find() && FORWARD.contains(m.group(1).toLowerCase(Locale.ROOT));
  }
}
