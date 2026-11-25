package com.ticketsystem.api.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.TestcontainersConfiguration;
import com.ticketsystem.api.TicketSystemApplication;
import com.ticketsystem.api.auth.entra.EntraSettingsService;
import com.ticketsystem.api.auth.mfa.MfaService;
import com.ticketsystem.api.crypto.job.DataKeyRotation;
import com.ticketsystem.api.mail.intake.MailboxService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The data-key rotation command through its real entry point (WP-12, spec section 10), against its
 * own database in the shared Postgres container (so rotating does not touch values other tests
 * encrypted with the test key). Proves every kind of stored secret moves to the new key id and
 * still decrypts with only the new key; a wrong or missing old key leaves values untouched and
 * exits 3 (fails safe); {@code --check} changes nothing; nothing secret is printed.
 */
class DataKeyRotationCommandIntegrationTest {

  // Synthetic, test-only keys (not secrets).
  static final String K1 = "1111111111111111111111111111111111111111111111111111111111111111";
  static final String K2 = "2222222222222222222222222222222222222222222222222222222222222222";
  static final String WRONG = "3333333333333333333333333333333333333333333333333333333333333333";
  static final String DB = "ticketsys_rotation_test";

  private static PostgreSQLContainer pg;
  private static JdbcTemplate db;
  private static String url;

