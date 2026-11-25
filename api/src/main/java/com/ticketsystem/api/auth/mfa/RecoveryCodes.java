package com.ticketsystem.api.auth.mfa;

import com.ticketsystem.api.auth.SecureTokens;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One-time recovery codes: 10 per enrolment, each 12 characters of Crockford base32 (60 random
 * bits) shown as {@code XXXX-XXXX-XXXX}. Only a SHA-256 of the user id and the normalised code is
 * stored; at 60 bits per code an offline search of a stolen hash is impractical. Input is
 * forgiving: case, spaces and hyphens are ignored, and O/I/L read as 0/1/1.
 */
public final class RecoveryCodes {

  public static final int COUNT = 10;
  static final int LENGTH = 12;
  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  private static final SecureRandom RANDOM = new SecureRandom();

  private RecoveryCodes() {}

  /** Ten new codes in display form. */
  public static List<String> generate() {
    List<String> codes = new ArrayList<>(COUNT);
    for (int i = 0; i < COUNT; i++) {
      StringBuilder code = new StringBuilder(LENGTH + 2);
      for (int j = 0; j < LENGTH; j++) {
        if (j > 0 && j % 4 == 0) {
          code.append('-');
        }
        code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      }
      codes.add(code.toString());
    }
    return codes;
  }

  /** The canonical 12 characters, or empty when the input cannot be a recovery code. */
  public static Optional<String> normalise(String input) {
    if (input == null || input.length() > 40) {
      return Optional.empty();
    }
    String s =
        input
            .toUpperCase(Locale.ROOT)
            .replace(" ", "")
            .replace("-", "")
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1');
    if (s.length() != LENGTH) {
      return Optional.empty();
    }
    for (int i = 0; i < s.length(); i++) {
      if (ALPHABET.indexOf(s.charAt(i)) < 0) {
        return Optional.empty();
      }
    }
    return Optional.of(s);
  }

  /** The stored hash of a normalised code for a user. */
  public static String hash(long userId, String normalised) {
    return SecureTokens.sha256Hex("rc:" + userId + ":" + normalised);
  }
}
