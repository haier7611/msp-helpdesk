package com.ticketsystem.api.mail.intake.poller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.mail.intake.InboundAttachments;
import com.ticketsystem.api.mail.intake.InboundMailProcessor;
import com.ticketsystem.api.mail.intake.MailboxService;
import com.ticketsystem.api.mail.intake.clientdata.IntakeStateRepository;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository;
import com.ticketsystem.api.mail.intake.job.InboundAttachmentWorker;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * ADR-039: the poller runs on its own scheduler thread. While a mail round is stuck (here: the
 * round's lease call blocks, as a slow mailbox or a slow attachment would), a job on Spring's
 * shared single-thread scheduler (where the SLA minute job, retention and the digest run) keeps
 * running on time, and the poller is on a thread of its own.
 */
class ImapPollerThreadTest {

  @Test
  void anSlaJobRunsWhileASlowMailRoundIsInProgress() throws Exception {
    CountDownLatch roundStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<String> pollerThread = new AtomicReference<>();
    IntakeStateRepository state = mock(IntakeStateRepository.class);
    when(state.tryLock(any(), anyString(), anyString(), any()))
        .thenAnswer(
            inv -> {
              pollerThread.set(Thread.currentThread().getName());
              roundStarted.countDown();
              release.await(10, TimeUnit.SECONDS); // a slow round
              return false;
            });
    ImapPoller poller =
        new ImapPoller(
            mock(MailboxRepository.class),
            state,
            mock(MailboxService.class),
            mock(InboundMailProcessor.class),
            new InboundAttachments(null, null),
            mock(InboundAttachmentWorker.class),
            mock(AuditService.class),
            Clock.systemUTC(),
            true,
            Duration.ofMillis(200),
            Duration.ZERO);

    // Spring's shared scheduler as Boot configures it: one thread.
    ThreadPoolTaskScheduler shared = new ThreadPoolTaskScheduler();
    shared.setPoolSize(1);
    shared.setThreadNamePrefix("scheduling-");
    shared.initialize();
    // Counts down once per SLA job run: the test waits on this signal, never on a fixed sleep.
    CountDownLatch slaRuns = new CountDownLatch(3);
    try {
      poller.start();
      assertThat(roundStarted.await(5, TimeUnit.SECONDS)).isTrue();
      shared.scheduleWithFixedDelay(slaRuns::countDown, Duration.ofMillis(50));
      // The SLA job ran several times while the mail round is still stuck.
      assertThat(slaRuns.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(release.getCount()).isEqualTo(1);
      assertThat(pollerThread.get()).startsWith("mail-poller-");
      assertThat(poller.isRunning()).isTrue();
    } finally {
      release.countDown();
      poller.stop();
      shared.shutdown();
    }
    assertThat(poller.isRunning()).isFalse();
  }

  @Test
  void withAutoStartOffNothingIsScheduled() {
    ImapPoller poller =
        new ImapPoller(
            mock(MailboxRepository.class),
            mock(IntakeStateRepository.class),
            mock(MailboxService.class),
            mock(InboundMailProcessor.class),
            new InboundAttachments(null, null),
            mock(InboundAttachmentWorker.class),
            mock(AuditService.class),
            Clock.systemUTC(),
            false,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30));
    poller.start();
    assertThat(poller.isRunning()).isFalse();
  }
}
