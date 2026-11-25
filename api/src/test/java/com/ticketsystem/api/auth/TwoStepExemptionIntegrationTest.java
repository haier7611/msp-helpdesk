package com.ticketsystem.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.OrganisationTestBase;
import com.ticketsystem.api.auth.mfa.RecoveryCodes;
import com.ticketsystem.api.retention.job.RetentionJob;
import com.ticketsystem.api.security.SecurityGoLive;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * WP-15 (ADR-031) through the real filter chain and Postgres: "trust this device" after a recovery
 * code (30-day cookie and record, never skips step-up), and the time-limited two-step exemption an
 * admin gives one engineer - who may grant it, the limits, sign-in and step-up while exempt, the
 * end date checked at sign-in and on every request, revoke, and role change / deactivation.
 */
@SecurityGoLive({4})
class TwoStepExemptionIntegrationTest extends OrganisationTestBase {

  private static final String AGENT_TREE = "/api/v1/agent/x";

  @Autowired RetentionJob retention;

  /** Whole seconds, so times read back from Postgres compare equal to the ones sent. */
  @BeforeEach
  void wholeSeconds() {
    clock.set(clock.instant().truncatedTo(ChronoUnit.SECONDS));
  }

  private static String exemption(long id) {
    return "/api/v1/admin/supporters/" + id + "/security/mfa-exemption";
  }

  private String grantBody(String reason, Instant endsAt) {
    return "{\"reason\":"
        + Browser.q(reason)
        + ",\"endsAt\":"
        + Browser.q(endsAt == null ? null : endsAt.toString())
        + "}";
  }

  private String grantBody(Duration period) {
    return grantBody("Phone lost, new one arrives Friday", clock.instant().plus(period));
  }

  private static JsonNode body(MockHttpServletResponse r) throws Exception {
    return JSON.readTree(r.getContentAsString());
  }

