package com.ticketsystem.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The three API trees and their role gates (spec sections 2, 9 and 11a). The gate runs before
 * routing, so a path that does not exist yet still answers 401/403 to the wrong caller and 404 to
 * the right one - which proves the gate covers the whole tree, including endpoints later items add.
 */
class RoleGateIntegrationTest extends AuthIntegrationTestBase {

  private static final String PORTAL = "/api/v1/portal/anything";
  private static final String AGENT = "/api/v1/agent/anything";
  private static final String ADMIN = "/api/v1/admin/anything";

  /** Expected status per tree for GET, by caller. */
  private void expect(Browser b, int portal, int agent, int admin) throws Exception {
    assertThat(b.get(PORTAL).getStatus()).as("portal").isEqualTo(portal);
    assertThat(b.get(AGENT).getStatus()).as("agent").isEqualTo(agent);
    assertThat(b.get(ADMIN).getStatus()).as("admin").isEqualTo(admin);
    // Writes too: a POST with a valid CSRF token is gated the same way.
    assertThat(b.post(PORTAL, "{}").getStatus()).as("portal POST").isEqualTo(portal);
    assertThat(b.post(AGENT, "{}").getStatus()).as("agent POST").isEqualTo(agent);
    assertThat(b.post(ADMIN, "{}").getStatus()).as("admin POST").isEqualTo(admin);
  }

  @Test
  void anonymousGets401OnEveryTree() throws Exception {
    expect(browser(), 401, 401, 401);
  }

  @Test
  void customerReachesOnlyThePortal() throws Exception {
    expect(signedIn(activeUser(Role.CUSTOMER)), 404, 403, 403);
  }

  @Test
  void agentReachesOnlyTheAgentTreeNeverThePortal() throws Exception {
    expect(signedIn(activeUser(Role.AGENT)), 403, 404, 403);
  }

  @Test
  void adminReachesAgentAndAdminTreesNeverThePortal() throws Exception {
    expect(signedIn(activeUser(Role.ADMIN)), 403, 404, 404);
  }

  @Test
  void theRealInvitationEndpointIsAdminOnly() throws Exception {
    String body =
        "{\"email\":"
            + Browser.q(uniqueEmail("x"))
            + ",\"name\":\"X\",\"role\":\"CUSTOMER\",\"organisationId\":"
            + newOrganisation("Gate Clinic")
            + "}";
    assertThat(browser().post("/api/v1/admin/invitations", body).getStatus()).isEqualTo(401);
    assertThat(
            signedIn(activeUser(Role.CUSTOMER)).post("/api/v1/admin/invitations", body).getStatus())
        .isEqualTo(403);
    assertThat(signedIn(activeUser(Role.AGENT)).post("/api/v1/admin/invitations", body).getStatus())
        .isEqualTo(403);
    assertThat(signedIn(activeUser(Role.ADMIN)).post("/api/v1/admin/invitations", body).getStatus())
        .isEqualTo(201);
  }

  @Test
  void aSessionWithoutTheFullAuthLevelCannotUseAnyTree() throws Exception {
    // WP-02c: a real half-signed-in session (password done, TOTP owed) gets 403 on every tree.
    AppUser admin = activeUser(Role.ADMIN);
    enrol(admin);
    Browser b = browser();
    assertThat(
            b.post("/api/v1/auth/sign-in", signInJson(admin.email(), GOOD_PASSWORD))
                .getContentAsString())
        .contains("\"authLevel\":\"MFA_REQUIRED\"");
    expect(b, 403, 403, 403);
  }

  @Test
  void pathsOutsideTheTreesAreDenied() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    for (String path : new String[] {"/api/v2/anything", "/api/v1/other", "/internal"}) {
      MockHttpServletResponse r = admin.get(path);
      assertThat(r.getStatus()).as(path).isEqualTo(403);
    }
    assertThat(browser().get("/api/v1/other").getStatus()).isEqualTo(401);
  }

  @Test
  void errorBodiesAreJsonWithoutDetails() throws Exception {
    MockHttpServletResponse r = signedIn(activeUser(Role.CUSTOMER)).get(ADMIN);
    assertThat(r.getContentType()).startsWith("application/json");
    assertThat(r.getContentAsString())
        .isEqualTo("{\"error\":\"forbidden\",\"message\":\"You do not have access to this.\"}");
  }
}
