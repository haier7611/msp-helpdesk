package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.sla.ticket.SlaPanel.PausedReason;
import com.ticketsystem.api.sla.ticket.SlaPanel.State;
import com.ticketsystem.api.sla.ticket.SlaPanel.Target;
import com.ticketsystem.api.ticket.TicketService.ClientReply;
import com.ticketsystem.api.ticket.agent.AgentTicketDetailService;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ADR-042: the live SLA panel on the agent ticket page - every state on the London calendar with
 * the WP-09a fixture holidays, P1 on 24/7, pauses per policy, reopen carrying on, consistency with
 * the stored values (queue column, reports), organisation scoping and never for customers.
 */
class SlaPanelIntegrationTest extends SlaTestBase {

  @Autowired SlaPanelService panels;
  @Autowired AgentTicketDetailService details;
  @Autowired DataSource dataSource;
  @Autowired TransactionTemplate transactions;

  private AppUser customerOf(long org) {
    return activeUser(Role.CUSTOMER, org);
  }

  private SlaPanel panel(long ticketId) {
    return panels.panel(admin, ticketId).orElseThrow();
  }

  private static String local(Instant i) {
    return i == null ? "-" : LocalDateTime.ofInstant(i, LONDON).toString();
  }

  private void clientReplies(long ticketId) {
    tickets.addClientReply(
        admin,
        ticketId,
        new ClientReply(null, "reporter@example.test", "<p>Here you go</p>", true, null, null));
  }

  @Test
  void runningInBusinessHoursCountsDownAndMatchesTheStoredValues() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00"); // Monday
    long id = ticketIn(org, customerOf(org), null); // P3: 4 business hours / 3 business days
    clockAt("2026-11-23T10:00");
    SlaPanel p = panel(id);

    assertThat(p.clock()).isEqualTo("BUSINESS_HOURS");
    assertThat(p.timezone()).isEqualTo("Europe/London");
    assertThat(p.priority()).isEqualTo("P3");
    assertThat(p.policyLabel()).isEqualTo("P3 Medium - business hours (Europe/London)");
    assertThat(p.asOf()).isEqualTo(at("2026-11-23T10:00"));
    assertThat(p.businessDayMinutes()).isEqualTo(540);
    Target fr = p.firstResponse();
    assertThat(fr.state()).isEqualTo(State.RUNNING);
    assertThat(fr.targetMinutes()).isEqualTo(240);
    assertThat(fr.usedMinutes()).isEqualTo(60);
    assertThat(fr.remainingBusinessMinutes()).isEqualTo(180);
    assertThat(local(fr.dueAt())).isEqualTo("2026-11-23T13:00");
    assertThat(local(fr.warnAt())).isEqualTo("2026-11-23T12:12"); // 80% of 240 = 192 min
    assertThat(local(fr.clockStopsAt())).isEqualTo("2026-11-23T17:30");
    assertThat(fr.nextClockStart()).isNull();
    assertThat(fr.pausedReason()).isNull();
    Target res = p.resolution();
    assertThat(res.state()).isEqualTo(State.RUNNING);
    assertThat(res.remainingBusinessMinutes()).isEqualTo(1620 - 60);
    assertThat(local(res.dueAt())).isEqualTo("2026-11-26T09:00");

