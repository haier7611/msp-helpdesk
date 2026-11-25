package com.ticketsystem.api.job.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code scheduled_job_lock}: keeps each scheduled job to one runner at a time across instances
 * (ShedLock-style). Holds no client data; it lives in a {@code clientdata} package so it follows
 * the JDBC rules without an ArchitectureTest allow-list entry, and only a SYSTEM scope may use it.
 * Leases use the database clock ({@code clock_timestamp()}), never the application clock, so a test
 * clock moved into the future cannot leave a lock held.
 */
@Repository
public class JobLockRepository {

  private final JdbcClient jdbc;

  public JobLockRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Takes the lock {@code name} for {@code lease} when it is free or its lease has run out. Commits
   * on its own (auto-commit), so the lock is visible to other runners at once.
   *
   * @return true when this runner now holds the lock
   */
  public boolean tryLock(AccessScope scope, String name, String owner, Duration lease) {
    requireSystem(scope);
    int n =
        jdbc.sql(
                "INSERT INTO scheduled_job_lock (name, locked_until, locked_at, locked_by) VALUES"
                    + " (:name, clock_timestamp() + CAST(:lease AS INTERVAL), clock_timestamp(),"
                    + " :owner) ON CONFLICT (name) DO UPDATE SET locked_until ="
                    + " EXCLUDED.locked_until, locked_at = EXCLUDED.locked_at, locked_by ="
                    + " EXCLUDED.locked_by WHERE scheduled_job_lock.locked_until <="
                    + " clock_timestamp()")
            .param("name", name)
            .param("lease", lease.toSeconds() + " seconds")
            .param("owner", owner)
            .update();
    return n == 1;
  }

  /** Gives the lock back (only when {@code owner} still holds it). */
  public void unlock(AccessScope scope, String name, String owner) {
    requireSystem(scope);
    jdbc.sql(
            "UPDATE scheduled_job_lock SET locked_until = clock_timestamp() WHERE name = :name AND"
                + " locked_by = :owner")
        .param("name", name)
        .param("owner", owner)
        .update();
  }

  private static void requireSystem(AccessScope scope) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
  }
}
