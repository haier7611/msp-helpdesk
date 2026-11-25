package com.ticketsystem.api.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Sends due outbox rows. One background thread per instance runs a round after every commit that
 * queued mail (the nudge) and on a fixed poll; rounds never overlap within an instance, and the
 * claim query's SKIP LOCKED keeps several instances from sending the same row.
 *
 * <p>Logs carry the outbox id, attempt number and a {@link SendFailure} summary only - never
 * addresses, subjects, bodies, tokens or exception messages.
 */
public class OutboxWorker implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

  /** Upper bound on rounds per run, so one run cannot hold the thread for ever. */
  private static final int MAX_ROUNDS = 50;

  private static final List<MailKind> ALL_KINDS = List.of(MailKind.values());

  /** Stored as the row's last error when every recipient was on a reserved test domain. */
  static final String NOT_EMAILED = "not emailed: test address";

  /** A row the reserved-domain guard did not send: neither a success nor a mailbox failure. */
  private static final SendFailure NOT_SENT = new SendFailure(true, null, NOT_EMAILED);

  private final OutboxRepository repository;
  private final MailTransport envTransport;
  private final ConfiguredSender configured;
  private final Function<SmtpAccount, MailTransport> accountTransport;
  private final MailSettings settings;
  private final RetryPolicy retryPolicy;
  private final Clock clock;
  private final AtomicBoolean nudgePending = new AtomicBoolean();
  private volatile ScheduledExecutorService executor;

  OutboxWorker(
      OutboxRepository repository, MailTransport transport, MailSettings settings, Clock clock) {
    this(repository, transport, null, null, settings, clock);
  }

  /**
   * @param envTransport the environment bootstrap sender, or null when SMTP_HOST is empty
   * @param configured the admin-configured mailbox lookup, or null
   * @param accountTransport builds a transport for a configured mailbox
   */
  OutboxWorker(
      OutboxRepository repository,
      MailTransport envTransport,
      ConfiguredSender configured,
      Function<SmtpAccount, MailTransport> accountTransport,
      MailSettings settings,
      Clock clock) {
    this.repository = repository;
    this.envTransport = envTransport;
    this.configured = configured;
    this.accountTransport = accountTransport;
    this.settings = settings;
    this.retryPolicy = new RetryPolicy(settings.retryDelays());
    this.clock = clock;
  }

  /** A sender: the configured mailbox ({@code mailboxId} set) or the environment sender. */
  private record Route(MailTransport transport, Long mailboxId) {}

  /**
   * Picks this round's one sender for every kind of mail: the enabled mailbox with outgoing
   * settings and a secret (lowest id) when there is one, else the environment sender, else null
   * (rows stay queued without using an attempt). Looked up at the start of every round, so
   * connecting, changing or removing a mailbox takes effect without a restart. Ticket mail and
   * account mail (invitations, password resets, sign-in links, security notices) follow the same
   * route. A configured mailbox that cannot send now makes all mail wait: it is never sent through
   * the environment sender instead, which would change its From address (and on the demo deliver
   * sign-in links to the mail catcher rather than the recipient).
   */
  private Route route() {
    Route env = envTransport == null ? null : new Route(envTransport, null);
    if (configured != null) {
      try {
        Optional<SmtpAccount> account = configured.current();
        if (account.isPresent()) {
          return new Route(accountTransport.apply(account.get()), account.get().mailboxId());
        }
      } catch (ConfiguredSender.Unavailable e) {
        if (!e.backingOff()) {
          // Logged and recorded once per attempt; between attempts the sender backs off quietly.
          log.warn(
              "Outbox: mailbox {} cannot send ({}); mail stays queued",
              e.mailboxId(),
              e.category());
          configured.recordResult(e.mailboxId(), e.category());
        }
        return null;
      }
    }
    return env;
  }

  /**
   * Sends every row that is due now, in batches, and returns how many were attempted. Safe to call
   * from several threads or instances at once. Does nothing when no sender is configured.
   */
  public int runOnce() {
    Route route = route();
    if (route == null) {
      return 0;
    }
    int recovered = repository.recoverStale(settings.staleAfter(), retryPolicy.maxAttempts());
    if (recovered > 0) {
      log.warn("Outbox: {} row(s) were interrupted while sending and are queued again", recovered);
    }
    return sendDue(route, ALL_KINDS);
  }

  /** Sends the due rows of these kinds through one sender; returns how many were attempted. */
  private int sendDue(Route route, List<MailKind> kinds) {
    int total = 0;
    String mailboxError = null;
    boolean anySent = false;
    for (int round = 0; round < MAX_ROUNDS; round++) {
      List<OutboxMessage> batch = repository.claimDue(settings.batchSize(), kinds);
      for (OutboxMessage m : batch) {
        SendFailure f = process(route, m);
        if (f == null) {
          anySent = true;
        } else if (f.mailboxLevel()) {
          mailboxError = f.summary();
        }
      }
      total += batch.size();
      if (batch.size() < settings.batchSize()) {
        break;
      }
    }
    Long mailboxId = route.mailboxId();
    if (mailboxId != null && total > 0) {
      // Only sign-in and connection failures describe the mailbox; a rejected address or message
      // is that email's problem. Anything sent, or only per-email failures, means
      // the mailbox itself works.
      configured.recordResult(mailboxId, anySent ? null : mailboxError);
    }
    return total;
  }

  /** Sends one row; returns the failure, or null when sent (or expired before sending). */
  private SendFailure process(Route route, OutboxMessage original) {
    Instant now = clock.instant();
    OutboxMessage m = original;
    if (m.expiresAt() != null && !now.isBefore(m.expiresAt())) {
      repository.markFailed(m.id(), "link expired before delivery");
      log.warn("Outbox {} failed: link expired before delivery", m.id());
      return null;
    }
    if (route.mailboxId() != null) {
      m = withoutReservedRecipients(m);
      if (m == null) {
        repository.markFailed(original.id(), NOT_EMAILED);
        return NOT_SENT;
      }
    }
    try {
      route.transport().send(m);
    } catch (Exception e) {
      SendFailure failure = SendFailure.classify(e);
      handleFailure(m, failure, now);
      return failure;
    }
    repository.markSent(m.id());
    log.info("Outbox {} sent (attempt {})", m.id(), m.attempts());
    return null;
  }

  /**
   * The reserved-domain guard: recipients on .example, .test, .invalid or .localhost are never sent
   * through a mailbox's SMTP, whatever the kind of mail. They are dropped with a log line (count
   * only, never an address) and, on the row's first attempt, a hidden system entry on its ticket.
   * Returns the message to the remaining recipients, or null when none remain.
   */
  private OutboxMessage withoutReservedRecipients(OutboxMessage m) {
    List<String> to =
        new ArrayList<>(m.to().stream().filter(a -> !ReservedDomains.isReserved(a)).toList());
    List<String> cc =
        new ArrayList<>(m.cc().stream().filter(a -> !ReservedDomains.isReserved(a)).toList());
    int dropped = m.to().size() + m.cc().size() - to.size() - cc.size();
    if (dropped == 0) {
      return m;
    }
    log.info(
        "Outbox {}: {} recipient(s) on a reserved test domain not emailed through the mailbox",
        m.id(),
        dropped);
    if (m.ticketId() != null && m.attempts() == 1) {
      try {
        configured.notEmailed(m.ticketId());
      } catch (RuntimeException e) {
        // The note is a courtesy; it never stops the send. Type only, no message.
        log.warn(
            "Outbox {}: could not note the test address ({})",
            m.id(),
            e.getClass().getSimpleName());
      }
    }
    if (to.isEmpty() && cc.isEmpty()) {
      return null;
    }
    if (to.isEmpty()) {
      to.add(cc.remove(0));
    }
    return m.withRecipients(List.copyOf(to), List.copyOf(cc));
  }

  private void handleFailure(OutboxMessage m, SendFailure failure, Instant now) {
    if (failure.permanent()) {
      repository.markFailed(m.id(), failure.summary());
      log.warn(
          "Outbox {} attempt {} failed permanently: {}", m.id(), m.attempts(), failure.summary());
      return;
    }
    Optional<Duration> delay = retryPolicy.delayAfter(m.attempts());
    if (delay.isEmpty()) {
      String reason = "gave up after " + m.attempts() + " attempts: " + failure.summary();
      repository.markFailed(m.id(), reason);
      log.warn("Outbox {} failed: {}", m.id(), reason);
      return;
    }
    if (m.expiresAt() != null && now.plus(delay.get()).isAfter(m.expiresAt())) {
      String reason = "link expires before the next attempt: " + failure.summary();
      repository.markFailed(m.id(), reason);
      log.warn("Outbox {} failed: {}", m.id(), reason);
      return;
    }
    repository.markRetry(m.id(), delay.get(), failure.summary());
    log.warn(
        "Outbox {} attempt {} failed: {}; retry in {} s",
        m.id(),
        m.attempts(),
        failure.summary(),
        delay.get().toSeconds());
  }

  /** Runs a round soon (after a commit that queued mail). Coalesces bursts into one round. */
  void nudge() {
    ScheduledExecutorService ex = executor;
    if (ex != null && nudgePending.compareAndSet(false, true)) {
      try {
        ex.execute(
            () -> {
              nudgePending.set(false);
              safeRun();
            });
      } catch (RejectedExecutionException e) {
        nudgePending.set(false); // shutting down; the next start's poll picks the row up
      }
    }
  }

  private void safeRun() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      // Usually the database is unreachable; the next poll retries. Type only, no message.
      log.error("Outbox round failed: {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    if (envTransport == null && configured == null) {
      log.warn("Outbound mail is not configured (SMTP_HOST is empty): emails are queued, not sent");
      return;
    }
    if (envTransport == null) {
      log.info(
          "SMTP_HOST is empty: emails are sent once an admin configures a sending mailbox"
              + " (ADR-046)");
    }
    ScheduledExecutorService ex =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "outbox-worker");
              t.setDaemon(true);
              return t;
            });
    long poll = settings.pollInterval().toMillis();
    // First poll one interval after start: rows queued while the app was down go out within it.
    ex.scheduleWithFixedDelay(this::safeRun, poll, poll, TimeUnit.MILLISECONDS);
    executor = ex;
  }

  @Override
  public synchronized void stop() {
    ScheduledExecutorService ex = executor;
    executor = null;
    if (ex == null) {
      return;
    }
    ex.shutdown();
    try {
      if (!ex.awaitTermination(30, TimeUnit.SECONDS)) {
        ex.shutdownNow();
      }
    } catch (InterruptedException e) {
      ex.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return executor != null;
  }

  @Override
  public boolean isAutoStartup() {
    return settings.autoStart();
  }
}
