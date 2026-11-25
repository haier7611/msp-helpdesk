package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.notification.NotificationKind;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.automation.SlaAutomationAdminService;
import com.ticketsystem.api.sla.automation.SlaAutomationAdminService.RuleInput;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Action;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.ActionType;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Conditions;
import com.ticketsystem.api.sla.automation.SlaAutomationTrigger;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TimelineEntryView;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-049: SLA automation rules run by the existing SLA evaluation - each trigger fires its rule
 * once per ticket, target and trigger; conditions filter; nothing repeats after a recalculation or
 * a lost stored state; a priority raise does not loop; assignment and notification guards; a
 * failing rule never blocks the others or the SLA values; a disabled rule does nothing.
 *
 * <p>Rules are installation-wide, so every test removes them afterwards (the other SLA classes
 * share this database) and limits its rules to its own organisation where it can.
 */
class SlaAutomationIntegrationTest extends SlaTestBase {

  @Autowired SlaAutomationAdminService automation;

  @BeforeEach
  @AfterEach
  void noRules() {
    jdbc.update("DELETE FROM sla_automation_rule");
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  private long rule(String name, SlaAutomationTrigger trigger, Conditions c, Action... actions) {
    return automation
        .create(admin, new RuleInput(name, true, trigger.name(), c, List.of(actions)), null)
        .id();
  }

  private static Conditions only(long org) {
    return new Conditions(false, List.of(org), List.of(), List.of());
  }

  private static Action note(String text) {
    return new Action(ActionType.ADD_NOTE, List.of(), null, text);
  }

  private static Action assign(long agentId) {
    return new Action(ActionType.ASSIGN, List.of(), agentId, null);
  }

  private static Action raise() {
    return new Action(ActionType.RAISE_PRIORITY, List.of(), null, null);
  }

  private int firings(long ruleId, long ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM sla_automation_firing WHERE rule_id = ? AND ticket_id = ?",
        Integer.class,
        ruleId,
        ticketId);
  }

  private List<String> systemNotes(long ticketId) {
    return jdbc.queryForList(
        "SELECT body_html FROM internal_note WHERE ticket_id = ? AND author_kind = 'SYSTEM'"
            + " ORDER BY id",
        String.class,
        ticketId);
  }

  private List<String> timeline(long ticketId) {
    return queries.detail(admin, ticketId).timelineEntries().stream()
        .map(TimelineEntryView::text)
        .toList();
  }

  private Priority priorityOf(long ticketId) {
    return Priority.valueOf(
        jdbc.queryForObject("SELECT priority FROM ticket WHERE id = ?", String.class, ticketId));
  }

  private Long assigneeOf(long ticketId) {
    return jdbc.queryForObject(
        "SELECT assignee_user_id FROM ticket WHERE id = ?", Long.class, ticketId);
  }

