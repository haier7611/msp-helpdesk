package com.ticketsystem.api.crypto.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.crypto.CryptoAuditEvent;
import com.ticketsystem.api.crypto.DataCipher;
import com.ticketsystem.api.crypto.clientdata.EncryptedSecretRepository;
import com.ticketsystem.api.crypto.clientdata.EncryptedSecretRepository.KeyCount;
import com.ticketsystem.api.crypto.clientdata.EncryptedSecretRepository.Kind;
import com.ticketsystem.api.crypto.clientdata.EncryptedSecretRepository.Stored;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Data-key rotation, a break-glass-style command run in the api container on the host:
 *
 * <pre>docker compose exec api java -jar /app/app.jar rotate-data-key [--check]</pre>
 *
 * With the new key as {@code TS_DATA_KEY} / {@code TS_DATA_KEY_ID} and the old one still in {@code
 * TS_DATA_OLD_KEYS}, it re-encrypts every stored secret (MFA secrets and pending enrolments,
 * mailbox passwords and OAuth2 secrets, the Microsoft sign-in client secret) under the current key,
 * one value per transaction, each only if unchanged since it was read. {@code --check} changes
 * nothing and only reports how many values each key id holds.
 *
 * <p>Fails safe: a value that cannot be decrypted (its key is not configured, or the key is wrong,
 * or the value was tampered with) is left exactly as it is and reported; the command then exits 3,
 * and the old key must stay configured. It prints counts and key ids only, never a key or a value.
 * Access to the host shell is the authorisation; the run is audit-logged.
 */
@Component
public class DataKeyRotation {

  public static final String COMMAND = "rotate-data-key";

  /** Exit codes. */
  public static final int OK = 0;

  public static final int UNREADABLE = 3;
  public static final int USAGE = 64;

  static final int PAGE = 200;

  /** What one run did. */
  public record Result(int rotated, int unreadable, int changedMeanwhile) {}

  private final EncryptedSecretRepository repo;
  private final DataCipher cipher;
  private final AuditService audit;
  private final TransactionTemplate tx;

  public DataKeyRotation(
      EncryptedSecretRepository repo,
      DataCipher cipher,
      AuditService audit,
      TransactionTemplate tx) {
    this.repo = repo;
    this.cipher = cipher;
    this.audit = audit;
    this.tx = tx;
  }

  /** Re-encrypts everything not yet on the current key. */
  public Result rotate() {
    AccessScope system = AccessScope.system("data key rotation command");
    String current = cipher.currentKeyId();
    int rotated = 0;
    int unreadable = 0;
    int changed = 0;
    for (Kind kind : Kind.values()) {
      long after = 0;
      while (true) {
        List<Stored> page = repo.notOnKey(system, kind, current, after, PAGE);
        if (page.isEmpty()) {
          break;
        }
        for (Stored s : page) {
          after = s.rowId();
          byte[] plain;
          try {
            plain = cipher.decrypt(s.sealed(), kind.associatedData(s.rowId()));
          } catch (IllegalStateException e) {
            unreadable++;
            continue;
          }
          DataCipher.Sealed fresh = cipher.encrypt(plain, kind.associatedData(s.rowId()));
          Arrays.fill(plain, (byte) 0);
          Boolean done = tx.execute(status -> repo.replace(system, s, fresh));
          if (Boolean.TRUE.equals(done)) {
            rotated++;
          } else {
            changed++;
          }
        }
      }
    }
    if (rotated > 0) {
      audit.record(
          CryptoAuditEvent.DATA_KEY_ROTATED,
          null,
          null,
          null,
          Map.of("key_id", current, "rows", rotated, "reason", "command"));
    }
    return new Result(rotated, unreadable, changed);
  }

  /** The command body; returns the process exit code. */
  public int run(boolean checkOnly, PrintStream out) {
    String current = cipher.currentKeyId();
    out.println("rotate-data-key: current key id " + current);
    if (!checkOnly) {
      Result r = rotate();
      out.println(
          "rotate-data-key: re-encrypted "
              + r.rotated()
              + " value(s); "
              + r.changedMeanwhile()
              + " changed by the running app meanwhile (already on the new key or to retry);"
              + " "
              + r.unreadable()
              + " could not be decrypted and were left unchanged.");
    }
    long stale = 0;
    for (KeyCount c : repo.countByKey(AccessScope.system("data key rotation report"))) {
      out.println("rotate-data-key:   " + c.kind() + " key " + c.keyId() + ": " + c.count());
      if (!c.keyId().equals(current)) {
        stale += c.count();
      }
    }
    if (stale > 0) {
      out.println(
          "rotate-data-key: "
              + stale
              + " value(s) are still on an older key. Keep TS_DATA_OLD_KEYS until this reports 0"
              + " (see docs/runbooks/rotate-data-key.md).");
      return UNREADABLE;
    }
    out.println(
        "rotate-data-key: every stored secret is on key "
            + current
            + ". The old key can be removed from TS_DATA_OLD_KEYS.");
    return OK;
  }

  /** Spring property overrides (tests); in production the settings come from the environment. */
  private static boolean isPropertyArg(String a) {
    return a.startsWith("--spring.") || a.startsWith("--app.");
  }

  /** Entry point from {@code main} when the first argument is {@value #COMMAND}. */
  public static int run(Class<?> application, String[] args) {
    boolean check = false;
    for (String a : args) {
      if (a.equals(COMMAND)) {
        continue;
      }
      if (a.equals("--check")) {
        check = true;
      } else if (!isPropertyArg(a)) {
        System.out.println("usage: java -jar app.jar rotate-data-key [--check]");
        return USAGE;
      }
    }
    String[] springArgs =
        Arrays.stream(args).filter(DataKeyRotation::isPropertyArg).toArray(String[]::new);
    final boolean checkOnly = check;
    try (ConfigurableApplicationContext ctx =
        new SpringApplicationBuilder(application)
            .web(WebApplicationType.NONE)
            .properties(
                "app.mail.outbox.auto-start=false",
                "app.mail.inbound.auto-start=false",
                "app.jobs.enabled=false",
                "spring.main.banner-mode=off")
            .run(springArgs)) {
      int code = ctx.getBean(DataKeyRotation.class).run(checkOnly, System.out);
      return SpringApplication.exit(ctx, () -> code);
    }
  }
}
