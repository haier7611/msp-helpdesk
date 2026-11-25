package com.ticketsystem.api.auth.mfa;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238 over RFC 4226 HOTP): HMAC-SHA1, 6 digits, 30-second steps, accepting the current
 * step and one either side for clock drift - the parameters every authenticator app supports. Plain
 * JDK code (the same algorithm as {@code dev.samstevens.totp}, without its QR and commons
 * dependencies); checked against the RFC 6238 test vectors. Replay protection is the caller's job:
 * {@link #matchingStep} returns the step a code matched so it can be accepted only once.
 */
public final class Totp {

  public static final int DIGITS = 6;
  public static final long STEP_SECONDS = 30;
  public static final int SECRET_BYTES = 20;
  private static final int WINDOW = 1;
  private static final int[] POW10 = {
    1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000
  };
  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();

  private Totp() {}

  /** A new random 160-bit secret (the RFC 4226 recommended length). */
  public static byte[] newSecret() {
    byte[] secret = new byte[SECRET_BYTES];
    RANDOM.nextBytes(secret);
    return secret;
  }

  public static long stepAt(Instant instant) {
    return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
  }

  /**
   * The time step within +-1 of {@code now} whose code equals {@code code}, if any. Every candidate
   * is compared in constant time and all three are always computed.
   */
  public static OptionalLong matchingStep(byte[] secret, String code, Instant now) {
    if (code == null) {
      return OptionalLong.empty();
    }
    String digits = code.replace(" ", "").replace("-", "");
    if (!digits.matches("\\d{" + DIGITS + "}")) {
      return OptionalLong.empty();
    }
    long current = stepAt(now);
    long found = Long.MIN_VALUE;
    for (long step = current - WINDOW; step <= current + WINDOW; step++) {
      boolean equal =
          MessageDigest.isEqual(
              codeAt(secret, step).getBytes(StandardCharsets.US_ASCII),
              digits.getBytes(StandardCharsets.US_ASCII));
      if (equal && found == Long.MIN_VALUE) {
        found = step;
      }
    }
    return found == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(found);
  }

  /** The 6-digit code for a time step (RFC 4226 dynamic truncation). */
  public static String codeAt(byte[] secret, long step) {
    return codeAt(secret, step, DIGITS, "HmacSHA1");
  }

  static String codeAt(byte[] secret, long step, int digits, String algorithm) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(secret, algorithm));
      byte[] counter = new byte[8];
      long value = step;
      for (int i = 7; i >= 0; i--) {
        counter[i] = (byte) (value & 0xff);
        value >>>= 8;
      }
      byte[] hash = mac.doFinal(counter);
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      int otp = binary % POW10[digits];
      return String.format("%0" + digits + "d", otp);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(algorithm + " not available", e);
    }
  }

  /** RFC 4648 base32 without padding - the "manual key" an authenticator app accepts. */
  public static String base32(byte[] bytes) {
    StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
    int buffer = 0;
    int bits = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
    }
    return out.toString();
  }

  /** The {@code otpauth://} URI the QR code carries. */
  public static String otpauthUri(String issuer, String account, byte[] secret) {
    String label = enc(issuer) + ":" + enc(account);
    return "otpauth://totp/"
        + label
        + "?secret="
        + base32(secret)
        + "&issuer="
        + enc(issuer)
        + "&algorithm=SHA1&digits="
        + DIGITS
        + "&period="
        + STEP_SECONDS;
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
