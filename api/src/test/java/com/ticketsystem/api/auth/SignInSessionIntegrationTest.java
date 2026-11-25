package com.ticketsystem.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Sign-in, sessions in Postgres, cookies, CSRF, sign-out, idle and absolute expiry, back-off. */
class SignInSessionIntegrationTest extends AuthIntegrationTestBase {

  private String sessionId(Browser b) {
    return new String(
        Base64.getDecoder().decode(b.cookie(Browser.SESSION_COOKIE)), StandardCharsets.UTF_8);
  }

  private Integer sessionRows(String sessionId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM spring_session WHERE session_id = ?", Integer.class, sessionId);
  }

  @Test
  void correctPasswordCreatesASessionRowAndHostPrefixedCookies() throws Exception {
    AppUser user = activeUser(Role.AGENT);
    Browser b = browser();

    MockHttpServletResponse r =
        b.post("/api/v1/auth/sign-in", signInJson(user.email().toUpperCase(), GOOD_PASSWORD));

    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentAsString())
        .contains("\"role\":\"AGENT\"")
        // WP-02c: a supporter without TOTP stops at enrolment after the password.
        .contains("\"authLevel\":\"MFA_ENROLMENT_REQUIRED\"")
        .doesNotContain("password");
    String sessionCookie =
        b.lastSetCookies().stream()
            .filter(h -> h.startsWith(Browser.SESSION_COOKIE + "="))
            .findFirst()
            .orElseThrow();
    assertThat(sessionCookie)
        .contains("Path=/")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Lax")
        .doesNotContain("Domain=");
    String csrfCookie =
        b.lastSetCookies().stream()
            .filter(h -> h.startsWith(Browser.CSRF_COOKIE + "=") && !h.contains("Max-Age=0"))
            .findFirst()
            .orElseThrow();
    assertThat(csrfCookie).contains("Path=/").contains("Secure").doesNotContain("HttpOnly");

    String id = sessionId(b);
    assertThat(sessionRows(id)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT principal_name FROM spring_session WHERE session_id = ?", String.class, id))
        .isEqualTo(Long.toString(user.id()));
    assertThat(
            jdbc.queryForObject(
                "SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
                Integer.class,
                id))
        .isEqualTo(8 * 3600);

    MockHttpServletResponse me = b.get("/api/v1/auth/me");
    assertThat(me.getStatus()).isEqualTo(200);
    assertThat(me.getContentAsString()).contains(user.email());
  }

  @Test
  void storedHashIsArgon2id() {
    AppUser user = activeUser(Role.CUSTOMER);
    assertThat(user.passwordHash()).startsWith("{argon2}$argon2id$");
  }

  @Test
  void wrongPasswordUnknownEmailAndInactiveAccountGetTheSameAnswer() throws Exception {
    AppUser user = activeUser(Role.ADMIN);
    long invited =
        users.insertInvited(
            uniqueEmail("invited"), "Ivy Example", Role.AGENT, null, clock.instant());
    String invitedEmail = users.findById(invited).orElseThrow().email();

    MockHttpServletResponse wrong =
        browser().post("/api/v1/auth/sign-in", signInJson(user.email(), "Not-The-Password-99"));
    MockHttpServletResponse unknown =
        browser()
            .post("/api/v1/auth/sign-in", signInJson(uniqueEmail("nobody"), "Not-The-Password-99"));
    MockHttpServletResponse inactive =
        browser().post("/api/v1/auth/sign-in", signInJson(invitedEmail, GOOD_PASSWORD));

    for (MockHttpServletResponse r : List.of(wrong, unknown, inactive)) {
      assertThat(r.getStatus()).isEqualTo(401);
      assertThat(r.getContentAsString()).isEqualTo(wrong.getContentAsString());
    }
    assertThat(wrong.getContentAsString()).contains("invalid_credentials");
    assertThat(auditEvents("SIGN_IN_FAILED", user.id())).hasSize(1);
  }

