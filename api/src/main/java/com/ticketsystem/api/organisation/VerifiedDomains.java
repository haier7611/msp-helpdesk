package com.ticketsystem.api.organisation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Rules for an organisation's verified email domains: a plain host name, stored lower case, never a
 * public mail domain (gmail.com, outlook.com and the maintained list in {@code
 * organisation/public-email-domains.txt}) or a subdomain of one. Uniqueness across organisations is
 * the database's primary key.
 */
@Component
public class VerifiedDomains {

  static final String LIST_RESOURCE = "/organisation/public-email-domains.txt";

  /** Host-name labels of 1-63 letters, digits or hyphens, at least two labels. */
  private static final Pattern DOMAIN =
      Pattern.compile(
          "(?=.{3,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
              + "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");

  /** The outcome of checking a domain an admin typed. */
  public sealed interface Check {
    record Valid(String domain) implements Check {}

    record Invalid(String code, String message) implements Check {}
  }

  private final Set<String> publicDomains;

  public VerifiedDomains() {
    this.publicDomains = load();
  }

  /** Normalises ({@code @Acme.Example.} -> {@code acme.example}) and checks a domain. */
  public Check check(String raw) {
    String domain = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    if (domain.startsWith("@")) {
      domain = domain.substring(1);
    }
    if (domain.endsWith(".")) {
      domain = domain.substring(0, domain.length() - 1);
    }
    if (!DOMAIN.matcher(domain).matches()) {
      return new Check.Invalid(
          "invalid_domain", "Enter a domain name such as acme-dental.example, without the @.");
    }
    if (isPublic(domain)) {
      return new Check.Invalid(
          "public_domain",
          domain
              + " is a public email domain, so it cannot be a verified domain. Save senders"
              + " from it as contacts instead.");
    }
    return new Check.Valid(domain);
  }

  /** The maintained public email domain list, sorted (Admin - Email, read-only view). */
  public List<String> publicDomains() {
    return publicDomains.stream().sorted().toList();
  }

  /** Is this domain, or a domain it belongs to, on the public list? */
  public boolean isPublic(String domain) {
    String d = domain.toLowerCase(Locale.ROOT);
    while (true) {
      if (publicDomains.contains(d)) {
        return true;
      }
      int dot = d.indexOf('.');
      if (dot < 0) {
        return false;
      }
      d = d.substring(dot + 1);
    }
  }

  private static Set<String> load() {
    Set<String> out = new HashSet<>();
    try (InputStream in = VerifiedDomains.class.getResourceAsStream(LIST_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing " + LIST_RESOURCE);
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        String entry = line.strip().toLowerCase(Locale.ROOT);
        if (!entry.isEmpty() && !entry.startsWith("#")) {
          out.add(entry);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Set.copyOf(out);
  }
}
