package com.ticketsystem.api.notification.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.PrincipalAccessReader;
import com.ticketsystem.api.job.JobRunner;
import com.ticketsystem.api.notification.DigestService;
import com.ticketsystem.api.notification.SupporterPreferences;
import com.ticketsystem.api.notification.clientdata.SupporterDirectoryRepository;
import com.ticketsystem.api.notification.clientdata.SupporterDirectoryRepository.SupporterRow;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The optional daily digest: at 08:00 in each supporter's own timezone, once per local date, to
 * supporters who turned it on. Each digest is read through that supporter's own access (built from
 * the database like a request's scope), so it never lists an organisation they cannot see. A run
 * after 08:00 (missed minute, restart) still sends that day's digest; the log table makes it once
 * per day even with two runners.
 */
@Component
public class DailyDigestJob {

  private static final Logger log = LoggerFactory.getLogger(DailyDigestJob.class);

  static final String LOCK = "wp09-daily-digest";
  static final LocalTime SEND_AT = LocalTime.of(8, 0);

  private final JobRunner runner;
  private final SupporterDirectoryRepository directory;
  private final SupporterPreferences preferences;
  private final PrincipalAccessReader access;
  private final DigestService digests;
  private final Clock clock;

  public DailyDigestJob(
      JobRunner runner,
      SupporterDirectoryRepository directory,
      SupporterPreferences preferences,
      PrincipalAccessReader access,
      DigestService digests,
      Clock clock) {
    this.runner = runner;
    this.directory = directory;
    this.preferences = preferences;
    this.access = access;
    this.digests = digests;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT2M")
  public void scheduled() {
    if (runner.schedulingEnabled()) {
      runOnce();
    }
  }

  /** One run; returns how many digests were sent (-1 = another runner held the lock). */
  public int runOnce() {
    return runner.runExclusive(
        LOCK,
        Duration.ofMinutes(10),
        () -> {
          AccessScope system = AccessScope.system("WP-09 daily digest");
          int sent = 0;
          for (SupporterRow s : directory.allActive(system)) {
            try {
              if (!preferences.dailyDigest(system, s.userId())) {
                continue;
              }
              ZonedDateTime local =
                  clock.instant().atZone(preferences.timezone(system, s.userId()));
              if (local.toLocalTime().isBefore(SEND_AT)) {
                continue;
              }
              AccessScope own = access.read(s.userId()).map(a -> a.toScope()).orElse(null);
              if (own == null || !own.isSupporter()) {
                continue;
              }
              if (digests.send(system, own, s, local.toLocalDate())) {
                sent++;
              }
            } catch (RuntimeException e) {
              log.warn(
                  "daily digest for user {} failed ({})", s.userId(), e.getClass().getSimpleName());
            }
          }
          return sent;
        });
  }
}
