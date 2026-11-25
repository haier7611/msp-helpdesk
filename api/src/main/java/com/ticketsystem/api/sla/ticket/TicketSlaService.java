package com.ticketsystem.api.sla.ticket;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.notification.NotificationKind;
import com.ticketsystem.api.notification.NotificationService;
import com.ticketsystem.api.notification.NotificationService.Notice;
import com.ticketsystem.api.sla.BusinessCalendar;
import com.ticketsystem.api.sla.ClockType;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.SlaCalculator;
import com.ticketsystem.api.sla.SlaInput;
import com.ticketsystem.api.sla.SlaResult;
import com.ticketsystem.api.sla.SlaWarning;
import com.ticketsystem.api.sla.StatusChange;
import com.ticketsystem.api.sla.TargetResult;
import com.ticketsystem.api.sla.TargetState;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.automation.SlaAutomationEngine;
import com.ticketsystem.api.sla.automation.SlaAutomationEngine.TicketFacts;
import com.ticketsystem.api.sla.automation.SlaAutomationTrigger;
import com.ticketsystem.api.sla.automation.SlaAutomationTrigger.Change;
import com.ticketsystem.api.sla.automation.SlaAutomationTrigger.Target;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.SlaFields;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.SlaTicketRow;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.SlaValues;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.StatusEvent;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.StoredState;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.sla.config.SlaConfigService.EffectivePolicy;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.Extra;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wires the SLA engine to tickets: recalculates and stores a ticket's SLA from its creation, status
 * history and first supporter public reply under the organisation's calendar and policy, and raises
 * the warnings - each 80% and each breach crossing notifies exactly once (the {@code *_notified_at}
 * columns are set in the same transaction as the notification), and a P1 ticket alerts every
 * supporter of the organisation once at creation (or when raised to P1) and at 80%.
 *
 * <p>Called after commit of every relevant ticket event and by the minute job; both run under a
 * SYSTEM scope. The ticket row is locked first, so concurrent runs serialise and the second sees
 * the first one's flags. A priority change records the old and new due times in the ticket history,
 * and so does a move to another organisation (or out of triage).
 *
 * <p>Triage tickets run the built-in default policy and business hours in the MSP timezone ({@link
 * SlaConfigService#policyFor}, {@link SlaConfigService#calendar}). When one is moved to an
 * organisation it is recalculated under that organisation's policy and calendar from its original
 * creation time - the clock is never restarted. Its warnings have no organisation to go to, so they
 * reach only the assignee (if any) while the ticket is open; a warning nobody received is not
 * marked as sent, so it goes to the organisation's supporters once the ticket is moved.
 */
@Service
public class TicketSlaService {

  private final TicketSlaRepository slaTickets;
  private final SlaConfigService config;
  private final NotificationService notifications;
  private final TicketHistoryRepository history;
  private final SlaAutomationEngine automation;
  private final Clock clock;

  public TicketSlaService(
      TicketSlaRepository slaTickets,
      SlaConfigService config,
      NotificationService notifications,
      TicketHistoryRepository history,
      SlaAutomationEngine automation,
      Clock clock) {
    this.slaTickets = slaTickets;
    this.config = config;
    this.notifications = notifications;
    this.automation = automation;
    this.history = history;
    this.clock = clock;
  }

  /** The outcome of one recalculation (for tests and logs; ids and enums only). */
  public record Outcome(
      boolean calculated,
      TargetState firstResponse,
      TargetState resolution,
      List<NotificationKind> notified) {

    static Outcome skipped() {
      return new Outcome(false, null, null, List.of());
    }
  }

  /**
   * The history entry reason of an "SLA due times recalculated" line after a move to another
   * organisation (a priority change has none).
   */
  public static final String DUE_CHANGE_ORGANISATION_MOVED = "ORGANISATION_MOVED";

  /** Recalculates in its own transaction (after-commit listeners and the minute job). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Outcome recalculateInNewTransaction(AccessScope scope, long ticketId) {
    return recalculate(scope, ticketId, false);
  }

  /**
   * Recalculates in its own transaction after the ticket moved to another organisation (or out of
   * triage), recording the old and new due times in the ticket history.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Outcome recalculateAfterOrganisationChange(AccessScope scope, long ticketId) {
    return recalculate(scope, ticketId, true, DUE_CHANGE_ORGANISATION_MOVED);
  }

  /**
   * Recalculates in its own transaction after a priority change, recording the old and new due
   * times in the ticket history.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Outcome recalculateAfterPriorityChange(AccessScope scope, long ticketId) {
    return recalculate(scope, ticketId, true);
  }

  /** Recalculates and stores the ticket's SLA as of now, raising any new warnings. */
  @Transactional
  public Outcome recalculate(AccessScope scope, long ticketId, boolean recordDueChange) {
    return recalculate(scope, ticketId, recordDueChange, null);
  }

  private Outcome recalculate(
      AccessScope scope, long ticketId, boolean recordDueChange, String dueChangeReason) {
    Optional<SlaTicketRow> locked = slaTickets.lock(scope, ticketId);
    if (locked.isEmpty()) {
      return Outcome.skipped();
    }
    SlaTicketRow t = locked.get();
    StoredState before = slaTickets.storedState(scope, ticketId).orElse(null);
    Instant now = clock.instant();
    Snapshot snap = snapshot(scope, t, now);
    Instant asOf = snap.asOf();
    EffectivePolicy policy = snap.policy();
    List<StatusChange> changes = snap.changes();
    Instant responded = snap.respondedAt();
    SlaResult r = snap.result();
    BusinessCalendar clockCal = snap.clock();

    TargetResult fr = r.firstResponse();
    TargetResult res = r.resolution();
    SlaWarning.Level frLevel = SlaWarning.levelOf(fr);
    SlaWarning.Level resLevel = SlaWarning.levelOf(res);

    Flags f = new Flags(t);
    List<NotificationKind> notified = new ArrayList<>();
    boolean p1 = t.priority() == Priority.P1;
    if (p1 && f.p1Alerted == null && !isFinished(t.status())) {
      if (notifyAll(scope, t, NotificationKind.P1_CREATED, null, "p1:" + t.id() + ":" + now)) {
        f.p1Alerted = now;
        notified.add(NotificationKind.P1_CREATED);
      }
    }
    if (!p1) {
      f.p1Alerted = null;
    }
    warn(scope, t, "FIRST_RESPONSE", frLevel, f, true, now, notified);
    warn(scope, t, "RESOLUTION", resLevel, f, false, now, notified);

    Instant frWarn = warnAt(fr, clockCal, asOf);
    Instant resWarn = warnAt(res, clockCal, asOf);
    Instant next = earliest(nextCheck(fr, frLevel, frWarn), nextCheck(res, resLevel, resWarn));
    Instant resolvedAt = resolvedAt(t.status(), changes);

    SlaWarning.Level overall = max(frLevel, resLevel);
    slaTickets.save(
        scope,
        t.id(),
        t.organisationId(),
        new SlaValues(
            r.clock(),
            policy.fallback(),
            fr.targetMinutes(),
            fr.dueAt(),
            frWarn,
            responded,
            fr.usedMinutes(),
            fr.state(),
            res.targetMinutes(),
            res.dueAt(),
            resWarn,
            resolvedAt,
            res.usedMinutes(),
            res.state(),
            r.pausedMinutes(),
            overall,
            now,
            next,
            f.frRisk,
            f.frBreach,
            f.resRisk,
            f.resBreach,
            f.p1Alerted));

    if (recordDueChange
        && (!Objects.equals(t.firstResponseDueAt(), fr.dueAt())
            || !Objects.equals(t.resolutionDueAt(), res.dueAt()))) {
      history.record(
          scope,
          t.organisationId(),
          t.id(),
          HistoryType.SLA_DUE_CHANGED,
          null,
          dueText(t.firstResponseDueAt(), t.resolutionDueAt()),
          dueText(fr.dueAt(), res.dueAt()),
          now,
          // After a move the reference is the ticket's organisation now (null: triage defaults).
          new Extra(
              null,
              dueChangeReason,
              DUE_CHANGE_ORGANISATION_MOVED.equals(dueChangeReason) ? t.organisationId() : null,
              null,
              null));
    }
    // The stored state changed - run the admin's SLA automation rules (same transaction;
    // each rule in its own savepoint, so a failing rule never undoes these values).
    List<SlaAutomationTrigger> changed = stateChanges(t, before, fr, frLevel, res, resLevel);
    if (!changed.isEmpty()) {
      automation.onStateChanges(
          scope,
          new TicketFacts(
              t.id(),
              t.organisationId(),
              t.priority(),
              t.requestTypeId(),
              t.status(),
              t.assigneeUserId()),
          changed);
    }
    return new Outcome(true, fr.state(), res.state(), notified);
  }

  /**
   * What changed between the stored SLA state and this recalculation - a target reaching at risk
   * (80%) from below it, becoming breached, or becoming met. Going straight past 80% to breached is
   * a breach only (as the built-in warnings). A ticket's first ever calculation when it is already
   * Resolved or Closed changes nothing (old tickets never fire rules retroactively).
   */
  static List<SlaAutomationTrigger> stateChanges(
      SlaTicketRow t,
      StoredState before,
      TargetResult fr,
      SlaWarning.Level frLevel,
      TargetResult res,
      SlaWarning.Level resLevel) {
    if (before == null || (before.calculatedAt() == null && isFinished(t.status()))) {
      return List.of();
    }
    List<SlaAutomationTrigger> out = new ArrayList<>();
    change(
        out,
        Target.FIRST_RESPONSE,
        before.firstResponseState(),
        storedLevel(
            before.firstResponseState(), before.firstResponseWarnAt(), before.calculatedAt()),
        fr.state(),
        frLevel);
    change(
        out,
        Target.RESOLUTION,
        before.resolutionState(),
        storedLevel(before.resolutionState(), before.resolutionWarnAt(), before.calculatedAt()),
        res.state(),
        resLevel);
    return out;
  }

  private static void change(
      List<SlaAutomationTrigger> out,
      Target target,
      TargetState oldState,
      SlaWarning.Level oldLevel,
      TargetState newState,
      SlaWarning.Level newLevel) {
    if (newLevel == SlaWarning.Level.AT_RISK && oldLevel == SlaWarning.Level.NONE) {
      out.add(SlaAutomationTrigger.of(target, Change.AT_RISK));
    }
    if (newState == TargetState.BREACHED && oldState != TargetState.BREACHED) {
      out.add(SlaAutomationTrigger.of(target, Change.BREACHED));
    }
    if (newState == TargetState.MET && oldState != TargetState.MET) {
      out.add(SlaAutomationTrigger.of(target, Change.MET));
    }
  }

  /**
   * The warning level a stored state stood at: breached, or at risk when the stored 80% instant had
   * come by the time it was calculated ({@link #warnAt} stores the evaluation instant once 80% is
   * used), else none.
   */
  static SlaWarning.Level storedLevel(TargetState state, Instant warnAt, Instant calculatedAt) {
    if (state == null) {
      return SlaWarning.Level.NONE;
    }
    return switch (state) {
      case BREACHED -> SlaWarning.Level.BREACHED;
      case MET, STOPPED -> SlaWarning.Level.NONE;
      case RUNNING, PAUSED ->
          warnAt != null && calculatedAt != null && !warnAt.isAfter(calculatedAt)
              ? SlaWarning.Level.AT_RISK
              : SlaWarning.Level.NONE;
    };
  }

  /**
   * Everything one calculation used and produced, so the stored values (queue column, reports) and
   * the ticket-page SLA panel come from the same inputs and the same engine.
   *
   * @param asOf the evaluation instant (now, or creation if the clock is behind it)
   * @param result the engine's result
   * @param policy the effective policy
   * @param calendar the organisation's business calendar
   * @param clock the calendar the priority runs on (24/7 for P1, else {@code calendar})
   * @param changes the status history fed to the engine
   * @param respondedAt the first supporter public reply, or null
   */
  public record Snapshot(
      Instant asOf,
      SlaResult result,
      EffectivePolicy policy,
      BusinessCalendar calendar,
      BusinessCalendar clock,
      List<StatusChange> changes,
      Instant respondedAt) {}

  /** Calculates the ticket's SLA as of {@code now} without storing anything or notifying. */
  public Snapshot snapshot(AccessScope scope, SlaTicketRow t, Instant now) {
    Instant asOf = now.isBefore(t.createdAt()) ? t.createdAt() : now;
    EffectivePolicy policy = config.policyFor(scope, t.organisationId(), t.requestTypeId());
    BusinessCalendar calendar = config.calendar(scope, t.organisationId());
    List<StatusChange> changes = statusHistory(t, slaTickets.statusChanges(scope, t.id()));
    Instant responded = slaTickets.firstSupporterReply(scope, t.id()).orElse(null);
    if (responded != null && responded.isBefore(t.createdAt())) {
      responded = t.createdAt();
    }
    SlaInput input =
        new SlaInput(t.createdAt(), t.priority(), policy.policy(), calendar, changes, responded);
    SlaResult r = SlaCalculator.calculate(input, asOf);
    BusinessCalendar clockCal =
        r.clock() == ClockType.TWENTY_FOUR_SEVEN
            ? BusinessCalendar.twentyFourSeven(calendar.zone())
            : calendar;
    return new Snapshot(asOf, r, policy, calendar, clockCal, changes, responded);
  }

  /** SLA fields of the given tickets for the agent read model (internal only). */
  @Transactional(readOnly = true)
  public Map<Long, SlaFields> fieldsFor(AccessScope scope, Collection<Long> ticketIds) {
    return slaTickets.fieldsFor(scope, ticketIds);
  }

  // ---------------------------------------------------------------------------------------------

  /** Mutable copy of the notified flags while one recalculation decides what to send. */
  private static final class Flags {
    Instant frRisk;
    Instant frBreach;
    Instant resRisk;
    Instant resBreach;
    Instant p1Alerted;

    Flags(SlaTicketRow t) {
      frRisk = t.firstResponseAtRiskNotifiedAt();
      frBreach = t.firstResponseBreachNotifiedAt();
      resRisk = t.resolutionAtRiskNotifiedAt();
      resBreach = t.resolutionBreachNotifiedAt();
      p1Alerted = t.p1AlertedAt();
    }
  }

  /**
   * Sends the 80% or breach notification of one target when its level is reached for the first
   * time, and re-arms a level the recalculation dropped below (e.g. after a priority change).
   */
  private void warn(
      AccessScope scope,
      SlaTicketRow t,
      String target,
      SlaWarning.Level level,
      Flags f,
      boolean firstResponse,
      Instant now,
      List<NotificationKind> notified) {
    Instant risk = firstResponse ? f.frRisk : f.resRisk;
    Instant breach = firstResponse ? f.frBreach : f.resBreach;
    boolean p1 = t.priority() == Priority.P1;
    switch (level) {
      case NONE -> {
        risk = null;
        breach = null;
      }
      case AT_RISK -> {
        breach = null;
        if (risk == null) {
          NotificationKind kind = p1 ? NotificationKind.P1_AT_RISK : NotificationKind.SLA_AT_RISK;
          String key = "sla:" + t.id() + ":" + target + ":risk:" + now;
          boolean sent;
          if (p1) {
            sent = notifyAll(scope, t, kind, target, key);
          } else {
            sent = notifyOwner(scope, t, kind, target, key);
          }
          if (sent) {
            notified.add(kind);
            risk = now;
          }
        }
      }
      case BREACHED -> {
        if (breach == null
            && notifyOwner(
                scope,
                t,
                NotificationKind.SLA_BREACHED,
                target,
                "sla:" + t.id() + ":" + target + ":breach:" + now)) {
          notified.add(NotificationKind.SLA_BREACHED);
          breach = now;
        }
        if (risk == null && breach != null) {
          // Straight past 80% (a priority change, a missed run): the breach notice covers it.
          risk = now;
        }
      }
    }
    if (firstResponse) {
      f.frRisk = risk;
      f.frBreach = breach;
    } else {
      f.resRisk = risk;
      f.resBreach = breach;
    }
  }

  /**
   * The assignee, or every supporter of the organisation when unassigned. True when the warning
   * counts as sent: always for an organisation ticket (as always); for a triage ticket only when
   * its assignee was told.
   */
  private boolean notifyOwner(
      AccessScope scope, SlaTicketRow t, NotificationKind kind, String detail, String key) {
    if (t.organisationId() == null) {
      return notifyTriageAssignee(scope, t, kind, detail, key);
    }
    Notice n = new Notice(kind, detail, t.id(), t.organisationId(), key);
    if (t.assigneeUserId() != null
        && notifications.notifyUsers(scope, List.of(t.assigneeUserId()), n) > 0) {
      return true;
    }
    notifications.notifyOrganisation(scope, n);
    return true;
  }

  /**
   * Every supporter who can work the organisation, out-of-office ones included (P1). A supporter
   * without access to the organisation is never told (spec rule: no notification about an
   * organisation they cannot access). A triage ticket has no organisation: only its assignee.
   */
  private boolean notifyAll(
      AccessScope scope, SlaTicketRow t, NotificationKind kind, String detail, String key) {
    if (t.organisationId() == null) {
      return notifyTriageAssignee(scope, t, kind, detail, key);
    }
    notifications.notifyOrganisation(
        scope, new Notice(kind, detail, t.id(), t.organisationId(), key));
    return true;
  }

  /**
   * A triage ticket's warning: only its assignee, and only while it is open (a Resolved or Closed
   * triage ticket calculated for the first time stays quiet).
   */
  private boolean notifyTriageAssignee(
      AccessScope scope, SlaTicketRow t, NotificationKind kind, String detail, String key) {
    if (t.assigneeUserId() == null || isFinished(t.status())) {
      return false;
    }
    Notice n = new Notice(kind, detail, t.id(), null, key);
    return notifications.notifyUsers(scope, List.of(t.assigneeUserId()), n) > 0;
  }

  /** NEW at creation, then every recorded status change, kept in time order and not before it. */
  static List<StatusChange> statusHistory(SlaTicketRow t, List<StatusEvent> events) {
    List<StatusChange> out = new ArrayList<>();
    out.add(new StatusChange(t.createdAt(), TicketStatus.NEW));
    Instant last = t.createdAt();
    for (StatusEvent e : events) {
      Instant at = e.at().isBefore(last) ? last : e.at();
      out.add(new StatusChange(at, e.status()));
      last = at;
    }
    return out;
  }

  /** When 80% will be used if the clock keeps running from {@code asOf}; null if not running. */
  static Instant warnAt(TargetResult r, BusinessCalendar clock, Instant asOf) {
    if (r.state() != TargetState.RUNNING && r.state() != TargetState.PAUSED) {
      return null;
    }
    long targetMillis = r.target().toMillis();
    long warnMillis = (targetMillis * SlaWarning.AT_RISK_PERCENT + 99) / 100;
    long usedMillis = r.used().toMillis();
    if (usedMillis >= warnMillis) {
      return asOf;
    }
    if (r.state() == TargetState.PAUSED) {
      return null;
    }
    return clock.addBusinessTime(asOf, Duration.ofMillis(warnMillis - usedMillis));
  }

  /** The next instant the minute job must recalculate without an event (80% or breach). */
  static Instant nextCheck(TargetResult r, SlaWarning.Level level, Instant warnAt) {
    if (r.state() != TargetState.RUNNING) {
      return null;
    }
    return switch (level) {
      case NONE -> warnAt;
      case AT_RISK -> r.dueAt() == null ? null : r.dueAt().plusSeconds(1);
      case BREACHED -> null;
    };
  }

  static Instant resolvedAt(TicketStatus status, List<StatusChange> changes) {
    if (status != TicketStatus.RESOLVED && status != TicketStatus.CLOSED) {
      return null;
    }
    Instant at = null;
    for (StatusChange c : changes) {
      if (c.status() == TicketStatus.RESOLVED) {
        at = c.at();
      } else if (c.status() != TicketStatus.CLOSED) {
        at = null;
      }
    }
    return at;
  }

  private static boolean isFinished(TicketStatus s) {
    return s == TicketStatus.RESOLVED || s == TicketStatus.CLOSED;
  }

  private static Instant earliest(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isBefore(b) ? a : b;
  }

  private static SlaWarning.Level max(SlaWarning.Level a, SlaWarning.Level b) {
    return a.ordinal() >= b.ordinal() ? a : b;
  }

  private static String dueText(Instant firstResponse, Instant resolution) {
    return "first response "
        + (firstResponse == null ? "-" : firstResponse.toString())
        + "; resolution "
        + (resolution == null ? "-" : resolution.toString());
  }
}
