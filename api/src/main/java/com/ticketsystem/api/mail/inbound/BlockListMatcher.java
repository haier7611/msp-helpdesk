package com.ticketsystem.api.mail.inbound;

import java.net.IDN;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Matches a sender against the admin block list: an exact sender address, or a blocked domain and
 * all its subdomains ({@code spam.example} blocks {@code a@spam.example} and {@code
 * a@mx.spam.example} but not {@code a@notspam.example}). Comparison ignores case, a trailing dot
 * and IDN form. An exact address rule wins over a domain rule. The guards on adding rules (client
 * domains, contacts, the support domain) belong to the admin settings.
 */
public final class BlockListMatcher {

  private final Set<String> addresses;
  private final Set<String> domains;

  /**
   * @param blockedAddresses full addresses, e.g. {@code promo@spam.example}
   * @param blockedDomains domains, e.g. {@code spam.example} (a leading {@code @} or {@code *.} is
   *     accepted and ignored)
   */
  public BlockListMatcher(Collection<String> blockedAddresses, Collection<String> blockedDomains) {
    this.addresses =
        blockedAddresses.stream()
            .map(BlockListMatcher::normaliseAddress)
            .filter(a -> a != null)
            .collect(Collectors.toUnmodifiableSet());
    this.domains =
        blockedDomains.stream()
            .map(d -> d == null ? "" : d.strip())
            .map(d -> d.startsWith("@") ? d.substring(1) : d)
            .map(d -> d.startsWith("*.") ? d.substring(2) : d)
            .map(BlockListMatcher::normaliseDomain)
            .filter(d -> d != null && d.contains("."))
            .collect(Collectors.toUnmodifiableSet());
  }

  /** An empty block list. */
  public static BlockListMatcher none() {
    return new BlockListMatcher(Set.of(), Set.of());
  }

  public Optional<BlockMatch> match(InboundAddress sender) {
    return sender == null ? Optional.empty() : match(sender.address());
  }

  public Optional<BlockMatch> match(String senderAddress) {
    String address = normaliseAddress(senderAddress);
    if (address == null) {
      return Optional.empty();
    }
    if (addresses.contains(address)) {
      return Optional.of(new BlockMatch(BlockMatch.Kind.ADDRESS, address));
    }
    String domain = address.substring(address.lastIndexOf('@') + 1);
    // Walk up the labels: mx.spam.example, spam.example, example.
    String d = domain;
    while (true) {
      if (domains.contains(d)) {
        return Optional.of(new BlockMatch(BlockMatch.Kind.DOMAIN, d));
      }
      int dot = d.indexOf('.');
      if (dot < 0) {
        return Optional.empty();
      }
      d = d.substring(dot + 1);
    }
  }

  private static String normaliseAddress(String address) {
    if (address == null) {
      return null;
    }
    String a = address.strip();
    int at = a.lastIndexOf('@');
    if (at <= 0 || at == a.length() - 1) {
      return null;
    }
    String domain = normaliseDomain(a.substring(at + 1));
    if (domain == null) {
      return null;
    }
    return a.substring(0, at).toLowerCase(Locale.ROOT) + "@" + domain;
  }

  private static String normaliseDomain(String domain) {
    String d = domain.strip().toLowerCase(Locale.ROOT);
    while (d.endsWith(".")) {
      d = d.substring(0, d.length() - 1);
    }
    if (d.isEmpty()) {
      return null;
    }
    try {
      d = IDN.toASCII(d, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      // keep the lower-case form
    }
    return d;
  }

  /**
   * Which rule matched.
   *
   * @param rule the normalised blocked address or domain
   */
  public record BlockMatch(Kind kind, String rule) {

    /** Rule type. */
    public enum Kind {
      ADDRESS,
      DOMAIN
    }
  }
}
