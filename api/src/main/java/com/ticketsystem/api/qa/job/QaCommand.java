package com.ticketsystem.api.qa.job;

import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The QA round's host-shell commands, run like the break-glass reset: one-off, no web server, no
 * mail sending, no scheduled jobs. Run on the host through the wrappers installed by
 * remote-deploy.sh ({@code ticketsys-qa-seed}, {@code ticketsys-qa-wipe}), which call:
 *
 * <pre>
 * docker compose exec api java -jar /app/app.jar qa-seed --credentials-file=PATH [--reset-passwords]
 * docker compose exec api java -jar /app/app.jar qa-wipe [--include-tester-data]</pre>
 *
 * Nothing secret is ever printed: passwords and recovery codes go only into the credentials file.
 */
public final class QaCommand {

  public static final String SEED = "qa-seed";
  public static final String WIPE = "qa-wipe";

  private static final String CREDENTIALS_FILE = "--credentials-file=";
  private static final String RESET_PASSWORDS = "--reset-passwords";
  private static final String INCLUDE_TESTER_DATA = "--include-tester-data";
  private static final int USAGE = 64;

  private QaCommand() {}

  public static boolean isQaCommand(String[] args) {
    return args.length > 0 && (SEED.equals(args[0]) || WIPE.equals(args[0]));
  }

  public static int run(Class<?> application, String[] args) {
    boolean seed = SEED.equals(args[0]);
    Path file = null;
    boolean reset = false;
    boolean includeTesterData = false;
    for (int i = 1; i < args.length; i++) {
      String a = args[i];
      if (a.startsWith(CREDENTIALS_FILE) && seed) {
        file = Path.of(a.substring(CREDENTIALS_FILE.length()));
      } else if (a.equals(RESET_PASSWORDS) && seed) {
        reset = true;
      } else if (a.equals(INCLUDE_TESTER_DATA) && !seed) {
        includeTesterData = true;
      } else if (!isProperty(a)) {
        return usage(seed);
      }
    }
    if (seed && (file == null || file.toString().isBlank())) {
      return usage(true);
    }
    String[] springArgs =
        Arrays.stream(args, 1, args.length).filter(QaCommand::isProperty).toArray(String[]::new);
    final Path credentials = file;
    final boolean resetPasswords = reset;
    final boolean includeTester = includeTesterData;
    try (ConfigurableApplicationContext ctx =
        new SpringApplicationBuilder(application)
            .web(WebApplicationType.NONE)
            .properties(
                "app.mail.outbox.auto-start=false",
                "app.mail.inbound.auto-start=false",
                "app.jobs.enabled=false",
                "spring.main.banner-mode=off")
            .run(springArgs)) {
      int code =
          seed
              ? ctx.getBean(QaSeeder.class).seed(credentials, resetPasswords, System.out)
              : ctx.getBean(QaWiper.class).wipe(includeTester, System.out);
      return SpringApplication.exit(ctx, () -> code);
    }
  }

  /** Spring property arguments (connection settings, attachments directory) pass through. */
  private static boolean isProperty(String a) {
    return a.startsWith("--spring.") || a.startsWith("--app.");
  }

  private static int usage(boolean seed) {
    System.out.println(
        seed
            ? "usage: java -jar app.jar qa-seed --credentials-file=<path> [--reset-passwords]"
            : "usage: java -jar app.jar qa-wipe [--include-tester-data]");
    return USAGE;
  }
}
