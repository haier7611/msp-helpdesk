package com.ticketsystem.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Random link tokens and codes, and their hashes (C7). Tokens are 256-bit, URL-safe base64 without
 * padding; only {@link #sha256Hex} of a token is ever stored. Hash comparisons use {@link
 * MessageDigest#isEqual} (constant time).
 */
public final class SecureTokens {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  private SecureTokens() {}

  /** A new 256-bit random token, 43 URL-safe characters. */
  public static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return URL.encodeToString(bytes);
  }

  /** Lower-case hex SHA-256 of the UTF-8 bytes. */
  public static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** Constant-time comparison of two strings (e.g. hex hashes or the setup token). */
  public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  /** True for a value shaped like one of our tokens (cheap pre-check before hashing). */
  public static boolean looksLikeToken(String value) {
    return value != null && value.length() == 43 && value.matches("[A-Za-z0-9_-]{43}");
  }
}
