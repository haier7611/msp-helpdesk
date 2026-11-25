package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.security.SecurityGoLive;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.agent.QueueExportService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * WP-05 queue filters, search, sort and paging, the CSV export (same query, audit-logged, formula
 * guard), the supporter picker, and the access rules on them (spec 12 security test 1).
 */
@SecurityGoLive({1})
class QueueAndExportIntegrationTest extends TicketTestBase {

  Created printer;
  Created vpn;
  Created formula;
  Created betaOne;

  @BeforeEach
  void queueData() {
    printer = create(acme, "Printer jams on tray 2", Priority.P3);
    vpn = create(acme, "VPN 100% down", Priority.P1);
    formula = create(acme, "=HYPERLINK(\"http://evil.example\",\"click\")", Priority.P4);
    betaOne = create(beta, "Beta clinic printer", Priority.P3);
  }

  private Created create(long org, String subject, Priority priority) {
    AppUser reporter = org == acme ? acmeCustomerUser : betaCustomerUser;
    return tickets.create(
        admin,
        new NewTicket(
            org,
            reporter.id(),
            null,
            generalType,
            priority,
            null,
            null,
            TicketSource.AGENT,
            subject,
            "<p>Details</p>",
            List.of(),
            null,
            null,
            null,
            null));
  }

  private static List<Long> ids(MockHttpServletResponse r) throws Exception {
    List<Long> out = new ArrayList<>();
    json(r.getContentAsString()).forEach(n -> out.add(n.get("id").asLong()));
    return out;
  }

