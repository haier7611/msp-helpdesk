package com.ticketsystem.api.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.TestcontainersConfiguration;
import com.ticketsystem.api.TicketSystemApplication;
import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.PrincipalAccessReader;
import com.ticketsystem.api.qa.job.QaCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The QA seed and wipe commands (ADR-030) through their real entry point, against their own
 * database in the shared Postgres container: idempotent seeding, credentials only in a mode-600
 * file (nothing secret on stdout), supporter MFA with 10 recovery codes, the restricted engineer's
 * scope, password rotation only on request, and a wipe that removes only what the seed created.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QaCommandsIntegrationTest {

  static final String DB = "ticketsys_qa_seed_test";

  private static PostgreSQLContainer pg;
  private static String url;
  private static JdbcTemplate db;
  private static long bystanderAdmin;
  private static long bystanderOrg;

  @TempDir static Path dir;

  private record Result(int exitCode, String printed) {}

  @BeforeAll
  static void database() {
    pg = TestcontainersConfiguration.sharedContainer();
    JdbcTemplate owner = TestcontainersConfiguration.ownerJdbc();
    owner.execute("DROP DATABASE IF EXISTS " + DB);
    owner.execute("CREATE DATABASE " + DB);
    url = pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + DB + "$1");
    db = new JdbcTemplate(new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword()));
  }

  private static Result run(String... commandArgs) {
    String[] connection = {
      "--spring.datasource.url=" + url,
      "--spring.datasource.username=ticketsys_app",
      "--spring.datasource.password=" + TestcontainersConfiguration.APP_ROLE_TEST_PASSWORD,
      "--spring.flyway.user=" + pg.getUsername(),
      "--spring.flyway.password=" + pg.getPassword(),
      "--app.attachments-dir=" + dir.resolve("attachments")
    };
    List<String> args = new ArrayList<>(List.of(commandArgs));
    args.addAll(List.of(connection));
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      int code = QaCommand.run(TicketSystemApplication.class, args.toArray(String[]::new));
      return new Result(code, buffer.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(original);
    }
  }

  private static int count(String sql, Object... args) {
    return db.queryForObject(sql, Integer.class, args);
  }

  private static Map<String, String[]> sheet(Path file) throws Exception {
    Map<String, String[]> out = new HashMap<>();
    for (String line : Files.readAllLines(file)) {
      if (line.startsWith("account|")) {
        String[] p = line.split("\\|", -1);
        out.put(p[1], p);
      }
    }
    return out;
  }

  private static long userId(String email) {
    return db.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
  }

  @Test
  @Order(1)
  void seedCreatesTheQaDataAndKeepsSecretsOffStdout() throws Exception {
    // An existing admin and an unrelated organisation exist before the seed (they must
    // survive everything below).
    Timestamp now = Timestamp.from(Instant.now());
    // Booting the application once migrates the fresh database.
    assertThat(run(QaCommand.WIPE).exitCode()).isZero();
    bystanderAdmin =
        db.queryForObject(
            "INSERT INTO app_user (email, name, role, status, password_hash, created_at,"
                + " updated_at) VALUES ('owner@owner-admin.example', 'Owner Admin', 'ADMIN',"
                + " 'ACTIVE', '{argon2}not-a-real-hash', ?, ?) RETURNING id",
            Long.class,
            now,
            now);
    bystanderOrg =
        db.queryForObject(
            "INSERT INTO organisation (name, timezone, created_at, updated_at) VALUES ('Real"
                + " Client Ltd', 'UTC', now(), now()) RETURNING id",
            Long.class);
    Path file = dir.resolve("qa-credentials.txt");

    Result seeded = run(QaCommand.SEED, "--credentials-file=" + file);

    assertThat(seeded.exitCode()).isZero();
    Map<String, String[]> accounts = sheet(file);
    assertThat(accounts).hasSize(13);
    // Nothing secret is printed: no password and no recovery code appears on stdout.
    for (String[] account : accounts.values()) {
      assertThat(seeded.printed()).doesNotContain(account[2]).doesNotContain(account[1]);
      for (String code : account[5].strip().split("\\s+")) {
        if (!code.isBlank()) {
          assertThat(seeded.printed()).doesNotContain(code);
        }
      }
    }
    // The sheet is mode 600.
    assertThat(Files.getPosixFilePermissions(file))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(Files.getPosixFilePermissions(file))
        .doesNotContain(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    // Organisations, with their verified domains and prefixes.
    assertThat(
            db.queryForList(
                "SELECT p.prefix FROM organisation_prefix p JOIN qa_seed s ON s.ref_id ="
                    + " p.organisation_id AND s.kind = 'ORGANISATION' ORDER BY p.prefix",
                String.class))
        .containsExactly("ACD", "BFA", "NWL", "QA");
    assertThat(count("SELECT count(*) FROM organisation_domain WHERE domain LIKE '%.example'"))
        .isEqualTo(4);
    // Accounts: 10 clients, 2 engineers, 1 QA admin; every password is strong and hashed.
    assertThat(count("SELECT count(*) FROM app_user WHERE role = 'CUSTOMER'")).isEqualTo(10);
    assertThat(count("SELECT count(*) FROM app_user WHERE role = 'AGENT'")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM app_user WHERE email = 'qa-admin@msp.example'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM app_user WHERE role = 'CUSTOMER' AND organisation_id"
                    + " = (SELECT organisation_id FROM organisation_prefix WHERE prefix = 'QA')"))
        .isEqualTo(5);
    accounts
        .values()
        .forEach(
            a -> {
              assertThat(a[2]).hasSize(24);
              assertThat(
                      db.queryForObject(
                          "SELECT password_hash FROM app_user WHERE email = ?", String.class, a[1]))
                  .startsWith("{")
                  .doesNotContain(a[2]);
            });

    // Supporters: MFA enrolled with 10 unused recovery codes (and the sheet holds all 10).
    for (String email : List.of("qa-admin@msp.example", "alex@msp.example", "riley@msp.example")) {
      long id = userId(email);
      assertThat(
              count(
                  "SELECT count(*) FROM user_mfa WHERE user_id = ? AND confirmed_at IS NOT NULL",
                  id))
          .isEqualTo(1);
      assertThat(count("SELECT count(*) FROM mfa_recovery_code WHERE user_id = ?", id))
          .isEqualTo(10);
      assertThat(accounts.get(email)[5].strip().split("\\s+")).hasSize(10);
    }
    assertThat(accounts.get("dana.reyes@acmedental.example")[5].strip()).isEmpty();

    // About ten tickets across all six statuses, one internal note with an image, one merged
    // duplicate, one CC colleague.
    assertThat(count("SELECT count(*) FROM ticket")).isEqualTo(10);
    assertThat(db.queryForList("SELECT DISTINCT status FROM ticket ORDER BY status", String.class))
        .containsExactlyInAnyOrder(
            "NEW", "OPEN", "PENDING_ON_CLIENT", "PENDING_ON_VENDOR", "RESOLVED", "CLOSED");
    assertThat(
            count(
                "SELECT count(*) FROM internal_note n JOIN attachment a ON a.internal_note_id ="
                    + " n.id WHERE a.content_type LIKE 'image/%'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM ticket WHERE duplicate_of_id IS NOT NULL")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM ticket_participant WHERE email = 'sam.okoye@acmedental.example'"))
        .isGreaterThanOrEqualTo(1);
    // Audited, and the audit event holds counts only.
    assertThat(
            db.queryForObject(
                "SELECT details::text FROM audit_event WHERE type = 'QA_SEED'", String.class))
        .doesNotContain("@")
        .contains("rows");
  }

  @Test
  @Order(2)
  void theRestrictedEngineerSeesBluefinOnly() {
    PrincipalAccessReader reader =
        new PrincipalAccessReader(
            JdbcClient.create(
                new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword())));
    AccessScope riley = reader.read(userId("riley@msp.example")).orElseThrow().toScope();
    AccessScope alex = reader.read(userId("alex@msp.example")).orElseThrow().toScope();
    long acd = orgId("ACD");
    long bfa = orgId("BFA");
    assertThat(riley.canSee(bfa)).isTrue();
    assertThat(riley.canSee(acd)).isFalse();
    assertThat(riley.canSee(orgId("NWL"))).isFalse();
    assertThatThrownBy(() -> riley.require(acd)).hasMessageContaining("could not find");
    assertThat(alex.canSee(acd) && alex.canSee(bfa) && alex.canSee(orgId("QA"))).isTrue();
    // Riley is assigned only Bluefin tickets.
    assertThat(
            count(
                "SELECT count(*) FROM ticket WHERE assignee_user_id = ? AND organisation_id <> ?",
                userId("riley@msp.example"),
                bfa))
        .isZero();
  }

  @Test
  @Order(3)
  void aSecondRunDuplicatesNothingAndKeepsEveryPassword() throws Exception {
    Path file = dir.resolve("qa-credentials.txt");
    Map<String, String[]> before = sheet(file);
    int users = count("SELECT count(*) FROM app_user");
    int tickets = count("SELECT count(*) FROM ticket");
    int orgs = count("SELECT count(*) FROM organisation");
    int markers = count("SELECT count(*) FROM qa_seed");
    String hash =
        db.queryForObject(
            "SELECT password_hash FROM app_user WHERE email = 'alex@msp.example'", String.class);

    Result again = run(QaCommand.SEED, "--credentials-file=" + file);

    assertThat(again.exitCode()).isZero();
    assertThat(count("SELECT count(*) FROM app_user")).isEqualTo(users);
    assertThat(count("SELECT count(*) FROM ticket")).isEqualTo(tickets);
    assertThat(count("SELECT count(*) FROM organisation")).isEqualTo(orgs);
    assertThat(count("SELECT count(*) FROM qa_seed")).isEqualTo(markers);
    assertThat(
            count(
                "SELECT count(*) FROM mfa_recovery_code WHERE user_id = ?",
                userId("alex@msp.example")))
        .isEqualTo(10);
    assertThat(
            db.queryForObject(
                "SELECT password_hash FROM app_user WHERE email = 'alex@msp.example'",
                String.class))
        .isEqualTo(hash);
    Map<String, String[]> after = sheet(file);
    before.forEach((email, row) -> assertThat(after.get(email)).containsExactly(row));
  }

  @Test
  @Order(4)
  void resetPasswordsRotatesPasswordsButNotRecoveryCodes() throws Exception {
    Path file = dir.resolve("qa-credentials.txt");
    Map<String, String[]> before = sheet(file);
    String hash =
        db.queryForObject(
            "SELECT password_hash FROM app_user WHERE email = 'alex@msp.example'", String.class);

    Result reset = run(QaCommand.SEED, "--credentials-file=" + file, "--reset-passwords");

    assertThat(reset.exitCode()).isZero();
    Map<String, String[]> after = sheet(file);
    assertThat(after).hasSameSizeAs(before);
    after.forEach(
        (email, row) -> {
          assertThat(row[2]).isNotEqualTo(before.get(email)[2]);
          assertThat(row[5]).isEqualTo(before.get(email)[5]);
          assertThat(reset.printed()).doesNotContain(row[2]);
        });
    assertThat(
            db.queryForObject(
                "SELECT password_hash FROM app_user WHERE email = 'alex@msp.example'",
                String.class))
        .isNotEqualTo(hash);
    assertThat(count("SELECT count(*) FROM audit_event WHERE type = 'QA_SEED'")).isEqualTo(3);
  }

  @Test
  @Order(5)
  void wipeRemovesOnlySeededDataAndKeepsTesterTicketsByDefault() throws Exception {
    // A tester raised a bug ticket in the QA organisation (not created by the seed).
    long qaOrg = orgId("QA");
    long tester = userId("tester1@qa.example");
    long type =
        db.queryForObject(
            "SELECT id FROM request_type WHERE name = 'Other or general question'", Long.class);
    Timestamp now = Timestamp.from(Instant.now());
    db.update(
        "INSERT INTO ticket (organisation_id, reporter_user_id, request_type_id, status, priority,"
            + " source, subject, created_at, updated_at, status_changed_at) VALUES (?, ?, ?,"
            + " 'NEW', 'P4', 'PORTAL', '[BUG][AUTH-01] tester ticket', ?, ?, ?)",
        qaOrg,
        tester,
        type,
        now,
        now,
        now);

    Result wiped = run(QaCommand.WIPE);

    assertThat(wiped.exitCode()).isZero();
    // The existing admin and the unrelated organisation are untouched.
    assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", bystanderAdmin)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM organisation WHERE id = ?", bystanderOrg)).isEqualTo(1);
    // Seeded tickets, accounts and organisations are gone, except what the tester ticket holds.
    assertThat(count("SELECT count(*) FROM ticket WHERE subject LIKE '[BUG]%'")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM ticket")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM app_user WHERE email = 'alex@msp.example'")).isZero();
    assertThat(count("SELECT count(*) FROM app_user WHERE email = 'tester1@qa.example'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM organisation WHERE id = ?", qaOrg)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM organisation WHERE id = ?", orgId("ACD", false)))
        .isZero();
    assertThat(wiped.printed()).contains("1 tickets").contains("--include-tester-data");
    assertThat(count("SELECT count(*) FROM audit_event WHERE type = 'QA_WIPE'"))
        .isGreaterThanOrEqualTo(1);

    // Explicitly including tester data removes the rest of the seed; still nothing else.
    Result all = run(QaCommand.WIPE, "--include-tester-data");

    assertThat(all.exitCode()).isZero();
    assertThat(count("SELECT count(*) FROM ticket")).isZero();
    assertThat(count("SELECT count(*) FROM qa_seed")).isZero();
    assertThat(count("SELECT count(*) FROM app_user")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", bystanderAdmin)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM organisation")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM organisation WHERE id = ?", bystanderOrg)).isEqualTo(1);
    // Wiping again does nothing, and the seed can run again afterwards.
    assertThat(run(QaCommand.WIPE).printed()).contains("Nothing changed");
    Path file = dir.resolve("qa-credentials-2.txt");
    assertThat(run(QaCommand.SEED, "--credentials-file=" + file).exitCode()).isZero();
    assertThat(count("SELECT count(*) FROM ticket")).isEqualTo(10);
    assertThat(run(QaCommand.WIPE).exitCode()).isZero();
    assertThat(count("SELECT count(*) FROM app_user")).isEqualTo(1);
  }

  @Test
  @Order(6)
  void usageErrorsChangeNothing() {
    assertThat(run(QaCommand.SEED).exitCode()).isEqualTo(64);
    assertThat(run(QaCommand.WIPE, "--bogus").exitCode()).isEqualTo(64);
  }

  private static long orgId(String prefix) {
    return orgId(prefix, true);
  }

  private static long orgId(String prefix, boolean mustExist) {
    List<Long> ids =
        db.queryForList(
            "SELECT organisation_id FROM organisation_prefix WHERE prefix = ?", Long.class, prefix);
    if (ids.isEmpty()) {
      if (mustExist) {
        throw new AssertionError("no organisation " + prefix);
      }
      return -1;
    }
    return ids.get(0);
  }

  @SuppressWarnings("unused")
  private static Set<String> unused() {
    return Set.of();
  }
}
