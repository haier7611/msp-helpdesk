package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.report.SlaReportService.BreachedItem;
import com.ticketsystem.api.sla.report.SlaReportService.Query;
import com.ticketsystem.api.sla.report.SlaReportService.Report;
import com.ticketsystem.api.ticket.TicketService.ClientReply;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The SLA report maths against the WP-09a hand-calculated fixture table: nine fixture cases (F01,
 * F02, F06, F10, F11, F23, F26, F27, F28) are replayed through the real services with the
 * controllable clock, and every figure below is worked out by hand from the fixture rows (first
 * response minutes: F06 60, F10 30, F23 15, F28 90, F26 10 clock minutes; resolution minutes: F10
 * 420, F23 360, F27 20). A duplicate is counted as opened but left out of every SLA measure (AUD-05
 * decision). The CSV holds the same rows as the page and its export is audit-logged.
 */
class SlaReportIntegrationTest extends SlaTestBase {

  private long org;
  private AppUser customer;
  private AppUser engineer;
  private final Map<String, Long> ids = new java.util.HashMap<>();

  @BeforeEach
  void replayFixtures() {
    org = londonOrganisation("Acme Dental");
    customer = activeUser(Role.CUSTOMER, org);
    engineer = restrictedAgent(org);

    // F01 / F02: created Saturday 23:00, still running.
    clockAt("2026-11-21T23:00");
    ids.put("F01", ticketIn(org, customer, Priority.P1));
    ids.put("F02", ticketIn(org, customer, null));

    // F26: P1 Saturday 10:00, reply + Pending 10:10, client reply 12:10, as of 13:00.
    clockAt("2026-11-21T10:00");
    long f26 = ticketIn(org, customer, Priority.P1);
    ids.put("F26", f26);
    clockAt("2026-11-21T10:10");
    tickets.addPublicReply(
        admin, f26, "<p>Restarting</p>", List.of(), TicketStatus.PENDING_ON_CLIENT);
    clockAt("2026-11-21T12:10");
    clientReply(f26);
    clockAt("2026-11-21T13:00");
    recalc(f26);

    // Duplicate of F02, Sunday.
    clockAt("2026-11-22T12:00");
    long dup = ticketIn(org, customer, null);
    ids.put("DUP", dup);
    tickets.markDuplicate(admin, dup, ids.get("F02"));

    // F06.
    clockAt("2026-11-23T09:00");
    long f06 = ticketIn(org, customer, null);
    ids.put("F06", f06);
    // F11, F23, F27, F28 are created at the same instant.
    long f11 = ticketIn(org, customer, Priority.P2);
    long f23 = ticketIn(org, customer, null);
    long f27 = ticketIn(org, customer, Priority.P2);
    long f28 = ticketIn(org, customer, Priority.P2);
    ids.put("F11", f11);
    ids.put("F23", f23);
    ids.put("F27", f27);
    ids.put("F28", f28);
    tickets.assign(admin, f23, engineer.id());
    clockAt("2026-11-23T09:15");
    tickets.addPublicReply(admin, f23, "<p>Looking</p>", List.of(), null);
    clockAt("2026-11-23T09:20");
    tickets.changeStatus(admin, f27, TicketStatus.RESOLVED);
    clockAt("2026-11-23T10:00");
    tickets.addPublicReply(
        admin, f06, "<p>Which one?</p>", List.of(), TicketStatus.PENDING_ON_CLIENT);
    clockAt("2026-11-23T10:30");
    tickets.addPublicReply(admin, f28, "<p>Sorry</p>", List.of(), null);
    clockAt("2026-11-23T11:00");
    recalc(f28);
    clockAt("2026-11-23T15:00");
    tickets.changeStatus(admin, f23, TicketStatus.RESOLVED);
    clockAt("2026-11-24T09:00");
    recalc(f11);
    clockAt("2026-11-24T10:00");
    clientReply(f06);
    clockAt("2026-11-24T11:00");
    recalc(f06);

    // F10: another ticket Monday 09:00, picked up by the admin at once.
    clockAt("2026-11-23T09:00");
    long f10 = ticketIn(org, customer, Priority.P2);
    ids.put("F10", f10);
    tickets.assign(admin, f10, adminUser.id());
    clockAt("2026-11-23T09:30");
    tickets.addPublicReply(admin, f10, "<p>On it</p>", List.of(), null);
    clockAt("2026-11-23T12:00");
    tickets.changeStatus(admin, f10, TicketStatus.RESOLVED);
    clockAt("2026-11-24T09:00");
    clientReply(f10);
    clockAt("2026-11-24T13:00");
    tickets.changeStatus(admin, f10, TicketStatus.RESOLVED);

    clockAt("2026-11-25T12:00");
  }

