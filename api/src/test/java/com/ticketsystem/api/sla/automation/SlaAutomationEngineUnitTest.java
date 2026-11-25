package com.ticketsystem.api.sla.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.notification.NotificationService;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.automation.SlaAutomationEngine.TicketFacts;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Action;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.ActionType;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Conditions;
import com.ticketsystem.api.sla.automation.clientdata.SlaAutomationRepository;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.TicketService.AutomationAssignResult;
import com.ticketsystem.api.ticket.agent.InternalNoteService;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.event.TicketEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * ADR-049 (audit PR #67): the engine runs rules only for the SYSTEM scope - an agent or admin scope
 * touches nothing at all (no repository read, no ticket change, no note, notification, history or
 * audit) - and a rule's ticket events are published only when its savepoint commits.
 */
class SlaAutomationEngineUnitTest {

  private static final long TICKET = 42;
  private static final long AGENT = 7;
  private static final SlaAutomationTrigger TRIGGER = SlaAutomationTrigger.FIRST_RESPONSE_BREACHED;
  private static final TicketFacts FACTS =
      new TicketFacts(TICKET, 3L, Priority.P3, 5L, TicketStatus.OPEN, null);

  private final SlaAutomationRepository rules = mock(SlaAutomationRepository.class);
  private final TicketService tickets = mock(TicketService.class);
  private final InternalNoteService notes = mock(InternalNoteService.class);
  private final NotificationService notifications = mock(NotificationService.class);
  private final TicketHistoryRepository history = mock(TicketHistoryRepository.class);
  private final AuditService audit = mock(AuditService.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final SlaAutomationEngine engine =
      new SlaAutomationEngine(
          rules,
          tickets,
          notes,
          notifications,
          history,
          audit,
          events,
          transactions,
          Clock.fixed(Instant.parse("2026-11-23T09:00:00Z"), ZoneOffset.UTC));

  private static SlaAutomationRule rule(Action... actions) {
    Instant t = Instant.parse("2026-11-01T00:00:00Z");
    return new SlaAutomationRule(
        1, "Escalate", true, 1, TRIGGER, Conditions.ANY, List.of(actions), t, t);
  }

  private void enabled(SlaAutomationRule rule) {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(rules.anyEnabled(any())).thenReturn(true);
    when(rules.enabledFor(any(), eq(TRIGGER))).thenReturn(List.of(rule));
    when(rules.recordFiring(any(), anyLong(), anyLong(), any(), any())).thenReturn(true);
  }

  @Test
  void anAgentOrAdminScopeRunsNothingAndTouchesNothing() {
    enabled(rule(new Action(ActionType.ADD_NOTE, List.of(), null, "x")));
    for (AccessScope scope :
        List.of(AccessScope.agent(AGENT, true, Set.of()), AccessScope.admin(AGENT))) {
      assertThat(engine.onStateChanges(scope, FACTS, List.of(TRIGGER))).isZero();
    }
    verifyNoInteractions(
        rules, tickets, notes, notifications, history, audit, events, transactions);
  }

  @Test
  void theSystemScopeRunsTheRule() {
    enabled(rule(new Action(ActionType.ADD_NOTE, List.of(), null, "x")));
    assertThat(engine.onStateChanges(AccessScope.system("test"), FACTS, List.of(TRIGGER)))
        .isEqualTo(1);
    verify(notes).addAutomationNote(any(), eq(TICKET), eq("x"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void aRuleThatCommitsPublishesItsHeldEvents() {
    enabled(rule(new Action(ActionType.ASSIGN, List.of(), AGENT, null)));
    TicketEvent assigned = new TicketEvent.AssigneeChanged(TICKET, null, AGENT, null);
    when(tickets.assignByAutomation(any(), eq(TICKET), eq(AGENT), anyString(), any()))
        .thenAnswer(
            inv -> {
              ((Consumer<TicketEvent>) inv.getArgument(4)).accept(assigned);
              // Held, not published, while the savepoint is still open.
              verify(events, never()).publishEvent(any(Object.class));
              return AutomationAssignResult.ASSIGNED;
            });

    assertThat(engine.onStateChanges(AccessScope.system("test"), FACTS, List.of(TRIGGER)))
        .isEqualTo(1);
    verify(events).publishEvent(assigned);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aRuleThatFailsAfterAnAssignmentOrRaisePublishesNoEvent() {
    enabled(
        rule(
            new Action(ActionType.ASSIGN, List.of(), AGENT, null),
            new Action(ActionType.RAISE_PRIORITY, List.of(), null, null),
            new Action(ActionType.ADD_NOTE, List.of(), null, "boom")));
    when(tickets.assignByAutomation(any(), eq(TICKET), eq(AGENT), anyString(), any()))
        .thenAnswer(
            inv -> {
              ((Consumer<TicketEvent>) inv.getArgument(4))
                  .accept(new TicketEvent.AssigneeChanged(TICKET, null, AGENT, null));
              return AutomationAssignResult.ASSIGNED;
            });
    when(tickets.raisePriorityByAutomation(any(), eq(TICKET), anyString(), any()))
        .thenAnswer(
            inv -> {
              ((Consumer<TicketEvent>) inv.getArgument(3))
                  .accept(new TicketEvent.PriorityChanged(TICKET, Priority.P3, Priority.P2, null));
              return Priority.P2;
            });
    when(notes.addAutomationNote(any(), anyLong(), anyString()))
        .thenThrow(new IllegalStateException("note failed"));

    assertThat(engine.onStateChanges(AccessScope.system("test"), FACTS, List.of(TRIGGER))).isZero();
    verify(transactions).rollback(any());
    verify(events, never()).publishEvent(any(Object.class));
  }
}