  @Test
  void signInWithoutTheCsrfHeaderIsRefused() throws Exception {
    AppUser user = activeUser(Role.CUSTOMER);
    Browser b = browser();
    b.get("/api/v1/auth/csrf");
    b.withoutCsrfHeader();

    MockHttpServletResponse r =
        b.post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD));

    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(r.getContentAsString()).contains("\"error\":\"csrf\"");
    assertThat(b.cookie(Browser.SESSION_COOKIE)).isNull();
  }

  @Test
  void aStateChangingRequestWithAWrongCsrfTokenIsRefused() throws Exception {
    AppUser user = activeUser(Role.ADMIN);
    Browser b = signedIn(user);
    b.setCookie(Browser.CSRF_COOKIE, "forged-token-value");
    b.withoutCsrfHeader();

    // The header does not match the server's view of the token.
    MockHttpServletResponse r =
        b.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/auth/sign-out")
                .header("X-XSRF-TOKEN", "another-forged-value"),
            org.springframework.http.HttpMethod.POST);

    assertThat(r.getStatus()).isEqualTo(403);
  }

  @Test
  void signInChangesTheSessionIdAndRotatesTheCsrfToken() throws Exception {
    AppUser user = activeUser(Role.CUSTOMER);
    Browser b = signedIn(user);
    String firstSession = b.cookie(Browser.SESSION_COOKIE);
    String firstCsrf = b.cookie(Browser.CSRF_COOKIE);

    // Signing in again on the same browser (e.g. after a fixation attempt) gets a new id.
    b.post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD));

    assertThat(b.cookie(Browser.SESSION_COOKIE)).isNotEqualTo(firstSession);
    assertThat(b.cookie(Browser.CSRF_COOKIE)).isNotEqualTo(firstCsrf);
    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(200);
  }

  @Test
  void signOutEndsTheSessionInTheDatabase() throws Exception {
    AppUser user = activeUser(Role.AGENT);
    Browser b = signedIn(user);
    String id = sessionId(b);
    String oldCookie = b.cookie(Browser.SESSION_COOKIE);

    assertThat(b.post("/api/v1/auth/sign-out", "{}").getStatus()).isEqualTo(204);

    assertThat(sessionRows(id)).isZero();
    assertThat(auditEvents("SIGN_OUT", user.id())).hasSize(1);
    // Replaying the old cookie does not bring the session back.
    Browser replay = browser();
    replay.setCookie(Browser.SESSION_COOKIE, oldCookie);
    assertThat(replay.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
  }

  @Test
  void sessionExpiresAfterEightHoursIdle() throws Exception {
    AppUser user = activeUser(Role.CUSTOMER);
    Browser b = signedIn(user);
    String id = sessionId(b);
    long nineHours = Duration.ofHours(9).toMillis();
    jdbc.update(
        "UPDATE spring_session SET last_access_time = last_access_time - ?,"
            + " expiry_time = expiry_time - ? WHERE session_id = ?",
        nineHours,
        nineHours,
        id);

    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
  }

  @Test
  void sessionEndsTwentyFourHoursAfterSignInEvenWhenActive() throws Exception {
    AppUser user = activeUser(Role.AGENT);
    Browser b = signedIn(user);
    String id = sessionId(b);

    clock.advance(Duration.ofHours(23).plusMinutes(59));
    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(200);

    clock.advance(Duration.ofMinutes(2));
    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
    assertThat(sessionRows(id)).isZero();
    assertThat(auditEvents("SESSION_EXPIRED", user.id())).hasSize(1);
  }

  @Test
  void repeatedFailuresBackOffExponentiallyButOnlyForThatAccountAndAddress() throws Exception {
    AppUser user = activeUser(Role.ADMIN);
    Browser attacker = browser();
    for (int i = 0; i < 3; i++) {
      assertThat(
              attacker
                  .post("/api/v1/auth/sign-in", signInJson(user.email(), "Wrong-Password-" + i))
                  .getStatus())
          .isEqualTo(401);
    }

    MockHttpServletResponse throttled =
        attacker.post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD));
    assertThat(throttled.getStatus()).isEqualTo(429);
    assertThat(throttled.getHeader("Retry-After")).isEqualTo("1");

    // The real user from another address is not locked out.
    assertThat(
            browser()
                .post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(200);

    // The delay doubles: after the wait, one more failure means 2 s.
    clock.advance(Duration.ofSeconds(2));
    attacker.post("/api/v1/auth/sign-in", signInJson(user.email(), "Wrong-Password-x"));
    assertThat(
            attacker
                .post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD))
                .getHeader("Retry-After"))
        .isEqualTo("2");
    assertThat(auditEvents("SIGN_IN_THROTTLED", user.id())).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void signInAttemptsArePerAddressCapped() throws Exception {
    Browser b = browser();
    int last = 0;
    for (int i = 0; i < 31; i++) {
      // Different unknown accounts, so only the per-IP cap can stop this.
      last =
          b.post("/api/v1/auth/sign-in", signInJson(uniqueEmail("spray"), "Wrong-Password-1"))
              .getStatus();
    }
    assertThat(last).isEqualTo(429);
  }

  @Test
  void auditEventsCarryNoSecrets() throws Exception {
    AppUser user = activeUser(Role.CUSTOMER);
    signedIn(user);
    browser().post("/api/v1/auth/sign-in", signInJson(user.email(), "Secret-Wrong-Guess-77"));

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT type, details::text AS details FROM audit_event WHERE target_user_id = ?",
            user.id());
    assertThat(rows).extracting(r -> r.get("type")).contains("SIGN_IN_SUCCEEDED", "SIGN_IN_FAILED");
    for (Map<String, Object> row : rows) {
      assertThat(row.get("details").toString())
          .doesNotContain(GOOD_PASSWORD)
          .doesNotContain("Secret-Wrong-Guess-77")
          .doesNotContain("argon2");
    }
  }

  @Test
  void deactivatedUserLosesTheSessionOnTheNextMeCall() throws Exception {
    AppUser user = activeUser(Role.CUSTOMER);
    Browser b = signedIn(user);
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", user.id());

    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
    assertThat(b.cookie(Browser.SESSION_COOKIE)).isNull();
  }

  @Test
  void passwordChangeNeedsTheCurrentPasswordAndSendsANotice() throws Exception {
    AppUser user = activeUser(Role.AGENT);
    Browser b = signedIn(user);

    MockHttpServletResponse wrong =
        b.post(
            "/api/v1/auth/password/change",
            "{\"currentPassword\":\"Not-My-Password-1\",\"newPassword\":\"Granite-Lantern-Orbit-7\"}");
    assertThat(wrong.getStatus()).isEqualTo(400);
    assertThat(wrong.getContentAsString()).contains("wrong_password");

    MockHttpServletResponse weak =
        b.post(
            "/api/v1/auth/password/change",
            "{\"currentPassword\":"
                + Browser.q(GOOD_PASSWORD)
                + ",\"newPassword\":\"password1234\"}");
    assertThat(weak.getStatus()).isEqualTo(400);
    assertThat(weak.getContentAsString()).contains("weak_password");

    MockHttpServletResponse ok =
        b.post(
            "/api/v1/auth/password/change",
            "{\"currentPassword\":"
                + Browser.q(GOOD_PASSWORD)
                + ",\"newPassword\":\"Granite-Lantern-Orbit-7\"}");
    assertThat(ok.getStatus()).isEqualTo(204);
    assertThat(mailer.sentTo(user.email()))
        .extracting(RecordingSystemMailer.Sent::type)
        .containsExactly("notice-PASSWORD_CHANGED");
    assertThat(users.findById(user.id()).orElseThrow().securityVersion()).isEqualTo(2);
    assertThat(auditEvents("PASSWORD_CHANGED", user.id())).hasSize(1);
    assertThat(
            browser()
                .post("/api/v1/auth/sign-in", signInJson(user.email(), "Granite-Lantern-Orbit-7"))
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void passwordChangeVoidsOutstandingResetAndMagicLinks() throws Exception {
    // Audit M1 (C7 mailbox compromise): links requested before a password change must die with it.
    AppUser customer = activeUser(Role.CUSTOMER);
    String emailJson = "{\"email\":" + Browser.q(customer.email()) + "}";
    browser().post("/api/v1/auth/password-reset/request", emailJson);
    String reset = mailer.last(customer.email(), "password-reset").token();
    browser().post("/api/v1/auth/magic-link/request", emailJson);
    String magic = mailer.last(customer.email(), "magic-link").token();

    Browser b = signedIn(customer);
    MockHttpServletResponse ok =
        b.post(
            "/api/v1/auth/password/change",
            "{\"currentPassword\":"
                + Browser.q(GOOD_PASSWORD)
                + ",\"newPassword\":\"Granite-Lantern-Orbit-7\"}");
    assertThat(ok.getStatus()).isEqualTo(204);

    MockHttpServletResponse resetAfter =
        browser()
            .post(
                "/api/v1/auth/password-reset/confirm",
                "{\"token\":" + Browser.q(reset) + ",\"password\":\"Attacker-Chosen-Lamp-42\"}");
    assertThat(resetAfter.getStatus()).isEqualTo(400);
    assertThat(resetAfter.getContentAsString()).contains("invalid_link");

    MockHttpServletResponse magicAfter =
        browser().post("/api/v1/auth/magic-link/consume", "{\"token\":" + Browser.q(magic) + "}");
    assertThat(magicAfter.getStatus()).isEqualTo(400);
    assertThat(magicAfter.getContentAsString()).contains("invalid_link");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth_token WHERE user_id = ? AND used_at IS NULL"
                    + " AND purpose IN ('PASSWORD_RESET', 'MAGIC_LINK')",
                Integer.class,
                customer.id()))
        .isZero();
    assertThat(
            browser()
                .post(
                    "/api/v1/auth/sign-in", signInJson(customer.email(), "Granite-Lantern-Orbit-7"))
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void anonymousMeIs401() throws Exception {
    MockHttpServletResponse r = browser().get("/api/v1/auth/me");
    assertThat(r.getStatus()).isEqualTo(401);
    assertThat(r.getContentAsString()).contains("unauthenticated");
  }
}