  private void clientReply(long id) {
    tickets.addClientReply(
        admin,
        id,
        new ClientReply(null, "reporter@example.test", "<p>Reply</p>", true, null, null));
  }

  private Query query() {
    return new Query(
        LocalDate.parse("2026-11-21"),
        LocalDate.parse("2026-11-25"),
        LONDON,
        org,
        null,
        null,
        null);
  }

  @Test
  void storedValuesMatchTheFixtureTable() {
    Map<String, Object> f11 = sla(ids.get("F11"));
    assertThat(local(f11.get("resolution_due_at"))).isEqualTo("2026-11-23T17:00");
    assertThat(f11.get("first_response_state")).isEqualTo("BREACHED");
    assertThat(f11.get("resolution_state")).isEqualTo("BREACHED");
    assertThat(f11.get("resolution_used_minutes")).isEqualTo(540L);
    Map<String, Object> f23 = sla(ids.get("F23"));
    assertThat(local(f23.get("first_response_due_at"))).isEqualTo("2026-11-23T13:00");
    assertThat(f23.get("resolution_state")).isEqualTo("MET");
    assertThat(f23.get("resolution_used_minutes")).isEqualTo(360L);
    Map<String, Object> f26 = sla(ids.get("F26"));
    assertThat(local(f26.get("resolution_due_at"))).isEqualTo("2026-11-21T16:00");
    assertThat(f26.get("resolution_used_minutes")).isEqualTo(60L);
    assertThat(f26.get("sla_paused_minutes")).isEqualTo(120L);
    assertThat(f26.get("first_response_state")).isEqualTo("MET");
  }

  @Test
  void reportMathsEqualTheHandCalculation() {
    Report r = reports.report(admin, query());

    assertThat(r.totals().opened()).isEqualTo(10);
    assertThat(r.totals().duplicates()).isEqualTo(1);
    assertThat(r.totals().resolved()).isEqualTo(3);
    assertThat(r.totals().stillOpen()).isEqualTo(6);
    assertThat(r.ageing().days0to2()).isEqualTo(3); // F06, F11, F28 (Mon 23)
    assertThat(r.ageing().days3to7()).isEqualTo(3); // F01, F02, F26 (Sat 21)

    assertThat(r.firstResponse().met()).isEqualTo(4); // F06, F10, F23, F26
    assertThat(r.firstResponse().breached()).isEqualTo(2); // F11, F28
    assertThat(r.firstResponse().notApplicable()).isEqualTo(1); // F27
    assertThat(r.firstResponse().metPercent()).isEqualTo(66.7);
    assertThat(r.resolution().met()).isEqualTo(3); // F10, F23, F27
    assertThat(r.resolution().breached()).isZero();
    assertThat(r.resolution().metPercent()).isEqualTo(100.0);

    // Business minutes (P2-P4): first response 60, 30, 15, 90; resolution 420, 360, 20.
    assertThat(r.times().businessHours().firstResponse().count()).isEqualTo(4);
    assertThat(r.times().businessHours().firstResponse().averageMinutes()).isEqualTo(48.8);
    assertThat(r.times().businessHours().firstResponse().medianMinutes()).isEqualTo(45.0);
    assertThat(r.times().businessHours().resolution().averageMinutes()).isEqualTo(266.7);
    assertThat(r.times().businessHours().resolution().medianMinutes()).isEqualTo(360.0);
    // Clock minutes (P1): F26's first response took 10 minutes; no P1 resolved yet.
    assertThat(r.times().clockHours().firstResponse().averageMinutes()).isEqualTo(10.0);
    assertThat(r.times().clockHours().resolution().count()).isZero();

    assertThat(r.breached())
        .extracting(BreachedItem::ticketId, BreachedItem::target, BreachedItem::overByMinutes)
        .containsExactlyInAnyOrder(
            tuple(ids.get("F11"), "FIRST_RESPONSE", 480L),
            tuple(ids.get("F11"), "RESOLUTION", 60L),
            tuple(ids.get("F28"), "FIRST_RESPONSE", 30L));
    assertThat(r.breached()).noneMatch(b -> b.ticketId() == ids.get("DUP"));

    assertThat(r.engineers()).hasSize(2);
    assertThat(r.engineers())
        .anySatisfy(
            e -> {
              assertThat(e.userId()).isEqualTo(engineer.id());
              assertThat(e.handled()).isEqualTo(1);
              assertThat(e.resolution().metPercent()).isEqualTo(100.0);
              assertThat(e.times().businessHours().resolution().averageMinutes()).isEqualTo(360.0);
            });
    assertThat(r.tickets()).hasSize(10);
    assertThat(r.tickets()).filteredOn(t -> t.duplicate()).hasSize(1);
  }