  @BeforeAll
  static void database() {
    pg = TestcontainersConfiguration.sharedContainer();
    JdbcTemplate admin = TestcontainersConfiguration.ownerJdbc();
    admin.execute("DROP DATABASE IF EXISTS " + DB);
    admin.execute("CREATE DATABASE " + DB);
    url = pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + DB + "$1");
    db = new JdbcTemplate(new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword()));
  }

  private record Result(int exitCode, String printed) {}

  private static Result run(String newKey, String newId, String oldKeys, String... extra) {
    String[] base = {
      DataKeyRotation.COMMAND,
      "--spring.datasource.url=" + url,
      "--spring.datasource.username=ticketsys_app",
      "--spring.datasource.password=" + TestcontainersConfiguration.APP_ROLE_TEST_PASSWORD,
      "--spring.flyway.user=" + pg.getUsername(),
      "--spring.flyway.password=" + pg.getPassword(),
      "--app.crypto.data-key=" + newKey,
      "--app.crypto.data-key-id=" + newId,
      "--app.crypto.old-keys=" + oldKeys
    };
    String[] args = new String[base.length + extra.length];
    System.arraycopy(base, 0, args, 0, base.length);
    System.arraycopy(extra, 0, args, base.length, extra.length);
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      int code = DataKeyRotation.run(TicketSystemApplication.class, args);
      return new Result(code, buffer.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(original);
    }
  }

  private static long insertUser() {
    Timestamp now = Timestamp.from(Instant.now());
    return db.queryForObject(
        "INSERT INTO app_user (email, name, role, status, password_hash, created_at, updated_at)"
            + " VALUES (?, 'Sam Example', 'AGENT', 'ACTIVE', '{argon2}x', ?, ?) RETURNING id",
        Long.class,
        "rot-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test",
        now,
        now);
  }

  private static long insertMailbox(DataCipher cipher, String secret) {
    long id =
        db.queryForObject(
            "INSERT INTO mailbox (name, address, imap_host, imap_port, imap_security, auth_type,"
                + " username, created_at, updated_at) VALUES ('Support', 'support@helpdesk.example',"
                + " 'imap.example.test', 993, 'TLS', 'PASSWORD', 'support', now(), now())"
                + " RETURNING id",
            Long.class);
    DataCipher.Sealed s =
        cipher.encrypt(secret.getBytes(StandardCharsets.UTF_8), MailboxService.aad(id));
    db.update(
        "UPDATE mailbox SET secret_ciphertext = ?, secret_key_id = ? WHERE id = ?",
        s.ciphertext(),
        s.keyId(),
        id);
    return id;
  }

  private static DataCipher.Sealed stored(String table, String idCol, String col, long id) {
    Map<String, Object> row =
        db.queryForMap(
            "SELECT "
                + col
                + "_ciphertext AS c, "
                + col
                + "_key_id AS k FROM "
                + table
                + " WHERE "
                + idCol
                + " = ?",
            id);
    return new DataCipher.Sealed((byte[]) row.get("c"), (String) row.get("k"));
  }

  @Test
  void rotatesEverySecretToTheNewKeyAndFailsSafeOnAWrongKey() {
    // First run against the empty database migrates it (Flyway as owner) and finds nothing.
    assertThat(run(K1, "k1", "").exitCode()).isZero();

    DataCipher k1 = new DataCipher(K1, "k1", "");
    long user = insertUser();
    byte[] totp = "synthetic-totp-secret".getBytes(StandardCharsets.UTF_8);
    DataCipher.Sealed mfa = k1.encrypt(totp, MfaService.aad(user));
    DataCipher.Sealed pending = k1.encrypt("pending".getBytes(), MfaService.aad(user));
    db.update(
        "INSERT INTO user_mfa (user_id, secret_ciphertext, secret_key_id, pending_ciphertext,"
            + " pending_key_id, confirmed_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
        user,
        mfa.ciphertext(),
        mfa.keyId(),
        pending.ciphertext(),
        pending.keyId());
    long mailbox = insertMailbox(k1, "synthetic-mailbox-password");
    DataCipher.Sealed entra =
        k1.encrypt("synthetic-entra-secret".getBytes(), EntraSettingsService.SECRET_AAD);
    db.update(
        "UPDATE entra_settings SET secret_ciphertext = ?, secret_key_id = ? WHERE id = 1",
        entra.ciphertext(),
        entra.keyId());

    byte[] mailboxBefore = stored("mailbox", "id", "secret", mailbox).ciphertext();
    // A wrong old key: nothing changes, exit 3.
    Result wrong = run(K2, "k2", "k1:" + WRONG);
    assertThat(wrong.exitCode()).isEqualTo(DataKeyRotation.UNREADABLE);
    assertThat(wrong.printed()).contains("4 could not be decrypted and were left unchanged");
    assertThat(stored("mailbox", "id", "secret", mailbox).keyId()).isEqualTo("k1");
    assertThat(stored("mailbox", "id", "secret", mailbox).ciphertext()).isEqualTo(mailboxBefore);
    assertThat(k1.decrypt(stored("user_mfa", "user_id", "secret", user), MfaService.aad(user)))
        .isEqualTo(totp);

    // The old key missing altogether: also left unchanged.
    assertThat(run(K2, "k2", "").exitCode()).isEqualTo(DataKeyRotation.UNREADABLE);
    assertThat(stored("user_mfa", "user_id", "secret", user).keyId()).isEqualTo("k1");

    // --check reports and changes nothing.
    Result check = run(K2, "k2", "k1:" + K1, "--check");
    assertThat(check.exitCode()).isEqualTo(DataKeyRotation.UNREADABLE);
    assertThat(check.printed()).contains("MFA_SECRET key k1: 1").contains("still on an older key");
    assertThat(stored("user_mfa", "user_id", "secret", user).keyId()).isEqualTo("k1");

    // The real rotation.
    Result ok = run(K2, "k2", "k1:" + K1);
    assertThat(ok.exitCode()).isZero();
    assertThat(ok.printed())
        .contains("re-encrypted 4 value(s)")
        .contains("every stored secret is on key k2")
        .doesNotContain(K1)
        .doesNotContain(K2)
        .doesNotContain("synthetic");

    // Only the new key is needed now; the old one alone no longer opens anything.
    DataCipher k2only = new DataCipher(K2, "k2", "");
    assertThat(k2only.decrypt(stored("user_mfa", "user_id", "secret", user), MfaService.aad(user)))
        .isEqualTo(totp);
    assertThat(k2only.decrypt(stored("user_mfa", "user_id", "pending", user), MfaService.aad(user)))
        .isEqualTo("pending".getBytes());
    assertThat(
            new String(
                k2only.decrypt(
                    stored("mailbox", "id", "secret", mailbox), MailboxService.aad(mailbox)),
                StandardCharsets.UTF_8))
        .isEqualTo("synthetic-mailbox-password");
    assertThat(
            k2only.decrypt(
                stored("entra_settings", "id", "secret", 1), EntraSettingsService.SECRET_AAD))
        .isEqualTo("synthetic-entra-secret".getBytes());
    assertThatThrownBy(
            () ->
                k1.decrypt(stored("mailbox", "id", "secret", mailbox), MailboxService.aad(mailbox)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_event WHERE type = 'DATA_KEY_ROTATED' AND"
                    + " details->>'key_id' = 'k2'",
                Integer.class))
        .isOne();

    // Running it again is harmless: nothing left to do, and the app starts without the old key.
    Result again = run(K2, "k2", "");
    assertThat(again.exitCode()).isZero();
    assertThat(again.printed()).contains("re-encrypted 0 value(s)");
  }

  @Test
  void unknownArgumentsPrintUsage() {
    Result r = run(K1, "k1", "", "--now");
    assertThat(r.exitCode()).isEqualTo(DataKeyRotation.USAGE);
    assertThat(r.printed()).contains("usage: java -jar app.jar rotate-data-key [--check]");
  }
}
