package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.notification.clientdata.NotificationRepository;
import com.ticketsystem.api.sla.clientdata.SlaConfigRepository;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * The admin SLA API (screens are WP-11b): defaults seeded on organisation create, validation,
 * audit, admin-only; and the scope rules of the WP-09 repositories (SLA data is internal).
 */
class SlaAdminApiIntegrationTest extends SlaTestBase {

  @Autowired SlaConfigRepository configRepo;
  @Autowired TicketSlaRepository slaRepo;
  @Autowired NotificationRepository notificationRepo;

  private Browser adminBrowser;

  @BeforeEach
  void signIn() throws Exception {
    clock.reset();
    adminBrowser = signedIn(adminUser);
  }

  private static final String P1_TARGETS =
      "{\"pendingOnVendorPauses\":true,\"targets\":{"
          + "\"P1\":{\"firstResponse\":{\"amount\":15,\"unit\":\"MINUTES\"},\"resolution\":{\"amount\":2,\"unit\":\"HOURS\"},\"clock\":\"TWENTY_FOUR_SEVEN\"},"
          + "\"P2\":{\"firstResponse\":{\"amount\":1,\"unit\":\"HOURS\"},\"resolution\":{\"amount\":8,\"unit\":\"HOURS\"},\"clock\":\"BUSINESS_HOURS\"},"
          + "\"P3\":{\"firstResponse\":{\"amount\":2,\"unit\":\"HOURS\"},\"resolution\":{\"amount\":2,\"unit\":\"DAYS\"},\"clock\":\"BUSINESS_HOURS\"},"
          + "\"P4\":{\"firstResponse\":{\"amount\":1,\"unit\":\"DAYS\"},\"resolution\":{\"amount\":10,\"unit\":\"DAYS\"},\"clock\":\"BUSINESS_HOURS\"}}}";

  @Test
  void aNewOrganisationStartsWithTheAdr017DefaultsAndTheAdminCanEditThem() throws Exception {
    MockHttpServletResponse created =
        adminBrowser.post(
            "/api/v1/admin/organisations",
            "{\"name\":\"Gamma Vets "
                + java.util.UUID.randomUUID().toString().replace("-", "")
                + "\",\"prefix\":\""
                + uniquePrefix()
                + "\",\"timezone\":\"Europe/London\"}");
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    long org = json(created.getContentAsString()).get("id").asLong();

    JsonNode sla =
        json(adminBrowser.get("/api/v1/admin/organisations/" + org + "/sla").getContentAsString());
    assertThat(sla.get("defaultHours").asBoolean()).isFalse();
    assertThat(sla.get("businessHours").size()).isEqualTo(5);
    assertThat(sla.get("settings").get("pendingReminderBusinessDays").asInt()).isEqualTo(3);
    assertThat(sla.get("settings").get("autoResolveBusinessDays").asInt()).isEqualTo(7);
    assertThat(sla.get("settings").get("autoCloseCalendarDays").asInt()).isEqualTo(5);
    assertThat(sla.get("settings").get("apiAlertClientEmails").asBoolean()).isFalse();
    JsonNode p1 = sla.get("policies").get(0).get("targets").get("P1");
    assertThat(p1.get("firstResponse").get("amount").asInt()).isEqualTo(30);
    assertThat(p1.get("clock").asString()).isEqualTo("TWENTY_FOUR_SEVEN");

    MockHttpServletResponse saved =
        adminBrowser.put(
            "/api/v1/admin/organisations/" + org + "/sla/policies/default", P1_TARGETS);
    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    JsonNode after = json(saved.getContentAsString());
    assertThat(after.get("policies").get(0).get("pendingOnVendorPauses").asBoolean()).isTrue();
    assertThat(
            after
                .get("policies")
                .get(0)
                .get("targets")
                .get("P1")
                .get("firstResponse")
                .get("amount")
                .asInt())
        .isEqualTo(15);

    // A request-type override, then removed again.
    assertThat(
            adminBrowser
                .put(
                    "/api/v1/admin/organisations/"
                        + org
                        + "/sla/policies/request-types/"
                        + alertType,
                    P1_TARGETS)
                .getStatus())
        .isEqualTo(200);
    assertThat(
            adminBrowser
                .delete(
                    "/api/v1/admin/organisations/"
                        + org
                        + "/sla/policies/request-types/"
                        + alertType)
                .getStatus())
        .isEqualTo(200);

    assertThat(
            adminBrowser
                .put(
                    "/api/v1/admin/organisations/" + org + "/sla/business-hours",
                    "[{\"dayOfWeek\":1,\"opens\":\"09:00\",\"closes\":\"17:00\"},"
                        + "{\"dayOfWeek\":6,\"opens\":\"10:00\",\"closes\":\"00:00\"}]")
                .getStatus())
        .isEqualTo(200);
    assertThat(
            adminBrowser
                .put(
                    "/api/v1/admin/organisations/" + org + "/sla/holidays",
                    "[{\"date\":\"2026-12-25\",\"name\":\"Christmas Day\"}]")
                .getStatus())
        .isEqualTo(200);
    assertThat(
            adminBrowser
                .put(
                    "/api/v1/admin/organisations/" + org + "/sla/timers",
                    "{\"pendingReminderEnabled\":true,\"pendingReminderBusinessDays\":2,"
                        + "\"autoResolveEnabled\":false,\"autoResolveBusinessDays\":7,"
                        + "\"autoCloseEnabled\":true,\"autoCloseCalendarDays\":3,"
                        + "\"apiAlertClientEmails\":true}")
                .getStatus())
        .isEqualTo(200);
    assertThat(auditEvents("SLA_SETTINGS_CHANGED", null))
        .filteredOn(a -> a.get("details").toString().contains("\"organisation\": " + org))
        .hasSizeGreaterThanOrEqualTo(6);
  }

