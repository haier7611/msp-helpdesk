package com.ticketsystem.api.sla;

import com.ticketsystem.api.sla.TicketStatus.ClockEffect;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Calculates a ticket's SLA position at a given instant: due times, used and paused time, and
 * met/breached for first response and resolution. Stateless and pure: the same input and instant
 * always give the same result, so the ticket code can recalculate whenever anything changes.
 *
 * <p>Rules:
 *
 * <ol>
 *   <li><b>Clock.</b> The priority's targets run on its {@link ClockType}: P1 on 24/7 (holidays
 *       ignored), others on the organisation's business calendar.
 *   <li><b>Running, paused and stopped time.</b> New, Open run the clock; Pending on client always
 *       pauses it; Pending on vendor runs it unless the policy pauses it; Resolved and Closed stop
 *       it. Only running time counts against a target. Paused time is reported; stopped time is
 *       neither.
 *   <li><b>Resolution.</b> Resolution time is running time from creation until the ticket is
 *       Resolved. A reopen carries on from where the clock stopped, and the latest Resolved counts:
 *       the target is met when the ticket is Resolved or Closed with no more running time used than
 *       the target allows.
 *   <li><b>First response.</b> The same status rules apply to the first-response clock (the status
 *       table's "SLA clock" column covers the whole SLA). It stops at the first public supporter
 *       reply: met if that reply came within the target. Resolved or Closed without a reply stops
 *       it without a result ({@link TargetState#STOPPED}).
 *   <li><b>Breach.</b> A target is breached as soon as used time exceeds it; using exactly the
 *       target is still within it. Breach is final because used time never decreases.
 *   <li><b>Due time.</b> The instant the target is, was or will be reached if the clock carries on
 *       in its current state; none while paused or stopped before being reached. For a met first
 *       response it is projected from the reply as if the clock had kept running.
 *   <li><b>Priority change.</b> The calculation always starts from the ticket's creation under the
 *       current priority's targets, so a priority change recalculates from creation. Keeping the
 *       old due times in the ticket history is the caller's job.
 *   <li><b>As of.</b> Status changes and a first response after {@code asOf} are ignored, so
 *       reports can evaluate any past instant.
 * </ol>
 */
public final class SlaCalculator {

  private SlaCalculator() {}

  /** The ticket's SLA position at {@code asOf}, which must not be before creation. */
  public static SlaResult calculate(SlaInput input, Instant asOf) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(asOf, "asOf");
    if (asOf.isBefore(input.createdAt())) {
      throw new IllegalArgumentException("asOf " + asOf + " is before creation");
    }
    PriorityTargets targets = input.policy().targetsFor(input.priority());
    BusinessCalendar clock =
        targets.clock() == ClockType.TWENTY_FOUR_SEVEN
            ? BusinessCalendar.twentyFourSeven(input.businessCalendar().zone())
            : input.businessCalendar();
    boolean vendorPauses = input.policy().pendingOnVendorPauses();
    List<Segment> segments = segments(input.history(), asOf, vendorPauses);

    Duration paused = Duration.ZERO;
    for (Segment s : segments) {
      if (s.effect() == ClockEffect.PAUSED) {
        paused = paused.plus(clock.businessTimeBetween(s.start(), s.end()));
      }
    }

    TargetResult firstResponse =
        firstResponse(input, asOf, clock, segments, targets.firstResponse(), vendorPauses);
    TargetResult resolution = resolution(input, asOf, clock, segments, targets.resolution());
    return new SlaResult(input.priority(), targets.clock(), firstResponse, resolution, paused);
  }

  private static TargetResult firstResponse(
      SlaInput input,
      Instant asOf,
      BusinessCalendar clock,
      List<Segment> segments,
      SlaTarget slaTarget,
      boolean vendorPauses) {
    Duration target = Duration.ofMinutes(slaTarget.toMinutes(clock));
    Instant respondedAt = input.firstRespondedAt();
    boolean responded = respondedAt != null && !respondedAt.isAfter(asOf);
    Instant limit = responded ? respondedAt : asOf;
    // At the reply the clock was in the state of the status before it; otherwise the current one.
    ClockEffect effectAtLimit =
        responded
            ? statusBefore(input.history(), respondedAt).clockEffect(vendorPauses)
            : statusAtOrBefore(input.history(), asOf).clockEffect(vendorPauses);
    Measure m = measure(clock, segments, limit, target, effectAtLimit);
    TargetState state;
    if (m.used().compareTo(target) > 0) {
      state = TargetState.BREACHED;
    } else if (responded) {
      state = TargetState.MET;
    } else {
      state = openState(effectAtLimit, TargetState.STOPPED);
    }
    return new TargetResult(target, m.used(), m.dueAt(), state);
  }

  private static TargetResult resolution(
      SlaInput input,
      Instant asOf,
      BusinessCalendar clock,
      List<Segment> segments,
      SlaTarget slaTarget) {
    Duration target = Duration.ofMinutes(slaTarget.toMinutes(clock));
    ClockEffect effectNow =
        statusAtOrBefore(input.history(), asOf).clockEffect(input.policy().pendingOnVendorPauses());
    Measure m = measure(clock, segments, asOf, target, effectNow);
    TargetState state =
        m.used().compareTo(target) > 0
            ? TargetState.BREACHED
            : openState(effectNow, TargetState.MET);
    return new TargetResult(target, m.used(), m.dueAt(), state);
  }

  private static TargetState openState(ClockEffect effect, TargetState whenStopped) {
    return switch (effect) {
      case RUNNING -> TargetState.RUNNING;
      case PAUSED -> TargetState.PAUSED;
      case STOPPED -> whenStopped;
    };
  }

  /** Running time used up to {@code limit}, and the instant the target is or will be reached. */
  private static Measure measure(
      BusinessCalendar clock,
      List<Segment> segments,
      Instant limit,
      Duration target,
      ClockEffect effectAtLimit) {
    Duration used = Duration.ZERO;
    Instant dueAt = null;
    for (Segment s : segments) {
      if (!s.start().isBefore(limit)) {
        break;
      }
      if (s.effect() != ClockEffect.RUNNING) {
        continue;
      }
      Instant end = s.end().isAfter(limit) ? limit : s.end();
      Duration d = clock.businessTimeBetween(s.start(), end);
      if (dueAt == null && used.plus(d).compareTo(target) >= 0) {
        dueAt = clock.addBusinessTime(s.start(), target.minus(used));
      }
      used = used.plus(d);
    }
    if (dueAt == null && effectAtLimit == ClockEffect.RUNNING) {
      dueAt = clock.addBusinessTime(limit, target.minus(used));
    }
    return new Measure(used, dueAt);
  }

  /** The history cut into back-to-back segments up to {@code asOf}, each with its clock effect. */
  private static List<Segment> segments(
      List<StatusChange> history, Instant asOf, boolean vendorPauses) {
    List<Segment> segments = new ArrayList<>();
    for (int i = 0; i < history.size(); i++) {
      Instant start = history.get(i).at();
      if (!start.isBefore(asOf)) {
        break;
      }
      Instant next = i + 1 < history.size() ? history.get(i + 1).at() : asOf;
      Instant end = next.isAfter(asOf) ? asOf : next;
      segments.add(new Segment(start, end, history.get(i).status().clockEffect(vendorPauses)));
    }
    return segments;
  }

  private static TicketStatus statusAtOrBefore(List<StatusChange> history, Instant instant) {
    TicketStatus status = history.get(0).status();
    for (StatusChange change : history) {
      if (change.at().isAfter(instant)) {
        break;
      }
      status = change.status();
    }
    return status;
  }

  private static TicketStatus statusBefore(List<StatusChange> history, Instant instant) {
    TicketStatus status = history.get(0).status();
    for (StatusChange change : history) {
      if (!change.at().isBefore(instant)) {
        break;
      }
      status = change.status();
    }
    return status;
  }

  private record Segment(Instant start, Instant end, ClockEffect effect) {}

  private record Measure(Duration used, Instant dueAt) {}
}
