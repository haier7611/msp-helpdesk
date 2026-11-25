package com.ticketsystem.api.mail.inbound;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One mailbox from an inbound address header.
 *
 * @param address the bare address, lower case (for matching; the original case is not kept)
 * @param displayName the decoded display name, or null
 */
public record InboundAddress(String address, String displayName) {

  public InboundAddress {
    address = address.toLowerCase(Locale.ROOT);
  }

  /** The part after the last {@code @}. */
  public String domain() {
    return address.substring(address.lastIndexOf('@') + 1);
  }

  /** The part before the last {@code @}. */
  public String localPart() {
    return address.substring(0, address.lastIndexOf('@'));
  }

  /**
   * Parses an address header leniently: anything unparseable gives an empty list rather than an
   * exception, and entries without an {@code @} are skipped.
   */
  static List<InboundAddress> parseHeader(String value) {
    List<InboundAddress> result = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return result;
    }
    InternetAddress[] parsed;
    try {
      parsed = InternetAddress.parseHeader(value, false);
    } catch (AddressException | RuntimeException e) {
      return result;
    }
    for (InternetAddress a : parsed) {
      if (a.isGroup()) {
        continue;
      }
      String addr = a.getAddress();
      if (addr == null) {
        continue;
      }
      addr = addr.strip();
      int at = addr.lastIndexOf('@');
      if (at <= 0 || at == addr.length() - 1 || addr.chars().anyMatch(c -> c <= ' ')) {
        continue;
      }
      String name = a.getPersonal();
      if (name != null) {
        name = InboundText.singleLine(name);
        if (name.isEmpty()) {
          name = null;
        }
      }
      result.add(new InboundAddress(addr, name));
    }
    return result;
  }
}
