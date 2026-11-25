package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.notification.NotificationKind;
import com.ticketsystem.api.sla.ClockType;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.SlaTarget;
import com.ticketsystem.api.sla.TargetState;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.sla.config.SlaConfigTypes.TargetRow;
import com.ticketsystem.api.sla.report.SlaReportService.Query;
import com.ticketsystem.api.sla.report.SlaReportService.Report;
import com.ticketsystem.api.sla.report.SlaReportService.TicketRow;
import com.ticketsystem.api.sla.ticket.SlaPanel.State;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.agent.AgentTicketDetailService;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TimelineEntryView;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * ADR-047 Decision 2: a triage ticket (no organisation) runs the built-in default SLA policy and
 * Monday-Friday 08:30-17:30 in the MSP timezone (Asia/Ho_Chi_Minh by default); moving it to an
 * organisation recalculates from the original creation time under that organisation's policy and
 * calendar, keeping the first response already given; the panel API and the report include it.
 */
class TriageSlaIntegrationTest extends SlaTestBase {

  private static final ZoneId MSP = ZoneId.of("Asia/Ho_Chi_Minh");

  @Autowired SlaPanelService panels;
  @Autowired AgentTicketDetailService details;
  @Autowired TicketSlaRepository slaTickets;

  /** MSP local time (UTC+7, no daylight saving) as an instant. */
  private static Instant msp(String localDateTime) {
    return ZonedDateTime.of(LocalDateTime.parse(localDateTime), MSP).toInstant();
  }

  private void mspClock(String localDateTime) {
    clock.set(msp(localDateTime));
  }

  private static String mspLocal(Object timestamp) {
    if (timestamp == null) {
      return "-";
    }
    if (timestamp instanceof Instant i) {
      return LocalDateTime.ofInstant(i, MSP).toString();
    }
    return LocalDateTime.ofInstant(((Timestamp) timestamp).toInstant(), MSP).toString();
  }

  /** A triage ticket from an unknown sender with {@code priority} (null = request type's, P3). */
  private long triage(Priority priority) {
    Created c =
        tickets.create(
            admin,
            new NewTicket(
                null,
                null,
                "unknown.sender@public-mail.example",
                generalType,
                priority,
                null,
                null,
                TicketSource.EMAIL,
                "Printer will not print",
                "<p>Help</p>",
                List.of(),
                null,
                null,
                null,
                null));
    return c.ticketId();
  }

  private void reply(long id, TicketStatus thenStatus) {
    tickets.addPublicReply(admin, id, "<p>Looking into it</p>", List.of(), thenStatus);
  }

