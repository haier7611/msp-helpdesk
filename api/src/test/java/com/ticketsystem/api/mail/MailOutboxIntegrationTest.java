package com.ticketsystem.api.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.ticketsystem.api.TestcontainersConfiguration;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox end to end on real Postgres (Testcontainers) and a real SMTP server (GreenMail,
 * in-process on 127.0.0.1). The worker's own thread is off here (auto-start=false) so each test
 * drives {@link OutboxWorker#runOnce()} itself; the nudge test starts it explicitly.
 */
@SpringBootTest(
    properties = {
      "app.mail.support-name=Example MSP Support",
      "app.mail.from-address=support@helpdesk.example",
      "app.mail.smtp.security=none",
      "app.mail.smtp.connect-timeout=2s",
      "app.mail.smtp.read-timeout=5s",
      "app.mail.outbox.auto-start=false",
      "app.mail.outbox.poll-interval=1h",
      "app.mail.outbox.batch-size=5",
      "app.mail.outbox.retry-delays=1m,2m,4m,8m,16m,32m"
    })
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class MailOutboxIntegrationTest {

  /**
   * The SMTP port, fixed by the first {@link #startSmtp()} (fresh ports, with retries) and kept for
   * every restart because the Spring context is configured with it.
   */
  static int smtpPort;

  static final String TOKEN = "tok_SYNTHETIC_9f8e7d6c5b4a";
  static GreenMail smtp;

  @DynamicPropertySource
  static void smtpServer(DynamicPropertyRegistry registry) {
    registry.add("app.mail.smtp.host", () -> "127.0.0.1");
    registry.add(
        "app.mail.smtp.port",
        () -> {
          startSmtp();
          return smtpPort;
        });
  }

  @Autowired MailOutbox outbox;
  @Autowired SystemMailService systemMail;
  @Autowired OutboxWorker worker;
  @Autowired OutboxRepository repository;
  @Autowired MailSettings settings;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  static synchronized void startSmtp() {
    if (smtp == null) {
      TestGreenMail.Started started =
          smtpPort == 0
              ? TestGreenMail.start(ServerSetup.PROTOCOL_SMTP)
              : TestGreenMail.start(() -> smtpPort, ServerSetup.PROTOCOL_SMTP);
      smtp = started.greenMail();
      smtpPort = started.port(ServerSetup.PROTOCOL_SMTP);
    }
  }

  static synchronized void stopSmtp() {
    if (smtp != null) {
      smtp.stop();
      smtp = null;
    }
  }

  @AfterAll
  static void shutdown() {
    stopSmtp();
  }

  @BeforeEach
  void reset() throws Exception {
    startSmtp();
    smtp.purgeEmailFromAllMailboxes();
    jdbc.update("DELETE FROM outbox_message");
  }

  // --- helpers --------------------------------------------------------------------------------

  static OutboundEmail email(String subject) {
    return OutboundEmail.builder(MailKind.CLIENT_FACING)
        .to("sam@client.example")
        .reference("NWD-001")
        .subject(subject)
        .body("<p>Body of " + subject + "</p>", "Body of " + subject)
        .build();
  }

  MailOutbox.QueuedEmail commit(OutboundEmail email) {
    return tx.execute(status -> outbox.enqueue(email));
  }

  Map<String, Object> row(long id) {
    return jdbc.queryForMap("SELECT * FROM outbox_message WHERE id = ?", id);
  }

  void makeDue(long id) {
    jdbc.update("UPDATE outbox_message SET next_attempt_at = now() WHERE id = ?", id);
  }

  /** Seconds from the database's now() until the row's next attempt. */
  long secondsUntilNextAttempt(long id) {
    return jdbc.queryForObject(
        "SELECT EXTRACT(EPOCH FROM next_attempt_at - now())::bigint FROM outbox_message WHERE id = ?",
        Long.class,
        id);
  }

  static MailOutbox.QueuedEmail passwordReset(Instant expiresAt, SystemMailService service) {
    return service.send(
        SystemEmail.PASSWORD_RESET,
        "alex.tran@helpdesk.example",
        Map.of(
            "recipientName",
            "Alex Tran",
            "link",
            "https://support.example.test/reset?token=" + TOKEN,
            "validFor",
            "15 minutes"),
        expiresAt);
  }

  // --- transactional outbox -------------------------------------------------------------------

  @Test
  void aRolledBackTransactionSendsNothingAndACommittedOneSendsExactlyOnce() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      outbox.enqueue(email("rolled back"));
                      throw new IllegalStateException("business change failed");
                    }))
        .isInstanceOf(IllegalStateException.class);
    commit(email("committed"));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_message", Integer.class))
        .isEqualTo(1);
    worker.runOnce();
    worker.runOnce();

    assertThat(smtp.getReceivedMessages()).hasSize(1);
    assertThat(subjectOf(smtp.getReceivedMessages()[0])).isEqualTo("[NWD-001] committed");
  }

  @Test
  void enqueueRefusesToRunOutsideATransaction() {
    assertThatThrownBy(() -> outbox.enqueue(email("no tx")))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void aSentMessageHasItsStoredMessageIdAutoSubmittedAndBothParts() throws Exception {
    MailOutbox.QueuedEmail first = commit(email("first"));
    MailOutbox.QueuedEmail second = commit(email("second"));
    assertThat(first.messageId())
        .isNotEqualTo(second.messageId())
        .matches("<[0-9a-f-]{36}@helpdesk\\.example>");

    assertThat(worker.runOnce()).isEqualTo(2);

    MimeMessage[] received = smtp.getReceivedMessages();
    assertThat(received).hasSize(2);
    Set<String> ids = new HashSet<>();
    for (MimeMessage m : received) {
      ids.add(m.getMessageID());
      assertThat(m.getHeader("Auto-Submitted", null)).isEqualTo("auto-generated");
      assertThat(m.getFrom()[0].toString())
          .isEqualTo("Example MSP Support <support@helpdesk.example>");
      assertThat(m.getContentType()).startsWith("multipart/alternative");
      MimeMultipart mp = (MimeMultipart) m.getContent();
      assertThat(mp.getCount()).isEqualTo(2);
      assertThat(mp.getBodyPart(0).isMimeType("text/plain")).isTrue();
      assertThat(mp.getBodyPart(1).isMimeType("text/html")).isTrue();
    }
    assertThat(ids).containsExactlyInAnyOrder(first.messageId(), second.messageId());

    Map<String, Object> r = row(first.id());
    assertThat(r.get("status")).isEqualTo("sent");
    assertThat(r.get("attempts")).isEqualTo(1);
    assertThat(r.get("sent_at")).isNotNull();
    assertThat(r.get("message_id")).isEqualTo(first.messageId());
    assertThat(r.get("subject")).isEqualTo("[NWD-001] first");
  }

  @Test
  void aSystemTemplateIsDeliveredRendered() throws Exception {
    MailOutbox.QueuedEmail q =
        tx.execute(
            s ->
                systemMail.send(
                    SystemEmail.FIRST_ADMIN_SETUP,
                    "alex.tran@helpdesk.example",
                    Map.of(
                        "recipientName",
                        "Alex <Tran>",
                        "signInLink",
                        "https://support.example.test"),
                    null));
    worker.runOnce();

    MimeMessage m = smtp.getReceivedMessages()[0];
    assertThat(m.getMessageID()).isEqualTo(q.messageId());
    assertThat(m.getSubject()).isEqualTo("Your Example MSP Support administrator account is ready");
    MimeMultipart mp = (MimeMultipart) m.getContent();
    assertThat((String) mp.getBodyPart(1).getContent()).contains("Alex &lt;Tran&gt;");
    assertThat((String) mp.getBodyPart(0).getContent()).contains("Alex <Tran>");
  }

  // --- retries --------------------------------------------------------------------------------

  @Test
  void smtpDownRetriesOnTheBackoffScheduleAndSendsOnceSmtpIsBack() {
    stopSmtp();
    long id = commit(email("retry me")).id();

    worker.runOnce();
    Map<String, Object> r = row(id);
    assertThat(r.get("status")).isEqualTo("queued");
    assertThat(r.get("attempts")).isEqualTo(1);
    assertThat((String) r.get("last_error")).startsWith("connection or transport error");
    assertThat(secondsUntilNextAttempt(id)).isBetween(50L, 61L);

    worker.runOnce(); // not due yet: nothing happens
    assertThat(row(id).get("attempts")).isEqualTo(1);

    makeDue(id);
    worker.runOnce();
    assertThat(row(id).get("attempts")).isEqualTo(2);
    assertThat(secondsUntilNextAttempt(id)).isBetween(110L, 121L);

    startSmtp();
    makeDue(id);
    worker.runOnce();
    r = row(id);
    assertThat(r.get("status")).isEqualTo("sent");
    assertThat(r.get("attempts")).isEqualTo(3);
    assertThat(r.get("last_error")).isNull();
    assertThat(smtp.getReceivedMessages()).hasSize(1);
  }

  @Test
  void smtpDownForGoodFailsAfterSevenAttempts() {
    stopSmtp();
    long id = commit(email("never delivered")).id();
    long[] expectedDelays = {60, 120, 240, 480, 960, 1920};

    for (int attempt = 1; attempt <= 6; attempt++) {
      makeDue(id);
      worker.runOnce();
      assertThat(row(id).get("status")).as("after attempt %d", attempt).isEqualTo("queued");
      assertThat(row(id).get("attempts")).isEqualTo(attempt);
      assertThat(secondsUntilNextAttempt(id))
          .isBetween(expectedDelays[attempt - 1] - 10, expectedDelays[attempt - 1] + 1);
    }
    makeDue(id);
    worker.runOnce();

    Map<String, Object> r = row(id);
    assertThat(r.get("status")).isEqualTo("failed");
    assertThat(r.get("attempts")).isEqualTo(7);
    assertThat((String) r.get("last_error")).startsWith("gave up after 7 attempts");

    startSmtp();
    makeDue(id);
    worker.runOnce(); // failed is terminal
    assertThat(row(id).get("attempts")).isEqualTo(7);
    assertThat(smtp.getReceivedMessages()).isEmpty();
  }

  @Test
  void anSmtp5xxFailsAtOnceWithoutRetry() throws Exception {
    try (RejectingSmtpServer rejecting = RejectingSmtpServer.start()) {
      MailSettings rejectSettings =
          MailSettings.from(
              new MailProperties(
                  "Example MSP Support",
                  "support@helpdesk.example",
                  null,
                  new MailProperties.Smtp(
                      "127.0.0.1",
                      rejecting.port(),
                      "none",
                      null,
                      null,
                      Duration.ofSeconds(2),
                      Duration.ofSeconds(5)),
                  null));
      OutboxWorker rejectingWorker =
          new OutboxWorker(
              repository, new SmtpMailTransport(rejectSettings), rejectSettings, Clock.systemUTC());
      long id = commit(email("rejected")).id();

      rejectingWorker.runOnce();

      Map<String, Object> r = row(id);
      assertThat(r.get("status")).isEqualTo("failed");
      assertThat(r.get("attempts")).isEqualTo(1);
      assertThat(r.get("last_error")).isEqualTo("SMTP 550 (permanent)");
      assertThat(rejecting.rcptCommands.get()).isEqualTo(1);
    }
  }

  @Test
  void aRowLeftInSendingByACrashIsRetried() {
    long id = commit(email("interrupted")).id();
    jdbc.update(
        "UPDATE outbox_message SET status = 'sending', attempts = 1,"
            + " claimed_at = now() - interval '11 minutes' WHERE id = ?",
        id);

    worker.runOnce();

    assertThat(row(id).get("status")).isEqualTo("sent");
    assertThat(row(id).get("attempts")).isEqualTo(2);
  }

  // --- concurrency ----------------------------------------------------------------------------

  @Test
  void aClaimedRowIsSkippedNotSharedByAConcurrentClaim() throws Exception {
    for (int i = 0; i < 6; i++) {
      commit(email("row " + i));
    }
    ExecutorService other = Executors.newSingleThreadExecutor();
    try {
      tx.executeWithoutResult(
          status -> {
            List<OutboxMessage> mine = repository.claimDue(3); // row locks held until rollback
            Future<List<OutboxMessage>> theirs = other.submit(() -> repository.claimDue(3));
            List<OutboxMessage> otherClaim;
            try {
              otherClaim = theirs.get(5, TimeUnit.SECONDS); // SKIP LOCKED: does not wait
            } catch (Exception e) {
              throw new AssertionError("the second claim blocked or failed", e);
            }
            Set<Long> a = new HashSet<>(mine.stream().map(OutboxMessage::id).toList());
            Set<Long> b = new HashSet<>(otherClaim.stream().map(OutboxMessage::id).toList());
            assertThat(a).hasSize(3);
            assertThat(b).hasSize(3).doesNotContainAnyElementsOf(a);
            status.setRollbackOnly();
          });
    } finally {
      other.shutdownNow();
    }
  }

  @Test
  void twoWorkersRunningAtOnceNeverSendTheSameRowTwice() throws Exception {
    int total = 30;
    for (int i = 0; i < total; i++) {
      commit(email("parallel " + i));
    }
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger attempted = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<?>> runs = new ArrayList<>();
    for (int t = 0; t < 2; t++) {
      runs.add(
          pool.submit(
              () -> {
                go.await();
                int n;
                while ((n = worker.runOnce()) > 0) {
                  attempted.addAndGet(n);
                }
                return null;
              }));
    }
    go.countDown();
    for (Future<?> run : runs) {
      run.get(60, TimeUnit.SECONDS);
    }
    pool.shutdown();

    assertThat(attempted.get()).isEqualTo(total);
    MimeMessage[] received = smtp.getReceivedMessages();
    assertThat(received).hasSize(total);
    Set<String> ids = new HashSet<>();
    for (MimeMessage m : received) {
      ids.add(m.getMessageID());
    }
    assertThat(ids).hasSize(total);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE status = 'sent' AND attempts = 1",
                Integer.class))
        .isEqualTo(total);
  }

  // --- sensitive mail -------------------------------------------------------------------------

  @Test
  void sensitiveBodiesAreBlankedAfterSending() throws Exception {
    MailOutbox.QueuedEmail q =
        tx.execute(s -> passwordReset(Instant.now().plusSeconds(900), systemMail));
    assertThat((String) row(q.id()).get("text_body")).contains(TOKEN);

    worker.runOnce();

    Map<String, Object> r = row(q.id());
    assertThat(r.get("status")).isEqualTo("sent");
    assertThat(r.get("html_body")).isEqualTo("");
    assertThat(r.get("text_body")).isEqualTo("");
    assertThat(r.get("sensitive")).isEqualTo(true);
    MimeMultipart mp = (MimeMultipart) smtp.getReceivedMessages()[0].getContent();
    assertThat((String) mp.getBodyPart(0).getContent()).contains(TOKEN);
  }

  @Test
  void sensitiveBodiesAreBlankedWhenTheLinkExpiresBeforeTheNextAttempt() {
    stopSmtp();
    MailOutbox.QueuedEmail q =
        tx.execute(s -> passwordReset(Instant.now().plusSeconds(30), systemMail));

    worker.runOnce(); // fails; next attempt (1 min) would be after the link expires

    Map<String, Object> r = row(q.id());
    assertThat(r.get("status")).isEqualTo("failed");
    assertThat(r.get("attempts")).isEqualTo(1);
    assertThat((String) r.get("last_error")).startsWith("link expires before the next attempt");
    assertThat(r.get("html_body")).isEqualTo("");
    assertThat(r.get("text_body")).isEqualTo("");
  }

  @Test
  void anExpiredLinkIsNeverSent() {
    long id =
        commit(
                OutboundEmail.builder(MailKind.SYSTEM)
                    .to("sam@client.example")
                    .subject("Your sign-in link")
                    .body("<p>" + TOKEN + "</p>", TOKEN)
                    .sensitive(Instant.now().minusSeconds(1))
                    .build())
            .id();

    worker.runOnce();

    Map<String, Object> r = row(id);
    assertThat(r.get("status")).isEqualTo("failed");
    assertThat(r.get("last_error")).isEqualTo("link expired before delivery");
    assertThat(r.get("text_body")).isEqualTo("");
    assertThat(smtp.getReceivedMessages()).isEmpty();
  }

  // --- logs -----------------------------------------------------------------------------------

  @Test
  void noLogLineContainsATokenBodyOrAddress(CapturedOutput output) {
    tx.execute(s -> passwordReset(Instant.now().plusSeconds(900), systemMail));
    worker.runOnce(); // sent
    stopSmtp();
    tx.execute(s -> passwordReset(Instant.now().plusSeconds(900), systemMail));
    worker.runOnce(); // temporary failure, logged
    startSmtp();

    String logs = output.getAll();
    assertThat(logs).contains("Outbox").contains("sent (attempt 1)").contains("retry in 60 s");
    assertThat(logs)
        .doesNotContain(TOKEN)
        .doesNotContain("alex.tran@helpdesk.example")
        .doesNotContain("Reset your")
        .doesNotContain("choose a new one");
  }

  // --- nudge ----------------------------------------------------------------------------------

  @Test
  void aCommitNudgesTheRunningWorkerSoMailGoesOutInSeconds() {
    // The poll is 1 h in this test context, so only the after-commit nudge can send it.
    worker.start();
    try {
      tx.executeWithoutResult(
          s -> {
            outbox.enqueue(email("rolled back, no nudge"));
            s.setRollbackOnly();
          });
      commit(email("nudged"));

      assertThat(smtp.waitForIncomingEmail(10_000, 1)).isTrue();
      assertThat(smtp.getReceivedMessages()).hasSize(1);
      assertThat(subjectOf(smtp.getReceivedMessages()[0])).isEqualTo("[NWD-001] nudged");
    } finally {
      worker.stop();
    }
  }

  private static String subjectOf(MimeMessage m) {
    try {
      return m.getSubject();
    } catch (jakarta.mail.MessagingException e) {
      throw new IllegalStateException(e);
    }
  }
}
