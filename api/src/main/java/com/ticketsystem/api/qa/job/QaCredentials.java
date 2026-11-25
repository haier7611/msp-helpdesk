package com.ticketsystem.api.qa.job;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The QA credentials sheet: one line per seeded account, written only to a mode-600 file and never
 * printed or logged. The file is the only place the generated passwords and recovery codes exist
 * (the database holds hashes), so a re-run reads it back to keep what it cannot regenerate.
 *
 * <p>Line format: {@code account|email|password|role|organisation|recovery codes (space
 * separated)}. Lines starting with {@code #} are comments.
 */
final class QaCredentials {

  /** Shown instead of a password when the account exists but the sheet has no entry for it. */
  static final String UNKNOWN_PASSWORD = "(not recorded - run with --reset-passwords)";

  record Entry(
      String email, String password, String role, String organisation, List<String> codes) {

    @Override
    public String toString() {
      return "Entry[" + role + "]";
    }
  }

  private final Map<String, Entry> entries = new LinkedHashMap<>();

  Entry get(String email) {
    return entries.get(email);
  }

  void put(Entry entry) {
    entries.put(entry.email(), entry);
  }

  /** Reads an existing sheet; a missing file is an empty sheet. */
  static QaCredentials read(Path file) throws IOException {
    QaCredentials sheet = new QaCredentials();
    if (!Files.isRegularFile(file)) {
      return sheet;
    }
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (!line.startsWith("account|")) {
        continue;
      }
      String[] p = line.split("\\|", -1);
      if (p.length < 6) {
        continue;
      }
      List<String> codes = new ArrayList<>();
      for (String c : p[5].strip().split("\\s+")) {
        if (!c.isEmpty()) {
          codes.add(c);
        }
      }
      sheet.put(new Entry(p[1], p[2], p[3], p[4], List.copyOf(codes)));
    }
    return sheet;
  }

  /** Writes the sheet atomically with mode 600 (the temporary file is created 600 too). */
  void write(Path file, Instant now) throws IOException {
    Path dir = file.toAbsolutePath().getParent();
    Set<PosixFilePermission> mode = PosixFilePermissions.fromString("rw-------");
    Path tmp =
        Files.createTempFile(
            dir, ".qa-credentials-", ".tmp", PosixFilePermissions.asFileAttribute(mode));
    try {
      StringBuilder sb = new StringBuilder();
      sb.append(
          "# Ticket System QA credentials - synthetic accounts only. Keep root-only (mode 600).\n");
      sb.append("# Never paste into tickets, comments, chat or the repo.\n");
      sb.append("# Written ").append(now).append(".\n");
      sb.append(
          "# account|email|password|role|organisation|recovery codes (one used per sign-in)\n");
      for (Entry e : entries.values()) {
        sb.append("account|")
            .append(e.email())
            .append('|')
            .append(e.password())
            .append('|')
            .append(e.role())
            .append('|')
            .append(e.organisation())
            .append('|')
            .append(String.join(" ", e.codes()))
            .append('\n');
      }
      Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      Files.setPosixFilePermissions(file, mode);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