  @Test
  void filtersNarrowTheReport() {
    Report p1 =
        reports.report(
            admin,
            new Query(
                LocalDate.parse("2026-11-21"),
                LocalDate.parse("2026-11-25"),
                LONDON,
                org,
                null,
                null,
                Priority.P1));
    assertThat(p1.tickets())
        .extracting(t -> t.ticketId())
        .containsExactlyInAnyOrder(ids.get("F01"), ids.get("F26"));
    Report mine =
        reports.report(
            admin,
            new Query(
                LocalDate.parse("2026-11-21"),
                LocalDate.parse("2026-11-25"),
                LONDON,
                org,
                engineer.id(),
                null,
                null));
    assertThat(mine.tickets()).extracting(t -> t.ticketId()).containsExactly(ids.get("F23"));
  }

  @Test
  void aRestrictedAgentCannotReportOnAnotherOrganisation() {
    assertThatThrownBy(() -> reports.report(acmeAgent, query()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    Report own =
        reports.report(
            acmeAgent,
            new Query(
                LocalDate.parse("2026-11-21"),
                LocalDate.parse("2026-11-25"),
                LONDON,
                null,
                null,
                null,
                null));
    assertThat(own.tickets()).noneMatch(t -> Long.valueOf(org).equals(t.organisationId()));
    AccessScope customerScope = AccessScope.customer(customer.id(), org);
    assertThatThrownBy(() -> reports.report(customerScope, query()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void theCsvHoldsTheSameRowsAsThePageAndIsAuditLogged() throws Exception {
    clock.reset();
    Browser b = signedIn(adminUser);
    String params = "?from=2026-11-21&to=2026-11-25&zone=Europe/London&organisationId=" + org;
    MockHttpServletResponse page = b.get("/api/v1/agent/sla-report" + params);
    assertThat(page.getStatus()).as(page.getContentAsString()).isEqualTo(200);
    int pageRows = json(page.getContentAsString()).get("tickets").size();
    MockHttpServletResponse csv = b.get("/api/v1/agent/sla-report/csv" + params);
    assertThat(csv.getStatus()).isEqualTo(200);
    assertThat(csv.getContentType()).startsWith("text/csv");
    String[] lines = csv.getContentAsString().split("\r\n");
    assertThat(lines[0]).startsWith("reference,organisation_id,subject,priority");
    assertThat(lines.length - 1).isEqualTo(pageRows).isEqualTo(10);
    List<Map<String, Object>> audits = auditEvents("SLA_REPORT_EXPORTED", null);
    assertThat(audits)
        .anySatisfy(
            a ->
                assertThat(a.get("details").toString())
                    .contains("\"rows\": 10")
                    .contains("\"organisation\": " + org));

    Browser customerBrowser = signedIn(customer);
    assertThat(customerBrowser.get("/api/v1/agent/sla-report" + params).getStatus()).isEqualTo(403);
    assertThat(b.get("/api/v1/agent/sla-report?from=2026-11-25&to=2026-11-21").getStatus())
        .isEqualTo(400);
  }
}
