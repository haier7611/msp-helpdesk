package com.ticketsystem.api.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.security.SecurityGoLive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Admin changes to a user's organisation access (spec 2) and security test 4, organisation part
 * (spec 12): an old session gets 401 once the user's organisation or supporter access changes, and
 * the new access applies after signing in again.
 */
@SecurityGoLive({4})
class AccessChangeIntegrationTest extends OrganisationTestBase {

  private long acme;
  private long beta;
  private AppUser adminUser;
  private Browser admin;

  @BeforeEach
  void setUp() throws Exception {
    acme = newOrganisation("Acme Dental");
    beta = newOrganisation("Beta Clinic");
    adminUser = activeUser(Role.ADMIN);
    admin = signedIn(adminUser);
  }

  private MockHttpServletResponse setOrganisation(long userId, long org) throws Exception {
    return admin.put(
        "/api/v1/admin/users/" + userId + "/organisation", "{\"organisationId\":" + org + "}");
  }

  private MockHttpServletResponse setAccess(long userId, String body) throws Exception {
    return admin.put("/api/v1/admin/users/" + userId + "/supporter-access", body);
  }

  @Test
  void customerSessionEndsWhenTheirOrganisationChanges() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER, acme);
    Browser old = signedIn(customer);
    assertThat(old.get("/api/v1/auth/me").getStatus()).isEqualTo(200);

    MockHttpServletResponse r = setOrganisation(customer.id(), beta);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(json(r.getContentAsString()).get("organisationId").asLong()).isEqualTo(beta);

    assertThat(old.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
    assertThat(old.get("/api/v1/portal/anything").getStatus()).isEqualTo(401);
    assertThat(auditEvents("USER_ORGANISATION_CHANGED", customer.id())).hasSize(1);
    // WP-02c's SecurityVersionFilter runs before AccessScopeFilter (before the security context is
    // loaded) and ends the stale session first, so the end is audited as SESSION_INVALIDATED.
    assertThat(auditEvents("SESSION_INVALIDATED", customer.id()))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains("security_version"));

    // Signing in again works and the new scope applies.
    assertThat(signedIn(customer).get("/api/v1/auth/me").getStatus()).isEqualTo(200);
  }

  @Test
  void agentSessionEndsWhenTheirAccessChangesAndTheNewAccessApplies() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser old = signedIn(agent);
    assertThat(old.get("/api/v1/agent/organisations/" + beta).getStatus()).isEqualTo(200);

    MockHttpServletResponse r =
        setAccess(agent.id(), "{\"allOrganisations\":false,\"organisationIds\":[" + acme + "]}");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentAsString()).contains("\"allOrganisations\":false");

    assertThat(old.get("/api/v1/agent/organisations/" + beta).getStatus()).isEqualTo(401);
    assertThat(auditEvents("SUPPORTER_ACCESS_CHANGED", agent.id())).hasSize(1);

    Browser fresh = signedIn(agent);
    assertThat(fresh.get("/api/v1/agent/organisations/" + acme).getStatus()).isEqualTo(200);
    assertThat(fresh.get("/api/v1/agent/organisations/" + beta).getStatus()).isEqualTo(404);

    // Back to all organisations.
    assertThat(setAccess(agent.id(), "{\"allOrganisations\":true}").getStatus()).isEqualTo(200);
    assertThat(fresh.get("/api/v1/agent/organisations/" + beta).getStatus()).isEqualTo(401);
    assertThat(signedIn(agent).get("/api/v1/agent/organisations/" + beta).getStatus())
        .isEqualTo(200);
  }

  @Test
  void otherUsersSessionsAreNotAffected() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser bystander = signedIn(activeUser(Role.AGENT));
    setAccess(agent.id(), "{\"allOrganisations\":false,\"organisationIds\":[" + acme + "]}");
    assertThat(bystander.get("/api/v1/agent/organisations/" + beta).getStatus()).isEqualTo(200);
    assertThat(admin.get("/api/v1/admin/organisations").getStatus()).isEqualTo(200);
  }

  @Test
  void invalidAccessChangesAreRefused() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER, acme);
    AppUser agent = activeUser(Role.AGENT);
    AppUser otherAdmin = activeUser(Role.ADMIN);

    assertThat(setOrganisation(agent.id(), acme).getStatus()).isEqualTo(400);
    assertThat(setOrganisation(customer.id(), 999999999).getStatus()).isEqualTo(400);
    assertThat(setOrganisation(999999999, acme).getStatus()).isEqualTo(404);
    assertThat(setAccess(customer.id(), "{\"allOrganisations\":true}").getStatus()).isEqualTo(400);
    assertThat(
            setAccess(otherAdmin.id(), "{\"allOrganisations\":false,\"organisationIds\":[1]}")
                .getStatus())
        .isEqualTo(400);
    assertThat(
            setAccess(agent.id(), "{\"allOrganisations\":false,\"organisationIds\":[]}")
                .getStatus())
        .isEqualTo(400);
    assertThat(
            setAccess(agent.id(), "{\"allOrganisations\":false,\"organisationIds\":[999999999]}")
                .getStatus())
        .isEqualTo(400);
    // Nothing changed for the refused agent requests.
    assertThat(
            jdbc.queryForObject(
                "SELECT all_organisations FROM app_user WHERE id = ?", Boolean.class, agent.id()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT organisation_id FROM app_user WHERE id = ?", Long.class, customer.id()))
        .isEqualTo(acme);
  }

  @Test
  void onlyAdminsCanChangeAccess() throws Exception {
    AppUser target = activeUser(Role.AGENT);
    Browser agent = signedIn(activeUser(Role.AGENT));
    assertThat(
            agent
                .put(
                    "/api/v1/admin/users/" + target.id() + "/supporter-access",
                    "{\"allOrganisations\":false,\"organisationIds\":[" + acme + "]}")
                .getStatus())
        .isEqualTo(403);
    assertThat(agent.get("/api/v1/admin/users/" + target.id() + "/access").getStatus())
        .isEqualTo(403);
    assertThat(admin.get("/api/v1/admin/users/" + target.id() + "/access").getStatus())
        .isEqualTo(200);
  }

  @Test
  void theDatabaseRefusesACustomerWithoutAnOrganisation() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO app_user (email, name, role, status, created_at, updated_at)"
                        + " VALUES (?, 'X', 'CUSTOMER', 'INVITED', now(), now())",
                    uniqueEmail("noorg")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO app_user (email, name, role, status, organisation_id, created_at,"
                        + " updated_at) VALUES (?, 'X', 'AGENT', 'INVITED', ?, now(), now())",
                    uniqueEmail("agentorg"),
                    acme))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}
