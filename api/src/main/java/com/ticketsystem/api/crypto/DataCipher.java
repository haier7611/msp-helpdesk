package com.ticketsystem.api.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encryption at rest for small secrets (MFA secrets now, mailbox credentials and the Entra client
 * secret later). AES-256-GCM with a random 96-bit IV per value; the stored form is {@code IV ||
 * ciphertext || tag}. Every value is stored next to the id of the key that encrypted it, so a new
 * key can be introduced while old values still decrypt (key rotation). The caller passes associated
 * data that binds a ciphertext to its row (for example the user id), so a value copied into another
 * row does not decrypt.
 *
 * <p>Keys come from the server environment only: {@code TS_DATA_KEY} (64 hex characters = 256 bits)
 * with id {@code TS_DATA_KEY_ID} (default {@code k1}), plus optional retired keys for decryption in
 * {@code TS_DATA_OLD_KEYS} as {@code id:hex,id:hex}. The app refuses to start without a valid
 * current key. Keys are never logged.
 */
@Component
public class DataCipher {

  /** A ciphertext and the id of the key that made it. */
  public record Sealed(byte[] ciphertext, String keyId) {}

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final String currentKeyId;
  private final Map<String, SecretKey> keys = new LinkedHashMap<>();

  public DataCipher(
      @Value("${app.crypto.data-key:}") String currentKeyHex,
      @Value("${app.crypto.data-key-id:k1}") String currentKeyId,
      @Value("${app.crypto.old-keys:}") String oldKeys) {
    this.currentKeyId = validId(currentKeyId);
    keys.put(this.currentKeyId, key(currentKeyHex, "TS_DATA_KEY"));
    if (oldKeys != null && !oldKeys.isBlank()) {
      for (String entry : oldKeys.split(",")) {
        int colon = entry.indexOf(':');
        if (colon <= 0) {
          throw new IllegalStateException("TS_DATA_OLD_KEYS must be id:hex,id:hex");
        }
        String id = validId(entry.substring(0, colon).trim());
        if (!keys.containsKey(id)) {
          keys.put(id, key(entry.substring(colon + 1).trim(), "TS_DATA_OLD_KEYS"));
        }
      }
    }
  }

  public String currentKeyId() {
    return currentKeyId;
  }

  public Sealed encrypt(byte[] plaintext, String associatedData) {
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentKeyId), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      byte[] body = cipher.doFinal(plaintext);
      return new Sealed(
          ByteBuffer.allocate(iv.length + body.length).put(iv).put(body).array(), currentKeyId);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("encryption failed", e);
    }
  }

  /** Decrypts; throws when the key is unknown or the value or its associated data was changed. */
  public byte[] decrypt(Sealed sealed, String associatedData) {
    SecretKey key = keys.get(sealed.keyId());
    if (key == null) {
      throw new IllegalStateException("no data key with id " + sealed.keyId());
    }
    byte[] all = sealed.ciphertext();
    if (all == null || all.length <= IV_BYTES) {
      throw new IllegalStateException("ciphertext too short");
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("decryption failed", e);
    }
  }

  private static String validId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}")) {
      throw new IllegalStateException("data key ids must be 1-32 letters, digits, _ or -");
    }
    return id;
  }

  private static SecretKey key(String hex, String name) {
    if (hex == null || !hex.matches("[0-9a-fA-F]{64}")) {
      // The value itself is never put in the message.
      throw new IllegalStateException(
          name + " must be 64 hex characters (openssl rand -hex 32); the value is not logged");
    }
    return new SecretKeySpec(HexFormat.of().parseHex(hex), "AES");
  }
}
