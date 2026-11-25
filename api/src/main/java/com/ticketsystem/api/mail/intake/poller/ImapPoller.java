package com.ticketsystem.api.mail.intake.poller;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.mail.MailAuditEvent;
import com.ticketsystem.api.mail.intake.InboundAttachments;
import com.ticketsystem.api.mail.intake.InboundMailProcessor;
import com.ticketsystem.api.mail.intake.InboundMailProcessor.MailboxContext;
import com.ticketsystem.api.mail.intake.InboundMailProcessor.Outcome;
import com.ticketsystem.api.mail.intake.InboundMailProcessor.Received;
import com.ticketsystem.api.mail.intake.MailboxService;
import com.ticketsystem.api.mail.intake.clientdata.IntakeStateRepository;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository.MailboxRow;
import com.ticketsystem.api.mail.intake.imap.ImapMailboxClient;
import com.ticketsystem.api.mail.intake.imap.MailboxAccessException;
import com.ticketsystem.api.mail.intake.job.InboundAttachmentWorker;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Polls every enabled mailbox (C11), every 30 seconds by default.
 *
 * <ul>
 *   <li>Its own thread: the poller runs on a dedicated single-thread scheduler ({@code
 *       mail-poller-1}), not on Spring's shared scheduler, so the SLA minute job, retention, the
 *       digest and the other {@code @Scheduled} jobs never wait behind a slow mailbox, and the
 *       poller never waits behind them. Rounds never overlap (fixed delay after the previous round,
 *       plus the lease below).
 *   <li>Ticket first, attachments after: each mail's ticket or comment is committed without its
 *       files; the job ids it returns go to {@link InboundAttachmentWorker} right after the commit,
 *       charged to this round's image budget. At the end of a round the worker is also handed any
 *       job a restart left behind.
 *   <li>One poller at a time: a lease row in {@code mail_poller_lock} (ShedLock style), taken with
 *       a conditional UPDATE, renewed before every message and released at the end. A second
 *       instance or thread finds it taken and skips the round; a poller whose renewal fails (it
 *       lost the lease) stops at once.
 *   <li>UIDVALIDITY + UID: only UIDs above the stored mark are read, read or unread. A mailbox with
 *       no position yet (never connected, or its folder changed) starts at the folder's UIDNEXT, so
 *       mail already there never becomes a ticket; {@link MailboxService} normally records it when
 *       the mailbox is saved. A new UIDVALIDITY starts again at UIDNEXT too (no bulk re-import),
 *       with a WARN log and a {@code MAILBOX_UIDVALIDITY_CHANGED} audit event.
 *   <li>Bounded memory: a round first lists UIDs and sizes only, then downloads and processes one
 *       message at a time, releasing it before the next. A round stops after {@link
 *       RoundLimits#maxMessages} messages or once {@link RoundLimits#byteBudget} bytes were read;
 *       the rest waits for the next round. A message over {@link RoundLimits#maxMessageBytes} (30
 *       MB) is never downloaded: a truncated copy becomes a "too large" triage ticket. Image
 *       decoding has its own per-round budget ({@link InboundAttachments#openRound}).
 *   <li>One transaction per message ({@link InboundMailProcessor#process}); the UID mark moves in
 *       that transaction. A unique-key conflict (another poller committed it first) rolls back and
 *       is logged as a duplicate. A transient database error stops this mailbox's round and the
 *       message is retried next round (up to {@link #TRANSIENT_ATTEMPTS} times). Any other failure
 *       files the mail as a triage ticket in a fallback transaction; if even that fails the round
 *       stops at that message and retries it next time.
 *   <li>Login, token, secret and connection failures are recorded on the mailbox with a short
 *       category and retried with back-off (1, 2, 4 ... 30 minutes); unread mail stays on the
 *       server. One failing mailbox never stops the others.
 * </ul>
 *
 * Logs carry mailbox ids, counts and failure categories only - never addresses, subjects or
 * content.
 */
@Component
public class ImapPoller implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ImapPoller.class);

  static final String LOCK = "imap";
  static final Duration LEASE = Duration.ofMinutes(5);
  static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

  /** How often a message failing with a transient database error is retried before triage. */
  static final int TRANSIENT_ATTEMPTS = 3;

  /**
   * Per-round limits for one mailbox.
   *
   * @param maxMessages messages processed per round
   * @param byteBudget bytes downloaded per round (the first message is always read)
   * @param maxMessageBytes largest message downloaded whole
   */
  public record RoundLimits(int maxMessages, long byteBudget, long maxMessageBytes) {
    public static final RoundLimits DEFAULT =
        new RoundLimits(200, 100L * 1024 * 1024, ImapMailboxClient.MAX_DOWNLOAD_BYTES);
  }

  /**
   * What a round did, for tests and the log. {@code bytesRead}: message bytes downloaded; {@code
   * peakHeldBytes}: the largest body held at once; {@code stoppedEarly}: a limit cut the round
   * short.
   */
  public record PollReport(
      boolean ran,
      int mailboxes,
      int failures,
      Map<String, Integer> outcomes,
      long bytesRead,
      long peakHeldBytes,
      boolean stoppedEarly) {}

  private final MailboxRepository mailboxes;
  private final IntakeStateRepository state;
  private final MailboxService mailboxService;
  private final InboundMailProcessor processor;
  private final InboundAttachments attachments;
  private final InboundAttachmentWorker attachmentWorker;
  private final AuditService audit;
  private final Clock clock;
  private final boolean autoStart;
  private final Duration pollInterval;
  private final Duration initialDelay;
  private volatile ThreadPoolTaskScheduler scheduler;
  private final String owner = "poller-" + UUID.randomUUID();

  /** Transient failures per message (mailbox:uidvalidity:uid), kept in memory between rounds. */
  private final Map<String, Integer> transientFailures = new ConcurrentHashMap<>();

  public ImapPoller(
      MailboxRepository mailboxes,
      IntakeStateRepository state,
      MailboxService mailboxService,
      InboundMailProcessor processor,
      InboundAttachments attachments,
      InboundAttachmentWorker attachmentWorker,
      AuditService audit,
      Clock clock,
      @Value("${app.mail.inbound.auto-start:true}") boolean autoStart,
      @Value("${app.mail.inbound.poll-interval:PT30S}") Duration pollInterval,
      @Value("${app.mail.inbound.initial-delay:PT30S}") Duration initialDelay) {
    this.mailboxes = mailboxes;
    this.state = state;
    this.mailboxService = mailboxService;
    this.processor = processor;
    this.attachments = attachments;
    this.attachmentWorker = attachmentWorker;
    this.audit = audit;
    this.clock = clock;
    this.autoStart = autoStart;
    this.pollInterval = pollInterval;
    this.initialDelay = initialDelay;
  }

  // ---------------------------------------------------------------------------------------------
  // Own scheduler thread

  @Override
  public void start() {
    if (!autoStart || scheduler != null) {
      return;
    }
    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
    s.setPoolSize(1);
    s.setThreadNamePrefix("mail-poller-");
    s.setDaemon(true);
    s.setWaitForTasksToCompleteOnShutdown(false);
    s.initialize();
    s.scheduleWithFixedDelay(
        this::scheduledQuietly, clock.instant().plus(initialDelay), pollInterval);
    scheduler = s;
  }

  @Override
  public void stop() {
    ThreadPoolTaskScheduler s = scheduler;
    scheduler = null;
    if (s != null) {
      s.shutdown();
    }
  }

  @Override
  public boolean isRunning() {
    return scheduler != null;
  }

  /** A failed round never cancels the schedule. */
  private void scheduledQuietly() {
    try {
      scheduled();
    } catch (RuntimeException e) {
      log.warn("mail poll round failed ({})", e.getClass().getSimpleName());
    }
  }

  /** One scheduled round (on the {@code mail-poller} thread). */
  public void scheduled() {
    if (!autoStart) {
      return;
    }
    PollReport report = pollOnce(owner);
    if (report.ran() && !report.outcomes().isEmpty()) {
      log.info("mail poll: {} mailbox(es), outcomes {}", report.mailboxes(), report.outcomes());
    }
  }

  /** One round as this instance. */
  public PollReport pollOnce() {
    return pollOnce(owner);
  }

  /** One round as {@code lockOwner} (tests use two owners to prove only one runs). */
  public PollReport pollOnce(String lockOwner) {
    return pollOnce(lockOwner, RoundLimits.DEFAULT);
  }

  /** One round as {@code lockOwner} with explicit limits (tests use small ones). */
  public PollReport pollOnce(String lockOwner, RoundLimits limits) {
    AccessScope scope = AccessScope.system("mail poller");
    if (!state.tryLock(scope, LOCK, lockOwner, LEASE)) {
      return new PollReport(false, 0, 0, Map.of(), 0, 0, false);
    }
    Round round = new Round(scope, lockOwner, limits);
    List<MailboxRow> due = List.of();
    // One image work budget for the whole round, all mailboxes.
    try (InboundAttachments.Round images = attachments.openRound()) {
      due = mailboxes.due(scope, clock.instant());
      for (MailboxRow m : due) {
        if (round.leaseLost) {
          break;
        }
        boolean ok;
        try {
          ok = pollMailbox(round, m);
        } catch (RuntimeException e) {
          // Anything unexpected about one mailbox stays with that mailbox.
          log.warn("mailbox {}: poll failed ({})", m.id(), e.getClass().getSimpleName());
          recordFailureQuietly(scope, m, "poll failed");
          ok = false;
        }
        if (!ok) {
          round.failures++;
        }
      }
      // Jobs a restart, a full queue or a failed attempt left behind.
      attachmentWorker.dispatchDue(images);
    } finally {
      state.unlock(scope, LOCK, lockOwner);
    }
    return new PollReport(
        true,
        due.size(),
        round.failures,
        round.outcomes,
        round.bytesRead,
        round.peakHeldBytes,
        round.stoppedEarly);
  }

  /** State of one round across its mailboxes. */
  private static final class Round {
    final AccessScope scope;
    final String lockOwner;
    final RoundLimits limits;
    final Map<String, Integer> outcomes = new LinkedHashMap<>();
    int failures;
    long bytesRead;
    long peakHeldBytes;
    boolean stoppedEarly;
    boolean leaseLost;

    Round(AccessScope scope, String lockOwner, RoundLimits limits) {
      this.scope = scope;
      this.lockOwner = lockOwner;
      this.limits = limits;
    }
  }

  private boolean pollMailbox(Round round, MailboxRow m) {
    AccessScope scope = round.scope;
    RoundLimits limits = round.limits;
    try (ImapMailboxClient client = mailboxService.open(scope, m)) {
      long validity = client.uidValidity();
      long lastUid = m.lastUid();
      if (m.uidValidity() == null) {
        // No starting position yet (never polled, or saved while the server could not be
        // reached): start at UIDNEXT, so mail already in the folder never becomes a ticket.
        long start = client.uidNext();
        if (!mailboxes.baseline(scope, m, validity, start)) {
          return true; // the settings or position changed meanwhile; the next round reads them
        }
        log.info("mailbox {}: starting position recorded", m.id());
        lastUid = start - 1;
      } else if (m.uidValidity() != validity) {
        // The server renumbered the folder: start again at UIDNEXT, no bulk re-import.
        long start = client.uidNext();
        if (!mailboxes.rebaseline(scope, m.id(), m.uidValidity(), validity, start)) {
          return true;
        }
        log.warn(
            "mailbox {}: UIDVALIDITY changed, starting again from new mail only; mail that arrived"
                + " since the last poll is not imported",
            m.id());
        recordRebaselineQuietly(m);
        lastUid = start - 1;
      }
      MailboxContext ctx = new MailboxContext(m.id(), m.address(), MailboxService.trustedIds(m));
      List<ImapMailboxClient.Pending> pending = client.listAfter(lastUid, limits.maxMessages());
      long mailboxBytes = 0;
      int processed = 0;
      for (ImapMailboxClient.Pending p : pending) {
        long expected =
            p.size() > limits.maxMessageBytes() ? ImapMailboxClient.TRUNCATED_COPY_BYTES : p.size();
        if (processed > 0 && mailboxBytes + expected > limits.byteBudget()) {
          // The rest waits for the next round; memory and time per round stay bounded.
          round.stoppedEarly = true;
          break;
        }
        if (!state.tryLock(scope, LOCK, round.lockOwner, LEASE)) {
          log.warn("mailbox {}: poller lease lost, stopping the round", m.id());
          round.leaseLost = true;
          return true;
        }
        Optional<ImapMailboxClient.Fetched> download =
            client.download(p.uid(), limits.maxMessageBytes());
        if (download.isEmpty()) {
          processed++;
          continue; // deleted from the server meanwhile
        }
        ImapMailboxClient.Fetched f = download.get();
        mailboxBytes += f.raw().length;
        round.bytesRead += f.raw().length;
        round.peakHeldBytes = Math.max(round.peakHeldBytes, client.peakHeldBytes());
        Step step;
        try {
          step = processOne(round, m, ctx, validity, f);
        } finally {
          client.release(f);
        }
        if (step == Step.STOP) {
          return false;
        }
        client.markSeen(f.uid());
        processed++;
      }
      mailboxes.recordSuccess(scope, m.id(), clock.instant());
      return true;
    } catch (MailboxAccessException e) {
      log.warn("mailbox {}: {}", m.id(), e.category().label());
      recordFailureQuietly(scope, m, e.category().label());
      return false;
    }
  }

  private enum Step {
    NEXT,
    STOP
  }

  private Step processOne(
      Round round, MailboxRow m, MailboxContext ctx, long validity, ImapMailboxClient.Fetched f) {
    AccessScope scope = round.scope;
    Received r = new Received(validity, f.uid(), f.raw(), f.tooLarge(), f.size());
    String key = m.id() + ":" + validity + ":" + f.uid();
    Outcome o;
    try {
      o = processor.process(scope, ctx, r);
    } catch (InboundMailProcessor.DuplicateMailException e) {
      // ON CONFLICT: another poller committed this mail first; this transaction rolled back.
      log.info("mailbox {}: message uid {} was committed by another poller", m.id(), f.uid());
      try {
        o = processor.recordDuplicate(scope, ctx, r);
      } catch (RuntimeException again) {
        log.warn(
            "mailbox {}: duplicate uid {} not recorded ({}); retrying next round",
            m.id(),
            f.uid(),
            again.getClass().getSimpleName());
        return Step.STOP;
      }
    } catch (RuntimeException e) {
      if (isTransient(e) && transientFailures.merge(key, 1, Integer::sum) < TRANSIENT_ATTEMPTS) {
        // A connection blip or deadlock: nothing was committed, try this message again next
        // round instead of filing it as "could not be processed".
        log.warn(
            "mailbox {}: message uid {} hit a transient database error ({}); retrying next round",
            m.id(),
            f.uid(),
            e.getClass().getSimpleName());
        return Step.STOP;
      }
      log.warn(
          "mailbox {}: message uid {} failed ({}), filing it in triage",
          m.id(),
          f.uid(),
          e.getClass().getSimpleName());
      try {
        o = processor.processFailed(scope, ctx, r);
      } catch (RuntimeException again) {
        log.error(
            "mailbox {}: message uid {} could not be filed ({}); retrying next round",
            m.id(),
            f.uid(),
            again.getClass().getSimpleName());
        recordFailureQuietly(scope, m, "processing failed");
        return Step.STOP;
      }
    }
    transientFailures.remove(key);
    // Committed: the files are processed now, off this mail's transaction.
    attachmentWorker.dispatch(o.attachmentJobs(), attachments.currentRound());
    if (o.duplicate()) {
      log.info("mailbox {}: message uid {} is a duplicate, logged", m.id(), f.uid());
    }
    round.outcomes.merge(o.outcome(), 1, Integer::sum);
    return Step.NEXT;
  }

  /** A database failure worth retrying as is: connection loss, deadlock, lock or query timeout. */
  static boolean isTransient(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof TransientDataAccessException
          || t instanceof RecoverableDataAccessException
          || t instanceof DataAccessResourceFailureException
          || t instanceof CannotCreateTransactionException
          || t instanceof SQLTransientException
          || t instanceof SQLRecoverableException) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  /** The admin's trace of a UIDVALIDITY re-baseline (audit log); never stops the round. */
  private void recordRebaselineQuietly(MailboxRow m) {
    try {
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("mailbox", m.id());
      d.put("reason", "UIDVALIDITY changed: restarted from new mail");
      audit.record(MailAuditEvent.MAILBOX_UIDVALIDITY_CHANGED, null, null, null, d);
    } catch (RuntimeException e) {
      log.warn("mailbox {}: re-baseline not audited ({})", m.id(), e.getClass().getSimpleName());
    }
  }

  private void recordFailureQuietly(AccessScope scope, MailboxRow m, String category) {
    try {
      Instant now = clock.instant();
      long minutes = 1L << Math.min(m.consecutiveFailures(), 5);
      Duration wait = Duration.ofMinutes(minutes);
      if (wait.compareTo(MAX_BACKOFF) > 0) {
        wait = MAX_BACKOFF;
      }
      mailboxes.recordFailure(scope, m.id(), category, now, now.plus(wait));
    } catch (RuntimeException e) {
      log.warn("mailbox {}: failure not recorded ({})", m.id(), e.getClass().getSimpleName());
    }
  }
}
