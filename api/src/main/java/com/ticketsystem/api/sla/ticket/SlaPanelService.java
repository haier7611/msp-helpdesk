package com.ticketsystem.api.sla.ticket;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.BusinessCalendar;
import com.ticketsystem.api.sla.ClockType;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TargetResult;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.SlaTicketRow;
import com.ticketsystem.api.sla.ticket.SlaPanel.PausedReason;
import com.ticketsystem.api.sla.ticket.SlaPanel.State;
import com.ticketsystem.api.sla.ticket.SlaPanel.Target;
import com.ticketsystem.api.sla.ticket.TicketSlaService.Snapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the SLA panel for the agent ticket page: the live position of both targets, calculated now
 * by {@link TicketSlaService#snapshot} (the same engine, policy, calendar and history as the stored
 * values), read-only - it stores nothing and sends no notification.
 *
 * <p>Supporters only (customers, API keys and SYSTEM get 404) and organisation-scoped in SQL: a
 * ticket of an organisation the agent does not work has no panel. A triage ticket has one (the
 * default policy and hours in the MSP timezone).
 */
@Service
public class SlaPanelService {

  private final TicketSlaRepository slaTickets;
  private final TicketSlaService sla;
  private final Clock clock;

  public SlaPanelService(TicketSlaRepository slaTickets, TicketSlaService sla, Clock clock) {
    this.slaTickets = slaTickets;
    this.sla = sla;
    this.clock = clock;
  }

  /**
   * The panel, or empty for a ticket the caller cannot see.
   *
   * <p>A plain read-only transaction. The ticket detail calls it only after its own transaction has
   * committed and released its connection ({@code AgentTicketDetailService}), so no request holds
   * two pool connections at once and a failure here cannot roll back the detail.
   */
  @Transactional(readOnly = true)
  public Optional<SlaPanel> panel(AccessScope scope, long ticketId) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    return slaTickets.find(scope, ticketId).map(t -> build(t, sla.snapshot(scope, t, now())));
  }

  private Instant now() {
    return clock.instant();
  }

  static SlaPanel build(SlaTicketRow t, Snapshot s) {
    boolean duplicate = t.duplicateOfId() != null;
    BusinessCalendar cal = s.clock();
    boolean vendorPauses = s.policy().policy().pendingOnVendorPauses();
    Target fr =
        target(s.result().firstResponse(), s.respondedAt(), t.status(), cal, s.asOf(), duplicate);
    Target res =
        target(
            s.result().resolution(),
            TicketSlaService.resolvedAt(t.status(), s.changes()),
            t.status(),
            cal,
            s.asOf(),
            duplicate);
    return new SlaPanel(
        s.asOf(),
        s.result().clock().name(),
        s.calendar().zone().getId(),
        t.priority().name(),
        policyLabel(t.priority(), s.result().clock(), s.calendar()),
        vendorPauses,
        cal.businessDayMinutes(),
        fr,
        res);
  }

  static Target target(
      TargetResult r,
      Instant metAt,
      TicketStatus status,
      BusinessCalendar cal,
      Instant asOf,
      boolean duplicate) {
    long target = r.targetMinutes();
    long used = r.usedMinutes();
    if (duplicate) {
      return new Target(
          State.NOT_APPLICABLE, target, null, null, null, used, null, null, null, null, null);
    }
    Long remaining = ceilMinutes(r.remaining());
    Instant warnAt = TicketSlaService.warnAt(r, cal, asOf);
    return switch (r.state()) {
      case STOPPED ->
          new Target(
              State.NOT_APPLICABLE, target, null, null, null, used, null, null, null, null, null);
      case MET ->
          new Target(State.MET, target, r.dueAt(), null, null, used, metAt, null, null, null, null);
      case BREACHED ->
          new Target(
              State.BREACHED,
              target,
              r.dueAt(),
              null,
              null,
              used,
              null,
              ceilMinutes(r.used().minus(r.target())),
              null,
              null,
              null);
      case PAUSED ->
          new Target(
              State.PAUSED,
              target,
              null,
              warnAt,
              remaining,
              used,
              null,
              null,
              null,
              null,
              status == TicketStatus.PENDING_ON_VENDOR ? PausedReason.VENDOR : PausedReason.CLIENT);
      case RUNNING ->
          cal.isOpenAt(asOf)
              ? new Target(
                  State.RUNNING,
                  target,
                  r.dueAt(),
                  warnAt,
                  remaining,
                  used,
                  null,
                  null,
                  null,
                  cal.closingAfter(asOf),
                  null)
              : new Target(
                  State.STOPPED_OUTSIDE_HOURS,
                  target,
                  r.dueAt(),
                  warnAt,
                  remaining,
                  used,
                  null,
                  null,
                  cal.nextOpening(asOf),
                  null,
                  null);
    };
  }

  /** "P2 High - business hours (Europe/London)" or "P1 Critical - 24/7". */
  static String policyLabel(Priority priority, ClockType clock, BusinessCalendar calendar) {
    String name =
        switch (priority) {
          case P1 -> "P1 Critical";
          case P2 -> "P2 High";
          case P3 -> "P3 Medium";
          case P4 -> "P4 Low";
        };
    return clock == ClockType.TWENTY_FOUR_SEVEN
        ? name + " - 24/7"
        : name + " - business hours (" + calendar.zone().getId() + ")";
  }

  /** Whole minutes, rounded up (so "1m left" shows until the very end; never negative). */
  static long ceilMinutes(Duration d) {
    if (d.isNegative() || d.isZero()) {
      return 0;
    }
    return (d.toMillis() + 59_999) / 60_000;
  }
}
