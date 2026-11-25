package com.ticketsystem.api.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The host wrappers {@code ticketsys-qa-seed} and {@code ticketsys-qa-wipe} (ADR-030) with a fake
 * {@code docker} on the PATH: the credentials sheet goes into the api container's store and comes
 * back to a root-only mode-600 file, the temporary copy is always removed, nothing secret is
 * printed, and bad arguments never reach the api.
 */
class QaWrapperScriptsTest {

  static final Path SCRIPTS = Path.of("..", "scripts", "qa");
  static final String SECRET = "Synthetic-Secret-Password-1234";

  @TempDir Path base;
  Path store;
  Path bin;
  Path log;

  @BeforeEach
  void fakeHost() throws IOException {
    store = Files.createDirectories(base.resolve("data").resolve("attachments"));
    bin = Files.createDirectories(base.resolve("bin"));
    log = base.resolve("docker.log");
    // The fake api container: logs its arguments, appends one account line to the sheet it was
    // given (like the real command), and exits with $FAKE_EXIT.
    Files.writeString(
        bin.resolve("docker"),
        "#!/bin/sh\n"
            + "echo \"$*\" >> \"$FAKE_LOG\"\n"
            + "for a in \"$@\"; do case \"$a\" in --credentials-file=*) f=\"${a#--credentials-file=}\";"
            + " esac; done\n"
            + "if [ -n \"${f:-}\" ]; then\n"
            + "  real=\"$TS_BASE/data/attachments/$(basename \"$f\")\"\n"
            + "  echo \"account|x@example.test|"
            + SECRET
            + "|ADMIN|QA|\" >> \"$real\"\n"
            + "fi\n"
            + "exit \"${FAKE_EXIT:-0}\"\n");
    bin.resolve("docker").toFile().setExecutable(true);
  }

  private record Out(int code, String text) {}

  private Out run(String script, int fakeExit, String... args) throws Exception {
    List<String> cmd = new ArrayList<>(List.of("sh", SCRIPTS.resolve(script).toString()));
    cmd.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    pb.environment().put("TS_BASE", base.toString());
    pb.environment().put("TS_QA_NO_ROOT_CHECK", "1");
    pb.environment().put("FAKE_LOG", log.toString());
    pb.environment().put("FAKE_EXIT", Integer.toString(fakeExit));
    Process p = pb.start();
    String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Out(p.waitFor(), text);
  }

  private String dockerLog() throws IOException {
    return Files.exists(log) ? Files.readString(log) : "";
  }

  @Test
  void seedMovesTheSheetToARootOnlyFileAndKeepsItOutOfOutput() throws Exception {
    Path cred = base.resolve("qa-credentials.txt");

    Out first = run("ticketsys-qa-seed.sh", 0);

    assertThat(first.code()).isZero();
    assertThat(first.text()).doesNotContain(SECRET).doesNotContain("x@example.test");
    assertThat(Files.getPosixFilePermissions(cred))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(Files.readAllLines(cred)).hasSize(1);
    // No copy is left in the attachment store.
    assertThat(Files.list(store).toList()).isEmpty();
    assertThat(dockerLog())
        .contains("exec -i ticketsys-api java -jar /app/app.jar qa-seed")
        .contains("--credentials-file=/data/attachments/.qa-credentials.txt")
        .doesNotContain(SECRET);

    // A second run hands the existing sheet to the command (it appends a second line).
    Out second = run("ticketsys-qa-seed.sh", 0, "--reset-passwords");
    assertThat(second.code()).isZero();
    assertThat(Files.readAllLines(cred)).hasSize(2);
    assertThat(dockerLog()).contains("--reset-passwords");
    assertThat(Files.list(store).toList()).isEmpty();
  }

  @Test
  void aFailedRunStillKeepsTheCredentialsItMade() throws Exception {
    Out failed = run("ticketsys-qa-seed.sh", 3);

    assertThat(failed.code()).isEqualTo(3);
    assertThat(Files.readAllLines(base.resolve("qa-credentials.txt"))).hasSize(1);
    assertThat(Files.list(store).toList()).isEmpty();
    assertThat(failed.text()).doesNotContain(SECRET);
  }

  @Test
  void badArgumentsNeverReachTheApi() throws Exception {
    assertThat(run("ticketsys-qa-seed.sh", 0, "--bogus").code()).isEqualTo(64);
    assertThat(run("ticketsys-qa-wipe.sh", 0, "--bogus").code()).isEqualTo(64);
    assertThat(dockerLog()).isEmpty();
  }

  @Test
  void wipePassesItsFlagAndDeletesTheSheetOnlyWhenAsked() throws Exception {
    Path cred = base.resolve("qa-credentials.txt");
    Files.writeString(cred, "account|x|y|z|w|\n");

    assertThat(run("ticketsys-qa-wipe.sh", 0, "--include-tester-data").code()).isZero();
    assertThat(dockerLog()).contains("qa-wipe --include-tester-data");
    assertThat(cred).exists();

    assertThat(run("ticketsys-qa-wipe.sh", 1, "--delete-credentials").code()).isEqualTo(1);
    assertThat(cred).exists();

    Out done = run("ticketsys-qa-wipe.sh", 0, "--delete-credentials");
    assertThat(done.code()).isZero();
    assertThat(cred).doesNotExist();
  }
}
