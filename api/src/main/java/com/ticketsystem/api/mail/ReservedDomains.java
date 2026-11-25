package com.ticketsystem.api.mail;

import java.util.List;
import java.util.Locale;

/**
 * The reserved test domains of RFC 2606 / RFC 6761 ({@code .example}, {@code .test}, {@code
 * .invalid}, {@code .localhost} and every subdomain). Seeded and synthetic contacts use them, so
 * mail to them is never sent through a real support mailbox: it would only bounce back into that
 * mailbox. The server-wide sender (Mailpit on dev and the demo) is not affected. Email intake never
 * adds them as ticket participants.
 */
public final class ReservedDomains {

  private static final List<String> TLDS = List.of("example", "test", "invalid", "localhost");

  private ReservedDomains() {}

  /** Whether the address's domain is, or is under, a reserved test domain. */
  public static boolean isReserved(String address) {
    if (address == null) {
      return false;
    }
    int at = address.lastIndexOf('@');
    String domain = address.substring(at + 1).strip().toLowerCase(Locale.ROOT);
    while (domain.endsWith(".")) {
      domain = domain.substring(0, domain.length() - 1);
    }
    for (String tld : TLDS) {
      if (domain.equals(tld) || domain.endsWith("." + tld)) {
        return true;
      }
    }
    return false;
  }
}
