package com.ticketsystem.api.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;

/**
 * An offline list of breached passwords (C15): no outbound call, no third party sees anything. The
 * resource holds the 72,957 entries of 12+ characters from three public lists in SecLists (MIT):
 * the NCSC top 100k, xato-net top 1M and Pwdb top 1M, lower-cased and de-duplicated (shorter
 * entries are refused by the length rule anyway). Comparison ignores case. Source and rebuild
 * steps: docs/35-security.md.
 */
@Component
public class BreachedPasswordList {

  static final String RESOURCE = "security/breached-passwords-12plus.txt.gz";

  private final Set<String> entries;

  public BreachedPasswordList() {
    this.entries = load();
  }

  public boolean contains(String password) {
    return entries.contains(password.toLowerCase(Locale.ROOT));
  }

  int size() {
    return entries.size();
  }

  private static Set<String> load() {
    InputStream raw = BreachedPasswordList.class.getClassLoader().getResourceAsStream(RESOURCE);
    if (raw == null) {
      throw new IllegalStateException("breached password list missing: " + RESOURCE);
    }
    Set<String> set = new HashSet<>(110_000);
    try (BufferedReader in =
        new BufferedReader(
            new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        if (!line.isEmpty()) {
          set.add(line.toLowerCase(Locale.ROOT));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + RESOURCE, e);
    }
    return Set.copyOf(set);
  }
}
