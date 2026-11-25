package com.ticketsystem.api.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.crypto.DataCipher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TOTP maths (RFC 6238 vectors), recovery codes, the data cipher and the QR encoding. */
class MfaUnitTest {

  private static final byte[] RFC_SECRET =
      "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  @Test
  void totpMatchesTheRfc6238Sha1TestVectors() {
    assertThat(Totp.codeAt(RFC_SECRET, 59 / 30, 8, "HmacSHA1")).isEqualTo("94287082");
    assertThat(Totp.codeAt(RFC_SECRET, 1111111109L / 30, 8, "HmacSHA1")).isEqualTo("07081804");
    assertThat(Totp.codeAt(RFC_SECRET, 1234567890L / 30, 8, "HmacSHA1")).isEqualTo("89005924");
    assertThat(Totp.codeAt(RFC_SECRET, 20000000000L / 30, 8, "HmacSHA1")).isEqualTo("65353130");
    // The 6-digit code is the last six digits of the same value.
    assertThat(Totp.codeAt(RFC_SECRET, 59 / 30)).isEqualTo("287082");
  }

  @Test
  void aCodeMatchesOnlyWithinOneStepEitherSide() {
    byte[] secret = Totp.newSecret();
    Instant now = Instant.parse("2026-11-18T10:00:15Z");
    long step = Totp.stepAt(now);
    assertThat(Totp.matchingStep(secret, Totp.codeAt(secret, step), now)).hasValue(step);
    assertThat(Totp.matchingStep(secret, Totp.codeAt(secret, step - 1), now)).hasValue(step - 1);
    assertThat(Totp.matchingStep(secret, Totp.codeAt(secret, step + 1), now)).hasValue(step + 1);
    assertThat(Totp.matchingStep(secret, Totp.codeAt(secret, step - 2), now)).isEmpty();
    assertThat(Totp.matchingStep(secret, Totp.codeAt(secret, step + 2), now)).isEmpty();
    // Spaces are tolerated; anything that is not six digits is not.
    String code = Totp.codeAt(secret, step);
    assertThat(Totp.matchingStep(secret, code.substring(0, 3) + " " + code.substring(3), now))
        .hasValue(step);
    assertThat(Totp.matchingStep(secret, "12345", now)).isEmpty();
    assertThat(Totp.matchingStep(secret, "abcdef", now)).isEmpty();
    assertThat(Totp.matchingStep(secret, null, now)).isEmpty();
  }

  @Test
  void base32AndOtpauthUriAreWhatAuthenticatorAppsExpect() {
    assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    assertThat(Totp.base32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    assertThat(Totp.otpauthUri("Example MSP Support", "sam@example.test", RFC_SECRET))
        .isEqualTo(
            "otpauth://totp/Example%20MSP%20Support:sam%40example.test"
                + "?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example%20MSP%20Support"
                + "&algorithm=SHA1&digits=6&period=30");
  }

  @Test
  void recoveryCodesAreTenDistinctSixtyBitCodes() {
    List<String> codes = RecoveryCodes.generate();
    assertThat(codes).hasSize(10);
    assertThat(new HashSet<>(codes)).hasSize(10);
    assertThat(codes)
        .allMatch(
            c -> c.matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}"));
  }

  @Test
  void recoveryCodeInputIsForgivingButStrict() {
    assertThat(RecoveryCodes.normalise("abcd-efgh-jkmn")).hasValue("ABCDEFGHJKMN");
    assertThat(RecoveryCodes.normalise(" ab cd ef gh jk mn ")).hasValue("ABCDEFGHJKMN");
    assertThat(RecoveryCodes.normalise("O0IL-0000-0000")).hasValue("001100000000");
    assertThat(RecoveryCodes.normalise("ABCD-EFGH-JKM")).isEmpty();
    assertThat(RecoveryCodes.normalise("ABCD-EFGH-JKMU")).isEmpty();
    assertThat(RecoveryCodes.normalise(null)).isEmpty();
    assertThat(RecoveryCodes.hash(1, "ABCDEFGHJKMN"))
        .isNotEqualTo(RecoveryCodes.hash(2, "ABCDEFGHJKMN"));
  }

  private static final String KEY_A =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final String KEY_B =
      "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

  @Test
  void dataCipherRoundTripsAndBindsTheValueToItsRow() {
    DataCipher cipher = new DataCipher(KEY_A, "k1", "");
    byte[] secret = Totp.newSecret();
    DataCipher.Sealed sealed = cipher.encrypt(secret, "user_mfa:7");
    assertThat(sealed.keyId()).isEqualTo("k1");
    assertThat(sealed.ciphertext()).hasSize(12 + secret.length + 16);
    assertThat(cipher.decrypt(sealed, "user_mfa:7")).isEqualTo(secret);
    // Same plaintext encrypts differently every time (random IV).
    assertThat(cipher.encrypt(secret, "user_mfa:7").ciphertext()).isNotEqualTo(sealed.ciphertext());
    // Copied to another user's row it does not decrypt.
    assertThatThrownBy(() -> cipher.decrypt(sealed, "user_mfa:8"))
        .isInstanceOf(IllegalStateException.class);
    // A flipped bit is detected.
    byte[] tampered = sealed.ciphertext().clone();
    tampered[tampered.length - 1] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(new DataCipher.Sealed(tampered, "k1"), "user_mfa:7"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void dataCipherKeepsOldKeysReadableForRotation() {
    DataCipher old = new DataCipher(KEY_A, "k1", "");
    DataCipher.Sealed sealed = old.encrypt(new byte[] {1, 2, 3}, "x");
    DataCipher rotated = new DataCipher(KEY_B, "k2", "k1:" + KEY_A);
    assertThat(rotated.decrypt(sealed, "x")).containsExactly(1, 2, 3);
    assertThat(rotated.encrypt(new byte[] {1}, "x").keyId()).isEqualTo("k2");
    assertThatThrownBy(() -> new DataCipher(KEY_B, "k2", "").decrypt(sealed, "x"))
        .hasMessageContaining("no data key with id k1");
  }

  @Test
  void dataCipherRefusesAMissingOrShortKeyWithoutPrintingIt() {
    assertThatThrownBy(() -> new DataCipher("", "k1", ""))
        .hasMessageContaining("TS_DATA_KEY must be 64 hex characters");
    assertThatThrownBy(() -> new DataCipher("abc123", "k1", "")).hasMessageNotContaining("abc123");
    assertThatThrownBy(() -> new DataCipher(KEY_A, "bad id!", ""))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void qrCodeIsSquarePathDataOnly() {
    QrCode qr = QrCode.of("otpauth://totp/X:y?secret=ABC");
    assertThat(qr.size()).isGreaterThanOrEqualTo(21 + 8);
    assertThat(qr.path()).matches("(M\\d+ \\d+h1v1h-1z)+");
  }
}
