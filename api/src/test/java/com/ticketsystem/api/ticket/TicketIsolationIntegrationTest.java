package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.security.SecurityGoLive;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.attachment.TestImages;
import com.ticketsystem.api.ticket.publicview.ClientThreadService;
import com.ticketsystem.api.ticket.publicview.ClientThreadService.ClientThread;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Spec 12 security test 1 (tickets part): an agent not assigned to the organisation gets 404 on
 * every ticket endpoint (detail, list, reference lookup, every change, comments, notes, links,
 * thumbnail, download); an admin gets through; customers have no ticket read route at all; internal
 * notes and their files never reach a customer or a public read model, even by direct id.
 */
@SecurityGoLive({1})
class TicketIsolationIntegrationTest extends TicketTestBase {

  @Autowired ClientThreadService clientThreads;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  private Created betaTicket;
  private Created acmeOther;
  private UUID betaPublicFile;
  private UUID betaNoteImage;

  @BeforeEach
  void betaData() {
    betaTicket = betaTicket();
    acmeOther = acmeTicket();
    betaPublicFile = upload(admin, "report.txt", "synthetic report".getBytes());
    tickets.addPublicReply(
        admin, betaTicket.ticketId(), "<p>See file</p>", List.of(betaPublicFile), null);
    betaNoteImage = upload(admin, "screen.png", TestImages.png(40, 40));
    notes.addNote(
        admin,
        betaTicket.ticketId(),
        "<p>Password is on the sticky note <img src=\"attachment:" + betaNoteImage + "\"></p>",
        List.of(betaNoteImage));
  }

  /** Every agent ticket endpoint, aimed at Beta's ticket, with a body that would otherwise work. */
  private Map<String, Object[]> betaEndpoints() {
    long id = betaTicket.ticketId();
    String ref = betaTicket.reference().toString();
    Map<String, Object[]> m = new LinkedHashMap<>();
    m.put("detail", new Object[] {HttpMethod.GET, "/api/v1/agent/tickets/" + id, null});
    m.put("sla panel", new Object[] {HttpMethod.GET, "/api/v1/agent/tickets/" + id + "/sla", null});
    m.put(
        "by-reference",
        new Object[] {HttpMethod.GET, "/api/v1/agent/tickets/by-reference/" + ref, null});
    m.put(
        "status",
        new Object[] {
          HttpMethod.POST, "/api/v1/agent/tickets/" + id + "/status", "{\"status\":\"OPEN\"}"
        });
    m.put(
        "priority",
        new Object[] {
          HttpMethod.POST, "/api/v1/agent/tickets/" + id + "/priority", "{\"priority\":\"P1\"}"
        });
    m.put(
        "request-type",
        new Object[] {
          HttpMethod.POST,
          "/api/v1/agent/tickets/" + id + "/request-type",
          "{\"requestTypeId\":" + alertType + "}"
        });
    m.put(
        "assignee",
        new Object[] {
          HttpMethod.POST,
          "/api/v1/agent/tickets/" + id + "/assignee",
          "{\"assigneeUserId\":" + acmeAgentUser.id() + "}"
        });
    m.put(
        "organisation",
        new Object[] {
          HttpMethod.POST,
          "/api/v1/agent/tickets/" + id + "/organisation",
          "{\"organisationId\":" + acme + "}"
        });
    m.put(
        "public-replies",
        new Object[] {
          HttpMethod.POST,
          "/api/v1/agent/tickets/" + id + "/public-replies",
          "{\"bodyHtml\":\"<p>x</p>\"}"
        });
    m.put(
        "internal-notes",
        new Object[] {
          HttpMethod.POST,
          "/api/v1/agent/tickets/" + id + "/internal-notes",
          "{\"bodyHtml\":\"<p>x</p>\"}"
        });
    m.put(
        "duplicate-of (source)",
        new Object[] {
          HttpMethod.POST,
          "/api/v1/agent/tickets/" + id + "/duplicate-of",
          "{\"ticketId\":" + acmeOther.ticketId() + "}"
        });
    m.put(
        "duplicate-of (target)",
        new Object[] {
          HttpMethod.POST,
          "/api/v1/agent/tickets/" + acmeOther.ticketId() + "/duplicate-of",
          "{\"reference\":\"" + ref + "\"}"
        });
    m.put(
        "links (source)",
        new Object[] {
          HttpMethod.POST,
          "/api/v1/agent/tickets/" + id + "/links",
          "{\"ticketId\":" + acmeOther.ticketId() + "}"
        });
    m.put(
        "links (target)",
        new Object[] {
          HttpMethod.POST,
          "/api/v1/agent/tickets/" + acmeOther.ticketId() + "/links",
          "{\"ticketId\":" + id + "}"
        });
    m.put(
        "follow-ups",
        new Object[] {HttpMethod.POST, "/api/v1/agent/tickets/" + id + "/follow-ups", null});
    m.put(
        "download (public)",
        new Object[] {HttpMethod.GET, "/api/v1/agent/attachments/" + betaPublicFile, null});
    m.put(
        "download (note)",
        new Object[] {HttpMethod.GET, "/api/v1/agent/attachments/" + betaNoteImage, null});
    m.put(
        "thumbnail",
        new Object[] {
          HttpMethod.GET, "/api/v1/agent/attachments/" + betaNoteImage + "/thumbnail", null
        });
    return m;
  }

