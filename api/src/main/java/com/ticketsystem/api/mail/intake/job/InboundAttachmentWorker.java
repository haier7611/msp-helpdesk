package com.ticketsystem.api.mail.intake.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.PrincipalAccessReader;
import com.ticketsystem.api.auth.UserStatus;
import com.ticketsystem.api.mail.inbound.BlockListMatcher;
import com.ticketsystem.api.mail.inbound.InboundAnalysis;
import com.ticketsystem.api.mail.inbound.InboundLimits;
import com.ticketsystem.api.mail.inbound.InboundMailEngine;
import com.ticketsystem.api.mail.intake.InboundAttachments;
import com.ticketsystem.api.mail.intake.InboundMailProcessor;
import com.ticketsystem.api.mail.intake.clientdata.InboundAttachmentJobRepository;
import com.ticketsystem.api.mail.intake.clientdata.InboundAttachmentJobRepository.Claimed;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.agent.InternalNoteService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Second step of ticket first, files after: processes the files of an email after its ticket or
 * comment was committed.
 *
 * <ul>
 *   <li>The poller (and a Blocked mail restore) hands over the job ids of each committed mail
 *       ({@link #dispatch}). One worker thread ({@code mail-attachments}) runs them in order, so
 *       attachment work never holds up the next mail, the poller or any other scheduled job, and at
 *       most one email's files are decoded at a time.
 *   <li>A job re-reads the email from the kept raw {@code .eml} (90 days), runs the same checks
 *       through {@link InboundAttachments#store} (type from the bytes, SVG refused, images
 *       re-encoded without EXIF, size and megapixel limits, per-email and per-round image budget),
 *       then in one transaction binds the stored files to the comment, replaces its placeholder
 *       body (inline images end as {@code src="attachment:<public id>"}) and lists refused files in
 *       a "Not attached" system entry. A file that fails never blocks the others.
 *   <li>Durable: the job row is written in the mail's own transaction. A job claimed by a worker
 *       holds a lease; a JVM that dies mid-job leaves it to be claimed again once the lease runs
 *       out. {@link #dispatchDue} (called at the end of every poll round) picks up jobs a restart
 *       or a full queue left behind. A failed attempt is retried after {@link #RETRY_DELAY}; after
 *       {@link #MAX_ATTEMPTS} attempts (counted when claimed, so a JVM that died running the job
 *       counts too) the job is given up with a system entry ("could not be processed, the original
 *       email is kept for 90 days").
 * </ul>
 *
 * With {@code app.mail.inbound.attachments.async=false} (the test suite) a job runs on the calling
 * thread straight after its mail's commit: same steps, same transactions, deterministic order. Logs
 * carry job and ticket ids, counts and times only.
 */
@Component
public class InboundAttachmentWorker {

  private static final Logger log = LoggerFactory.getLogger(InboundAttachmentWorker.class);

  static final Duration LEASE = Duration.ofMinutes(5);
  static final Duration RETRY_DELAY = Duration.ofMinutes(1);

  /**
   * Attempts per job, counted when a job is claimed (not when a failure is recorded), so a job
   * whose processing kills the JVM (out of memory, a native decoder crash, a hard kill) is also
   * given up after this many claims instead of crash-looping the API on every lease expiry.
   */
  public static final int MAX_ATTEMPTS = 3;

  static final int QUEUE = 500;
  static final int SWEEP_BATCH = 100;

  public static final String GAVE_UP =
      "Attachments could not be processed (the original email is kept for 90 days).";

  /** What one {@link #runOnce} call did (tests). */
  public enum Result {
    DONE,
    SKIPPED,
    RETRY,
    FAILED
  }

  private final InboundAttachmentJobRepository jobs;
  private final MailboxRepository mailboxes;
  private final InboundAttachments attachments;
  private final TicketService tickets;
  private final InternalNoteService notes;
  private final PrincipalAccessReader principals;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean async;
  private final ThreadPoolExecutor executor;
  private final Set<Long> queued = ConcurrentHashMap.newKeySet();

  /** Test hook: hold every dispatch, as if the JVM stopped right after the mail's commit. */
  private volatile boolean paused;

  public InboundAttachmentWorker(
      InboundAttachmentJobRepository jobs,
      MailboxRepository mailboxes,
      InboundAttachments attachments,
      TicketService tickets,
      InternalNoteService notes,
      PrincipalAccessReader principals,
      PlatformTransactionManager transactions,
      Clock clock,
      @Value("${app.mail.inbound.attachments.async:true}") boolean async) {
    this.jobs = jobs;
    this.mailboxes = mailboxes;
    this.attachments = attachments;
    this.tickets = tickets;
    this.notes = notes;
    this.principals = principals;
    // Always a transaction of its own, never the caller's (a job runs after its mail's commit).
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
    this.async = async;
    this.executor =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE),
            r -> {
              Thread t = new Thread(r, "mail-attachments");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Hands committed jobs to the worker, charged to {@code round} (the poll round that read the
   * mail; null = a round of their own). Never throws: a job that cannot be queued stays in the
   * table for {@link #dispatchDue}.
   */
  public void dispatch(List<Long> jobIds, InboundAttachments.Round round) {
    if (paused || jobIds.isEmpty()) {
      return;
    }
    InboundAttachments.Round charged = round == null ? InboundAttachments.Round.detached() : round;
    for (Long id : jobIds) {
      if (!async) {
        runQuietly(id, charged);
        continue;
      }
      if (!queued.add(id)) {
        continue; // already waiting in the queue
      }
      try {
        executor.execute(
            () -> {
              queued.remove(id);
              runQuietly(id, charged);
            });
      } catch (RejectedExecutionException e) {
        queued.remove(id);
        log.warn("attachment job {}: queue full, left for the next sweep", id);
      }
    }
  }

  /** Dispatches every job that is due (left by a restart, a full queue or a retry). */
  public void dispatchDue(InboundAttachments.Round round) {
    if (paused) {
      return;
    }
    try {
      List<Long> due = jobs.due(system(), clock.instant(), SWEEP_BATCH);
      dispatch(due, round);
    } catch (RuntimeException e) {
      log.warn("attachment jobs: sweep failed ({})", e.getClass().getSimpleName());
    }
  }

  /** Test hook (see {@link #paused}). */
  public void pauseForTest(boolean pause) {
    this.paused = pause;
  }

  /** Test hook: waits until the queue is empty and the worker idle. */
  public boolean awaitIdle(Duration timeout) throws InterruptedException {
    long end = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < end) {
      if (executor.getQueue().isEmpty() && executor.getActiveCount() == 0 && queued.isEmpty()) {
        return true;
      }
      Thread.sleep(20);
    }
    return false;
  }

  private void runQuietly(long id, InboundAttachments.Round round) {
    try {
      attachments.inRound(round, () -> runOnce(id));
    } catch (RuntimeException e) {
      log.warn("attachment job {}: failed ({})", id, e.getClass().getSimpleName());
    }
  }

  /** One attempt at job {@code id} on this thread, in this thread's round (if any). */
  public Result runOnce(long id) {
    AccessScope system = system();
    Claimed job = tx.execute(status -> jobs.claim(system, id, clock.instant(), LEASE).orElse(null));
    if (job == null) {
      return Result.SKIPPED; // finished, or another worker holds it
    }
    if (job.attempts() > MAX_ATTEMPTS) {
      // Every earlier attempt was claimed and never came back (the JVM died while running it).
      return gaveUpAfterCrashes(system, job);
    }
    long started = System.nanoTime();
    try {
      InboundAnalysis.Analysed a = analyse(system, job);
      int[] counts = new int[2];
      tx.executeWithoutResult(
          status -> {
            AccessScope owner =
                job.ownerUserId() == null ? system : supporterScope(job.ownerUserId());
            String body =
                job.replyOnly()
                    ? InboundMailProcessor.replyBody(a)
                    : InboundMailProcessor.newTicketBody(a);
            InboundAttachments.Stored s = attachments.store(owner, a, body, job.replyOnly());
            if (job.publicCommentId() != null) {
              tickets.completeEmailFiles(owner, job.publicCommentId(), s.bodyHtml(), s.ids());
            } else {
              notes.completeEmailFiles(owner, job.internalNoteId(), s.bodyHtml(), s.ids());
            }
            String note = InboundAttachments.notAttachedNote(s);
            if (note != null) {
              notes.addSystemEntry(system, job.ticketId(), "EMAIL_ATTACHMENTS", note);
            }
            jobs.finish(system, job.id());
            counts[0] = s.ids().size();
            counts[1] = s.notAttached().size();
          });
      log.info(
          "attachment job {} (ticket {}): {} stored, {} not attached, {} ms",
          job.id(),
          job.ticketId(),
          counts[0],
          counts[1],
          (System.nanoTime() - started) / 1_000_000);
      return Result.DONE;
    } catch (RuntimeException e) {
      return failed(system, job, e);
    }
  }

  /** The email again, from the kept raw copy. */
  private InboundAnalysis.Analysed analyse(AccessScope system, Claimed job) {
    InboundAttachmentJobRepository.RawMail raw =
        jobs.raw(system, job.inboundMailId()).orElseThrow(() -> new GiveUp("mail log row gone"));
    if (raw.raw() == null) {
      throw new GiveUp("raw email purged");
    }
    String address =
        raw.mailboxId() == null
            ? null
            : mailboxes.find(system, raw.mailboxId()).map(m -> m.address()).orElse(null);
    InboundMailEngine engine =
        new InboundMailEngine(
            address == null ? "support@unconfigured.invalid" : address,
            Set.of(),
            InboundLimits.DEFAULT);
    if (!(engine.analyse(raw.raw(), BlockListMatcher.none())
        instanceof InboundAnalysis.Analysed a)) {
      throw new GiveUp("email no longer parses");
    }
    return a;
  }

  /**
   * A job claimed more than {@link #MAX_ATTEMPTS} times without a recorded outcome: its processing
   * keeps killing the JVM. It is never processed again: FAILED, with the visible entry on the
   * ticket. If even that cannot be recorded, the lease runs out and the next claim ends here again.
   */
  private Result gaveUpAfterCrashes(AccessScope system, Claimed job) {
    log.error(
        "attachment job {} (ticket {}): claimed {} times without finishing (the worker died each"
            + " time), giving up",
        job.id(),
        job.ticketId(),
        job.attempts() - 1);
    try {
      recordGaveUp(system, job);
    } catch (RuntimeException e) {
      log.warn(
          "attachment job {}: failure not recorded ({})", job.id(), e.getClass().getSimpleName());
    }
    return Result.FAILED;
  }

  private void recordGaveUp(AccessScope system, Claimed job) {
    tx.executeWithoutResult(
        status -> {
          jobs.fail(system, job.id(), clock.instant());
          notes.addSystemEntry(system, job.ticketId(), "EMAIL_ATTACHMENTS", GAVE_UP);
        });
  }

  private Result failed(AccessScope system, Claimed job, RuntimeException e) {
    boolean giveUp = e instanceof GiveUp || job.attempts() >= MAX_ATTEMPTS;
    log.warn(
        "attachment job {} (ticket {}): attempt {} failed ({}){}",
        job.id(),
        job.ticketId(),
        job.attempts(),
        e instanceof GiveUp g ? g.getMessage() : e.getClass().getSimpleName(),
        giveUp ? ", giving up" : ", retrying later");
    try {
      if (!giveUp) {
        tx.executeWithoutResult(
            status -> jobs.retryAt(system, job.id(), clock.instant().plus(RETRY_DELAY)));
        return Result.RETRY;
      }
      recordGaveUp(system, job);
      return Result.FAILED;
    } catch (RuntimeException again) {
      // The job stays RUNNING until its lease runs out; the next sweep claims it again, and once
      // the claims exceed MAX_ATTEMPTS it is given up there (gaveUpAfterCrashes).
      log.warn(
          "attachment job {}: failure not recorded ({})",
          job.id(),
          again.getClass().getSimpleName());
      return giveUp ? Result.FAILED : Result.RETRY;
    }
  }

  private AccessScope supporterScope(long userId) {
    return principals
        .read(userId)
        .filter(p -> p.status() == UserStatus.ACTIVE)
        .map(PrincipalAccessReader.PrincipalAccess::toScope)
        .orElseThrow(() -> new GiveUp("supporter no longer active"));
  }

  private static AccessScope system() {
    return AccessScope.system("inbound attachments");
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  /** A failure that retrying cannot fix. */
  static final class GiveUp extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GiveUp(String reason) {
      super(reason, null, false, false);
    }
  }
}