  private Browser passwordOnly(AppUser user) throws Exception {
    Browser b = browser();
    assertThat(b.post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD)).getStatus())
        .isEqualTo(200);
    return b;
  }

  private String levelAfterPassword(Browser b, AppUser user) throws Exception {
    MockHttpServletResponse r =
        b.post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD));
    assertThat(r.getStatus()).isEqualTo(200);
    return body(r).get("authLevel").asString();
  }

  /** Ten recovery codes stored for an already enrolled user, returned in plain text. */
  private List<String> recoveryCodes(AppUser user) {
    List<String> codes = RecoveryCodes.generate();
    mfaRepository.replaceRecoveryCodes(
        user.id(),
        codes.stream()
            .map(c -> RecoveryCodes.hash(user.id(), RecoveryCodes.normalise(c).orElseThrow()))
            .toList(),
        clock.instant());
    return codes;
  }

  private static String recovery(String code, boolean trust) {
    return "{\"recoveryCode\":" + Browser.q(code) + ",\"trustDevice\":" + trust + "}";
  }

  private int exemptionRows(AppUser user) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM mfa_exemption WHERE user_id = ?", Integer.class, user.id());
  }

  // ---- C: trusted device after a recovery code -------------------------------------------------

  @Test
  void aRecoveryCodeWithTrustSkipsTheCodeNextTimeButNeverSkipsStepUp() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    enrol(admin);
    enrol(agent);
    List<String> codes = recoveryCodes(admin);
    Browser b = passwordOnly(admin);
    MockHttpServletResponse r = b.post("/api/v1/auth/mfa/recovery", recovery(codes.get(0), true));
    assertThat(r.getStatus()).isEqualTo(200);
    // The trust cookie lives exactly 30 days, like the record.
    String trustCookie =
        b.lastSetCookies().stream()
            .filter(h -> h.startsWith("__Host-TS_TRUST="))
            .findFirst()
            .orElseThrow();
    assertThat(trustCookie)
        .contains("Max-Age=" + Duration.ofDays(30).toSeconds())
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Lax");
    Instant until =
        jdbc.queryForObject(
                "SELECT max(trusted_until) FROM user_device WHERE user_id = ?",
                java.sql.Timestamp.class,
                admin.id())
            .toInstant();
    assertThat(Duration.between(clock.instant(), until)).isEqualTo(Duration.ofDays(30));
    assertThat(trustCookie).doesNotContain("Max-Age=" + Duration.ofDays(400).toSeconds());
    assertThat(auditEvents("TRUSTED_DEVICE_ADDED", admin.id()).get(0).get("details").toString())
        .contains("recovery_code");
    b.post("/api/v1/auth/sign-out", "{}");

    assertThat(levelAfterPassword(b, admin)).isEqualTo("FULL");
    // Trusted, but a sensitive action still asks for a code.
    MockHttpServletResponse sensitive =
        b.post("/api/v1/admin/supporters/" + agent.id() + "/security/reset-mfa", "{}");
    assertThat(sensitive.getStatus()).isEqualTo(403);
    assertThat(sensitive.getContentAsString()).contains("step_up_required");
    assertThat(b.put(exemption(agent.id()), grantBody(Duration.ofDays(7))).getStatus())
        .isEqualTo(403);
  }

  @Test
  void aRecoveryCodeWithoutTrustAsksAgainNextTime() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    List<String> codes = recoveryCodes(agent);
    Browser b = passwordOnly(agent);
    assertThat(b.post("/api/v1/auth/mfa/recovery", recovery(codes.get(0), false)).getStatus())
        .isEqualTo(200);
    assertThat(b.lastSetCookies()).noneMatch(h -> h.startsWith("__Host-TS_TRUST="));
    b.post("/api/v1/auth/sign-out", "{}");
    assertThat(levelAfterPassword(b, agent)).isEqualTo("MFA_REQUIRED");
  }

  @Test
  void trustAfterARecoveryCodeEndsAfterThirtyDays() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    List<String> codes = recoveryCodes(agent);
    Browser b = passwordOnly(agent);
    b.post("/api/v1/auth/mfa/recovery", recovery(codes.get(0), true));
    b.post("/api/v1/auth/sign-out", "{}");
    clock.advance(Duration.ofDays(29));
    assertThat(levelAfterPassword(b, agent)).isEqualTo("FULL");
    b.post("/api/v1/auth/sign-out", "{}");
    clock.advance(Duration.ofDays(1).plusMinutes(1));
    assertThat(levelAfterPassword(b, agent)).isEqualTo("MFA_REQUIRED");
  }

  @Test
  void theLongLivedDeviceCookieAloneNoLongerSkipsTheCode() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    Browser b = passwordOnly(agent);
    b.post(
        "/api/v1/auth/mfa/verify",
        "{\"code\":" + Browser.q(totp(agent)) + ",\"trustDevice\":true}");
    b.post("/api/v1/auth/sign-out", "{}");
    assertThat(b.cookie("__Host-TS_DEVICE")).isNotNull();
    // Without the trust cookie (expired in the browser after 30 days, or cleared) the code is due.
    Browser other = browser();
    other.setCookie("__Host-TS_DEVICE", b.cookie("__Host-TS_DEVICE"));
    assertThat(levelAfterPassword(other, agent)).isEqualTo("MFA_REQUIRED");
    // Another user's trust cookie means nothing for this account.
    AppUser second = activeUser(Role.AGENT);
    enrol(second);
    Browser third = browser();
    third.setCookie("__Host-TS_TRUST", b.cookie("__Host-TS_TRUST"));
    assertThat(levelAfterPassword(third, second)).isEqualTo("MFA_REQUIRED");
  }

  // ---- B: per-engineer exemption
  // -----------------------------------------------------------------

  @Test
  void anAdminExemptsAnEngineerWhoThenSignsInWithPasswordOnly() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT); // never set up two-step
    Browser a = signedIn(admin);
    Instant endsAt = clock.instant().plus(Duration.ofDays(7));
    MockHttpServletResponse granted =
        a.put(exemption(agent.id()), grantBody("Phone lost, new one arrives Friday", endsAt));
    assertThat(granted.getStatus()).isEqualTo(200);
    assertThat(body(granted).get("endsAt").asString()).isEqualTo(endsAt.toString());

    // Notice to the engineer; audit without the reason text.
    assertThat(mailer.last(agent.email(), "notice-MFA_EXEMPTION_GRANTED").expiresAt())
        .isEqualTo(endsAt);
    List<Map<String, Object>> audit = auditEvents("MFA_EXEMPTION_GRANTED", agent.id());
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0).get("actor_user_id")).isEqualTo(admin.id());
    assertThat(audit.get(0).get("details").toString())
        .contains(endsAt.toString())
        .doesNotContain("Phone lost");

    // Admin - Supporters shows "Exempt until".
    JsonNode detail = body(a.get("/api/v1/admin/supporters/" + agent.id()));
    assertThat(detail.get("mfaExemptUntil").asString()).isEqualTo(endsAt.toString());
    assertThat(detail.get("mfaExemptReason").asString())
        .isEqualTo("Phone lost, new one arrives Friday");

    // Password only: no set-up step, straight to FULL; /me carries the date for the banner.
    Browser b = browser().ip("203.0.113.45");
    assertThat(levelAfterPassword(b, agent)).isEqualTo("FULL");
    assertThat(b.get(AGENT_TREE).getStatus()).isEqualTo(404);
    assertThat(body(b.get("/api/v1/auth/me")).get("mfaExemptUntil").asString())
        .isEqualTo(endsAt.toString());
    Map<String, Object> signIn = auditEvents("MFA_SIGN_IN_SUCCEEDED", agent.id()).get(0);
    assertThat(signIn.get("details").toString()).contains("exemption");
    assertThat(String.valueOf(signIn.get("client_ip"))).contains("203.0.113.45");
  }

  @Test
  void anEnrolledExemptEngineerIsNotAskedForTheCodeEither() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    signedIn(admin).put(exemption(agent.id()), grantBody(Duration.ofDays(3)));
    assertThat(levelAfterPassword(browser(), agent)).isEqualTo("FULL");
  }

  @Test
  void onlyAnAdminWithAFreshStepUpCanGrantOrRevoke() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    AppUser otherAgent = activeUser(Role.AGENT);
    AppUser customer = activeUser(Role.CUSTOMER);

    // An engineer or a customer cannot reach it at all.
    assertThat(signedIn(otherAgent).put(exemption(agent.id()), grantBody(Duration.ofDays(1))))
        .extracting(MockHttpServletResponse::getStatus)
        .isEqualTo(403);
    assertThat(signedIn(customer).put(exemption(agent.id()), grantBody(Duration.ofDays(1))))
        .extracting(MockHttpServletResponse::getStatus)
        .isEqualTo(403);
    assertThat(exemptionRows(agent)).isZero();

    // An admin whose step-up is older than 10 minutes is asked again.
    Browser a = signedIn(admin);
    clock.advance(Duration.ofMinutes(11));
    MockHttpServletResponse stale = a.put(exemption(agent.id()), grantBody(Duration.ofDays(1)));
    assertThat(stale.getStatus()).isEqualTo(403);
    assertThat(stale.getContentAsString()).contains("step_up_required");
    assertThat(a.delete(exemption(agent.id())).getContentAsString()).contains("step_up_required");
    assertThat(exemptionRows(agent)).isZero();
  }

  @Test
  void adminsYourselfCustomersAndInactiveAccountsCannotBeExempt() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser otherAdmin = activeUser(Role.ADMIN);
    AppUser customer = activeUser(Role.CUSTOMER);
    AppUser gone = activeUser(Role.AGENT);
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", gone.id());
    Browser a = signedIn(admin);

    MockHttpServletResponse toAdmin =
        a.put(exemption(otherAdmin.id()), grantBody(Duration.ofDays(1)));
    assertThat(toAdmin.getStatus()).isEqualTo(409);
    assertThat(toAdmin.getContentAsString()).contains("exemption_not_allowed");
    assertThat(a.put(exemption(admin.id()), grantBody(Duration.ofDays(1))).getStatus())
        .isEqualTo(400);
    assertThat(a.put(exemption(customer.id()), grantBody(Duration.ofDays(1))).getStatus())
        .isEqualTo(404);
    assertThat(a.put(exemption(gone.id()), grantBody(Duration.ofDays(1))).getStatus())
        .isEqualTo(409);
    assertThat(a.put(exemption(999_999_999L), grantBody(Duration.ofDays(1))).getStatus())
        .isEqualTo(404);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mfa_exemption WHERE user_id IN (?, ?, ?, ?)",
                Integer.class,
                admin.id(),
                otherAdmin.id(),
                customer.id(),
                gone.id()))
        .isZero();
  }

  @Test
  void theReasonAndTheEndDateAreRequiredAndLimited() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser a = signedIn(admin);
    Instant now = clock.instant();
    String path = exemption(agent.id());

    assertThat(a.put(path, grantBody(Duration.ofDays(30).plusMinutes(1))).getStatus())
        .isEqualTo(400);
    assertThat(a.put(path, grantBody("Holiday cover", now.minusSeconds(1))).getStatus())
        .isEqualTo(400);
    assertThat(a.put(path, grantBody("Holiday cover", null)).getStatus()).isEqualTo(400);
    assertThat(a.put(path, grantBody("   ", now.plus(Duration.ofDays(1)))).getStatus())
        .isEqualTo(400);
    assertThat(a.put(path, grantBody(null, now.plus(Duration.ofDays(1)))).getStatus())
        .isEqualTo(400);
    assertThat(a.put(path, grantBody("x".repeat(201), now.plus(Duration.ofDays(1)))).getStatus())
        .isEqualTo(400);
    assertThat(
            a.put(
                    path,
                    "{\"reason\":\"two\\nlines\",\"endsAt\":"
                        + Browser.q(now.plus(Duration.ofDays(1)).toString())
                        + "}")
                .getContentAsString())
        .contains("one line");
    assertThat(exemptionRows(agent)).isZero();

    // Exactly 30 days and 200 characters are fine; granting again replaces the exemption.
    assertThat(a.put(path, grantBody("x".repeat(200), now.plus(Duration.ofDays(30)))).getStatus())
        .isEqualTo(200);
    assertThat(a.put(path, grantBody(Duration.ofDays(2))).getStatus()).isEqualTo(200);
    assertThat(exemptionRows(agent)).isEqualTo(1);
    List<Map<String, Object>> audit = auditEvents("MFA_EXEMPTION_GRANTED", agent.id());
    assertThat(audit).hasSize(2);
    assertThat(audit.get(1).get("details").toString()).contains("\"replaced\": true");
  }

  @Test
  void anEndedExemptionIsIgnoredAtSignInAndEndsTheExemptSessionOnItsNextRequest() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    // A browser trusted earlier (code step done then): its sign-ins are FULL because of the trust,
    // not because of the exemption, so they are not tied to it.
    Browser trusted = passwordOnly(agent);
    trusted.post(
        "/api/v1/auth/mfa/verify",
        "{\"code\":" + Browser.q(totp(agent)) + ",\"trustDevice\":true}");
    trusted.post("/api/v1/auth/sign-out", "{}");
    signedIn(admin).put(exemption(agent.id()), grantBody(Duration.ofHours(1)));

    Browser exempt = browser();
    assertThat(levelAfterPassword(exempt, agent)).isEqualTo("FULL");
    assertThat(levelAfterPassword(trusted, agent)).isEqualTo("FULL");
    assertThat(exempt.get(AGENT_TREE).getStatus()).isEqualTo(404);

    clock.advance(Duration.ofHours(1).plusMinutes(1));
    assertThat(exempt.get(AGENT_TREE).getStatus()).isEqualTo(401);
    assertThat(trusted.get(AGENT_TREE).getStatus()).isEqualTo(404);
    assertThat(auditEvents("SESSION_INVALIDATED", agent.id()).toString())
        .contains("mfa_exemption_ended");
    assertThat(exemptionRows(agent)).isZero();
    assertThat(auditEvents("MFA_EXEMPTION_EXPIRED", agent.id())).hasSize(1);

    // Next sign-in asks for the code again; the expiry is recorded once only.
    assertThat(levelAfterPassword(browser(), agent)).isEqualTo("MFA_REQUIRED");
    assertThat(auditEvents("MFA_EXEMPTION_EXPIRED", agent.id())).hasSize(1);
  }

  @Test
  void anExemptionThatEndedWhileNobodyWasSignedInIsIgnoredAtSignIn() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser a = signedIn(admin);
    a.put(exemption(agent.id()), grantBody(Duration.ofDays(1)));
    clock.advance(Duration.ofDays(1));
    // Not shown as exempt any more, even before anyone notices (a fresh admin session: the first
    // one has reached its 24-hour limit).
    a = signedIn(admin);
    assertThat(
            body(a.get("/api/v1/admin/supporters/" + agent.id())).path("mfaExemptUntil").isNull())
        .isTrue();
    // Never set up: the set-up step comes back.
    assertThat(levelAfterPassword(browser(), agent)).isEqualTo("MFA_ENROLMENT_REQUIRED");
    assertThat(auditEvents("MFA_EXEMPTION_EXPIRED", agent.id())).hasSize(1);
  }

  @Test
  void revokingEndsEverySessionAtOnceAndTheNextSignInAsksForTheCode() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    Browser a = signedIn(admin);
    assertThat(a.delete(exemption(agent.id())).getStatus()).isEqualTo(404);
    a.put(exemption(agent.id()), grantBody(Duration.ofDays(5)));
    Browser exempt = browser();
    assertThat(levelAfterPassword(exempt, agent)).isEqualTo("FULL");

    assertThat(a.delete(exemption(agent.id())).getStatus()).isEqualTo(204);
    assertThat(exempt.get(AGENT_TREE).getStatus()).isEqualTo(401);
    assertThat(exemptionRows(agent)).isZero();
    List<Map<String, Object>> audit = auditEvents("MFA_EXEMPTION_REVOKED", agent.id());
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0).get("details").toString()).contains("\"ended_by\": \"admin\"");
    assertThat(levelAfterPassword(browser(), agent)).isEqualTo("MFA_REQUIRED");
    assertThat(
            body(a.get("/api/v1/admin/supporters/" + agent.id())).path("mfaExemptUntil").isNull())
        .isTrue();
  }

  @Test
  void becomingAnAdminOrBeingDeactivatedRemovesTheExemption() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser promoted = activeUser(Role.AGENT);
    AppUser leaver = activeUser(Role.AGENT);
    Browser a = signedIn(admin);
    a.put(exemption(promoted.id()), grantBody(Duration.ofDays(5)));
    a.put(exemption(leaver.id()), grantBody(Duration.ofDays(5)));

    assertThat(
            a.put("/api/v1/admin/supporters/" + promoted.id() + "/role", "{\"role\":\"ADMIN\"}")
                .getStatus())
        .isEqualTo(200);
    assertThat(exemptionRows(promoted)).isZero();
    assertThat(auditEvents("MFA_EXEMPTION_REVOKED", promoted.id()).toString())
        .contains("role_change");
    // As an admin they must set up two-step at the next sign-in.
    assertThat(levelAfterPassword(browser(), users.findById(promoted.id()).orElseThrow()))
        .isEqualTo("MFA_ENROLMENT_REQUIRED");

    assertThat(a.post("/api/v1/admin/supporters/" + leaver.id() + "/deactivate", "{}").getStatus())
        .isEqualTo(200);
    assertThat(exemptionRows(leaver)).isZero();
    assertThat(auditEvents("MFA_EXEMPTION_REVOKED", leaver.id()).toString())
        .contains("deactivation");
  }

  @Test
  void theDatabaseDropsTheExemptionOnAnyRoleOrStatusChange() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    signedIn(admin).put(exemption(agent.id()), grantBody(Duration.ofDays(5)));
    assertThat(exemptionRows(agent)).isEqualTo(1);
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", agent.id());
    assertThat(exemptionRows(agent)).isZero();
  }

  @Test
  void stepUpForAnExemptEngineerIsTheirPassword() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser a = signedIn(admin);
    a.put(exemption(agent.id()), grantBody(Duration.ofDays(5)));
    Browser b = browser();
    assertThat(levelAfterPassword(b, agent)).isEqualTo("FULL");

    // A step-up action is refused until the password is entered again.
    String stepUpOnly = "/api/v1/auth/mfa/recovery-codes";
    assertThat(b.post(stepUpOnly, "{}").getContentAsString()).contains("step_up_required");
    MockHttpServletResponse wrong =
        b.post("/api/v1/auth/step-up", "{\"password\":\"Not-The-Password-1\"}");
    assertThat(wrong.getStatus()).isEqualTo(400);
    assertThat(wrong.getContentAsString()).contains("password is not right");
    assertThat(
            b.post("/api/v1/auth/step-up", "{\"password\":" + Browser.q(GOOD_PASSWORD) + "}")
                .getStatus())
        .isEqualTo(200);
    // Past the step-up gate (then refused for its own reason: nothing to regenerate).
    MockHttpServletResponse after = b.post(stepUpOnly, "{}");
    assertThat(after.getContentAsString()).doesNotContain("step_up_required");
    assertThat(after.getContentAsString()).contains("mfa_not_enrolled");
    assertThat(auditEvents("STEP_UP_SUCCEEDED", agent.id()).toString()).contains("password");
  }

  @Test
  void passwordStepUpIsRefusedForEveryoneWhoIsNotExempt() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    AppUser admin = activeUser(Role.ADMIN);
    for (AppUser u : List.of(agent, admin)) {
      Browser b = signedIn(u);
      MockHttpServletResponse r =
          b.post("/api/v1/auth/step-up", "{\"password\":" + Browser.q(GOOD_PASSWORD) + "}");
      assertThat(r.getStatus()).isEqualTo(403);
      assertThat(r.getContentAsString()).contains("password_step_up_not_allowed");
    }
  }

  @Test
  void revokingEndsEvenSessionsThatDidNotUseTheExemptionButKeepsTrustedDevices() throws Exception {
    // Documented choice: a revoke bumps security_version, so every session ends - including one
    // that completed the code step - and trusted devices stay.
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    Browser a = signedIn(admin);
    Browser trusted = passwordOnly(agent);
    trusted.post(
        "/api/v1/auth/mfa/verify",
        "{\"code\":" + Browser.q(totp(agent)) + ",\"trustDevice\":true}");
    a.put(exemption(agent.id()), grantBody(Duration.ofDays(5)));
    assertThat(a.delete(exemption(agent.id())).getStatus()).isEqualTo(204);
    assertThat(trusted.get(AGENT_TREE).getStatus()).isEqualTo(401);
    // The trusted device still skips the code.
    assertThat(levelAfterPassword(trusted, agent)).isEqualTo("FULL");
  }

  @Test
  void wrongPasswordsAtStepUpBackOffLikeCodesAndACorrectOneWorksOnceTheDelayIsOver()
      throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    signedIn(admin).put(exemption(agent.id()), grantBody(Duration.ofDays(5)));
    Browser b = browser();
    assertThat(levelAfterPassword(b, agent)).isEqualTo("FULL");
    String stepUpOnly = "/api/v1/auth/mfa/recovery-codes";
    String wrongPassword = "{\"password\":\"Not-The-Password-1\"}";
    String rightPassword = "{\"password\":" + Browser.q(GOOD_PASSWORD) + "}";

    // Two free misses, the third starts a 1 s delay.
    for (int i = 0; i < 3; i++) {
      assertThat(b.post("/api/v1/auth/step-up", wrongPassword).getStatus()).isEqualTo(400);
    }
    // Inside the delay even the right password is refused, and step-up is not granted.
    MockHttpServletResponse throttled = b.post("/api/v1/auth/step-up", rightPassword);
    assertThat(throttled.getStatus()).isEqualTo(429);
    assertThat(throttled.getHeader("Retry-After")).isEqualTo("1");
    assertThat(b.post(stepUpOnly, "{}").getContentAsString()).contains("step_up_required");

    // The next miss doubles the delay (2 s); still refused 1 s later.
    clock.advance(Duration.ofSeconds(1));
    assertThat(b.post("/api/v1/auth/step-up", wrongPassword).getStatus()).isEqualTo(400);
    assertThat(b.post("/api/v1/auth/step-up", rightPassword).getHeader("Retry-After"))
        .isEqualTo("2");
    clock.advance(Duration.ofSeconds(1));
    assertThat(b.post("/api/v1/auth/step-up", rightPassword).getStatus()).isEqualTo(429);

    // Once the delay is over the right password works and clears the count.
    clock.advance(Duration.ofSeconds(1));
    assertThat(b.post("/api/v1/auth/step-up", rightPassword).getStatus()).isEqualTo(200);
    assertThat(b.post(stepUpOnly, "{}").getContentAsString()).doesNotContain("step_up_required");
    assertThat(b.post("/api/v1/auth/step-up", wrongPassword).getStatus()).isEqualTo(400);
    assertThat(b.post("/api/v1/auth/step-up", rightPassword).getStatus()).isEqualTo(200);

    List<Map<String, Object>> failed = auditEvents("MFA_FAILED", agent.id());
    assertThat(failed).hasSize(5);
    assertThat(failed)
        .allSatisfy(e -> assertThat(e.get("details").toString()).contains("step_up_password"));
    List<Map<String, Object>> refused = auditEvents("MFA_THROTTLED", agent.id());
    assertThat(refused).hasSize(3);
    assertThat(refused)
        .allSatisfy(e -> assertThat(e.get("details").toString()).contains("step_up_password"));
  }

  @Test
  void wrongPasswordsAtStepUpShareTheBackOffWithCodes() throws Exception {
    // Same key as the TOTP and recovery-code checks (mfa:<user id>): switching between a password
    // and a code does not buy extra guesses.
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    signedIn(admin).put(exemption(agent.id()), grantBody(Duration.ofDays(5)));
    Browser b = browser();
    assertThat(levelAfterPassword(b, agent)).isEqualTo("FULL");
    for (int i = 0; i < 3; i++) {
      assertThat(
              b.post("/api/v1/auth/step-up", "{\"password\":\"Not-The-Password-1\"}").getStatus())
          .isEqualTo(400);
    }
    assertThat(
            b.post("/api/v1/auth/step-up", "{\"code\":" + Browser.q(totp(agent)) + "}").getStatus())
        .isEqualTo(429);
  }

  @Test
  void theNightlyRetentionRemovesEndedExemptionsOfEngineersWhoNeverCameBack() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser away = activeUser(Role.AGENT);
    AppUser stillExempt = activeUser(Role.AGENT);
    Browser a = signedIn(admin);
    a.put(
        exemption(away.id()),
        grantBody("Away, phone stolen", clock.instant().plus(Duration.ofDays(1))));
    a.put(exemption(stillExempt.id()), grantBody(Duration.ofDays(5)));
    clock.advance(Duration.ofDays(1).plusSeconds(1));

    // A dry run only counts: nothing goes.
    retention.runOnce(true);
    assertThat(exemptionRows(away)).isEqualTo(1);

    retention.runOnce(false);
    // The ended row (and its reason) is gone, recorded once; the current one stays as it was.
    assertThat(exemptionRows(away)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mfa_exemption WHERE reason = 'Away, phone stolen'",
                Integer.class))
        .isZero();
    List<Map<String, Object>> expired = auditEvents("MFA_EXEMPTION_EXPIRED", away.id());
    assertThat(expired).hasSize(1);
    assertThat(expired.get(0).get("details").toString()).doesNotContain("phone stolen");
    assertThat(exemptionRows(stillExempt)).isEqualTo(1);
    assertThat(auditEvents("MFA_EXEMPTION_EXPIRED", stillExempt.id())).isEmpty();

    // A later run or sign-in does not record it again.
    retention.runOnce(false);
    assertThat(levelAfterPassword(browser(), away)).isEqualTo("MFA_ENROLMENT_REQUIRED");
    assertThat(auditEvents("MFA_EXEMPTION_EXPIRED", away.id())).hasSize(1);
  }
}
