package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * WP-05 contacts (Customers screen, new ticket with inline contact) and the triage "remember" ticks
 * (spec 11b, ADR-022), including cross-organisation denial and the public-domain refusal.
 */
class ContactAndTriageIntegrationTest extends TicketTestBase {

  private static String uniqueDomain() {
    return "clinic-" + UUID.randomUUID().toString().replace("-", "") + ".example";
  }

  private Created triageFrom(String sender) {
    return tickets.create(
        admin,
        new NewTicket(
            null,
            null,
            sender,
            generalType,
            null,
            null,
            null,
            TicketSource.EMAIL,
            "Scanner offline",
            "<p>Help please</p>",
            List.of(),
            null,
            null,
            null,
            null));
  }

  private Long organisationOf(long ticketId) {
    return jdbc.queryForObject(
        "SELECT organisation_id FROM ticket WHERE id = ?", Long.class, ticketId);
  }

  private static String contactJson(long org, String name, String email, boolean invite) {
    return "{\"organisationId\":"
        + org
        + ",\"name\":"
        + Browser.q(name)
        + ",\"email\":"
        + Browser.q(email)
        + ",\"invite\":"
        + invite
        + "}";
  }

  // ---------------------------------------------------------------------------------------------
  // Contacts

  @Test
  void anAgentCreatesSearchesAndInvitesContactsOfTheirOrganisation() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    String email = uniqueEmail("robin.dental");
    MockHttpServletResponse created =
        agent.post("/api/v1/agent/contacts", contactJson(acme, "Robin Example", email, false));
    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode c = json(created.getContentAsString());
    long id = c.get("id").asLong();
    assertThat(c.get("status").asString()).isEqualTo("INVITED");
    assertThat(c.get("organisationId").asLong()).isEqualTo(acme);
    assertThat(mailer.sentTo(email)).isEmpty();
    assertThat(auditEvents("CONTACT_CREATED", id)).hasSize(1);
    // Cannot sign in until invited: no password.
    assertThat(
            jdbc.queryForObject(
                "SELECT password_hash IS NULL FROM app_user WHERE id = ?", Boolean.class, id))
        .isTrue();

    JsonNode page = json(agent.get("/api/v1/agent/contacts?q=robin.dental").getContentAsString());
    assertThat(page.get("total").asLong()).isEqualTo(1);
    assertThat(page.get("items").get(0).get("email").asString()).isEqualTo(email);

