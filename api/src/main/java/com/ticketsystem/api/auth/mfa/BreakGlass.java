package com.ticketsystem.api.auth.mfa;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Emails;
import com.ticketsystem.api.auth.UserRepository;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Break-glass MFA reset (C8) for a supporter - typically the only admin - who lost their
 * authenticator and recovery codes. Run inside the api container on the host:
 *
 * <pre>docker compose exec api java -jar /app/app.jar reset-mfa --email=admin@example.test</pre>
 *
 * It starts the application without the web server or the outbox sender, resets MFA exactly like
 * the admin action (every session ends, trusted devices revoked, notice queued for the running app
 * to send), writes an MFA_RESET audit event with method {@code break_glass} and no actor, and
 * prints nothing secret. Access to the host shell is the authorisation.
 */
@Component
public class BreakGlass {

  public static final String COMMAND = "reset-mfa";

  private final UserRepository users;
  private final MfaService mfa;

  public BreakGlass(UserRepository users, MfaService mfa) {
    this.users = users;
    this.mfa = mfa;
  }

  /** Resets MFA for the supporter with this email; returns a process exit code. */
  public int resetMfa(String rawEmail, PrintStream out) {
    Optional<AppUser> user =
        Emails.normalise(rawEmail).flatMap(users::findByEmail).filter(u -> u.role().isSupporter());
    if (user.isEmpty()) {
      out.println("reset-mfa: no agent or admin with that email address. Nothing changed.");
      return 2;
    }
    try {
      mfa.reset(user.get().id(), null, "break-glass", "break_glass");
    } catch (ApiException e) {
      out.println("reset-mfa: " + e.getMessage());
      return 2;
    }
    out.println(
        "reset-mfa: two-step sign-in reset for user id "
            + user.get().id()
            + ". All their sessions ended; they set up their authenticator app again at the next"
            + " sign-in.");
    return 0;
  }

  /** Entry point from {@code main} when the first argument is {@value #COMMAND}. */
  public static int run(Class<?> application, String[] args) {
    String email =
        Arrays.stream(args)
            .filter(a -> a.startsWith("--email="))
            .map(a -> a.substring("--email=".length()))
            .findFirst()
            .orElse(null);
    if (email == null || email.isBlank()) {
      System.out.println("usage: java -jar app.jar reset-mfa --email=<agent email>");
      return 64;
    }
    String[] springArgs =
        Arrays.stream(args)
            .filter(a -> !a.equals(COMMAND) && !a.startsWith("--email="))
            .toArray(String[]::new);
    try (ConfigurableApplicationContext ctx =
        new SpringApplicationBuilder(application)
            .web(WebApplicationType.NONE)
            .properties("app.mail.outbox.auto-start=false", "spring.main.banner-mode=off")
            .run(springArgs)) {
      int code = ctx.getBean(BreakGlass.class).resetMfa(email, System.out);
      return SpringApplication.exit(ctx, () -> code);
    }
  }
}