  @Test
  void invalidSettingsAreRefused() throws Exception {
    String base = "/api/v1/admin/organisations/" + acme + "/sla";
    String p1OnBusinessHours = P1_TARGETS.replaceFirst("TWENTY_FOUR_SEVEN", "BUSINESS_HOURS");
    assertThat(adminBrowser.put(base + "/policies/default", p1OnBusinessHours).getStatus())
        .isEqualTo(400);
    String missingP4 = P1_TARGETS.substring(0, P1_TARGETS.indexOf(",\"P4\"")) + "}}";
    assertThat(adminBrowser.put(base + "/policies/default", missingP4).getStatus()).isEqualTo(400);
    assertThat(
            adminBrowser
                .put(
                    base + "/business-hours",
                    "[{\"dayOfWeek\":1,\"opens\":\"17:00\",\"closes\":\"09:00\"}]")
                .getStatus())
        .isEqualTo(400);
    assertThat(adminBrowser.put(base + "/business-hours", "[]").getStatus()).isEqualTo(400);
    assertThat(
            adminBrowser
                .put(
                    base + "/timers",
                    "{\"pendingReminderEnabled\":true,\"pendingReminderBusinessDays\":0,"
                        + "\"autoResolveEnabled\":true,\"autoResolveBusinessDays\":7,"
                        + "\"autoCloseEnabled\":true,\"autoCloseCalendarDays\":5,"
                        + "\"apiAlertClientEmails\":false}")
                .getStatus())
        .isEqualTo(400);
    AppUser noAccess = restrictedAgent(beta);
    assertThat(
            adminBrowser
                .put(base + "/default-assignee", "{\"userId\":" + noAccess.id() + "}")
                .getStatus())
        .isEqualTo(400);
    assertThat(adminBrowser.get("/api/v1/admin/organisations/999999999/sla").getStatus())
        .isEqualTo(404);
  }

  @Test
  void agentsAndCustomersGet403() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    assertThat(agent.get("/api/v1/admin/organisations/" + acme + "/sla").getStatus())
        .isEqualTo(403);
    assertThat(
            agent
                .put("/api/v1/admin/organisations/" + acme + "/sla/policies/default", P1_TARGETS)
                .getStatus())
        .isEqualTo(403);
    Browser customer = signedIn(acmeCustomerUser);
    assertThat(customer.get("/api/v1/admin/organisations/" + acme + "/sla").getStatus())
        .isEqualTo(403);
  }

  @Test
  void slaRepositoriesAreInternalAndScoped() {
    long id = acmeTicket().ticketId();
    long betaId = betaTicket().ticketId();
    // Customers and API keys never read SLA configuration or SLA values.
    for (AccessScope s : List.of(acmeCustomer, AccessScope.apiKey(1, acme))) {
      assertThatThrownBy(() -> configRepo.policies(s, acme)).isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> slaRepo.lock(s, id)).isInstanceOf(RuntimeException.class);
      assertThat(slaRepo.fieldsFor(s, List.of(id))).isEmpty();
    }
    // A restricted agent sees only their organisations.
    assertThatThrownBy(() -> configRepo.settings(acmeAgent, beta))
        .isInstanceOf(RuntimeException.class);
    assertThat(slaRepo.fieldsFor(acmeAgent, List.of(id, betaId))).containsOnlyKeys(id);
    assertThat(slaRepo.fieldsFor(admin, List.of(id, betaId))).containsOnlyKeys(id, betaId);
    // Writes are admin-only; job work lists SYSTEM-only.
    assertThatThrownBy(() -> configRepo.replaceHolidays(acmeAgent, acme, List.of()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> slaRepo.needingRecalculation(admin, clock.instant(), 1))
        .isInstanceOf(RuntimeException.class);
    // The bell: customers get nothing.
    assertThatThrownBy(() -> notificationRepo.unreadCount(acmeCustomer))
        .isInstanceOf(RuntimeException.class);
    AppUser customerOfBeta = activeUser(Role.CUSTOMER, beta);
    assertThat(customerOfBeta).isNotNull();
  }
}