  @Test
  void listsWithNamesAndTotalForOneOrganisation() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    MockHttpServletResponse r = agent.get("/api/v1/agent/tickets?organisationId=" + acme);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getHeader("X-Total-Count")).isEqualTo("3");
    assertThat(ids(r)).containsExactly(formula.ticketId(), vpn.ticketId(), printer.ticketId());
    JsonNode first = json(r.getContentAsString()).get(0);
    assertThat(first.get("organisationName").asString()).startsWith("Acme Dental");
    assertThat(first.get("reporterName").asString()).isEqualTo("Sam Example");
    assertThat(first.get("reporterEmail").asString()).isEqualTo(acmeCustomerUser.email());
    assertThat(first.get("requestTypeName").asString()).isEqualTo("Software and applications");
  }

  @Test
  void searchesSubjectEmailAndReferenceWithWildcardsEscaped() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    String base = "/api/v1/agent/tickets?organisationId=" + acme + "&q=";
    assertThat(ids(agent.get(base + "printer"))).containsExactly(printer.ticketId());
    assertThat(ids(agent.get(base + printer.reference().toString().toLowerCase())))
        .containsExactly(printer.ticketId());
    // A typed % matches itself, not everything.
    assertThat(ids(agent.get(base + "100%"))).containsExactly(vpn.ticketId());
    assertThat(ids(agent.get(base + "%%"))).isEmpty();
    // Reporter email.
    assertThat(ids(agent.get(base + acmeCustomerUser.email()))).hasSize(3);
    assertThat(agent.get(base + "x".repeat(201)).getStatus()).isEqualTo(400);
  }

  @Test
  void filtersByPrioritySortsAndPages() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    String base = "/api/v1/agent/tickets?organisationId=" + acme;
    assertThat(ids(agent.get(base + "&priority=P1"))).containsExactly(vpn.ticketId());
    assertThat(ids(agent.get(base + "&sort=oldest")))
        .containsExactly(printer.ticketId(), vpn.ticketId(), formula.ticketId());
    assertThat(ids(agent.get(base + "&sort=priority")))
        .containsExactly(vpn.ticketId(), printer.ticketId(), formula.ticketId());
    assertThat(agent.get(base + "&sort=updated").getStatus()).isEqualTo(200);
    MockHttpServletResponse page1 = agent.get(base + "&sort=oldest&limit=2&page=1");
    assertThat(ids(page1)).containsExactly(formula.ticketId());
    assertThat(page1.getHeader("X-Total-Count")).isEqualTo("3");
    assertThat(agent.get(base + "&sort=sideways").getStatus()).isEqualTo(400);
    assertThat(agent.get(base + "&priority=P9").getStatus()).isEqualTo(400);
    assertThat(agent.get(base + "&page=-1").getStatus()).isEqualTo(400);
  }

  @Test
  void excludeTriageLeavesOutTriageTickets() throws Exception {
    Created tri = triageTicket();
    Browser agent = signedIn(acmeAgentUser);
    assertThat(ids(agent.get("/api/v1/agent/tickets?limit=100"))).contains(tri.ticketId());
    assertThat(ids(agent.get("/api/v1/agent/tickets?limit=100&excludeTriage=true")))
        .doesNotContain(tri.ticketId())
        .contains(printer.ticketId());
  }

  @Test
  void aRestrictedAgentNeverSeesAnotherOrganisationInListCountOrExport() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    MockHttpServletResponse all = agent.get("/api/v1/agent/tickets?limit=100&q=printer");
    assertThat(ids(all)).contains(printer.ticketId()).doesNotContain(betaOne.ticketId());
    MockHttpServletResponse other = agent.get("/api/v1/agent/tickets?organisationId=" + beta);
    assertThat(ids(other)).isEmpty();
    assertThat(other.getHeader("X-Total-Count")).isEqualTo("0");
    String csv = agent.get("/api/v1/agent/tickets/export?q=printer").getContentAsString();
    assertThat(csv)
        .contains(printer.reference().toString())
        .doesNotContain(betaOne.reference().toString())
        .doesNotContain("Beta clinic printer");
  }

  @Test
  void theExportMatchesTheListAndIsAuditLoggedOnce() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    int before = auditEvents("QUEUE_EXPORTED", null).size();
    String filter = "organisationId=" + acme + "&sort=oldest";
    List<String> listed = new ArrayList<>();
    json(agent.get("/api/v1/agent/tickets?" + filter).getContentAsString())
        .forEach(n -> listed.add(n.get("reference").asString()));

    MockHttpServletResponse r = agent.get("/api/v1/agent/tickets/export?" + filter + "&q=");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentType()).startsWith("text/csv");
    assertThat(r.getHeader("Content-Disposition"))
        .startsWith("attachment")
        .contains("tickets-")
        .contains(".csv");
    assertThat(r.getHeader("X-Export-Truncated")).isNull();
    String[] lines = r.getContentAsString().split("\r\n");
    assertThat(lines[0])
        .isEqualTo(
            "Reference,Organisation,Subject,Status,Priority,Request type,Assignee,Reporter,"
                + "Source,Created,Updated,First response time,Resolution time,SLA met,Close reason");
    List<String> exported = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) {
      exported.add(lines[i].split(",", 2)[0]);
    }
    assertThat(exported).isEqualTo(listed);
    // Formula guard: the subject starts with "=", so the cell starts with "'=" (and is quoted).
    assertThat(lines[3]).contains("\"'=HYPERLINK(\"\"http://evil.example\"\"");

    List<Map<String, Object>> events = auditEvents("QUEUE_EXPORTED", null);
    assertThat(events).hasSize(before + 1);
    Map<String, Object> e = events.get(events.size() - 1);
    assertThat(((Number) e.get("actor_user_id")).longValue()).isEqualTo(acmeAgentUser.id());
    assertThat(e.get("details").toString()).contains("\"rows\": 3").contains("\"organisation\"");
  }

  @Test
  void theCellGuardQuotesAndNeutralisesFormulas() {
    assertThat(QueueExportService.cell("=1+1")).isEqualTo("'=1+1");
    assertThat(QueueExportService.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    assertThat(QueueExportService.cell("-2,3")).isEqualTo("\"'-2,3\"");
    assertThat(QueueExportService.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(QueueExportService.cell("plain")).isEqualTo("plain");
    // Spreadsheets skip leading whitespace and control characters, so they must not hide a formula.
    assertThat(QueueExportService.cell(" =1+1")).isEqualTo("' =1+1");
    assertThat(QueueExportService.cell("  \u0001 @SUM(A1)")).isEqualTo("'  \u0001 @SUM(A1)");
    assertThat(QueueExportService.cell("\t=1+1")).isEqualTo("'\t=1+1");
    assertThat(QueueExportService.cell("\r+1")).isEqualTo("\"'\r+1\"");
    assertThat(QueueExportService.cell(" plain -text")).isEqualTo(" plain -text");
  }

  @Test
  void supportersPickerIsScoped() throws Exception {
    AppUser betaOnly = restrictedAgent(beta);
    Browser agent = signedIn(acmeAgentUser);
    MockHttpServletResponse r = agent.get("/api/v1/agent/supporters?organisationId=" + acme);
    assertThat(r.getStatus()).isEqualTo(200);
    List<Long> found = new ArrayList<>();
    json(r.getContentAsString()).forEach(n -> found.add(n.get("id").asLong()));
    assertThat(found)
        .contains(acmeAgentUser.id(), adminUser.id())
        .doesNotContain(betaOnly.id(), acmeCustomerUser.id());
    assertThat(agent.get("/api/v1/agent/supporters?organisationId=" + beta).getStatus())
        .isEqualTo(404);
    assertThat(agent.get("/api/v1/agent/supporters").getStatus()).isEqualTo(200);
  }

  @Test
  void customersGet403OnEveryNewAgentEndpoint() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    long t = printer.ticketId();
    Object[][] calls = {
      {HttpMethod.GET, "/api/v1/agent/tickets?q=printer", null},
      {HttpMethod.GET, "/api/v1/agent/tickets/export", null},
      {HttpMethod.GET, "/api/v1/agent/supporters", null},
      {HttpMethod.GET, "/api/v1/agent/contacts", null},
      {HttpMethod.POST, "/api/v1/agent/contacts", "{}"},
      {HttpMethod.POST, "/api/v1/agent/contacts/" + acmeCustomerUser.id() + "/invitation", "{}"},
      {HttpMethod.GET, "/api/v1/agent/tickets/" + t + "/triage-options", null},
      {HttpMethod.POST, "/api/v1/agent/tickets/" + t + "/triage-assignment", "{}"},
      {HttpMethod.GET, "/api/v1/agent/saved-replies", null},
      {HttpMethod.POST, "/api/v1/agent/saved-replies", "{}"},
      {HttpMethod.PUT, "/api/v1/agent/saved-replies/1", "{}"},
      {HttpMethod.DELETE, "/api/v1/agent/saved-replies/1", null},
      {HttpMethod.POST, "/api/v1/agent/tickets/" + t + "/saved-replies/1/render", "{}"},
    };
    for (Object[] call : calls) {
      assertThat(c.send((HttpMethod) call[0], (String) call[1], (String) call[2]).getStatus())
          .as(call[0] + " " + call[1])
          .isEqualTo(403);
    }
    // The service refuses a customer scope too (defence in depth behind the role gate).
    AccessScope customer = acmeCustomer;
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                queries.list(
                    customer,
                    new com.ticketsystem.api.ticket.agent.AgentTicketViews.QueueQuery(
                        null, null, false, null, false, false, false, null, 50, null, null, false,
                        null, 0)))
        .hasMessageContaining("could not find");
    assertThat(Role.CUSTOMER.isSupporter()).isFalse();
  }
}