  /** A ticket created now, then the clock moved 30 days on and recalculated: both breached. */
  private long breachedTicketIn(long org, Priority priority) {
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, activeUser(Role.CUSTOMER, org), priority);
    clock.set(clock.instant().plus(Duration.ofDays(30)));
    recalc(id);
    return id;
  }

  /** Forgets the stored SLA results, as if a crash or restore lost them. */
  private void loseStoredState(long ticketId) {
    jdbc.update(
        "UPDATE ticket SET first_response_state = 'RUNNING', first_response_warn_at = NULL,"
            + " resolution_state = 'RUNNING', resolution_warn_at = NULL WHERE id = ?",
        ticketId);
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void eachAtRiskAndBreachTriggerFiresItsRuleOnce() {
    long org = londonOrganisation("Automation Dental");
    long frRisk =
        rule("FR risk", SlaAutomationTrigger.FIRST_RESPONSE_AT_RISK, only(org), note("a"));
    long frBreach =
        rule("FR breach", SlaAutomationTrigger.FIRST_RESPONSE_BREACHED, only(org), note("b"));
    long resRisk = rule("Res risk", SlaAutomationTrigger.RESOLUTION_AT_RISK, only(org), note("c"));
    long resBreach =
        rule("Res breach", SlaAutomationTrigger.RESOLUTION_BREACHED, only(org), note("d"));
    // P1: 30 min first response and 4 h resolution on the 24/7 clock.
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, activeUser(Role.CUSTOMER, org), Priority.P1);
    assertThat(systemNotes(id)).isEmpty();

    clockAt("2026-11-23T09:25"); // 83% of 30 min
    recalc(id);
    assertThat(firings(frRisk, id)).isEqualTo(1);
    assertThat(firings(frBreach, id)).isZero();

    clockAt("2026-11-23T09:31");
    recalc(id);
    assertThat(firings(frBreach, id)).isEqualTo(1);
    assertThat(firings(resRisk, id)).isZero();

    clockAt("2026-11-23T12:13"); // 80% of 4 h was 12:12
    recalc(id);
    assertThat(firings(resRisk, id)).isEqualTo(1);

    clockAt("2026-11-23T13:01");
    recalc(id);
    recalc(id);
    minuteJob.runOnce();
    assertThat(firings(resBreach, id)).isEqualTo(1);
    assertThat(List.of(firings(frRisk, id), firings(frBreach, id), firings(resRisk, id)))
        .containsOnly(1);
    assertThat(systemNotes(id)).containsExactly("<p>a</p>", "<p>b</p>", "<p>c</p>", "<p>d</p>");
    assertThat(timeline(id))
        .contains(
            "Automation (FR risk): internal note added (first response at risk)",
            "Automation (Res breach): internal note added (resolution breached)");
    assertThat(auditEvents("SLA_AUTOMATION_ACTION", null))
        .filteredOn(a -> a.get("details").toString().contains("\"ticket\": " + id))
        .hasSize(4);
  }

  @Test
  void metTriggersFireOnceEvenAfterAReopen() {
    long org = londonOrganisation("Automation Dental");
    long frMet = rule("FR met", SlaAutomationTrigger.FIRST_RESPONSE_MET, only(org), note("met"));
    long resMet = rule("Res met", SlaAutomationTrigger.RESOLUTION_MET, only(org), note("done"));
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, activeUser(Role.CUSTOMER, org), null);

    clockAt("2026-11-23T10:00");
    tickets.addPublicReply(admin, id, "<p>Looking now</p>", List.of(), null);
    assertThat(firings(frMet, id)).isEqualTo(1);
    assertThat(firings(resMet, id)).isZero();

    clockAt("2026-11-23T11:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    assertThat(firings(resMet, id)).isEqualTo(1);
    // Reopened and resolved again: met again, but the rule already fired for this ticket.
    clockAt("2026-11-23T11:30");
    tickets.changeStatus(admin, id, TicketStatus.OPEN);
    clockAt("2026-11-23T12:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    assertThat(firings(resMet, id)).isEqualTo(1);
    assertThat(systemNotes(id)).containsExactly("<p>met</p>", "<p>done</p>");
  }

  @Test
  void conditionsAreAndedAndFilterTickets() {
    long org = londonOrganisation("Automation Dental");
    long other = londonOrganisation("Other Clinic");
    SlaAutomationTrigger t = SlaAutomationTrigger.FIRST_RESPONSE_BREACHED;
    long otherOrg = rule("Other org", t, only(other), note("x"));
    long p1Only =
        rule(
            "P1 only",
            t,
            new Conditions(false, List.of(org), List.of(Priority.P1), List.of()),
            note("x"));
    long alertsOnly =
        rule(
            "Alerts",
            t,
            new Conditions(false, List.of(), List.of(), List.of(alertType)),
            note("x"));
    long matching =
        rule(
            "All match",
            t,
            new Conditions(false, List.of(org), List.of(Priority.P3), List.of(generalType)),
            note("x"));
    long triageOnly =
        rule("Triage", t, new Conditions(true, List.of(), List.of(), List.of()), note("x"));

    long id = breachedTicketIn(org, Priority.P3);
    assertThat(firings(otherOrg, id)).isZero();
    assertThat(firings(p1Only, id)).isZero();
    assertThat(firings(alertsOnly, id)).isZero();
    assertThat(firings(triageOnly, id)).isZero();
    assertThat(firings(matching, id)).isEqualTo(1);

    // The triage marker: a ticket in Email triage (no organisation).
    clockAt("2026-11-23T09:00");
    long tri = triageTicket().ticketId();
    clock.set(clock.instant().plus(Duration.ofDays(30)));
    recalc(tri);
    assertThat(firings(triageOnly, tri)).isEqualTo(1);
    assertThat(firings(matching, tri)).isZero();
    assertThat(timeline(tri))
        .contains("Automation (Triage): internal note added (first response breached)");
  }

  @Test
  void nothingRepeatsAfterARecalculationOrALostStoredState() {
    long org = londonOrganisation("Automation Dental");
    long r = rule("Once", SlaAutomationTrigger.FIRST_RESPONSE_BREACHED, only(org), note("once"));
    long id = breachedTicketIn(org, Priority.P3);
    assertThat(firings(r, id)).isEqualTo(1);

    recalc(id);
    minuteJob.runOnce();
    // As after a crash or a restore: the state change is seen again, the firing record stops it.
    loseStoredState(id);
    recalc(id);
    assertThat(sla(id).get("first_response_state")).isEqualTo("BREACHED");
    assertThat(firings(r, id)).isEqualTo(1);
    assertThat(systemNotes(id)).containsExactly("<p>once</p>");
  }

  @Test
  void aPriorityRaiseRecalculatesTheSlaButNeverLoops() {
    long org = londonOrganisation("Automation Dental");
    long r = rule("Escalate", SlaAutomationTrigger.FIRST_RESPONSE_BREACHED, only(org), raise());
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, activeUser(Role.CUSTOMER, org), Priority.P3);
    clockAt("2026-11-23T13:31"); // P3 first response: 4 business hours, due 13:00
    recalc(id);

    // Raised once; the PriorityChanged reaction recalculated under P2 (still breached).
    assertThat(priorityOf(id)).isEqualTo(Priority.P2);
    assertThat(local(sla(id).get("first_response_due_at"))).isEqualTo("2026-11-23T10:00");
    assertThat(timeline(id))
        .contains(
            "Priority changed from P3 Medium to P2 High by Automation (Escalate)",
            "SLA due times recalculated for the new priority");
    loseStoredState(id);
    recalc(id);
    minuteJob.runOnce();
    assertThat(priorityOf(id)).isEqualTo(Priority.P2);
    assertThat(firings(r, id)).isEqualTo(1);

    // Already P1: nothing to raise, said in the timeline.
    rule("Escalate P1", SlaAutomationTrigger.FIRST_RESPONSE_BREACHED, only(org), raise());
    long p1 = breachedTicketIn(org, Priority.P1);
    assertThat(priorityOf(p1)).isEqualTo(Priority.P1);
    assertThat(timeline(p1))
        .contains(
            "Automation (Escalate P1): priority not raised - it is already P1 Critical (first"
                + " response breached)");
  }

  @Test
  void assignOnlyWhenUnassignedAndToAnAgentWhoCanWorkTheOrganisation() {
    long org = londonOrganisation("Automation Dental");
    long other = londonOrganisation("Other Clinic");
    AppUser outsider = restrictedAgent(other);
    AppUser leaver = restrictedAgent(org);
    AppUser first = restrictedAgent(org);
    AppUser second = restrictedAgent(org);
    SlaAutomationTrigger t = SlaAutomationTrigger.FIRST_RESPONSE_BREACHED;
    rule("To outsider", t, only(org), assign(outsider.id()));
    rule("To leaver", t, only(org), assign(leaver.id()));
    rule("To first", t, only(org), assign(first.id()));
    rule("To second", t, only(org), assign(second.id()));
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", leaver.id());

    long id = breachedTicketIn(org, Priority.P3);
    assertThat(assigneeOf(id)).isEqualTo(first.id());
    String firstName = users.findById(first.id()).orElseThrow().name();
    String secondName = users.findById(second.id()).orElseThrow().name();
    assertThat(timeline(id))
        .contains(
            "Assigned to " + firstName + " by Automation (To first)",
            "Automation (To second): not assigned to "
                + secondName
                + " - the ticket already has an assignee (first response breached)")
        .anyMatch(
            l ->
                l.startsWith("Automation (To outsider): not assigned to ")
                    && l.contains("cannot work this organisation"))
        .anyMatch(
            l ->
                l.startsWith("Automation (To leaver): not assigned to ")
                    && l.contains("deactivated"));
    // Never a pick-up: the status stays New.
    assertThat(statusOf(id)).isEqualTo(TicketStatus.NEW);
  }

  @Test
  void notificationsGoOnlyToAgentsWhoCanWorkTheOrganisation() {
    long org = londonOrganisation("Automation Dental");
    long other = londonOrganisation("Other Clinic");
    AppUser ours = restrictedAgent(org);
    AppUser outsider = restrictedAgent(other);
    SlaAutomationTrigger t = SlaAutomationTrigger.FIRST_RESPONSE_BREACHED;
    rule(
        "Tell them",
        t,
        only(org),
        new Action(ActionType.NOTIFY_AGENTS, List.of(ours.id(), outsider.id()), null, null),
        new Action(ActionType.NOTIFY_ORGANISATION, List.of(), null, null));

    long id = breachedTicketIn(org, Priority.P3);
    assertThat(notifications(id, NotificationKind.SLA_AUTOMATION, ours.id())).isEqualTo(1);
    assertThat(notifications(id, NotificationKind.SLA_AUTOMATION, outsider.id())).isZero();
    assertThat(timeline(id))
        .contains("Automation (Tell them): 1 agent notified (first response breached)")
        .anyMatch(
            l -> l.startsWith("Automation (Tell them): the organisation's agents notified ("));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE ticket_id = ? AND subject LIKE"
                    + " '%SLA automation: first response breached%' AND to_addresses = ?",
                Integer.class, id, users.findById(outsider.id()).orElseThrow().email()))
        .isZero();
  }

  @Test
  void aFailingRuleIsRolledBackAndNeverBlocksTheOthersOrTheSlaValues() {
    long org = londonOrganisation("Automation Dental");
    SlaAutomationTrigger t = SlaAutomationTrigger.FIRST_RESPONSE_BREACHED;
    long broken = rule("Broken", t, only(org), note("partial"), note("never"));
    long fine = rule("Fine", t, only(org), note("fine"));
    // An ASSIGN action without an agent cannot be saved through the API; written directly it
    // throws while the rule runs, after its first note was added.
    jdbc.update(
        "UPDATE sla_automation_rule SET actions = CAST(? AS jsonb) WHERE id = ?",
        "[{\"type\":\"ADD_NOTE\",\"text\":\"partial\"},{\"type\":\"ASSIGN\",\"agentId\":null}]",
        broken);

    long id = breachedTicketIn(org, Priority.P3);
    assertThat(sla(id).get("first_response_state")).isEqualTo("BREACHED");
    assertThat(firings(broken, id)).isZero();
    assertThat(firings(fine, id)).isEqualTo(1);
    assertThat(systemNotes(id)).containsExactly("<p>fine</p>");
  }

  @Test
  void aDisabledRuleDoesNothing() {
    long org = londonOrganisation("Automation Dental");
    long r = rule("Off", SlaAutomationTrigger.FIRST_RESPONSE_BREACHED, only(org), note("off"));
    automation.setEnabled(admin, r, false, null);
    long id = breachedTicketIn(org, Priority.P3);
    assertThat(firings(r, id)).isZero();
    assertThat(systemNotes(id)).isEmpty();
  }
}