    // Same engine and inputs as the stored values the queue column and reports read.
    recalc(id);
    Map<String, Object> stored = sla(id);
    assertThat(local(stored.get("first_response_due_at"))).isEqualTo(local(fr.dueAt()));
    assertThat(local(stored.get("resolution_due_at"))).isEqualTo(local(res.dueAt()));
    assertThat(stored.get("first_response_state")).isEqualTo("RUNNING");
    assertThat(stored.get("first_response_used_minutes")).isEqualTo(fr.usedMinutes());
    // And it rides on the agent ticket detail.
    assertThat(details.detail(admin, id).slaPanel()).isEqualTo(p);
  }

  @Test
  void outsideBusinessHoursTheClockIsStoppedUntilTheNextOpening() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), Priority.P4); // 1 business day / 10 business days
    clockAt("2026-11-23T18:00");
    Target fr = panel(id).firstResponse();
    assertThat(fr.state()).isEqualTo(State.STOPPED_OUTSIDE_HOURS);
    assertThat(fr.usedMinutes()).isEqualTo(510);
    assertThat(fr.remainingBusinessMinutes()).isEqualTo(30);
    assertThat(local(fr.nextClockStart())).isEqualTo("2026-11-24T08:30");
    assertThat(local(fr.dueAt())).isEqualTo("2026-11-24T09:00");
    assertThat(fr.clockStopsAt()).isNull();
    assertThat(panel(id).resolution().state()).isEqualTo(State.STOPPED_OUTSIDE_HOURS);

    // Over the weekend: until Monday.
    clockAt("2026-11-28T12:00");
    long weekend = ticketIn(org, customerOf(org), Priority.P4);
    assertThat(local(panel(weekend).firstResponse().nextClockStart()))
        .isEqualTo("2026-11-30T08:30");
  }

  @Test
  void holidaysKeepTheClockStopped() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-12-24T17:00"); // Thursday; Fri 25 and Mon 28 are holidays
    long id = ticketIn(org, customerOf(org), Priority.P4);
    clockAt("2026-12-25T10:00");
    Target fr = panel(id).firstResponse();
    assertThat(fr.state()).isEqualTo(State.STOPPED_OUTSIDE_HOURS);
    assertThat(fr.usedMinutes()).isEqualTo(30);
    assertThat(fr.remainingBusinessMinutes()).isEqualTo(510);
    assertThat(local(fr.nextClockStart())).isEqualTo("2026-12-29T08:30");
    assertThat(local(fr.dueAt())).isEqualTo("2026-12-29T17:00");
  }

  @Test
  void p1RunsAroundTheClockEvenAtNightAndAtWeekends() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-21T23:00"); // Saturday night
    long id = ticketIn(org, customerOf(org), Priority.P1);
    clockAt("2026-11-21T23:10");
    SlaPanel p = panel(id);
    assertThat(p.clock()).isEqualTo("TWENTY_FOUR_SEVEN");
    assertThat(p.policyLabel()).isEqualTo("P1 Critical - 24/7");
    assertThat(p.businessDayMinutes()).isEqualTo(1440);
    assertThat(p.firstResponse().state()).isEqualTo(State.RUNNING);
    assertThat(p.firstResponse().remainingBusinessMinutes()).isEqualTo(20);
    assertThat(p.firstResponse().clockStopsAt()).isNull();
    assertThat(local(p.firstResponse().dueAt())).isEqualTo("2026-11-21T23:30");
    assertThat(p.resolution().remainingBusinessMinutes()).isEqualTo(230);
  }

  @Test
  void pendingOnClientPausesWithTheTimeLeftFrozen() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null);
    clockAt("2026-11-23T10:00");
    tickets.addPublicReply(
        admin, id, "<p>Which printer?</p>", List.of(), TicketStatus.PENDING_ON_CLIENT);
    clockAt("2026-11-23T15:00");
    SlaPanel p = panel(id);
    Target res = p.resolution();
    assertThat(res.state()).isEqualTo(State.PAUSED);
    assertThat(res.pausedReason()).isEqualTo(PausedReason.CLIENT);
    assertThat(res.remainingBusinessMinutes()).isEqualTo(1560);
    assertThat(res.dueAt()).isNull();
    clockAt("2026-11-24T15:00");
    assertThat(panel(id).resolution().remainingBusinessMinutes()).isEqualTo(1560);

    Target fr = p.firstResponse();
    assertThat(fr.state()).isEqualTo(State.MET);
    assertThat(local(fr.metAt())).isEqualTo("2026-11-23T10:00");
    assertThat(fr.usedMinutes()).isEqualTo(60);
    assertThat(fr.remainingBusinessMinutes()).isNull();
  }

  @Test
  void pendingOnVendorRunsByDefaultAndPausesWhenThePolicySaysSo() {
    long runs = londonOrganisation("Acme Dental");
    long pauses = londonOrganisation("Beta Clinic");
    slaConfig.savePolicy(admin, pauses, null, true, SlaConfigService.defaultTargets(), null);
    long[] ids = new long[2];
    long[] orgs = {runs, pauses};
    for (int i = 0; i < 2; i++) {
      clockAt("2026-11-23T09:00");
      ids[i] = ticketIn(orgs[i], customerOf(orgs[i]), null);
      clockAt("2026-11-23T10:00");
      tickets.addPublicReply(
          admin, ids[i], "<p>Asked the supplier</p>", List.of(), TicketStatus.PENDING_ON_VENDOR);
    }
    clockAt("2026-11-23T11:00");
    SlaPanel running = panel(ids[0]);
    assertThat(running.pendingOnVendorPauses()).isFalse();
    assertThat(running.resolution().state()).isEqualTo(State.RUNNING);
    assertThat(running.resolution().usedMinutes()).isEqualTo(120);
    SlaPanel paused = panel(ids[1]);
    assertThat(paused.pendingOnVendorPauses()).isTrue();
    assertThat(paused.resolution().state()).isEqualTo(State.PAUSED);
    assertThat(paused.resolution().pausedReason()).isEqualTo(PausedReason.VENDOR);
    assertThat(paused.resolution().usedMinutes()).isEqualTo(60);
    assertThat(paused.resolution().remainingBusinessMinutes()).isEqualTo(1560);
  }

  @Test
  void breachedShowsHowLateAndStaysAfterResolution() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), Priority.P2); // 1 h / 8 business hours
    clockAt("2026-11-23T10:30");
    Target fr = panel(id).firstResponse();
    assertThat(fr.state()).isEqualTo(State.BREACHED);
    assertThat(fr.breachedByMinutes()).isEqualTo(30);
    assertThat(local(fr.dueAt())).isEqualTo("2026-11-23T10:00");
    assertThat(fr.remainingBusinessMinutes()).isNull();

    clockAt("2026-11-23T11:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    clockAt("2026-11-23T12:00");
    SlaPanel after = panel(id);
    assertThat(after.firstResponse().state()).isEqualTo(State.BREACHED);
    assertThat(after.firstResponse().breachedByMinutes()).isEqualTo(60);
    assertThat(after.resolution().state()).isEqualTo(State.MET);
    assertThat(local(after.resolution().metAt())).isEqualTo("2026-11-23T11:00");
    assertThat(after.resolution().usedMinutes()).isEqualTo(120);
  }

  @Test
  void aReopenedTicketCarriesOnFromWhereTheClockStopped() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), Priority.P2);
    tickets.assign(admin, id, adminUser.id());
    clockAt("2026-11-23T09:30");
    tickets.addPublicReply(admin, id, "<p>On it</p>", List.of(), null);
    clockAt("2026-11-23T12:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    assertThat(panel(id).resolution().state()).isEqualTo(State.MET);
    clockAt("2026-11-24T09:00");
    clientReplies(id); // Resolved -> Open
    clockAt("2026-11-24T10:00");
    Target res = panel(id).resolution();
    assertThat(res.state()).isEqualTo(State.RUNNING);
    assertThat(res.usedMinutes()).isEqualTo(240); // 180 before the reopen + 60 since
    assertThat(res.remainingBusinessMinutes()).isEqualTo(240);
    assertThat(res.metAt()).isNull();
    clockAt("2026-11-24T13:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    Target done = panel(id).resolution();
    assertThat(done.state()).isEqualTo(State.MET);
    assertThat(done.usedMinutes()).isEqualTo(420);
    assertThat(local(done.metAt())).isEqualTo("2026-11-24T13:00");
  }

  @Test
  void notApplicableWithoutAReplyAndForDuplicates() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long noReply = ticketIn(org, customerOf(org), Priority.P2);
    long original = ticketIn(org, customerOf(org), Priority.P2);
    long duplicate = ticketIn(org, customerOf(org), Priority.P2);
    clockAt("2026-11-23T09:20");
    tickets.changeStatus(admin, noReply, TicketStatus.RESOLVED);
    tickets.markDuplicate(admin, duplicate, original);
    assertThat(panel(noReply).firstResponse().state()).isEqualTo(State.NOT_APPLICABLE);
    assertThat(panel(noReply).resolution().state()).isEqualTo(State.MET);
    assertThat(panel(duplicate).firstResponse().state()).isEqualTo(State.NOT_APPLICABLE);
    assertThat(panel(duplicate).resolution().state()).isEqualTo(State.NOT_APPLICABLE);
  }

  @Test
  void triageTicketsHaveAPanelOnTheDefaultsAdr047() {
    clockAt("2026-11-23T09:00");
    long id = triageTicket().ticketId();
    SlaPanel p = panels.panel(admin, id).orElseThrow();
    assertThat(p.timezone()).isEqualTo("Asia/Ho_Chi_Minh"); // the MSP timezone
    assertThat(p.firstResponse().targetMinutes()).isEqualTo(240);
    assertThat(details.detail(admin, id).slaPanel()).isEqualTo(p);
  }

  @Test
  void onlyAgentsOfTheOrganisationSeeIt() throws Exception {
    long org = londonOrganisation("Acme Dental");
    long other = londonOrganisation("Beta Clinic");
    AppUser customer = customerOf(org);
    AppUser ours = restrictedAgent(org);
    AppUser theirs = restrictedAgent(other);
    clock.reset();
    long id = ticketIn(org, customer, null);

    assertThat(panels.panel(restrictedScope(ours, org), id)).isPresent();
    assertThat(panels.panel(restrictedScope(theirs, other), id)).isEmpty();
    assertThatThrownBy(() -> panels.panel(AccessScope.customer(customer.id(), org), id))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> panels.panel(AccessScope.apiKey(1, org), id))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> panels.panel(SYSTEM, id)).isInstanceOf(ApiException.class);

    Browser mine = signedIn(ours);
    MockHttpServletResponse r = mine.get("/api/v1/agent/tickets/" + id + "/sla");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode body = json(r.getContentAsString());
    // Real time here (sign-in uses the real clock): running or stopped outside hours.
    assertThat(body.get("firstResponse").get("state").asString())
        .isIn("RUNNING", "STOPPED_OUTSIDE_HOURS");
    JsonNode detail = json(mine.get("/api/v1/agent/tickets/" + id).getContentAsString());
    assertThat(detail.get("slaPanel").get("policyLabel").asString()).startsWith("P3 Medium");

    Browser foreign = signedIn(theirs);
    assertThat(foreign.get("/api/v1/agent/tickets/" + id + "/sla").getStatus()).isEqualTo(404);
    assertThat(foreign.get("/api/v1/agent/tickets/999999999/sla").getStatus()).isEqualTo(404);

    Browser client = signedIn(customer);
    MockHttpServletResponse c = client.get("/api/v1/agent/tickets/" + id + "/sla");
    assertThat(c.getStatus()).isEqualTo(403);
    assertThat(c.getContentAsString().toLowerCase(Locale.ROOT))
        .doesNotContain("remaining")
        .doesNotContain("due");
  }

  @Test
  void anAgentCreatedTicketsOpeningMessageIsNotTheFirstResponseAdr040() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null); // raised by an agent (source AGENT)
    // ADR-040: the description is stored as the agent's own public message...
    assertThat(
            jdbc.queryForObject(
                "SELECT author_kind FROM public_comment WHERE ticket_id = ? ORDER BY id LIMIT 1",
                String.class,
                id))
        .isEqualTo("SUPPORTER");
    clockAt("2026-11-23T09:30");
    // ...but it does not meet first response.
    Target open = panel(id).firstResponse();
    assertThat(open.state()).isEqualTo(State.RUNNING);
    assertThat(open.metAt()).isNull();
    assertThat(open.usedMinutes()).isEqualTo(30);
    tickets.addPublicReply(admin, id, "<p>Looking now</p>", List.of(), null);
    Target met = panel(id).firstResponse();
    assertThat(met.state()).isEqualTo(State.MET);
    assertThat(local(met.metAt())).isEqualTo("2026-11-23T09:30");
    assertThat(met.usedMinutes()).isEqualTo(30);
  }

  @Test
  void theNextOpeningFollowsLocalTimeAcrossTheClockChange() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-10-23T16:00"); // Friday, BST (UTC+1); clocks go back on Sunday 25 October
    long id = ticketIn(org, customerOf(org), null); // P3: 240 business minutes
    clockAt("2026-10-24T12:00");
    Target fr = panel(id).firstResponse();
    assertThat(fr.state()).isEqualTo(State.STOPPED_OUTSIDE_HOURS);
    assertThat(fr.usedMinutes()).isEqualTo(90);
    assertThat(fr.remainingBusinessMinutes()).isEqualTo(150);
    // Monday 08:30 GMT is 08:30 UTC (not 07:30 as it would be on BST).
    assertThat(fr.nextClockStart()).isEqualTo(Instant.parse("2026-10-26T08:30:00Z"));
    assertThat(local(fr.dueAt())).isEqualTo("2026-10-26T11:00");
  }

  @Test
  void aPanelThatCannotBeCalculatedIsHiddenAndTheTicketStillOpens() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null);
    // A history row the engine cannot read (an unknown status).
    jdbc.update(
        "INSERT INTO ticket_event (ticket_id, type, old_value, new_value, created_at, actor_kind)"
            + " VALUES (?, 'STATUS', 'NEW', 'NOT_A_STATUS', now(), 'SYSTEM')",
        id);
    assertThatThrownBy(() -> panels.panel(admin, id)).isInstanceOf(RuntimeException.class);
    var detail = details.detail(admin, id);
    assertThat(detail.slaPanel()).isNull();
    assertThat(detail.id()).isEqualTo(id);
  }

  @Test
  void theTicketDetailNeverHoldsTwoPoolConnectionsAtOnce() throws Exception {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null);
    clockAt("2026-11-23T10:00");
    // The panel is not computed in a transaction nested inside the detail's.
    assertThatThrownBy(() -> transactions.executeWithoutResult(s -> details.detail(admin, id)))
        .isInstanceOf(IllegalTransactionStateException.class); // Propagation.NEVER

    // Leave exactly one connection free: a page view needing a second one at the same time would
    // wait out the pool timeout and the panel would come back hidden (or the detail would fail).
    HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
    List<Connection> held = new ArrayList<>();
    try {
      for (int i = 0; i < pool.getMaximumPoolSize() - 1; i++) {
        held.add(pool.getConnection());
      }
      TicketDetail detail =
          CompletableFuture.supplyAsync(() -> details.detail(admin, id)).get(60, TimeUnit.SECONDS);
      assertThat(detail.id()).isEqualTo(id);
      assertThat(detail.slaPanel()).isNotNull();
      assertThat(detail.slaPanel().firstResponse().state()).isEqualTo(State.RUNNING);
    } finally {
      for (Connection c : held) {
        c.close();
      }
    }
  }
}
