package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.automation.SlaAutomationAdminService;
import com.ticketsystem.api.sla.automation.SlaAutomationAdminService.RuleInput;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Action;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.ActionType;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Conditions;
import com.ticketsystem.api.sla.automation.SlaAutomationTrigger;
import com.ticketsystem.api.sla.automation.clientdata.SlaAutomationRepository;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * ADR-049 (audit PR #67 Medium 1): the automation entry points that assign, raise priority, add a
 * note or read and record rules run only for the SYSTEM scope. An agent who works the ticket's
 * organisation and an admin both get the 404-style refusal and nothing changes. Each assertion
 * would fail if the method's SYSTEM guard were removed (both scopes may otherwise see and change
 * the ticket and read the rules).
 */
class SlaAutomationSystemOnlyIntegrationTest extends SlaTestBase {

  @Autowired SlaAutomationAdminService automation;
  @Autowired SlaAutomationRepository repository;

  @BeforeEach
  @AfterEach
  void noRules() {
    jdbc.update("DELETE FROM sla_automation_rule");
  }

  private static void refused(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private List<AccessScope> agentAndAdmin() {
    return List.of(acmeAgent, admin);
  }

  private String priorityOf(long ticketId) {
    return jdbc.queryForObject("SELECT priority FROM ticket WHERE id = ?", String.class, ticketId);
  }

  private Long assigneeOf(long ticketId) {
    return jdbc.queryForObject(
        "SELECT assignee_user_id FROM ticket WHERE id = ?", Long.class, ticketId);
  }

  private int notesOn(long ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM internal_note WHERE ticket_id = ?", Integer.class, ticketId);
  }

  @Test
  void raisingPriorityByAutomationRefusesAgentAndAdmin() {
    long id = acmeTicket().ticketId();
    String before = priorityOf(id);
    assertThat(before).isNotEqualTo(Priority.P1.name());
    for (AccessScope scope : agentAndAdmin()) {
      refused(() -> tickets.raisePriorityByAutomation(scope, id, "Rule", e -> {}));
    }
    assertThat(priorityOf(id)).isEqualTo(before);
  }

  @Test
  void assigningByAutomationRefusesAgentAndAdmin() {
    long id = acmeTicket().ticketId();
    assertThat(assigneeOf(id)).isNull();
    for (AccessScope scope : agentAndAdmin()) {
      refused(() -> tickets.assignByAutomation(scope, id, acmeAgentUser.id(), "Rule", e -> {}));
    }
    assertThat(assigneeOf(id)).isNull();
  }

  @Test
  void addingAnAutomationNoteRefusesAgentAndAdmin() {
    long id = acmeTicket().ticketId();
    int before = notesOn(id);
    for (AccessScope scope : agentAndAdmin()) {
      refused(() -> notes.addAutomationNote(scope, id, "Escalated"));
    }
    assertThat(notesOn(id)).isEqualTo(before);
  }

  @Test
  void theEngineRepositoryMethodsRefuseAgentAndAdmin() {
    long ruleId =
        automation
            .create(
                admin,
                new RuleInput(
                    "Escalate",
                    true,
                    SlaAutomationTrigger.FIRST_RESPONSE_BREACHED.name(),
                    Conditions.ANY,
                    List.of(new Action(ActionType.ADD_NOTE, List.of(), null, "x"))),
                null)
            .id();
    long ticketId = acmeTicket().ticketId();
    for (AccessScope scope : agentAndAdmin()) {
      refused(() -> repository.enabledFor(scope, SlaAutomationTrigger.FIRST_RESPONSE_BREACHED));
      refused(() -> repository.anyEnabled(scope));
      refused(
          () ->
              repository.recordFiring(
                  scope,
                  ruleId,
                  ticketId,
                  SlaAutomationTrigger.FIRST_RESPONSE_BREACHED,
                  Instant.now()));
    }
    assertThat(repository.firingCount(admin, ruleId)).isZero();
    // The SYSTEM scope (the engine) is let through.
    assertThat(repository.anyEnabled(SYSTEM)).isTrue();
    assertThat(repository.enabledFor(SYSTEM, SlaAutomationTrigger.FIRST_RESPONSE_BREACHED))
        .hasSize(1);
  }
}
