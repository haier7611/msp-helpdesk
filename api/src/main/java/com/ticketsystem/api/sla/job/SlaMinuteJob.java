package com.ticketsystem.api.sla.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.job.JobRunner;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository;
import com.ticketsystem.api.sla.ticket.PendingTimersService;
import com.ticketsystem.api.sla.ticket.TicketSlaService;
import com.ticketsystem.api.ticket.assignment.AutoAssignmentService;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The SLA minute job (runs every minute, and drives the timers), one runner at a time (database
 * lock): auto-assignment the after-commit reaction missed, SLA recalculation of changed tickets and
 * of tickets whose 80% or breach instant has come (raising each warning once), then the
 * Pending-on-client reminder / auto-resolve and the Resolved auto-close. Each ticket is handled in
 * its own transaction; a crash mid-run leaves no half-updated ticket and the next run catches up
 * (no in-memory timers).
 */
@Component
public class SlaMinuteJob {

  private static final Logger log = LoggerFactory.getLogger(SlaMinuteJob.class);

  static final String LOCK = "wp09-sla-minute";
  static final int BATCH = 2000;

  /** Counts of one run (-1 = another runner held the lock). */
  public record RunResult(int autoAssigned, int recalculated, int timers) {}

  private final JobRunner runner;
  private final TicketSlaRepository slaTickets;
  private final AutoAssignmentService autoAssignment;
  private final TicketSlaService sla;
  private final PendingTimersService timers;
  private final Clock clock;

  public SlaMinuteJob(
      JobRunner runner,
      TicketSlaRepository slaTickets,
      AutoAssignmentService autoAssignment,
      TicketSlaService sla,
      PendingTimersService timers,
      Clock clock) {
    this.runner = runner;
    this.slaTickets = slaTickets;
    this.autoAssignment = autoAssignment;
    this.sla = sla;
    this.timers = timers;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  public void scheduled() {
    if (runner.schedulingEnabled()) {
      runOnce();
    }
  }

  /** One run under the lock; null when another runner holds it. */
  public RunResult runOnce() {
    int[] counts = new int[3];
    int r =
        runner.runExclusive(
            LOCK,
            Duration.ofMinutes(10),
            () -> {
              AccessScope system = AccessScope.system("WP-09 SLA minute job");
              for (long id : slaTickets.awaitingAutoAssignment(system, BATCH)) {
                if (guard(() -> autoAssignment.applyInNewTransaction(system, id), id)) {
                  counts[0]++;
                }
              }
              for (long id : slaTickets.needingRecalculation(system, clock.instant(), BATCH)) {
                if (guard(() -> sla.recalculateInNewTransaction(system, id), id)) {
                  counts[1]++;
                }
              }
              for (long id : timers.pendingCandidates(system, BATCH)) {
                if (guard(() -> timers.processPending(system, id), id)) {
                  counts[2]++;
                }
              }
              for (long id : timers.resolvedCandidates(system, BATCH)) {
                if (guard(() -> timers.processResolved(system, id), id)) {
                  counts[2]++;
                }
              }
              return 0;
            });
    return r < 0 ? null : new RunResult(counts[0], counts[1], counts[2]);
  }

  /** Runs one ticket's step; a failure is logged (ids only) and the run goes on. */
  private static boolean guard(Runnable step, long ticketId) {
    try {
      step.run();
      return true;
    } catch (RuntimeException e) {
      log.warn("WP-09 minute job: ticket {} failed ({})", ticketId, e.getClass().getSimpleName());
      return false;
    }
  }
}
