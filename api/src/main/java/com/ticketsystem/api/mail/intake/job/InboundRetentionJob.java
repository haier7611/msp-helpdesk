package com.ticketsystem.api.mail.intake.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.intake.clientdata.InboundMailRepository;
import com.ticketsystem.api.mail.intake.clientdata.IntakeStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbound mail retention: raw {@code .eml} cleared after 90 days, Blocked mail log rows deleted
 * after 30 days, expired test-connection probes deleted. Hourly, SYSTEM scope.
 */
@Component
public class InboundRetentionJob {

  private static final Logger log = LoggerFactory.getLogger(InboundRetentionJob.class);
  static final Duration BLOCKED_LOG = Duration.ofDays(30);

  private final InboundMailRepository inbound;
  private final IntakeStateRepository state;
  private final Clock clock;

  public InboundRetentionJob(
      InboundMailRepository inbound, IntakeStateRepository state, Clock clock) {
    this.inbound = inbound;
    this.state = state;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
  public void run() {
    int n = runOnce();
    if (n > 0) {
      log.info("inbound mail retention: {} row(s) purged", n);
    }
  }

  @Transactional
  public int runOnce() {
    AccessScope scope = AccessScope.system("inbound mail retention");
    Instant now = clock.instant();
    return inbound.purgeRaw(scope, now)
        + inbound.purgeBlocked(scope, now.minus(BLOCKED_LOG))
        + state.purgeProbes(scope, now.minus(Duration.ofDays(1)));
  }
}
