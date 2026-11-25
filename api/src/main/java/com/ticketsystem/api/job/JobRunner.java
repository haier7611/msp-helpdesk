package com.ticketsystem.api.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.job.clientdata.JobLockRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs a scheduled job body under the database lock of {@link JobLockRepository}, so two instances
 * (or two overlapping runs) never work at the same time. A run that finds the lock taken does
 * nothing; a run that crashes leaves the lease to expire, and the next run catches up (jobs are
 * idempotent and each ticket is handled in its own transaction).
 */
@Component
public class JobRunner {

  private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

  private final JobLockRepository locks;
  private final boolean scheduling;

  public JobRunner(JobLockRepository locks, @Value("${app.jobs.enabled:true}") boolean scheduling) {
    this.locks = locks;
    this.scheduling = scheduling;
  }

  /** False when scheduled runs are switched off (tests call the jobs directly). */
  public boolean schedulingEnabled() {
    return scheduling;
  }

  /**
   * Runs {@code body} when the lock {@code name} is free.
   *
   * @return what {@code body} returned, or -1 when another runner holds the lock
   */
  public int runExclusive(String name, Duration lease, Supplier<Integer> body) {
    AccessScope system = AccessScope.system("job lock " + name);
    String owner = UUID.randomUUID().toString();
    if (!locks.tryLock(system, name, owner, lease)) {
      log.debug("job {} skipped: another runner holds the lock", name);
      return -1;
    }
    try {
      return body.get();
    } finally {
      try {
        locks.unlock(system, name, owner);
      } catch (RuntimeException e) {
        log.warn(
            "job {} could not release its lock ({}); it expires with the lease",
            name,
            e.getClass().getSimpleName());
      }
    }
  }
}
