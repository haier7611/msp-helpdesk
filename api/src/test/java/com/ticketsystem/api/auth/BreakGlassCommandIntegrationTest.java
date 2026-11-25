package com.ticketsystem.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.TestcontainersConfiguration;
import com.ticketsystem.api.TicketSystemApplication;
import com.ticketsystem.api.auth.mfa.BreakGlass;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The break-glass MFA reset through its real entry point (audit PR #9, M1): {@link BreakGlass#run}
 * with the operator's arguments, which starts the application without a web server ({@code
 * WebApplicationType.NONE}) against a real Postgres, exactly as {@code java -jar app.jar reset-mfa
 * --email=...} does from {@link TicketSystemApplication#main}. Proves the non-web context starts
 * (no servlet-only beans), the reset happens and is audited, and nothing secret is printed.
 */
class BreakGlassCommandIntegrationTest {

  private static PostgreSQLContainer pg;
  private static JdbcTemplate owner;

  @BeforeAll
  static void database() {
    pg = TestcontainersConfiguration.sharedContainer();
    owner = TestcontainersConfiguration.ownerJdbc();
  }

  /** Runs the command as the operator would, capturing what it prints. */
  private record Result(int exitCode, String printed) {}

  private static Result run(String... commandArgs) {
    // Database settings come from the environment in production; here they are synthetic
    // test-only values passed as Spring arguments.
    String[] connection = {
      "--spring.datasource.url=" + pg.getJdbcUrl(),
      "--spring.datasource.username=ticketsys_app",
      "--spring.datasource.password=" + TestcontainersConfiguration.APP_ROLE_TEST_PASSWORD,
      "--spring.flyway.user=" + pg.getUsername(),
      "--spring.flyway.password=" + pg.getPassword()
    };
    String[] args = new String[commandArgs.length + connection.length];
    System.arraycopy(commandArgs, 0, args, 0, commandArgs.length);
    System.arraycopy(connection, 0, args, commandArgs.length, connection.length);
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      int code = BreakGlass.run(TicketSystemApplication.class, args);
      return new Result(code, buffer.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(original);
    }
  }

  private static long insertSupporter(String email) {
    Timestamp now = Timestamp.from(Instant.now());
    return owner.queryForObject(
        "INSERT INTO app_user (email, name, role, status, password_hash, created_at, updated_at)"
            + " VALUES (?, 'Sam Example', 'ADMIN', 'ACTIVE', '{argon2}not-a-real-hash', ?, ?)"
            + " RETURNING id",
        Long.class,
        email,
        now,
        now);
  }

  private static int count(String sql, Object... args) {
    return owner.queryForObject(sql, Integer.class, args);
  }

  @Test
  void resetMfaThroughTheCommandEntryPoint() {
    // First run also migrates a fresh database; an unknown address changes nothing (exit 2).
    String nobody = "nobody-" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
    Result unknown = run(BreakGlass.COMMAND, "--email=" + nobody);
    assertThat(unknown.exitCode()).isEqualTo(2);
    assertThat(unknown.printed()).contains("no agent or admin").doesNotContain(nobody);

    String email = "admin-" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
    long id = insertSupporter(email);
    Instant now = Instant.now();
    owner.update(
        "INSERT INTO user_mfa (user_id, secret_ciphertext, secret_key_id, confirmed_at,"
            + " updated_at) VALUES (?, ?, 'test1', ?, ?)",
        id,
        new byte[] {1, 2, 3},
        Timestamp.from(now),
        Timestamp.from(now));
    owner.update(
        "INSERT INTO mfa_recovery_code (user_id, code_hash, created_at) VALUES (?, ?, ?)",
        id,
        "a".repeat(64),
        Timestamp.from(now));
    owner.update(
        "INSERT INTO user_device (user_id, token_hash, label, created_at, last_used_at,"
            + " trusted_until) VALUES (?, ?, 'Firefox on Linux', ?, ?, ?)",
        id,
        "b".repeat(64),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now.plus(30, ChronoUnit.DAYS)));
    String sessionId = UUID.randomUUID().toString();
    owner.update(
        "INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,"
            + " max_inactive_interval, expiry_time, principal_name) VALUES (?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID().toString(),
        sessionId,
        now.toEpochMilli(),
        now.toEpochMilli(),
        28800,
        now.plus(8, ChronoUnit.HOURS).toEpochMilli(),
        Long.toString(id));
    int versionBefore = count("SELECT security_version FROM app_user WHERE id = ?", id);
    int outboxBefore =
        count("SELECT count(*) FROM outbox_message WHERE to_addresses = ?", email.toLowerCase());

    // The operator may type the address in any case.
    Result reset = run(BreakGlass.COMMAND, "--email=" + email.toUpperCase());

    assertThat(reset.exitCode()).isZero();
    assertThat(reset.printed())
        .contains("reset-mfa: two-step sign-in reset for user id " + id)
        .doesNotContainIgnoringCase(email);
    assertThat(count("SELECT count(*) FROM user_mfa WHERE user_id = ?", id)).isZero();
    assertThat(count("SELECT count(*) FROM mfa_recovery_code WHERE user_id = ?", id)).isZero();
    assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", "" + id))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM user_device WHERE user_id = ? AND trusted_until IS NOT NULL",
                id))
        .isZero();
    assertThat(count("SELECT security_version FROM app_user WHERE id = ?", id))
        .isGreaterThan(versionBefore);
    assertThat(
            owner.queryForList(
                "SELECT actor_user_id, details->>'method' AS method FROM audit_event"
                    + " WHERE type = 'MFA_RESET' AND target_user_id = ?",
                id))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.get("actor_user_id")).isNull();
              assertThat(e.get("method")).isEqualTo("break_glass");
            });
    // The notice is queued for the running application to send.
    assertThat(
            count(
                "SELECT count(*) FROM outbox_message WHERE to_addresses = ?", email.toLowerCase()))
        .isEqualTo(outboxBefore + 1);
  }

  @Test
  void withoutAnEmailItPrintsUsageAndStartsNothing() {
    Result r = run(BreakGlass.COMMAND);
    assertThat(r.exitCode()).isEqualTo(64);
    assertThat(r.printed()).contains("usage: java -jar app.jar reset-mfa --email=");
  }
}
