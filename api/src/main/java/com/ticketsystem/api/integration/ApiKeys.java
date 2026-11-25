package com.ticketsystem.api.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The integrations API key format: {@code tsk_<key id>_<secret>}, where the key id is 8 random
 * lower-case letters or digits (the visible prefix, stored in clear to find the row) and the secret
 * is 32 random bytes in unpadded base64url (43 characters). Only SHA-256 of the whole key string is
 * stored. No HMAC (C16).
 */
public final class ApiKeys {

  static final String PREFIX = "tsk_";
  private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
  private static final Pattern FORMAT = Pattern.compile("^tsk_([a-z0-9]{8})_([A-Za-z0-9_-]{43})$");
  private static final SecureRandom RANDOM = new SecureRandom();

  /** A freshly generated key: the full string (shown once) and what is stored. */
  public record Generated(String keyId, String fullKey, byte[] hash) {
    /** The visible prefix shown in lists, e.g. {@code tsk_ab12cd34}. */
    public String displayPrefix() {
      return PREFIX + keyId;
    }

    @Override
    public String toString() {
      return "Generated[keyId=" + keyId + "]"; // never the key
    }
  }

  private ApiKeys() {}

  public static Generated generate() {
    StringBuilder id = new StringBuilder(8);
    for (int i = 0; i < 8; i++) {
      id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
    }
    byte[] secret = new byte[32];
    RANDOM.nextBytes(secret);
    String full =
        PREFIX + id + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    return new Generated(id.toString(), full, hash(full));
  }

  /** The key id of a well-formed key, or empty (no lookup is made for a malformed key). */
  public static Optional<String> keyIdOf(String presented) {
    if (presented == null || presented.length() > 64) {
      return Optional.empty();
    }
    Matcher m = FORMAT.matcher(presented);
    return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
  }

  public static byte[] hash(String fullKey) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(fullKey.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Constant-time comparison of two hashes. */
  public static boolean matches(byte[] storedHash, String presented) {
    return MessageDigest.isEqual(storedHash, hash(presented));
  }
}
