package com.ticketsystem.api.mail.inbound;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spots automated mail (auto-replies, bulk and list mail, bounces, no-reply senders) for loop
 * safety: such mail still becomes a ticket, but the system never sends it an automatic email, and
 * an auto-reply never reopens a ticket.
 */
public final class AutomatedMailDetector {

  private static final Pattern NO_REPLY_LOCAL =
      Pattern.compile("(noreply|donotreply|dontreply|mailerdaemon|postmaster|bounces?)([0-9+].*)?");

  private AutomatedMailDetector() {}

  public static AutomatedMailFlags detect(InboundMessage message) {
    Set<AutomatedMailFlags.Signal> signals = EnumSet.noneOf(AutomatedMailFlags.Signal.class);
    boolean autoReply = false;

    for (String value : message.header("Auto-Submitted")) {
      String v = token(value);
      if (!v.isEmpty() && !v.equals("no")) {
        signals.add(AutomatedMailFlags.Signal.AUTO_SUBMITTED);
        autoReply |= v.equals("auto-replied");
      }
    }
    for (String name :
        new String[] {"X-Autoreply", "X-Autorespond", "X-Auto-Reply", "X-Autoresponder"}) {
      if (!message.header(name).isEmpty()) {
        signals.add(AutomatedMailFlags.Signal.AUTO_REPLY_HEADER);
        autoReply = true;
      }
    }
    for (String value : message.header("Precedence")) {
      String v = token(value);
      if (v.equals("bulk") || v.equals("list") || v.equals("junk")) {
        signals.add(AutomatedMailFlags.Signal.PRECEDENCE_BULK);
      } else if (v.equals("auto_reply")) {
        signals.add(AutomatedMailFlags.Signal.AUTO_REPLY_HEADER);
        autoReply = true;
      }
    }
    if (!message.header("List-Unsubscribe").isEmpty() || !message.header("List-Id").isEmpty()) {
      signals.add(AutomatedMailFlags.Signal.LIST_HEADERS);
    }
    for (String value : message.header("X-Auto-Response-Suppress")) {
      String v = token(value);
      if (!v.isEmpty() && !v.equals("none")) {
        signals.add(AutomatedMailFlags.Signal.AUTO_RESPONSE_SUPPRESS);
      }
    }
    if (message.from() != null && isNoReply(message.from().localPart())) {
      signals.add(AutomatedMailFlags.Signal.NO_REPLY_SENDER);
    }
    for (String value : message.header("Return-Path")) {
      if (value.replaceAll("\\s", "").equals("<>")) {
        signals.add(AutomatedMailFlags.Signal.NULL_RETURN_PATH);
      }
    }
    if (message
        .firstHeader("Content-Type")
        .map(v -> v.toLowerCase(Locale.ROOT).startsWith("multipart/report"))
        .orElse(false)) {
      signals.add(AutomatedMailFlags.Signal.DELIVERY_REPORT);
      autoReply = true;
    }
    return new AutomatedMailFlags(signals, autoReply);
  }

  /**
   * True for an address whose local part marks an automated sender (no-reply, mailer-daemon,
   * postmaster, bounces). The system never sends automatic client email to such an address.
   */
  public static boolean isAutomatedAddress(String address) {
    if (address == null) {
      return false;
    }
    int at = address.lastIndexOf('@');
    return at > 0 && isNoReply(address.substring(0, at));
  }

  static boolean isNoReply(String localPart) {
    String compact = localPart.toLowerCase(Locale.ROOT).replaceAll("[-_.]", "");
    return NO_REPLY_LOCAL.matcher(compact).matches();
  }

  /** First token of a header value, lower case, without parameters or comments. */
  private static String token(String value) {
    String v = value.strip().toLowerCase(Locale.ROOT);
    int cut = v.length();
    for (char c : new char[] {';', '(', ' ', ','}) {
      int i = v.indexOf(c);
      if (i >= 0) {
        cut = Math.min(cut, i);
      }
    }
    return v.substring(0, cut);
  }
}
