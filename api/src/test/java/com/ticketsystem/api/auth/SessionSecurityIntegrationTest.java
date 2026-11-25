package com.ticketsystem.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.mfa.BreakGlass;
import com.ticketsystem.api.auth.session.SecurityVersionService;
import com.ticketsystem.api.security.SecurityGoLive;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security test 4 (spec section 12) and session management (WP-02c): an old session gets 401 on its
 * very next request after each security change, the session list and sign-out, the admin security
 * actions, new-device notices and the break-glass reset.
 */
@SecurityGoLive({4})
class SessionSecurityIntegrationTest extends AuthIntegrationTestBase {

  private static final JsonMapper JSON = new JsonMapper();

  @Autowired SecurityVersionService securityVersion;
  @Autowired BreakGlass breakGlass;

  private static JsonNode json(MockHttpServletResponse r) throws Exception {
    return JSON.readTree(r.getContentAsString());
  }

  private int sessionRowsOf(AppUser user) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM spring_session WHERE principal_name = ?",
        Integer.class,
        Long.toString(user.id()));
  }

  private static String tree(AppUser user) {
    return switch (user.role()) {
      case CUSTOMER -> "/api/v1/portal/x";
      case AGENT -> "/api/v1/agent/x";
      case ADMIN -> "/api/v1/admin/x";
    };
  }

  /** The old session is refused on its next request, without any /me call first. */
  private void assertEnded(Browser old, AppUser user) throws Exception {
    assertThat(old.get(tree(user)).getStatus()).isEqualTo(401);
  }

  private void assertAlive(Browser b, AppUser user) throws Exception {
    assertThat(b.get(tree(user)).getStatus()).isEqualTo(404);
  }

  private int trustedDevices(AppUser user) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM user_device WHERE user_id = ? AND trusted_until IS NOT NULL",
        Integer.class,
        user.id());
  }

  private Browser trustedSession(AppUser user) throws Exception {
    if (!secrets.containsKey(user.id())) {
      enrol(user);
    }
    Browser b = browser();
    b.post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD));
    assertThat(
            b.post(
                    "/api/v1/auth/mfa/verify",
                    "{\"code\":" + Browser.q(totp(user)) + ",\"trustDevice\":true}")
                .getStatus())
        .isEqualTo(200);
    return b;
  }

  @Test
  void passwordChangeEndsEveryOtherSessionAtOnceAndRevokesTrustedDevices() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser keep = trustedSession(agent);
    Browser other = signedIn(agent);
    assertThat(sessionRowsOf(agent)).isEqualTo(2);
    assertThat(trustedDevices(agent)).isEqualTo(1);

    String body =
        "{\"currentPassword\":"
            + Browser.q(GOOD_PASSWORD)
            + ",\"newPassword\":\"Copper-Meadow-Lantern-58\"}";
    assertThat(keep.post("/api/v1/auth/password/change", body).getStatus()).isEqualTo(204);

    // Deleted in the same transaction, not at some later check.
    assertThat(sessionRowsOf(agent)).isEqualTo(1);
    assertEnded(other, agent);
    assertAlive(keep, agent);
    assertThat(trustedDevices(agent)).isZero();
    assertThat(auditEvents("SECURITY_VERSION_BUMPED", agent.id()).toString()).contains("PASSWORD");
  }

  @Test
  void passwordResetEndsEverySession() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER);
    Browser a = signedIn(customer);
    Browser b = signedIn(customer);
    browser()
        .post(
            "/api/v1/auth/password-reset/request",
            "{\"email\":" + Browser.q(customer.email()) + "}");
    String token = mailer.last(customer.email(), "password-reset").token();
    assertThat(
            browser()
                .post(
                    "/api/v1/auth/password-reset/confirm",
                    "{\"token\":"
                        + Browser.q(token)
                        + ",\"password\":\"Copper-Meadow-Lantern-58\"}")
                .getStatus())
        .isEqualTo(204);
    assertThat(sessionRowsOf(customer)).isZero();
    assertEnded(a, customer);
    assertEnded(b, customer);
  }

  @Test
  void mfaReEnrolmentEndsOtherSessions() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    Browser keep = signedIn(admin);
    Browser other = signedIn(admin);
    MockHttpServletResponse start = keep.post("/api/v1/auth/mfa/enrolment", "{}");
    assertThat(start.getStatus()).isEqualTo(200);
    byte[] secret = MfaIntegrationTest.base32Decode(json(start).get("manualKey").asString());
    secrets.put(admin.id(), secret);
    clock.advance(java.time.Duration.ofSeconds(31));
    String c =
        com.ticketsystem.api.auth.mfa.Totp.codeAt(
            secret, com.ticketsystem.api.auth.mfa.Totp.stepAt(clock.instant()));
    assertThat(
            keep.post("/api/v1/auth/mfa/enrolment/confirm", "{\"code\":" + Browser.q(c) + "}")
                .getStatus())
        .isEqualTo(200);
    assertEnded(other, admin);
    assertAlive(keep, admin);
  }

  @Test
  void adminMfaResetEndsEverySessionRevokesTrustAndForcesReEnrolment() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser agentSession = trustedSession(agent);
    Browser adminSession = signedIn(admin);

    // Agents may not; the admin tree is admin-only.
    assertThat(
            agentSession
                .post("/api/v1/admin/supporters/" + admin.id() + "/security/reset-mfa", "{}")
                .getStatus())
        .isEqualTo(403);

    assertThat(
            adminSession
                .post("/api/v1/admin/supporters/" + agent.id() + "/security/reset-mfa", "{}")
                .getStatus())
        .isEqualTo(204);
    assertEnded(agentSession, agent);
    assertThat(trustedDevices(agent)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_mfa WHERE user_id = ?", Integer.class, agent.id()))
        .isZero();
    assertThat(mailer.sentTo(agent.email()))
        .extracting(RecordingSystemMailer.Sent::type)
        .contains("notice-MFA_RESET");
    List<Map<String, Object>> audit = auditEvents("MFA_RESET", agent.id());
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0).get("actor_user_id")).isEqualTo(admin.id());

    // The trusted browser no longer skips anything: enrolment is required again.
    assertThat(
            agentSession
                .post("/api/v1/auth/sign-in", signInJson(agent.email(), GOOD_PASSWORD))
                .getContentAsString())
        .contains("MFA_ENROLMENT_REQUIRED");
  }

  @Test
  void adminSecurityActionsRefuseSelfCustomersAndUnknownUsers() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser customer = activeUser(Role.CUSTOMER);
    Browser b = signedIn(admin);
    assertThat(
            b.post("/api/v1/admin/supporters/" + admin.id() + "/security/sign-out-everywhere", "{}")
                .getStatus())
        .isEqualTo(400);
    assertThat(
            b.post(
                    "/api/v1/admin/supporters/" + customer.id() + "/security/sign-out-everywhere",
                    "{}")
                .getStatus())
        .isEqualTo(404);
    assertThat(b.post("/api/v1/admin/supporters/999999999/security/reset-mfa", "{}").getStatus())
        .isEqualTo(404);
    assertThat(
            signedIn(customer)
                .post(
                    "/api/v1/admin/supporters/" + admin.id() + "/security/sign-out-everywhere",
                    "{}")
                .getStatus())
        .isEqualTo(403);
  }

  @Test
  void adminSignOutEverywhereEndsTheSupportersSessionsButKeepsTrust() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser a = trustedSession(agent);
    Browser b = signedIn(agent);
    assertThat(
            b.post("/api/v1/admin/supporters/" + admin.id() + "/security/sign-out-everywhere", "{}")
                .getStatus())
        .isEqualTo(403);

    assertThat(
            signedIn(admin)
                .post(
                    "/api/v1/admin/supporters/" + agent.id() + "/security/sign-out-everywhere",
                    "{}")
                .getStatus())
        .isEqualTo(204);
    assertThat(sessionRowsOf(agent)).isZero();
    assertEnded(a, agent);
    assertEnded(b, agent);
    assertThat(trustedDevices(agent)).isEqualTo(1);
  }

  @Test
  void adminSignOutEverywhereNeedsAFreshStepUp() throws Exception {
    // Audit PR #9, L1: like reset-MFA, a trusted-device sign-in (no code entered) or a step-up
    // older than 10 minutes cannot force a supporter out.
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser agentSession = signedIn(agent);
    Browser b = trustedSession(admin);
    b.post("/api/v1/auth/sign-out", "{}");
    assertThat(
            b.post("/api/v1/auth/sign-in", signInJson(admin.email(), GOOD_PASSWORD))
                .getContentAsString())
        .contains("\"authLevel\":\"FULL\"");
    String path = "/api/v1/admin/supporters/" + agent.id() + "/security/sign-out-everywhere";

    MockHttpServletResponse refused = b.post(path, "{}");
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).contains("step_up_required");
    assertAlive(agentSession, agent);
    assertThat(auditEvents("SECURITY_VERSION_BUMPED", agent.id())).isEmpty();

    assertThat(
            b.post("/api/v1/auth/step-up", "{\"code\":" + Browser.q(totp(admin)) + "}").getStatus())
        .isEqualTo(200);
    clock.advance(java.time.Duration.ofMinutes(11));
    assertThat(b.post(path, "{}").getContentAsString()).contains("step_up_required");
    assertAlive(agentSession, agent);

    assertThat(
            b.post("/api/v1/auth/step-up", "{\"code\":" + Browser.q(totp(admin)) + "}").getStatus())
        .isEqualTo(200);
    assertThat(b.post(path, "{}").getStatus()).isEqualTo(204);
    assertEnded(agentSession, agent);
    assertThat(auditEvents("SECURITY_VERSION_BUMPED", agent.id()))
        .singleElement()
        .satisfies(e -> assertThat(e.get("actor_user_id")).isEqualTo(admin.id()));
  }

  @Test
  void deactivationAndRoleChangeEndSessionsOnTheNextRequestEvenWithoutABump() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    AppUser admin = activeUser(Role.ADMIN);
    Browser agentSession = signedIn(agent);
    Browser adminSession = signedIn(admin);

    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", agent.id());
    assertEnded(agentSession, agent);

    jdbc.update("UPDATE app_user SET role = 'AGENT' WHERE id = ?", admin.id());
    assertThat(adminSession.get("/api/v1/agent/x").getStatus()).isEqualTo(401);
    assertThat(auditEvents("SESSION_INVALIDATED", admin.id()).toString()).contains("role_changed");
  }

  @Test
  void eachBumpReasonEndsOtherSessionsAndRevokesTrustOnlyWhereAdr019Says() throws Exception {
    for (SecurityVersionService.Reason reason : SecurityVersionService.Reason.values()) {
      AppUser agent = activeUser(Role.AGENT);
      Browser session = trustedSession(agent);
      securityVersion.bump(agent.id(), reason, null, null, "203.0.113.9");
      assertThat(session.get("/api/v1/agent/x").getStatus()).as(reason.name()).isEqualTo(401);
      assertThat(trustedDevices(agent))
          .as(reason.name())
          .isEqualTo(reason.revokesTrustedDevices() ? 0 : 1);
    }
  }

  @Test
  void theSessionListShowsOnlyMyOwnSessionsAndSignsOneOutWithinOneRequest() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    AppUser someoneElse = activeUser(Role.AGENT);
    Browser mine = signedIn(agent);
    Browser second = signedIn(agent);
    Browser theirs = signedIn(someoneElse);

    JsonNode list = json(mine.get("/api/v1/auth/sessions"));
    assertThat(list).hasSize(2);
    assertThat(list.get(0).get("current").asBoolean()).isTrue();
    assertThat(list.get(0).get("ipAddress").asString()).startsWith("198.51.100.");
    assertThat(list.get(0).get("authLevel").asString()).isEqualTo("FULL");
    String rawCookie = mine.cookie(Browser.SESSION_COOKIE);
    assertThat(list.toString()).doesNotContain(rawCookie);
    String secondId = list.get(1).get("id").asString();

    // Someone else's handle is not found from my session, and theirs still works.
    String theirId = json(theirs.get("/api/v1/auth/sessions")).get(0).get("id").asString();
    assertThat(delete(mine, "/api/v1/auth/sessions/" + theirId).getStatus()).isEqualTo(404);
    assertAlive(theirs, someoneElse);

    assertThat(delete(mine, "/api/v1/auth/sessions/" + secondId).getStatus()).isEqualTo(204);
    assertEnded(second, agent);
    assertAlive(mine, agent);

    // Sign out all others.
    Browser third = signedIn(agent);
    Browser fourth = signedIn(agent);
    MockHttpServletResponse others = mine.post("/api/v1/auth/sessions/sign-out-others", "{}");
    assertThat(json(others).get("count").asInt()).isEqualTo(2);
    assertEnded(third, agent);
    assertEnded(fourth, agent);
    assertAlive(mine, agent);

    // Ending the current session signs this browser out.
    String currentId = json(mine.get("/api/v1/auth/sessions")).get(0).get("id").asString();
    assertThat(delete(mine, "/api/v1/auth/sessions/" + currentId).getStatus()).isEqualTo(204);
    assertEnded(mine, agent);
  }

  @Test
  void aSignInFromANewBrowserSendsANoticeButTheFirstAndKnownOnesDoNot() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER);
    Browser first = signedIn(customer);
    assertThat(first.cookie("__Host-TS_DEVICE")).isNotNull();
    assertThat(first.lastSetCookies().stream().filter(h -> h.startsWith("__Host-TS_DEVICE=")))
        .allMatch(
            h -> h.contains("HttpOnly") && h.contains("Secure") && h.contains("SameSite=Lax"));
    assertThat(mailer.sentTo(customer.email())).isEmpty();

    signedIn(customer);
    assertThat(mailer.sentTo(customer.email()))
        .extracting(RecordingSystemMailer.Sent::type)
        .containsExactly("notice-NEW_DEVICE");

    first.post("/api/v1/auth/sign-out", "{}");
    signedIn(customer, first);
    assertThat(mailer.sentTo(customer.email())).hasSize(1);
    String deviceHash =
        jdbc.queryForObject(
            "SELECT token_hash FROM user_device WHERE user_id = ? ORDER BY id LIMIT 1",
            String.class,
            customer.id());
    assertThat(deviceHash).isNotEqualTo(first.cookie("__Host-TS_DEVICE")).hasSize(64);
  }

  @Test
  void breakGlassResetsMfaAndIsAudited() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    Browser session = signedIn(admin);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        breakGlass.resetMfa(
            admin.email().toUpperCase(), new PrintStream(out, true, StandardCharsets.UTF_8));
    assertThat(code).isZero();
    String printed = out.toString(StandardCharsets.UTF_8);
    assertThat(printed).contains("reset").doesNotContain(admin.email());
    assertEnded(session, admin);
    List<Map<String, Object>> audit = auditEvents("MFA_RESET", admin.id());
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0).get("actor_user_id")).isNull();
    assertThat(audit.get(0).get("details").toString()).contains("break_glass");

    ByteArrayOutputStream none = new ByteArrayOutputStream();
    assertThat(breakGlass.resetMfa(uniqueEmail("nobody"), new PrintStream(none))).isEqualTo(2);
    AppUser customer = activeUser(Role.CUSTOMER);
    assertThat(breakGlass.resetMfa(customer.email(), new PrintStream(none))).isEqualTo(2);
  }

  private static MockHttpServletResponse delete(Browser b, String path) throws Exception {
    return b.perform(MockMvcRequestBuilders.delete(path), HttpMethod.DELETE);
  }
}
