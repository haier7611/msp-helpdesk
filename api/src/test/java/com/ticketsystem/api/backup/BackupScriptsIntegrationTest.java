package com.ticketsystem.api.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.TestcontainersConfiguration;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.TicketTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * WP-12 host scripts against a real Postgres: the restore drill ({@code
 * scripts/backup/ticketsys-restore-drill.sh}) and the full export ({@code
 * scripts/backup/ticketsys-export.sh}). Both are POSIX sh and are copied into the shared
 * Testcontainers Postgres and run there with {@code TS_PG_LOCAL=1} (on the host they reach the
 * database through {@code docker exec}). The scripts folder is {@code ../scripts} from the api
 * project ({@code scripts/ci/api.sh} mounts it read-only).
 */
class BackupScriptsIntegrationTest extends TicketTestBase {

  static final Path SCRIPTS = Path.of("..", "scripts", "backup");
  static final String STAMP = "20261118T023000Z";

  private final PostgreSQLContainer pg = TestcontainersConfiguration.sharedContainer();
  private final JdbcTemplate owner = TestcontainersConfiguration.ownerJdbc();
  private String work;

  @BeforeEach
  void copyScripts() throws Exception {
    work = "/tmp/ts-" + UUID.randomUUID().toString().substring(0, 8);
    for (String name : List.of("ticketsys-restore-drill.sh", "ticketsys-export.sh")) {
      Path script = SCRIPTS.resolve(name);
      assertThat(script).as("scripts folder mounted at ../scripts").exists();
      pg.copyFileToContainer(Transferable.of(Files.readAllBytes(script)), work + "/" + name);
    }
    exec("mkdir -p " + work + "/backups");
  }

  private ExecResult exec(String shell) throws Exception {
    return pg.execInContainer("sh", "-c", shell);
  }

  private ExecResult drill(String... args) throws Exception {
    return exec(
        "TS_PG_LOCAL=1 TS_BACKUP_DIR="
            + work
            + "/backups TS_DRILL_DB=ticketsys_drill_test sh "
            + work
            + "/ticketsys-restore-drill.sh "
            + String.join(" ", args)
            + " 2>&1");
  }

  /** A ticket with an uploaded file, so the archive check has a real attachment row. */
  private UUID ticketWithFile() {
    UUID upload = upload(admin, "report.txt", "synthetic report".getBytes(StandardCharsets.UTF_8));
    Created c =
        tickets.create(
            admin,
            new NewTicket(
                acme,
                acmeCustomerUser.id(),
                null,
                generalType,
                null,
                null,
                null,
                TicketSource.AGENT,
                "Drill fixture",
                "<p>Synthetic.</p>",
                List.of(upload),
                null,
                null,
                null,
                null));
    return jdbc.queryForObject(
        "SELECT storage_key FROM attachment WHERE ticket_id = ?", UUID.class, c.ticketId());
  }

  /** pg_dump of the live test database plus an attachments archive holding {@code keys}. */
  private void backup(List<String> keys) throws Exception {
    assertThat(
            exec("pg_dump -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -Fc > "
                    + work
                    + "/backups/db-"
                    + STAMP
                    + ".dump")
                .getExitCode())
        .isZero();
    StringBuilder list = new StringBuilder();
    keys.forEach(k -> list.append(k).append('\n'));
    pg.copyFileToContainer(
        Transferable.of(list.toString().getBytes(StandardCharsets.UTF_8)), work + "/keys");
    ExecResult tar =
        exec(
            "cd "
                + work
                + " && mkdir -p data/attachments && while read k; do mkdir -p"
                + " data/attachments/$(echo $k | cut -c1-2) && echo x >"
                + " data/attachments/$(echo $k | cut -c1-2)/$k; done < keys && tar -C data -czf"
                + " backups/attachments-"
                + STAMP
                + ".tar.gz attachments");
    assertThat(tar.getExitCode()).as(tar.getStderr()).isZero();
  }

  private List<String> allBoundKeys() {
    return owner.queryForList(
        "SELECT storage_key::text FROM attachment WHERE status = 'BOUND' UNION SELECT"
            + " thumbnail_key::text FROM attachment WHERE status = 'BOUND' AND thumbnail_key IS"
            + " NOT NULL",
        String.class);
  }

