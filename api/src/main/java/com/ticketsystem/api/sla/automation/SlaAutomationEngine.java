package com.ticketsystem.api.sla.automation;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.notification.NotificationKind;
import com.ticketsystem.api.notification.NotificationService;
import com.ticketsystem.api.notification.NotificationService.Notice;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Action;
import com.ticketsystem.api.sla.automation.clientdata.SlaAutomationRepository;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.TicketService.AutomationAssignResult;
import com.ticketsystem.api.ticket.agent.InternalNoteService;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ActorKind;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.AutomationOutcome;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.Extra;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import com.ticketsystem.api.ticket.event.TicketEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the admin-defined SLA automation rules when the SLA evaluation stores a state change of a
 * ticket ({@link com.ticketsystem.api.sla.ticket.TicketSlaService} calls {@link #onStateChanges}
 * after saving the recalculation, in the same transaction - there is no second SLA calculation).
 *
 * <p>For each trigger, the enabled rules for it run in position order; a rule whose conditions all
 * match first inserts its firing record (rule, ticket, target, trigger) and only if that insert is
 * new performs its actions - so a rule fires at most once per ticket, target and trigger, whatever
 * restarts, recalculations or its own priority raise do later. Each rule runs in a savepoint: a
 * rule that fails is rolled back (firing record included), logged with ids only and skipped, and
 * the other rules and the SLA values still commit (like the minute job's per-ticket guard). The
 * ticket events of a rule's priority raise or assignment are held back and published only once its
 * savepoint has committed, so a rolled-back rule leaves no event (and no "assigned to you"
 * notification) behind.
 *
 * <p>Conditions are matched against the ticket as the SLA evaluation saw it at the state change: a
 * later rule in the same evaluation still sees the original priority and assignee, even if an
 * earlier rule raised or assigned it.
 *
 * <p>Actions run with the SYSTEM scope of the SLA evaluation. Every action writes a ticket timeline
 * line done by "Automation (rule name)" and an audit event. Notifications go only to active agents
 * who can work the ticket's organisation (the notification service filters them); a triage ticket
 * has no organisation, so "notify the organisation's agents" reaches nobody there (as the built-in
 * triage warnings) while chosen agents are told (every agent may see triage).
 */
@Service
public class SlaAutomationEngine {

  private static final Logger log = LoggerFactory.getLogger(SlaAutomationEngine.class);

  /** The ticket as the SLA evaluation saw it (the values conditions are matched against). */
  public record TicketFacts(
      long id,
      Long organisationId,
      Priority priority,
      long requestTypeId,
      TicketStatus status,
      Long assigneeUserId) {}

  private final SlaAutomationRepository rules;
  private final TicketService tickets;
  private final InternalNoteService notes;
  private final NotificationService notifications;
  private final TicketHistoryRepository history;
  private final AuditService audit;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate savepoint;
  private final Clock clock;

  public SlaAutomationEngine(
      SlaAutomationRepository rules,
      TicketService tickets,
      InternalNoteService notes,
      NotificationService notifications,
      TicketHistoryRepository history,
      AuditService audit,
      ApplicationEventPublisher events,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.rules = rules;
    this.tickets = tickets;
    this.notes = notes;
    this.notifications = notifications;
    this.history = history;
    this.audit = audit;
    this.events = events;
    this.savepoint = new TransactionTemplate(transactions);
    this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    this.clock = clock;
  }

  /**
   * Runs the matching rules for each trigger. Only a SYSTEM scope runs rules (the after-commit
   * reactions and the minute job); never throws - a failure is logged and the SLA values commit.
   *
   * @return how many rules fired (for tests and logs)
   */
  public int onStateChanges(
      AccessScope scope, TicketFacts ticket, List<SlaAutomationTrigger> triggers) {
    if (!scope.isSystem() || triggers.isEmpty()) {
      return 0;
    }
    int fired = 0;
    try {
      if (!rules.anyEnabled(scope)) {
        return 0;
      }
      for (SlaAutomationTrigger trigger : triggers) {
        for (SlaAutomationRule rule : rules.enabledFor(scope, trigger)) {
          if (!rule.conditions()
              .matches(ticket.organisationId(), ticket.priority(), ticket.requestTypeId())) {
            continue;
          }
          if (runGuarded(scope, rule, ticket, trigger)) {
            fired++;
          }
        }
      }
    } catch (RuntimeException e) {
      log.warn(
          "ADR-049 automation: ticket {} could not be evaluated ({})",
          ticket.id(),
          e.getClass().getSimpleName());
    }
    return fired;
  }

  /** One rule in its own savepoint; true when it fired. */
  private boolean runGuarded(
      AccessScope scope, SlaAutomationRule rule, TicketFacts ticket, SlaAutomationTrigger trigger) {
    List<TicketEvent> held = new ArrayList<>();
    try {
      Boolean fired = savepoint.execute(status -> run(scope, rule, ticket, trigger, held));
      // The savepoint committed: the rule's changes stay, so their events may go out (still inside
      // the SLA transaction, so after-commit listeners run only if that commits too).
      held.forEach(events::publishEvent);
      return Boolean.TRUE.equals(fired);
    } catch (RuntimeException e) {
      log.warn(
          "ADR-049 automation: rule {} on ticket {} ({}) failed ({}); skipped",
          rule.id(),
          ticket.id(),
          trigger,
          e.getClass().getSimpleName());
      return false;
    }
  }

  private boolean run(
      AccessScope scope,
      SlaAutomationRule rule,
      TicketFacts ticket,
      SlaAutomationTrigger trigger,
      List<TicketEvent> held) {
    Instant now = clock.instant();
    if (!rules.recordFiring(scope, rule.id(), ticket.id(), trigger, now)) {
      return false; // fired before for this ticket, target and trigger
    }
    for (Action action : rule.actions()) {
      perform(scope, rule, ticket, trigger, action, now, held);
    }
    return true;
  }

  private void perform(
      AccessScope scope,
      SlaAutomationRule rule,
      TicketFacts t,
      SlaAutomationTrigger trigger,
      Action action,
      Instant now,
      List<TicketEvent> held) {
    String key = "auto:" + rule.id() + ":" + t.id() + ":" + trigger.name();
    Notice notice =
        new Notice(
            NotificationKind.SLA_AUTOMATION, trigger.name(), t.id(), t.organisationId(), key);
    switch (action.type()) {
      case NOTIFY_AGENTS -> {
        int told = notifications.notifyUsers(scope, action.agentIds(), notice);
        line(
            scope,
            rule,
            t,
            trigger,
            action,
            told > 0 ? AutomationOutcome.NOTIFIED_AGENTS : AutomationOutcome.NOBODY_NOTIFIED,
            Integer.toString(told),
            null,
            now);
      }
      case NOTIFY_ORGANISATION -> {
        int told = t.organisationId() == null ? 0 : notifications.notifyOrganisation(scope, notice);
        line(
            scope,
            rule,
            t,
            trigger,
            action,
            told > 0 ? AutomationOutcome.NOTIFIED_ORGANISATION : AutomationOutcome.NOBODY_NOTIFIED,
            Integer.toString(told),
            null,
            now);
      }
      case ASSIGN -> {
        if (t.status() == TicketStatus.CLOSED) {
          line(scope, rule, t, trigger, action, AutomationOutcome.SKIPPED_CLOSED, null, null, now);
          return;
        }
        AutomationAssignResult r =
            tickets.assignByAutomation(scope, t.id(), action.agentId(), rule.name(), held::add);
        switch (r) {
          // The ASSIGNEE history row ("Assigned to ... by Automation (rule)") is the line.
          case ASSIGNED -> audit(rule, t, trigger, action, "ASSIGNED");
          case ALREADY_ASSIGNED ->
              line(
                  scope,
                  rule,
                  t,
                  trigger,
                  action,
                  AutomationOutcome.ASSIGN_SKIPPED_ASSIGNED,
                  null,
                  action.agentId(),
                  now);
          case NOT_ASSIGNABLE ->
              line(
                  scope,
                  rule,
                  t,
                  trigger,
                  action,
                  AutomationOutcome.ASSIGN_SKIPPED_NOT_ALLOWED,
                  null,
                  action.agentId(),
                  now);
        }
      }
      case RAISE_PRIORITY -> {
        if (t.status() == TicketStatus.CLOSED) {
          line(scope, rule, t, trigger, action, AutomationOutcome.SKIPPED_CLOSED, null, null, now);
          return;
        }
        // The PRIORITY history row ("Priority changed ... by Automation (rule)") is the line; the
        // PriorityChanged event (held until the savepoint commits) recalculates the SLA after
        // commit
        // as for a manual change, and the firing record stops this rule from firing again.
        Priority raised = tickets.raisePriorityByAutomation(scope, t.id(), rule.name(), held::add);
        if (raised == null) {
          line(scope, rule, t, trigger, action, AutomationOutcome.PRIORITY_AT_TOP, null, null, now);
        } else {
          audit(rule, t, trigger, action, "PRIORITY_" + raised.name());
        }
      }
      case ADD_NOTE -> {
        notes.addAutomationNote(scope, t.id(), action.text());
        line(scope, rule, t, trigger, action, AutomationOutcome.NOTE_ADDED, null, null, now);
      }
    }
  }

  /** A timeline line (SLA_AUTOMATION history row) plus the matching audit event. */
  private void line(
      AccessScope scope,
      SlaAutomationRule rule,
      TicketFacts t,
      SlaAutomationTrigger trigger,
      Action action,
      AutomationOutcome outcome,
      String value,
      Long agentId,
      Instant now) {
    history.record(
        scope,
        t.organisationId(),
        t.id(),
        HistoryType.SLA_AUTOMATION,
        null,
        trigger.name(),
        value,
        now,
        new Extra(ActorKind.SYSTEM, outcome.name(), agentId, null, null, null, rule.name()));
    audit(rule, t, trigger, action, outcome.name());
  }

  private void audit(
      SlaAutomationRule rule,
      TicketFacts t,
      SlaAutomationTrigger trigger,
      Action action,
      String outcome) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("rule", rule.id());
    d.put("ticket", t.id());
    d.put("trigger", trigger.name());
    d.put("action", action.type().name());
    d.put("status", outcome);
    audit.record(SlaAutomationAuditEvent.SLA_AUTOMATION_ACTION, null, null, null, d);
  }
}