    MockHttpServletResponse invited =
        agent.post("/api/v1/agent/contacts/" + id + "/invitation", "{}");
    assertThat(invited.getStatus()).isEqualTo(202);
    assertThat(json(invited.getContentAsString()).get("expiresAt").asString()).isNotBlank();
    assertThat(mailer.sentTo(email)).extracting(s -> s.type()).contains("customer-invitation");
  }

  @Test
  void createWithInviteSendsTheInvitation() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    String email = uniqueEmail("kim");
    assertThat(
            agent
                .post("/api/v1/agent/contacts", contactJson(acme, "Kim Example", email, true))
                .getStatus())
        .isEqualTo(201);
    assertThat(mailer.sentTo(email)).extracting(s -> s.type()).contains("customer-invitation");
  }

  @Test
  void contactRefusals() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    // Another organisation: 404, nothing created.
    String email = uniqueEmail("other");
    assertThat(
            agent
                .post("/api/v1/agent/contacts", contactJson(beta, "Other Example", email, false))
                .getStatus())
        .isEqualTo(404);
    assertThat(users.findByEmail(email)).isEmpty();
    // An existing account.
    MockHttpServletResponse dup =
        agent.post(
            "/api/v1/agent/contacts",
            contactJson(acme, "Dup", betaCustomerUser.email().toUpperCase(), false));
    assertThat(dup.getStatus()).isEqualTo(409);
    assertThat(json(dup.getContentAsString()).get("error").asString()).isEqualTo("contact_exists");
    // Bad input.
    assertThat(
            agent
                .post("/api/v1/agent/contacts", contactJson(acme, "", uniqueEmail("x"), false))
                .getStatus())
        .isEqualTo(400);
    assertThat(
            agent
                .post("/api/v1/agent/contacts", contactJson(acme, "Name", "not-an-email", false))
                .getStatus())
        .isEqualTo(400);
    // Invitations: an active contact is refused, another organisation's is not found.
    MockHttpServletResponse active =
        agent.post("/api/v1/agent/contacts/" + acmeCustomerUser.id() + "/invitation", "{}");
    assertThat(active.getStatus()).isEqualTo(409);
    assertThat(json(active.getContentAsString()).get("error").asString())
        .isEqualTo("already_active");
    assertThat(
            agent
                .post("/api/v1/agent/contacts/" + betaCustomerUser.id() + "/invitation", "{}")
                .getStatus())
        .isEqualTo(404);
    assertThat(
            agent
                .post("/api/v1/agent/contacts/" + acmeAgentUser.id() + "/invitation", "{}")
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void contactSearchIsScopedToTheAgentsOrganisations() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    assertThat(agent.get("/api/v1/agent/contacts?organisationId=" + beta).getStatus())
        .isEqualTo(404);
    String all = agent.get("/api/v1/agent/contacts?limit=100&q=customer").getContentAsString();
    assertThat(all).doesNotContain(betaCustomerUser.email());
    String own =
        agent
            .get("/api/v1/agent/contacts?organisationId=" + acme + "&q=" + acmeCustomerUser.email())
            .getContentAsString();
    assertThat(json(own).get("total").asLong()).isEqualTo(1);
  }

  @Test
  void anAgentStillCannotCreateAnOrganisation() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    assertThat(
            agent
                .post(
                    "/api/v1/admin/organisations",
                    "{\"name\":\"Gamma Optics\",\"prefix\":\"GMO\",\"timezone\":\"Europe/London\","
                        + "\"verifiedDomains\":[]}")
                .getStatus())
        .isEqualTo(403);
  }

  // ---------------------------------------------------------------------------------------------
  // Triage

  @Test
  void bothTicksSaveTheContactAndDomainAndSetTheReporter() throws Exception {
    String domain = uniqueDomain();
    String sender = "pat@" + domain;
    Created tri = triageFrom(sender);
    Browser agent = signedIn(acmeAgentUser);

    JsonNode options =
        json(
            agent
                .get("/api/v1/agent/tickets/" + tri.ticketId() + "/triage-options")
                .getContentAsString());
    assertThat(options.get("senderEmail").asString()).isEqualTo(sender);
    assertThat(options.get("senderDomain").asString()).isEqualTo(domain);
    assertThat(options.get("domainOffered").asBoolean()).isTrue();
    assertThat(options.get("contactExists").asBoolean()).isFalse();

    MockHttpServletResponse r =
        agent.post(
            "/api/v1/agent/tickets/" + tri.ticketId() + "/triage-assignment",
            "{\"organisationId\":" + acme + ",\"saveContact\":true,\"addDomain\":true}");
    assertThat(r.getStatus()).isEqualTo(200);
    JsonNode body = json(r.getContentAsString());
    assertThat(body.get("reference").asString()).startsWith(activePrefixOf(acme) + "-");
    long contactId = body.get("contactId").asLong();

    assertThat(organisationOf(tri.ticketId())).isEqualTo(acme);
    assertThat(
            jdbc.queryForObject(
                "SELECT reporter_user_id FROM ticket WHERE id = ?", Long.class, tri.ticketId()))
        .isEqualTo(contactId);
    assertThat(
            jdbc.queryForObject(
                "SELECT role || ':' || organisation_id || ':' || status FROM app_user WHERE id = ?",
                String.class,
                contactId))
        .isEqualTo("CUSTOMER:" + acme + ":INVITED");
    assertThat(
            jdbc.queryForObject(
                "SELECT organisation_id FROM organisation_domain WHERE domain = ?",
                Long.class,
                domain))
        .isEqualTo(acme);
    assertThat(auditEvents("CONTACT_CREATED", contactId)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE type = 'ORGANISATION_DOMAIN_ADDED' AND"
                    + " details->>'domain' = ? AND details->>'reason' = 'triage'",
                Long.class,
                domain))
        .isEqualTo(1L);
    // The old TRI- reference still resolves to the ticket.
    assertThat(references.resolve(admin, tri.reference().toString()).orElseThrow().ticketId())
        .isEqualTo(tri.ticketId());

    // A second triage ticket from the same domain: the domain is no longer offered and refused.
    Created again = triageFrom("lee@" + domain);
    JsonNode o2 =
        json(
            agent
                .get("/api/v1/agent/tickets/" + again.ticketId() + "/triage-options")
                .getContentAsString());
    assertThat(o2.get("domainOffered").asBoolean()).isFalse();
    MockHttpServletResponse taken =
        agent.post(
            "/api/v1/agent/tickets/" + again.ticketId() + "/triage-assignment",
            "{\"organisationId\":" + acme + ",\"addDomain\":true}");
    assertThat(taken.getStatus()).isEqualTo(409);
    assertThat(json(taken.getContentAsString()).get("error").asString()).isEqualTo("domain_taken");
    assertThat(organisationOf(again.ticketId())).isNull();
  }

  @Test
  void theDomainTickIsNotOfferedAndIsRefusedForAPublicDomain() throws Exception {
    Created tri =
        triageFrom("someone." + UUID.randomUUID().toString().replace("-", "") + "@gmail.com");
    Browser agent = signedIn(acmeAgentUser);
    JsonNode options =
        json(
            agent
                .get("/api/v1/agent/tickets/" + tri.ticketId() + "/triage-options")
                .getContentAsString());
    assertThat(options.get("domainOffered").asBoolean()).isFalse();
    MockHttpServletResponse r =
        agent.post(
            "/api/v1/agent/tickets/" + tri.ticketId() + "/triage-assignment",
            "{\"organisationId\":" + acme + ",\"saveContact\":true,\"addDomain\":true}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(json(r.getContentAsString()).get("error").asString()).isEqualTo("public_domain");
    assertThat(organisationOf(tri.ticketId())).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM organisation_domain WHERE domain = 'gmail.com'", Long.class))
        .isZero();

    // The contact tick alone is fine for a public-domain sender.
    MockHttpServletResponse ok =
        agent.post(
            "/api/v1/agent/tickets/" + tri.ticketId() + "/triage-assignment",
            "{\"organisationId\":" + acme + ",\"saveContact\":true}");
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(json(ok.getContentAsString()).get("contactId").isNull()).isFalse();
  }

  @Test
  void triageRefusals() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    // Another organisation: 404, ticket stays in triage.
    Created tri = triageFrom("pat@" + uniqueDomain());
    assertThat(
            agent
                .post(
                    "/api/v1/agent/tickets/" + tri.ticketId() + "/triage-assignment",
                    "{\"organisationId\":" + beta + ",\"saveContact\":true}")
                .getStatus())
        .isEqualTo(404);
    assertThat(organisationOf(tri.ticketId())).isNull();

    // Sender is another organisation's customer: 409, and nothing changed (one transaction).
    Created spoof = triageFrom(betaCustomerUser.email());
    MockHttpServletResponse exists =
        agent.post(
            "/api/v1/agent/tickets/" + spoof.ticketId() + "/triage-assignment",
            "{\"organisationId\":" + acme + ",\"saveContact\":true}");
    assertThat(exists.getStatus()).isEqualTo(409);
    assertThat(json(exists.getContentAsString()).get("error").asString())
        .isEqualTo("contact_exists");
    assertThat(organisationOf(spoof.ticketId())).isNull();

    // Not a triage ticket.
    Created normal = acmeTicket();
    MockHttpServletResponse notTriage =
        agent.get("/api/v1/agent/tickets/" + normal.ticketId() + "/triage-options");
    assertThat(notTriage.getStatus()).isEqualTo(409);
    assertThat(json(notTriage.getContentAsString()).get("error").asString())
        .isEqualTo("not_in_triage");
    assertThat(
            agent
                .post(
                    "/api/v1/agent/tickets/" + normal.ticketId() + "/triage-assignment",
                    "{\"organisationId\":" + acme + "}")
                .getStatus())
        .isEqualTo(409);

    // Another organisation's ticket: 404.
    Created other = betaTicket();
    assertThat(
            agent.get("/api/v1/agent/tickets/" + other.ticketId() + "/triage-options").getStatus())
        .isEqualTo(404);
  }

  @Test
  void anExistingContactOfTheSameOrganisationIsReused() throws Exception {
    Created tri = triageFrom(acmeCustomerUser.email());
    Browser agent = signedIn(acmeAgentUser);
    JsonNode options =
        json(
            agent
                .get("/api/v1/agent/tickets/" + tri.ticketId() + "/triage-options")
                .getContentAsString());
    assertThat(options.get("contactExists").asBoolean()).isTrue();
    MockHttpServletResponse r =
        agent.post(
            "/api/v1/agent/tickets/" + tri.ticketId() + "/triage-assignment",
            "{\"organisationId\":" + acme + ",\"saveContact\":true}");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(json(r.getContentAsString()).get("contactId").asLong())
        .isEqualTo(acmeCustomerUser.id());
  }
}