  @Test
  void theDrillRestoresChecksCountsAndFilesAndReplaysTheErasureLog() throws Exception {
    ticketWithFile();
    backup(allBoundKeys());
    // Erased on the live system AFTER the backup: the replay must erase them in the restore too.
    long user = acmeCustomerUser.id();
    owner.queryForObject("SELECT ts_erase_user(?, now())", Boolean.class, user);
    owner.update("INSERT INTO user_erasure (user_id, erased_at) VALUES (?, now())", user);

    ExecResult r = drill();

    assertThat(r.getExitCode()).as(r.getStdout()).isZero();
    assertThat(r.getStdout())
        .contains("restore-drill: row counts match the dump for all")
        .contains("referenced file(s)")
        .contains("erasure log replayed")
        .contains("restore-drill: OK");
    // The scratch database is gone and the result was recorded.
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM pg_database WHERE datname = 'ticketsys_drill_test'",
                Integer.class))
        .isZero();
    assertThat(exec("cat " + work + "/backups/RESTORE_DRILL_LAST").getStdout())
        .contains(" OK dump=db-" + STAMP + ".dump");
  }

  @Test
  void aMissingAttachmentFileIsReportedByKey() throws Exception {
    UUID missing = ticketWithFile();
    List<String> keys = allBoundKeys();
    keys.remove(missing.toString());
    backup(keys);

    ExecResult r = drill();

    assertThat(r.getExitCode()).isEqualTo(1);
    assertThat(r.getStdout())
        .contains("attachment files missing")
        .contains(missing.toString())
        .contains("FAILED");
    assertThat(exec("cat " + work + "/backups/RESTORE_DRILL_LAST").getStdout()).contains("FAILED");
  }

  @Test
  void aMissingAttachmentsArchiveFailsClearly() throws Exception {
    backup(allBoundKeys());
    exec("rm " + work + "/backups/attachments-" + STAMP + ".tar.gz");

    ExecResult r = drill();

    assertThat(r.getExitCode()).isEqualTo(1);
    assertThat(r.getStdout())
        .contains("no attachments archive for this dump")
        .contains("attachments-" + STAMP + ".tar.gz");
  }

  @Test
  void noDumpAtAllFails() throws Exception {
    ExecResult r = drill();
    assertThat(r.getExitCode()).isEqualTo(1);
    assertThat(r.getStdout()).contains("no dump found");
  }

  @Test
  void theExportCarriesDataAndSafeSettingsButNoSecrets() throws Exception {
    ticketWithFile();
    String base = work + "/host";
    String env =
        String.join(
            "\n",
            "POSTGRES_DB=ticketsys",
            "POSTGRES_USER=ticketsys",
            "POSTGRES_PASSWORD=synthetic-db-password-AAAA",
            "APP_DB_PASSWORD=synthetic-app-password-BBBB",
            "TS_DATA_KEY=synthetic-data-key-CCCC",
            "TS_DATA_KEY_ID=k1",
            "TS_SETUP_TOKEN=synthetic-setup-token-DDDD",
            "SMTP_PASSWORD=synthetic-smtp-password-EEEE",
            "TS_PUBLIC_BASE_URL=https://support.example.test",
            "MAIL_FROM_ADDRESS=support@helpdesk.example",
            "GIT_SHA=0123abc",
            "");
    exec(
        "mkdir -p " + base + "/data/attachments/ab && echo x > " + base + "/data/attachments/ab/f");
    pg.copyFileToContainer(Transferable.of(env.getBytes(StandardCharsets.UTF_8)), base + "/.env");

    ExecResult r =
        exec("TS_PG_LOCAL=1 TS_BASE=" + base + " sh " + work + "/ticketsys-export.sh 2>&1");

    assertThat(r.getExitCode()).as(r.getStdout()).isZero();
    assertThat(r.getStdout()).contains("export: " + base + "/exports/ticketsys-export-");
    String out = base + "/exports";
    ExecResult unpack =
        exec(
            "cd "
                + out
                + " && ls -l *.tar && tar -xf ticketsys-export-*.tar && ls ticketsys-export-*/");
    assertThat(unpack.getStdout())
        .startsWith("-rw-------")
        .contains("db.dump")
        .contains("attachments.tar.gz")
        .contains("settings.env")
        .contains("MANIFEST.txt");
    String settings = exec("cat " + out + "/ticketsys-export-*/settings.env").getStdout();
    assertThat(settings)
        .contains("TS_PUBLIC_BASE_URL=https://support.example.test")
        .contains("TS_DATA_KEY_ID=k1")
        .doesNotContain("PASSWORD=")
        .doesNotContain("TS_DATA_KEY=")
        .doesNotContain("TS_SETUP_TOKEN");
    assertThat(
            exec("cd " + out + " && tar -xOf ticketsys-export-*.tar | grep -c synthetic-")
                .getStdout()
                .trim())
        .isEqualTo("0");
    String manifest = exec("cat " + out + "/ticketsys-export-*/MANIFEST.txt").getStdout();
    assertThat(manifest)
        .contains("release: 0123abc")
        .contains("secrets: no settings secrets included")
        .contains("credentials: db.dump holds password hashes")
        .contains("db.dump")
        .contains("attachment files: 1");
    assertThat(
            exec("cd "
                    + out
                    + "/ticketsys-export-*/ && sed -n '/^sha256:/,$p' MANIFEST.txt"
                    + " | tail -n +2 | sha256sum -c -")
                .getStdout())
        .contains("db.dump: OK")
        .contains("attachments.tar.gz: OK");
    // Live credentials are not exported: their tables come without data.
    String list = exec("pg_restore --list " + out + "/ticketsys-export-*/db.dump").getStdout();
    assertThat(list)
        .contains("TABLE DATA public ticket ")
        .doesNotContain("TABLE DATA public spring_session ")
        .doesNotContain("TABLE DATA public auth_token ")
        .doesNotContain("TABLE DATA public user_device ");
  }
}