  @Test
  void anAgentNotAssignedToTheOrganisationGets404OnEveryTicketEndpoint() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    for (Map.Entry<String, Object[]> e : betaEndpoints().entrySet()) {
      Object[] v = e.getValue();
      MockHttpServletResponse r = agent.send((HttpMethod) v[0], (String) v[1], (String) v[2]);
      assertThat(r.getStatus()).as(e.getKey()).isEqualTo(404);
      assertThat(r.getContentAsString())
          .as(e.getKey())
          .doesNotContain("Beta")
          .doesNotContain(betaTicket.reference().toString());
    }
    // Nothing changed.
    assertThat(statusOf(betaTicket.ticketId()))
        .isEqualTo(
            com.ticketsystem.api.sla.TicketStatus.OPEN); // picked up by the admin's reply in setUp
    assertThat(statusOf(acmeOther.ticketId())).isEqualTo(com.ticketsystem.api.sla.TicketStatus.NEW);
    // The other organisation's 404 looks exactly like a missing id.
    String missing = agent.get("/api/v1/agent/tickets/999999999").getContentAsString();
    assertThat(agent.get("/api/v1/agent/tickets/" + betaTicket.ticketId()).getContentAsString())
        .isEqualTo(missing);
  }

  @Test
  void theQueueOnlyListsTheAgentsOrganisationsAndTriage() throws Exception {
    Created tri = triageTicket();
    Browser agent = signedIn(acmeAgentUser);
    String list = agent.get("/api/v1/agent/tickets?limit=100").getContentAsString();
    List<Long> ids = new ArrayList<>();
    json(list).forEach(n -> ids.add(n.get("id").asLong()));
    assertThat(ids)
        .contains(acmeOther.ticketId(), tri.ticketId())
        .doesNotContain(betaTicket.ticketId());
    String filtered =
        agent.get("/api/v1/agent/tickets?organisationId=" + beta).getContentAsString();
    assertThat(json(filtered).size()).isZero();
  }

  @Test
  void anAdminReachesEveryOrganisation() throws Exception {
    Browser a = signedIn(adminUser);
    assertThat(a.get("/api/v1/agent/tickets/" + betaTicket.ticketId()).getStatus()).isEqualTo(200);
    assertThat(a.get("/api/v1/agent/attachments/" + betaNoteImage).getStatus()).isEqualTo(200);
    assertThat(a.get("/api/v1/agent/tickets/by-reference/" + betaTicket.reference()).getStatus())
        .isEqualTo(200);
    assertThat(
            a.post(
                    "/api/v1/agent/tickets/" + betaTicket.ticketId() + "/priority",
                    "{\"priority\":\"P2\"}")
                .getStatus())
        .isEqualTo(204);
  }

  @Test
  void customersGet403OnEveryAgentTicketRouteEvenForTheirOwnTicket() throws Exception {
    Browser c = signedIn(betaCustomerUser);
    for (Map.Entry<String, Object[]> e : betaEndpoints().entrySet()) {
      Object[] v = e.getValue();
      assertThat(c.send((HttpMethod) v[0], (String) v[1], (String) v[2]).getStatus())
          .as(e.getKey())
          .isEqualTo(403);
    }
  }

  @Test
  void thePortalHasOnlyRaiseRoutesAndNoReadRoute() {
    Set<String> portal = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String p : info.getPatternValues()) {
        if (p.startsWith("/api/v1/portal")) {
          for (RequestMethod m : info.getMethodsCondition().getMethods()) {
            portal.add(m + " " + p);
          }
        }
      }
    }
    assertThat(portal)
        .containsExactlyInAnyOrder(
            "GET /api/v1/portal/request-types",
            "POST /api/v1/portal/attachments",
            "POST /api/v1/portal/tickets",
            "GET /api/v1/portal/account",
            "PATCH /api/v1/portal/account");
  }

  @Test
  void internalNoteFilesAreNeverServedToCustomersEvenByDirectId() {
    // Service level, with the customer's own organisation's ticket: still 404.
    assertThatThrownBy(() -> attachments.download(betaCustomer, betaNoteImage))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    assertThatThrownBy(() -> attachments.thumbnail(betaCustomer, betaNoteImage))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> attachments.download(AccessScope.apiKey(1, beta), betaNoteImage))
        .isInstanceOf(ApiException.class);
    assertThat(attachments.listForTicket(betaCustomer, betaTicket.ticketId(), true))
        .extracting(a -> a.publicId())
        .containsExactly(betaPublicFile);
    // And never to an agent of another organisation.
    assertThatThrownBy(() -> attachments.download(acmeAgent, betaPublicFile))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void thePublicReadModelHasNoNotesOrNoteFilesEvenWithASystemScope() {
    ClientThread thread =
        clientThreads.thread(
            AccessScope.system("test: client email render"), betaTicket.ticketId());
    String all = thread.toString();
    assertThat(all).doesNotContain("sticky note").doesNotContain(betaNoteImage.toString());
    assertThat(thread.comments()).hasSize(2);
    assertThat(thread.comments().get(1).attachments())
        .extracting(a -> a.id())
        .containsExactly(betaPublicFile);
    assertThatThrownBy(() -> clientThreads.thread(acmeCustomer, betaTicket.ticketId()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void customersAndApiKeysReadNoAgentModel() {
    assertThatThrownBy(() -> queries.detail(betaCustomer, betaTicket.ticketId()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> queries.detail(AccessScope.apiKey(1, beta), betaTicket.ticketId()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> notes.addNote(betaCustomer, betaTicket.ticketId(), "<p>x</p>", List.of()))
        .isInstanceOf(ApiException.class);
  }
}