  @Test
  void aTriageTicketRunsTheDefaultBusinessHoursInTheMspTimezone() {
    mspClock("2026-11-23T09:00"); // Monday
    long id = triage(null); // P3: 4 business hours / 3 business days

    Map<String, Object> s = sla(id);
    assertThat(s.get("sla_clock")).isEqualTo("BUSINESS_HOURS");
    assertThat(s.get("sla_policy_fallback")).isEqualTo(false);
    assertThat(s.get("first_response_target_minutes")).isEqualTo(240L);
    assertThat(mspLocal(s.get("first_response_due_at"))).isEqualTo("2026-11-23T13:00");
    assertThat(mspLocal(s.get("resolution_due_at"))).isEqualTo("2026-11-26T09:00");
    assertThat(s.get("first_response_state")).isEqualTo("RUNNING");

    // Outside the default hours the clock is stopped (evening in the MSP timezone).
    mspClock("2026-11-23T18:00");
    SlaPanel p = panels.panel(admin, id).orElseThrow();
    assertThat(p.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(p.policyLabel()).isEqualTo("P3 Medium - business hours (Asia/Ho_Chi_Minh)");
    assertThat(p.firstResponse().state()).isEqualTo(State.BREACHED); // was due 13:00
    assertThat(p.resolution().state()).isEqualTo(State.STOPPED_OUTSIDE_HOURS);
    assertThat(mspLocal(p.resolution().nextClockStart())).isEqualTo("2026-11-24T08:30");
  }

  @Test
  void aTriageP1RunsAroundTheClock() {
    mspClock("2026-11-21T23:00"); // Saturday night
    long id = triage(Priority.P1);
    Map<String, Object> s = sla(id);
    assertThat(s.get("sla_clock")).isEqualTo("TWENTY_FOUR_SEVEN");
    assertThat(mspLocal(s.get("first_response_due_at"))).isEqualTo("2026-11-21T23:30");
    assertThat(mspLocal(s.get("resolution_due_at"))).isEqualTo("2026-11-22T03:00");
    // Unassigned and no organisation: nobody to alert, so the P1 alert is not marked as sent.
    assertThat(notifications(id, NotificationKind.P1_CREATED)).isZero();
    Object alerted =
        jdbc.queryForObject("SELECT p1_alerted_at FROM ticket WHERE id = ?", Object.class, id);
    assertThat(alerted).isNull();
  }

  @Test
  void pendingOnClientPausesATriageTicket() {
    mspClock("2026-11-23T09:00");
    long id = triage(null);
    mspClock("2026-11-23T10:00");
    reply(id, TicketStatus.PENDING_ON_CLIENT);
    mspClock("2026-11-23T15:00");
    recalc(id);
    Map<String, Object> s = sla(id);
    assertThat(s.get("first_response_state")).isEqualTo("MET");
    assertThat(s.get("first_response_used_minutes")).isEqualTo(60L);
    assertThat(s.get("resolution_state")).isEqualTo("PAUSED");
    assertThat(s.get("resolution_due_at")).isNull();
    assertThat(s.get("resolution_used_minutes")).isEqualTo(60L);
    SlaPanel p = panels.panel(admin, id).orElseThrow();
    assertThat(p.resolution().state()).isEqualTo(State.PAUSED);
    assertThat(p.resolution().remainingBusinessMinutes()).isEqualTo(1620 - 60);
  }

  @Test
  void movingToAnOrganisationRecalculatesFromCreationUnderItsPolicyAndCalendar() {
    long org = londonOrganisation("Acme Dental");
    // The organisation's own P3: first response 2 business hours (default is 4).
    Map<Priority, TargetRow> targets = new EnumMap<>(SlaConfigService.defaultTargets());
    targets.put(
        Priority.P3,
        new TargetRow(SlaTarget.hours(2), SlaTarget.days(3), ClockType.BUSINESS_HOURS));
    slaConfig.savePolicy(admin, org, null, false, targets, null);

    mspClock("2026-11-23T15:00"); // Monday 08:00 in London
    long id = triage(null);
    // Triage: MSP calendar, default 4 business hours -> 150 min Monday + 90 min Tuesday.
    assertThat(mspLocal(sla(id).get("first_response_due_at"))).isEqualTo("2026-11-24T10:00");
    mspClock("2026-11-23T15:45"); // 08:45 London
    reply(id, null);
    Instant responded = msp("2026-11-23T15:45");

    mspClock("2026-11-23T16:30"); // 09:30 London
    tickets.changeOrganisation(admin, id, org);

    Map<String, Object> s = sla(id);
    // London: created 08:00, opens 08:30; 2 business hours -> 10:30; 3 business days -> Wed 17:30.
    assertThat(local(s.get("first_response_due_at"))).isEqualTo("2026-11-23T10:30");
    assertThat(local(s.get("resolution_due_at"))).isEqualTo("2026-11-25T17:30");
    assertThat(s.get("first_response_target_minutes")).isEqualTo(120L);
    // The reply given in triage still meets first response (15 London business minutes).
    assertThat(((Timestamp) s.get("first_responded_at")).toInstant()).isEqualTo(responded);
    assertThat(s.get("first_response_state")).isEqualTo("MET");
    assertThat(s.get("first_response_used_minutes")).isEqualTo(15L);
    assertThat(s.get("resolution_used_minutes")).isEqualTo(60L); // 08:30-09:30, not restarted

    // The move is recorded like a priority change, old and new due times kept.
    Map<String, Object> h =
        jdbc.queryForMap(
            "SELECT old_value, new_value, reason FROM ticket_event WHERE ticket_id = ? AND type ="
                + " 'SLA_DUE_CHANGED'",
            id);
    assertThat(h.get("reason")).isEqualTo(TicketSlaService.DUE_CHANGE_ORGANISATION_MOVED);
    assertThat((String) h.get("old_value")).contains(msp("2026-11-24T10:00").toString());
    assertThat((String) h.get("new_value")).contains(at("2026-11-23T10:30").toString());
    assertThat(details.detail(admin, id).timelineEntries())
        .extracting(TimelineEntryView::text)
        .contains("SLA due times recalculated for the new organisation");
  }

  @Test
  void aTriageWarningNobodyReceivedReachesTheOrganisationAfterTheMove() {
    long org = londonOrganisation("Acme Dental");
    AppUser ours = restrictedAgent(org);
    mspClock("2026-11-23T15:00"); // Monday 08:00 London
    long id = triage(Priority.P2); // 1 h first response
    mspClock("2026-11-23T17:00"); // breached in triage (MSP 15:00-16:00 is the hour)
    recalc(id);
    assertThat(sla(id).get("first_response_state")).isEqualTo("BREACHED");
    assertThat(notifications(id, NotificationKind.SLA_BREACHED)).isZero();

    tickets.changeOrganisation(admin, id, org); // London 10:00: 08:30-09:30 used, breached
    assertThat(sla(id).get("first_response_state")).isEqualTo("BREACHED");
    assertThat(notifications(id, NotificationKind.SLA_BREACHED, ours.id())).isEqualTo(1);
  }

  @Test
  void aTriageWarningGoesToTheAssignee() {
    AppUser agent = restrictedAgent();
    mspClock("2026-11-23T09:00");
    long id = triage(null); // P3: 240 business minutes
    tickets.assign(admin, id, agent.id());
    mspClock("2026-11-23T12:12"); // 80%
    recalc(id);
    assertThat(notifications(id, NotificationKind.SLA_AT_RISK, agent.id())).isEqualTo(1);
    assertThat(notifications(id, NotificationKind.SLA_AT_RISK)).isEqualTo(1);
  }

  @Test
  void theSlaPanelApiAndTheTicketDetailServeTriageTickets() throws Exception {
    AppUser agent = restrictedAgent();
    clock.reset();
    long id = triage(null);
    assertThat(details.detail(admin, id).slaPanel()).isNotNull();

    Browser b = signedIn(agent);
    MockHttpServletResponse r = b.get("/api/v1/agent/tickets/" + id + "/sla");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode body = json(r.getContentAsString());
    assertThat(body.get("timezone").asString()).isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(body.get("firstResponse").get("state").asString())
        .isIn("RUNNING", "STOPPED_OUTSIDE_HOURS");
    JsonNode detail = json(b.get("/api/v1/agent/tickets/" + id).getContentAsString());
    assertThat(detail.get("slaPanel").get("policyLabel").asString()).startsWith("P3 Medium");
    assertThat(detail.get("sla")).isNotNull();
    assertThat(detail.get("sla").isNull()).isFalse();
  }

  @Test
  void triageTicketsFiledBeforeAdr047GetTheirValuesFromTheMinuteJob() {
    mspClock("2026-11-23T09:00");
    long open = triage(null);
    long closed = triage(null);
    mspClock("2026-11-23T09:30");
    tickets.changeStatus(admin, closed, TicketStatus.RESOLVED);
    forceStatus(closed, TicketStatus.CLOSED);
    // As stored before ADR-047: never calculated.
    jdbc.update(
        "UPDATE ticket SET sla_calculated_at = NULL, first_response_due_at = NULL,"
            + " resolution_due_at = NULL, first_response_state = NULL, resolution_state = NULL"
            + " WHERE id IN (?, ?)",
        open,
        closed);
    mspClock("2026-11-23T10:00");
    minuteJob.runOnce();
    assertThat(mspLocal(sla(open).get("first_response_due_at"))).isEqualTo("2026-11-23T13:00");
    assertThat(sla(open).get("first_response_state")).isEqualTo("RUNNING");
    Map<String, Object> c = sla(closed);
    assertThat(c.get("resolution_state")).isEqualTo("MET");
    assertThat(c.get("resolution_used_minutes")).isEqualTo(30L);
    assertThat(c.get("first_response_state")).isEqualTo("STOPPED"); // no reply: not applicable

    // Once calculated, the Closed triage ticket is never picked again: the backfill runs once.
    Object calculatedAt =
        jdbc.queryForObject(
            "SELECT sla_calculated_at FROM ticket WHERE id = ?", Object.class, closed);
    assertThat(calculatedAt).isNotNull();
    mspClock("2026-11-23T11:00");
    assertThat(slaTickets.needingRecalculation(SYSTEM, clock.instant(), 1000))
        .doesNotContain(closed);
    minuteJob.runOnce();
    assertThat(sla(closed)).isEqualTo(c);
    assertThat(
            jdbc.queryForObject(
                "SELECT sla_calculated_at FROM ticket WHERE id = ?", Object.class, closed))
        .isEqualTo(calculatedAt);
  }

  @Test
  void aTriageP1NobodyWasToldAboutAlertsTheOrganisationAfterTheMove() {
    long org = londonOrganisation("Acme Dental");
    AppUser ours = restrictedAgent(org);
    mspClock("2026-11-23T15:00");
    long id = triage(Priority.P1); // unassigned: nobody to tell, not marked as sent
    assertThat(notifications(id, NotificationKind.P1_CREATED)).isZero();

    mspClock("2026-11-23T15:10");
    tickets.changeOrganisation(admin, id, org);
    assertThat(notifications(id, NotificationKind.P1_CREATED, ours.id())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT p1_alerted_at FROM ticket WHERE id = ?", Object.class, id))
        .isNotNull();
    recalc(id); // marked as sent now: not repeated
    assertThat(notifications(id, NotificationKind.P1_CREATED, ours.id())).isEqualTo(1);
  }

  @Test
  void movingTwiceRecordsEachRealChangeOnce() {
    long first = londonOrganisation("Acme Dental");
    long second = londonOrganisation("Beta Clinic");
    Map<Priority, TargetRow> targets = new EnumMap<>(SlaConfigService.defaultTargets());
    targets.put(
        Priority.P3,
        new TargetRow(SlaTarget.hours(2), SlaTarget.days(3), ClockType.BUSINESS_HOURS));
    slaConfig.savePolicy(admin, second, null, false, targets, null);

    mspClock("2026-11-23T15:00"); // Monday 08:00 London
    long id = triage(null);
    mspClock("2026-11-23T16:00");
    tickets.changeOrganisation(admin, id, first); // default P3 4 h in London -> 12:30
    assertThat(local(sla(id).get("first_response_due_at"))).isEqualTo("2026-11-23T12:30");
    tickets.changeOrganisation(admin, id, second); // its P3 2 h -> 10:30
    assertThat(local(sla(id).get("first_response_due_at"))).isEqualTo("2026-11-23T10:30");
    // Same organisation again, and a repeated recalculation: nothing changes, no new line.
    tickets.changeOrganisation(admin, id, second);
    slaService.recalculateAfterOrganisationChange(SYSTEM, id);

    List<Map<String, Object>> lines =
        jdbc.queryForList(
            "SELECT reason, reason_ref_id FROM ticket_event WHERE ticket_id = ? AND type ="
                + " 'SLA_DUE_CHANGED' ORDER BY id",
            id);
    assertThat(lines).hasSize(2);
    assertThat(lines)
        .extracting(l -> ((Number) l.get("reason_ref_id")).longValue())
        .containsExactly(first, second);
    assertThat(details.detail(admin, id).timelineEntries())
        .extracting(TimelineEntryView::text)
        .filteredOn(t -> t.startsWith("SLA due times"))
        .containsExactly(
            "SLA due times recalculated for the new organisation",
            "SLA due times recalculated for the new organisation");
  }

  @Test
  void triageSlaReachesAgentsOnlyAndNeverAnotherOrganisationsTickets() throws Exception {
    long orgA = londonOrganisation("Acme Dental");
    long orgB = londonOrganisation("Beta Clinic");
    AppUser agentA = restrictedAgent(orgA);
    AppUser customer = activeUser(Role.CUSTOMER, orgA);
    clock.reset();
    long tri = triage(null);
    long bTicket = ticketIn(orgB, activeUser(Role.CUSTOMER, orgB), null);
    AccessScope scopeA = restrictedScope(agentA, orgA);

    // An agent restricted to A sees triage SLA (as they see triage tickets), never B's.
    assertThat(panels.panel(scopeA, tri)).isPresent();
    assertThat(panels.panel(scopeA, bTicket)).isEmpty();
    assertThat(slaService.fieldsFor(scopeA, List.of(tri, bTicket))).containsOnlyKeys(tri);
    assertThat(slaTickets.find(scopeA, bTicket)).isEmpty();

    // Customers and API keys get no SLA data for a triage ticket.
    AccessScope customerScope = AccessScope.customer(customer.id(), orgA);
    AccessScope apiKey = AccessScope.apiKey(1, orgA);
    for (AccessScope s : List.of(customerScope, apiKey)) {
      assertThatThrownBy(() -> panels.panel(s, tri)).isInstanceOf(ApiException.class);
      assertThat(slaService.fieldsFor(s, List.of(tri))).isEmpty();
      assertThatThrownBy(() -> slaTickets.find(s, tri)).isInstanceOf(ApiException.class);
    }

    Browser mine = signedIn(agentA);
    assertThat(mine.get("/api/v1/agent/tickets/" + tri + "/sla").getStatus()).isEqualTo(200);
    assertThat(mine.get("/api/v1/agent/tickets/" + bTicket + "/sla").getStatus()).isEqualTo(404);
    Browser client = signedIn(customer);
    MockHttpServletResponse c = client.get("/api/v1/agent/tickets/" + tri + "/sla");
    assertThat(c.getStatus()).isEqualTo(403);
    assertThat(c.getContentAsString().toLowerCase(Locale.ROOT))
        .doesNotContain("remaining")
        .doesNotContain("due");
  }

  /** The report for 1 February 2027 in the MSP timezone, optionally for one organisation. */
  private static Query february1(Long organisationId) {
    LocalDate day = LocalDate.parse("2027-02-01");
    return new Query(day, day, MSP, organisationId, null, null, null);
  }

  @Test
  void theReportCountsTriageTicketsUnlessAnOrganisationIsChosen() {
    long org = londonOrganisation("Acme Dental");
    mspClock("2027-02-01T09:00"); // Monday
    long id = triage(Priority.P2);
    mspClock("2027-02-01T11:00"); // first response breached by an hour
    recalc(id);

    Report all = reports.report(admin, february1(null));
    TicketRow row =
        all.tickets().stream().filter(t -> t.ticketId() == id).findFirst().orElseThrow();
    assertThat(row.organisationId()).isNull();
    assertThat(row.firstResponseState()).isEqualTo(TargetState.BREACHED);
    assertThat(all.breached())
        .anySatisfy(
            b -> {
              assertThat(b.ticketId()).isEqualTo(id);
              assertThat(b.organisationId()).isNull();
              assertThat(b.overByMinutes()).isEqualTo(60L);
            });
    // A restricted agent sees triage too (spec 11a), so it is in their report.
    Report agents = reports.report(restrictedScope(restrictedAgent(org), org), february1(null));
    assertThat(agents.tickets()).anySatisfy(t -> assertThat(t.ticketId()).isEqualTo(id));
    Report oneOrg = reports.report(admin, february1(org));
    assertThat(oneOrg.tickets()).noneMatch(t -> t.ticketId() == id);
    // The CSV lists it too, with an empty organisation.
    assertThat(reports.csv(admin, february1(null), null)).contains("TRI-");
  }
}
