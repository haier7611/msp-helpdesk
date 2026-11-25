package com.ticketsystem.api.retention.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.mfa.MfaExemptionService;
import com.ticketsystem.api.job.JobRunner;
import com.ticketsystem.api.retention.RetentionAuditEvent;
import com.ticketsystem.api.retention.clientdata.RetentionRepository;
import com.ticketsystem.api.retention.clientdata.RetentionRepository.BlobKeys;
import com.ticketsystem.api.ticket.attachment.BlobStore;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nightly retention, under the shared job lock so only one instance runs it. Each step works in
 * bounded batches, each batch in its own transaction, so an error rolls back only that batch (the
 * next night retries it) and the other steps still run. Every step that removed something writes a
 * {@code RETENTION_RUN} audit event with counts only.
 *
 * <ul>
 *   <li>Closed tickets older than their organisation's retention period (default 3 years since they
 *       closed), with comments, notes, history, attachments (rows, then files after commit), mail
 *       log rows and outbox rows.
 *   <li>Audit events after 1 year (through the {@code purge_audit_events} function).
 *   <li>Sent or failed outbox rows after 90 days.
 *   <li>Link tokens 30 days after they expired.
 *   <li>Two-step exemptions whose end time has passed, with their free-text reason, when the
 *       engineer never signed in again to trigger the clean-up at sign-in. Each writes its own
 *       {@code MFA_EXEMPTION_EXPIRED} event; skipped in dry-run.
 * </ul>
 *
 * Handled elsewhere and not repeated here: raw {@code .eml} after 90 days and the Blocked mail log
 * after 30 days ({@code InboundRetentionJob}, hourly), expired pending uploads ({@code
 * PendingAttachmentCleanupJob}), expired sessions (Spring Session JDBC's own cleanup, every minute)
 * and idle devices ({@code DeviceService}).
 *
 * <p>{@code app.retention.dry-run=true} (environment variable {@code APP_RETENTION_DRY_RUN}, passed
 * through by docker-compose.yml, default false) only counts and logs what would go; the run logs
 * "retention: dry run" at start so a switch that did not take is visible before anything goes.
 */
@Component
public class RetentionJob {

  private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

  static final String LOCK = "retention";
  static final Duration AUDIT_RETENTION = Duration.ofDays(365);
  static final Duration OUTBOX_RETENTION = Duration.ofDays(90);
  static final Duration TOKEN_GRACE = Duration.ofDays(30);
  static final int TICKET_BATCH = 100;
  static final int ROW_BATCH = 5000;

  /** Upper bound of batches per step and run; the rest waits for the next night. */
  static final int MAX_BATCHES = 50;

  /** What one run removed (or, in dry-run, would remove). */
  public record Report(
      boolean dryRun, long tickets, long attachments, long auditEvents, long outbox, long tokens) {}

  private final JobRunner runner;
  private final RetentionRepository repo;
  private final BlobStore blobs;
  private final AuditService audit;
  private final MfaExemptionService exemptions;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean dryRun;

  public RetentionJob(
      JobRunner runner,
      RetentionRepository repo,
      BlobStore blobs,
      AuditService audit,
      MfaExemptionService exemptions,
      TransactionTemplate tx,
      Clock clock,
      @Value("${app.retention.dry-run:false}") boolean dryRun) {
    this.runner = runner;
    this.repo = repo;
    this.blobs = blobs;
    this.audit = audit;
    this.exemptions = exemptions;
    this.tx = tx;
    this.clock = clock;
    this.dryRun = dryRun;
  }

  /** 03:40 UTC, after the 02:30 UTC backup, so a deleted row is still in last night's backup. */
  @Scheduled(cron = "${app.retention.cron:0 40 3 * * *}", zone = "UTC")
  public void scheduled() {
    if (runner.schedulingEnabled()) {
      runOnce();
    }
  }

  /** One run under the lock; null when another runner holds it. */
  public Report runOnce() {
    return runOnce(dryRun);
  }

  /** One run, dry or not; null when another runner holds the lock. */
  public Report runOnce(boolean dry) {
    if (dry) {
      log.info("retention: dry run, nothing will be deleted");
    } else {
      log.info("retention: starting (deletes for real; APP_RETENTION_DRY_RUN=true to only count)");
    }
    Report[] out = new Report[1];
    int r =
        runner.runExclusive(
            LOCK,
            Duration.ofHours(1),
            () -> {
              AccessScope system = AccessScope.system("WP-12 nightly retention");
              out[0] = dry ? count(system) : purge(system);
              return 0;
            });
    if (r < 0) {
      return null;
    }
    Report report = out[0];
    log.info(
        "retention{}: {} ticket(s), {} attachment file(s), {} audit event(s), {} outbox row(s),"
            + " {} link token(s)",
        dry ? " (dry run, nothing deleted)" : "",
        report.tickets(),
        report.attachments(),
        report.auditEvents(),
        report.outbox(),
        report.tokens());
    return report;
  }

  private Report count(AccessScope system) {
    Instant now = clock.instant();
    return new Report(
        true,
        repo.countExpiredClosedTickets(system, now),
        0,
        repo.countAudit(system, now.minus(AUDIT_RETENTION)),
        repo.countFinishedOutbox(system, now.minus(OUTBOX_RETENTION)),
        repo.countExpiredTokens(system, now.minus(TOKEN_GRACE)));
  }

  private Report purge(AccessScope system) {
    Instant now = clock.instant();
    long[] ticketCounts = purgeTickets(system, now);
    long auditEvents =
        batches(
            "audit events",
            () -> repo.purgeAudit(system, now.minus(AUDIT_RETENTION), ROW_BATCH),
            ROW_BATCH);
    long outbox =
        batches(
            "outbox",
            () -> repo.deleteFinishedOutbox(system, now.minus(OUTBOX_RETENTION), ROW_BATCH),
            ROW_BATCH);
    long tokens =
        batches(
            "link tokens",
            () -> repo.deleteExpiredTokens(system, now.minus(TOKEN_GRACE), ROW_BATCH),
            ROW_BATCH);
    purgeEndedExemptions();
    record("tickets", ticketCounts[0], ticketCounts[1]);
    record("audit_events", auditEvents, -1);
    record("outbox", outbox, -1);
    record("link_tokens", tokens, -1);
    return new Report(false, ticketCounts[0], ticketCounts[1], auditEvents, outbox, tokens);
  }

  /** Ended two-step exemptions; their own audit events, so no RETENTION_RUN row here. */
  private void purgeEndedExemptions() {
    try {
      int n = exemptions.sweepEnded();
      if (n > 0) {
        log.info("retention: {} ended two-step exemption(s) removed", n);
      }
    } catch (RuntimeException e) {
      log.error(
          "retention: removing ended two-step exemptions failed ({})",
          e.getClass().getSimpleName());
    }
  }

  /** Returns {tickets deleted, attachment files deleted}. */
  private long[] purgeTickets(AccessScope system, Instant now) {
    long tickets = 0;
    long files = 0;
    for (int i = 0; i < MAX_BATCHES; i++) {
      TicketBatch batch;
      try {
        batch =
            tx.execute(
                status -> {
                  List<Long> ids = repo.expiredClosedTickets(system, now, TICKET_BATCH);
                  List<BlobKeys> k = repo.attachmentFiles(system, ids);
                  return new TicketBatch(ids.size(), repo.deleteTickets(system, ids), k);
                });
      } catch (RuntimeException e) {
        // The batch rolled back as a whole; files are untouched. Type only in the log.
        log.error(
            "retention: a closed-ticket batch failed and was rolled back ({})",
            e.getClass().getSimpleName());
        break;
      }
      if (batch == null || batch.selected() == 0) {
        break;
      }
      tickets += batch.deleted();
      // Files go only after the rows are committed: a rolled-back batch keeps its files.
      for (BlobKeys k : batch.files()) {
        files += deleteFile(k.storageKey());
        files += deleteFile(k.thumbnailKey());
      }
      if (batch.selected() < TICKET_BATCH) {
        break;
      }
    }
    return new long[] {tickets, files};
  }

  private record TicketBatch(int selected, int deleted, List<BlobKeys> files) {}

  private int deleteFile(UUID key) {
    if (key == null) {
      return 0;
    }
    try {
      blobs.delete(key);
      return 1;
    } catch (IOException e) {
      log.warn("retention: could not delete attachment file (key {})", key);
      return 0;
    }
  }

  private long batches(String what, IntSupplier batch, int size) {
    long total = 0;
    for (int i = 0; i < MAX_BATCHES; i++) {
      int n;
      try {
        Integer got = tx.execute(status -> batch.getAsInt());
        n = got == null ? 0 : got;
      } catch (RuntimeException e) {
        log.error(
            "retention: a {} batch failed and was rolled back ({})",
            what,
            e.getClass().getSimpleName());
        break;
      }
      total += n;
      if (n < size) {
        break;
      }
    }
    return total;
  }

  private void record(String kind, long rows, long attachments) {
    if (rows <= 0) {
      return;
    }
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("kind", kind);
    d.put("rows", rows);
    if (attachments >= 0) {
      d.put("attachments", attachments);
    }
    audit.record(RetentionAuditEvent.RETENTION_RUN, null, null, null, d);
  }
}
