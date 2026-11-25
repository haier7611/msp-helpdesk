package com.ticketsystem.api.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketTestBase;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * ADR-038 Admin - Customers: admin-only list across organisations and every admin action, with its
 * audit row, step-up where required, and the organisation-move participant re-check. Synthetic data
 * only.
 */
class AdminCustomerIntegrationTest extends TicketTestBase {

  private static final String BASE = "/api/v1/admin/customers";

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  private AppUser invitedCustomer(long org) {
    long id =
        users.insertInvited(
            uniqueEmail("invited"), "Robin Example", Role.CUSTOMER, org, Instant.now());
    return users.findById(id).orElseThrow();
  }

  private String status(long id) {
    return jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, id);
  }

  private long organisationOf(long id) {
    return jdbc.queryForObject("SELECT organisation_id FROM app_user WHERE id = ?", Long.class, id);
  }

  private static List<String> emails(JsonNode page) {
    return page.get("items").valueStream().map(n -> n.get("email").asString()).toList();
  }

  @Test
  void anAdminListsCustomersOfEveryOrganisationWithSearchFiltersSortAndPages() throws Exception {
    AppUser invited = invitedCustomer(beta);
    Browser b = signedIn(adminUser);

    JsonNode all = json(b.get(BASE + "?limit=100&sort=organisation").getContentAsString());
    assertThat(emails(all))
        .contains(acmeCustomerUser.email(), betaCustomerUser.email(), invited.email());
    // Customers only: never an agent or admin.
    assertThat(emails(all)).doesNotContain(adminUser.email(), acmeAgentUser.email());

    JsonNode betaOnly = json(b.get(BASE + "?organisationId=" + beta).getContentAsString());
    assertThat(emails(betaOnly))
        .containsExactlyInAnyOrder(betaCustomerUser.email(), invited.email());
    assertThat(betaOnly.get("total").asLong()).isEqualTo(2);

    JsonNode invitedOnly =
        json(b.get(BASE + "?organisationId=" + beta + "&status=INVITED").getContentAsString());
    assertThat(emails(invitedOnly)).containsExactly(invited.email());
    assertThat(invitedOnly.get("items").get(0).get("organisationName").asString())
        .startsWith("Beta Clinic");

    JsonNode byEmail =
        json(b.get(BASE + "?q=" + acmeCustomerUser.email().substring(0, 20)).getContentAsString());
    assertThat(emails(byEmail)).containsExactly(acmeCustomerUser.email());

    // Pages: one per page, the second page holds the other one.
    JsonNode first =
        json(b.get(BASE + "?organisationId=" + beta + "&limit=1&page=0").getContentAsString());
    JsonNode second =
        json(b.get(BASE + "?organisationId=" + beta + "&limit=1&page=1").getContentAsString());
    assertThat(first.get("items")).hasSize(1);
    assertThat(second.get("items")).hasSize(1);
    assertThat(emails(first)).doesNotContainAnyElementsOf(emails(second));

    // Last sign-in sort: the customer who signed in comes first.
    signedIn(betaCustomerUser);
    JsonNode recent =
        json(b.get(BASE + "?organisationId=" + beta + "&sort=lastSignIn").getContentAsString());
    assertThat(emails(recent).get(0)).isEqualTo(betaCustomerUser.email());
    assertThat(recent.get("items").get(0).get("lastSignInAt").isNull()).isFalse();

    assertThat(b.get(BASE + "?status=SOMETHING").getStatus()).isEqualTo(400);
    assertThat(b.get(BASE + "?sort=email").getStatus()).isEqualTo(400);
  }

  @Test
  void erasedCustomersAreHiddenByDefaultAndShownOnlyWithTheErasedFilter() throws Exception {
    AppUser gone = activeUser(Role.CUSTOMER, beta);
    Browser b = signedIn(adminUser);
    assertThat(b.post("/api/v1/admin/users/" + gone.id() + "/erase", "{}").getStatus())
        .isEqualTo(204);

    JsonNode visible = json(b.get(BASE + "?organisationId=" + beta).getContentAsString());
    assertThat(visible.get("items").valueStream().map(n -> n.get("id").asLong()))
        .doesNotContain(gone.id());
    JsonNode erased =
        json(b.get(BASE + "?organisationId=" + beta + "&status=ERASED").getContentAsString());
    assertThat(erased.get("items")).hasSize(1);
    JsonNode row = erased.get("items").get(0);
    assertThat(row.get("name").asString()).isEqualTo("Former user");
    assertThat(row.get("email").isNull()).isTrue();
    assertThat(row.get("status").asString()).isEqualTo("ERASED");

    // An erased customer can no longer be acted on.
    MockHttpServletResponse r = b.post(BASE + "/" + gone.id() + "/reactivate", "{}");
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(r.getContentAsString()).contains("\"erased\"");
    assertThat(b.post(BASE + "/" + gone.id() + "/password-reset", "{}").getStatus()).isEqualTo(409);
  }

  @Test
  void theDetailShowsSignInMethodsTicketsAndTheInvitation() throws Exception {
    acmeTicket();
    acmeTicket();
    jdbc.update(
        "INSERT INTO external_identity (user_id, tenant_id, object_id, linked_at) VALUES (?, ?,"
            + " ?, now())",
        acmeCustomerUser.id(),
        UUID.randomUUID(),
        UUID.randomUUID());
    AppUser invited = invitedCustomer(acme);
    Browser b = signedIn(adminUser);
    assertThat(b.post(BASE + "/" + invited.id() + "/invitation", "{}").getStatus()).isEqualTo(202);

    JsonNode d = json(b.get(BASE + "/" + acmeCustomerUser.id()).getContentAsString());
    assertThat(d.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(d.get("passwordSet").asBoolean()).isTrue();
    assertThat(d.get("microsoftLinked").asBoolean()).isTrue();
    assertThat(d.get("ticketsRaised").asLong()).isEqualTo(2);
    assertThat(d.get("organisationId").asLong()).isEqualTo(acme);
    assertThat(d.get("invitationExpiresAt").isNull()).isTrue();

    JsonNode i = json(b.get(BASE + "/" + invited.id()).getContentAsString());
    assertThat(i.get("status").asString()).isEqualTo("INVITED");
    assertThat(i.get("passwordSet").asBoolean()).isFalse();
    assertThat(i.get("microsoftLinked").asBoolean()).isFalse();
    assertThat(i.get("invitationExpiresAt").isNull()).isFalse();
  }

  @Test
  void agentsEngineersAndCustomersGet403EverywhereAndNothingChanges() throws Exception {
    AppUser allOrgsAgent = activeUser(Role.AGENT);
    String target = BASE + "/" + betaCustomerUser.id();
    for (Browser b :
        List.of(signedIn(allOrgsAgent), signedIn(acmeAgentUser), signedIn(acmeCustomerUser))) {
      assertThat(b.get(BASE).getStatus()).isEqualTo(403);
      assertThat(b.get(target).getStatus()).isEqualTo(403);
      assertThat(b.post(target + "/invitation", "{}").getStatus()).isEqualTo(403);
      assertThat(b.post(target + "/password-reset", "{}").getStatus()).isEqualTo(403);
      assertThat(b.post(target + "/sign-out-everywhere", "{}").getStatus()).isEqualTo(403);
      assertThat(b.post(target + "/deactivate", "{}").getStatus()).isEqualTo(403);
      assertThat(b.post(target + "/reactivate", "{}").getStatus()).isEqualTo(403);
      assertThat(b.put(target + "/organisation", "{\"organisationId\":" + acme + "}").getStatus())
          .isEqualTo(403);
    }
    assertThat(status(betaCustomerUser.id())).isEqualTo("ACTIVE");
    assertThat(organisationOf(betaCustomerUser.id())).isEqualTo(beta);
    assertThat(mailer.sentTo(betaCustomerUser.email())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE target_user_id = ? AND type LIKE"
                    + " 'CUSTOMER_%'",
                Integer.class, betaCustomerUser.id()))
        .isZero();
  }

  @Test
  void agentAndAdminIdsAre404Here() throws Exception {
    Browser b = signedIn(adminUser);
    assertThat(b.get(BASE + "/" + acmeAgentUser.id()).getStatus()).isEqualTo(404);
    assertThat(b.post(BASE + "/" + acmeAgentUser.id() + "/deactivate", "{}").getStatus())
        .isEqualTo(404);
    assertThat(b.get(BASE + "/999999999").getStatus()).isEqualTo(404);
    assertThat(status(acmeAgentUser.id())).isEqualTo("ACTIVE");
  }

  @Test
  void resendInvitationEmailsALinkOnlyToInvitedCustomers() throws Exception {
    AppUser invited = invitedCustomer(acme);
    Browser b = signedIn(adminUser);

    MockHttpServletResponse r = b.post(BASE + "/" + invited.id() + "/invitation", "{}");
    assertThat(r.getStatus()).isEqualTo(202);
    assertThat(json(r.getContentAsString()).get("expiresAt").asString()).isNotBlank();
    assertThat(mailer.last(invited.email(), "customer-invitation")).isNotNull();
    assertThat(auditEvents("INVITATION_CREATED", invited.id())).hasSize(1);

    MockHttpServletResponse active =
        b.post(BASE + "/" + acmeCustomerUser.id() + "/invitation", "{}");
    assertThat(active.getStatus()).isEqualTo(409);
    assertThat(active.getContentAsString()).contains("not_invited");
  }

  @Test
  void passwordResetEmailsActiveCustomersOnly() throws Exception {
    AppUser invited = invitedCustomer(acme);
    Browser b = signedIn(adminUser);

    assertThat(b.post(BASE + "/" + acmeCustomerUser.id() + "/password-reset", "{}").getStatus())
        .isEqualTo(204);
    assertThat(mailer.last(acmeCustomerUser.email(), "password-reset")).isNotNull();
    List<Map<String, Object>> events =
        auditEvents("PASSWORD_RESET_REQUESTED", acmeCustomerUser.id());
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("details").toString())
        .contains("admin")
        .doesNotContain(acmeCustomerUser.email());
    assertThat(events.get(0).get("actor_user_id")).isEqualTo(adminUser.id());

    MockHttpServletResponse r = b.post(BASE + "/" + invited.id() + "/password-reset", "{}");
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(mailer.sentTo(invited.email())).isEmpty();
  }

  @Test
  void forceSignOutEndsTheirSessionsAndNeedsAFreshStepUp() throws Exception {
    Browser customer = signedIn(acmeCustomerUser);
    Browser b = signedIn(adminUser);
    assertThat(customer.get("/api/v1/auth/me").getStatus()).isEqualTo(200);

    assertThat(
            b.post(BASE + "/" + acmeCustomerUser.id() + "/sign-out-everywhere", "{}").getStatus())
        .isEqualTo(204);
    assertThat(customer.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
    assertThat(auditEvents("CUSTOMER_SIGNED_OUT_EVERYWHERE", acmeCustomerUser.id())).hasSize(1);
    assertThat(auditEvents("SECURITY_VERSION_BUMPED", acmeCustomerUser.id()).toString())
        .contains("ADMIN_SIGN_OUT");
    // They can sign in again (nothing else changed).
    assertThat(signedIn(acmeCustomerUser).get("/api/v1/auth/me").getStatus()).isEqualTo(200);

    clock.advance(Duration.ofMinutes(11));
    MockHttpServletResponse late =
        b.post(BASE + "/" + acmeCustomerUser.id() + "/sign-out-everywhere", "{}");
    assertThat(late.getStatus()).isEqualTo(403);
    assertThat(late.getContentAsString()).contains("step_up_required");
    assertThat(auditEvents("CUSTOMER_SIGNED_OUT_EVERYWHERE", acmeCustomerUser.id())).hasSize(1);
  }

  @Test
  void deactivateBlocksSignInKeepsTicketsAndReactivateRestoresIt() throws Exception {
    Created t = acmeTicket();
    Browser customer = signedIn(acmeCustomerUser);
    Browser b = signedIn(adminUser);

    MockHttpServletResponse r = b.post(BASE + "/" + acmeCustomerUser.id() + "/deactivate", "{}");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(json(r.getContentAsString()).get("status").asString()).isEqualTo("DEACTIVATED");
    assertThat(customer.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
    assertThat(
            browser()
                .post("/api/v1/auth/sign-in", signInJson(acmeCustomerUser.email(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(401);
    assertThat(mailer.last(acmeCustomerUser.email(), "notice-ACCOUNT_DEACTIVATED")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT reporter_user_id FROM ticket WHERE id = ?", Long.class, t.ticketId()))
        .isEqualTo(acmeCustomerUser.id());
    List<Map<String, Object>> events = auditEvents("CUSTOMER_DEACTIVATED", acmeCustomerUser.id());
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("details").toString()).contains("ACTIVE");
    assertThat(b.post(BASE + "/" + acmeCustomerUser.id() + "/deactivate", "{}").getStatus())
        .isEqualTo(409);

    MockHttpServletResponse back = b.post(BASE + "/" + acmeCustomerUser.id() + "/reactivate", "{}");
    assertThat(back.getStatus()).isEqualTo(200);
    assertThat(json(back.getContentAsString()).get("status").asString()).isEqualTo("ACTIVE");
    assertThat(auditEvents("CUSTOMER_REACTIVATED", acmeCustomerUser.id())).hasSize(1);
    assertThat(signedIn(acmeCustomerUser).get("/api/v1/auth/me").getStatus()).isEqualTo(200);
    assertThat(b.post(BASE + "/" + acmeCustomerUser.id() + "/reactivate", "{}").getStatus())
        .isEqualTo(409);
  }

  @Test
  void anInvitedCustomerDeactivatedLosesTheLinkAndComesBackAsInvited() throws Exception {
    AppUser invited = invitedCustomer(acme);
    Browser b = signedIn(adminUser);
    b.post(BASE + "/" + invited.id() + "/invitation", "{}");
    String token = mailer.last(invited.email(), "customer-invitation").token();

    assertThat(b.post(BASE + "/" + invited.id() + "/deactivate", "{}").getStatus()).isEqualTo(200);
    assertThat(mailer.sentTo(invited.email()))
        .noneMatch(s -> s.type().equals("notice-ACCOUNT_DEACTIVATED"));
    assertThat(
            browser()
                .post("/api/v1/auth/invitations/inspect", "{\"token\":" + Browser.q(token) + "}")
                .getStatus())
        .isNotEqualTo(200);

    MockHttpServletResponse back = b.post(BASE + "/" + invited.id() + "/reactivate", "{}");
    assertThat(json(back.getContentAsString()).get("status").asString()).isEqualTo("INVITED");
    assertThat(auditEvents("CUSTOMER_REACTIVATED", invited.id()).toString()).contains("INVITED");
  }

  @Test
  void moveNeedsStepUpReChecksParticipantsAndLeavesTicketsWithTheOldOrganisation()
      throws Exception {
    Created raised = acmeTicket();
    AppUser colleague = activeUser(Role.CUSTOMER, acme);
    Created ccOnAccount = ticket(admin, acme, colleague);
    Created ccByAddress = ticket(admin, acme, colleague);
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, user_id, email, created_at) VALUES (?, ?, ?,"
            + " now())",
        ccOnAccount.ticketId(),
        acmeCustomerUser.id(),
        acmeCustomerUser.email());
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, user_id, email, created_at) VALUES (?, NULL,"
            + " ?, now())",
        ccByAddress.ticketId(),
        acmeCustomerUser.email());
    // The colleague stays a participant: only the moved customer goes.
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, user_id, email, created_at) VALUES (?, ?, ?,"
            + " now())",
        raised.ticketId(),
        colleague.id(),
        colleague.email());
    Browser customer = signedIn(acmeCustomerUser);
    Browser b = signedIn(adminUser);
    String path = BASE + "/" + acmeCustomerUser.id() + "/organisation";

    clock.advance(Duration.ofMinutes(11));
    MockHttpServletResponse late = b.put(path, "{\"organisationId\":" + beta + "}");
    assertThat(late.getStatus()).isEqualTo(403);
    assertThat(late.getContentAsString()).contains("step_up_required");
    assertThat(organisationOf(acmeCustomerUser.id())).isEqualTo(acme);

    b = signedIn(adminUser);
    MockHttpServletResponse r = b.put(path, "{\"organisationId\":" + beta + "}");
    assertThat(r.getStatus()).isEqualTo(200);
    JsonNode moved = json(r.getContentAsString());
    assertThat(moved.get("participantTicketsRemoved").asInt()).isEqualTo(2);
    assertThat(moved.get("customer").get("organisationId").asLong()).isEqualTo(beta);
    assertThat(organisationOf(acmeCustomerUser.id())).isEqualTo(beta);
    // Their sessions end (security_version).
    assertThat(customer.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
    // Participant re-check: gone from both Acme tickets (by account and by address), each a
    // timeline row; the colleague stays.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_participant WHERE user_id = ? OR email = ?",
                Integer.class,
                acmeCustomerUser.id(),
                acmeCustomerUser.email()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_participant WHERE ticket_id = ? AND user_id = ?",
                Integer.class,
                raised.ticketId(),
                colleague.id()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_event WHERE type = 'PARTICIPANT_REMOVED' AND reason ="
                    + " 'CONTACT_MOVED' AND ticket_id IN (?, ?) AND actor_user_id = ?",
                Integer.class,
                ccOnAccount.ticketId(),
                ccByAddress.ticketId(),
                adminUser.id()))
        .isEqualTo(2);
    // The ticket they raised stays with Acme, still theirs.
    assertThat(
            jdbc.queryForMap(
                "SELECT organisation_id, reporter_user_id FROM ticket WHERE id = ?",
                raised.ticketId()))
        .containsEntry("organisation_id", acme)
        .containsEntry("reporter_user_id", acmeCustomerUser.id());
    List<Map<String, Object>> events = auditEvents("CUSTOMER_MOVED", acmeCustomerUser.id());
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("details").toString())
        .contains("\"organisation\": " + beta)
        .contains("\"old_organisation\": " + acme)
        .contains("\"tickets\": 2")
        .doesNotContain(acmeCustomerUser.email());

    MockHttpServletResponse same = b.put(path, "{\"organisationId\":" + beta + "}");
    assertThat(same.getStatus()).isEqualTo(409);
    assertThat(same.getContentAsString()).contains("same_organisation");
    assertThat(b.put(path, "{\"organisationId\":999999999}").getStatus()).isEqualTo(400);
    assertThat(b.put(path, "{}").getStatus()).isEqualTo(400);
  }

  @Test
  void theOlderUserOrganisationEndpointNowNeedsStepUpAndReChecksParticipantsToo() throws Exception {
    Created other = ticket(admin, acme, activeUser(Role.CUSTOMER, acme));
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, user_id, email, created_at) VALUES (?, ?, ?,"
            + " now())",
        other.ticketId(),
        acmeCustomerUser.id(),
        acmeCustomerUser.email());
    Browser b = signedIn(adminUser);
    String path = "/api/v1/admin/users/" + acmeCustomerUser.id() + "/organisation";
    clock.advance(Duration.ofMinutes(11));
    assertThat(b.put(path, "{\"organisationId\":" + beta + "}").getStatus()).isEqualTo(403);

    b = signedIn(adminUser);
    assertThat(b.put(path, "{\"organisationId\":" + beta + "}").getStatus()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_participant WHERE ticket_id = ?",
                Integer.class,
                other.ticketId()))
        .isZero();
  }

  @Test
  void noCustomerAuditEventEverCarriesANameOrAddress() throws Exception {
    AppUser invited = invitedCustomer(acme);
    Browser b = signedIn(adminUser);
    long id = acmeCustomerUser.id();
    b.post(BASE + "/" + id + "/sign-out-everywhere", "{}");
    b.post(BASE + "/" + id + "/deactivate", "{}");
    b.post(BASE + "/" + id + "/reactivate", "{}");
    b.put(BASE + "/" + id + "/organisation", "{\"organisationId\":" + beta + "}");
    b.post(BASE + "/" + invited.id() + "/deactivate", "{}");
    List<String> details =
        jdbc.queryForList(
            "SELECT details::text FROM audit_event WHERE type LIKE 'CUSTOMER_%' AND"
                + " target_user_id IN (?, ?)",
            String.class, id, invited.id());
    assertThat(details).hasSize(5);
    assertThat(details)
        .allSatisfy(
            d ->
                assertThat(d.toLowerCase(Locale.ROOT))
                    .doesNotContain("@")
                    .doesNotContain("sam example")
                    .doesNotContain("robin example"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void theOpenApiDocumentMatchesTheRegisteredEndpoints() throws Exception {
    Set<String> registered = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String p : info.getPatternValues()) {
        for (RequestMethod m : info.getMethodsCondition().getMethods()) {
          registered.add(m.name().toLowerCase(Locale.ROOT) + " " + p);
        }
      }
    }
    Map<String, Object> doc;
    try (InputStream in = getClass().getResourceAsStream("/openapi/admin-customers.yaml")) {
      doc = new Yaml().load(in);
    }
    Set<String> documented = new TreeSet<>();
    ((Map<String, Map<String, Object>>) doc.get("paths"))
        .forEach(
            (path, ops) ->
                ops.keySet().stream()
                    .filter(k -> Set.of("get", "post", "put", "delete", "patch").contains(k))
                    .forEach(k -> documented.add(k + " " + path)));
    assertThat(registered).containsAll(documented);
    Set<String> customers = new TreeSet<>();
    registered.stream().filter(e -> e.contains("/api/v1/admin/customers")).forEach(customers::add);
    assertThat(customers).hasSize(8);
    assertThat(documented).containsAll(customers);
  }
}
