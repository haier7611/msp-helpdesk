package com.ticketsystem.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.security.SecurityGoLive;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Password reset and customer magic link (C7, ADR-013, security test 4 token part): no enumeration,
 * single use, expiry, newer issuance voids older, only hashes stored.
 */
@SecurityGoLive({4})
class LinkFlowsIntegrationTest extends AuthIntegrationTestBase {

  private static String emailJson(String email) {
    return "{\"email\":" + Browser.q(email) + "}";
  }

  private static String resetJson(String token, String password) {
    return "{\"token\":" + Browser.q(token) + ",\"password\":" + Browser.q(password) + "}";
  }

  private static String tokenJson(String token) {
    return "{\"token\":" + Browser.q(token) + "}";
  }

  // --- password reset ---

  @Test
  void resetRequestsAnswerIdenticallyForKnownUnknownAndInactiveEmails() throws Exception {
    AppUser active = activeUser(Role.AGENT);
    AppUser deactivated = activeUser(Role.CUSTOMER);
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", deactivated.id());

    MockHttpServletResponse known =
        browser().post("/api/v1/auth/password-reset/request", emailJson(active.email()));
    MockHttpServletResponse unknown =
        browser().post("/api/v1/auth/password-reset/request", emailJson(uniqueEmail("ghost")));
    MockHttpServletResponse inactive =
        browser().post("/api/v1/auth/password-reset/request", emailJson(deactivated.email()));
    MockHttpServletResponse garbage =
        browser().post("/api/v1/auth/password-reset/request", emailJson("not an email"));

    for (MockHttpServletResponse r : List.of(known, unknown, inactive, garbage)) {
      assertThat(r.getStatus()).isEqualTo(202);
      assertThat(r.getContentAsString()).isEqualTo(known.getContentAsString());
    }
    assertThat(mailer.sent())
        .extracting(RecordingSystemMailer.Sent::to)
        .containsExactly(active.email());
  }

  @Test
  void resetLinkWorksOnceAndOnlyItsHashIsStored() throws Exception {
    AppUser user = activeUser(Role.CUSTOMER);
    browser().post("/api/v1/auth/password-reset/request", emailJson(user.email()));
    RecordingSystemMailer.Sent mail = mailer.last(user.email(), "password-reset");
    assertThat(mail.link()).startsWith("https://support.example.test/reset-password#token=");
    String token = mail.token();
    assertThat(token).hasSize(43);

    // Only the SHA-256 is in the database, never the raw token.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth_token WHERE token_hash = ?",
                Integer.class,
                SecureTokens.sha256Hex(token)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth_token WHERE token_hash = ? OR token_hash LIKE ?",
                Integer.class,
                token,
                "%" + token.substring(0, 10) + "%"))
        .isZero();
    // The outbox row is marked sensitive and expires with the token.
    assertThat(
            jdbc.queryForObject(
                "SELECT sensitive FROM outbox_message WHERE to_addresses = ? ORDER BY id DESC"
                    + " LIMIT 1",
                Boolean.class,
                user.email()))
        .isTrue();

    String newPassword = "Copper-Meadow-Kettle-58";
    assertThat(
            browser()
                .post("/api/v1/auth/password-reset/confirm", resetJson(token, newPassword))
                .getStatus())
        .isEqualTo(204);

    MockHttpServletResponse replay =
        browser()
            .post("/api/v1/auth/password-reset/confirm", resetJson(token, "Other-Lamp-Basket-19"));
    assertThat(replay.getStatus()).isEqualTo(400);
    assertThat(replay.getContentAsString()).contains("invalid_link");

    assertThat(
            browser()
                .post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(401);
    assertThat(
            browser()
                .post("/api/v1/auth/sign-in", signInJson(user.email(), newPassword))
                .getStatus())
        .isEqualTo(200);
    assertThat(mailer.sentTo(user.email()))
        .extracting(RecordingSystemMailer.Sent::type)
        .contains("notice-PASSWORD_CHANGED");
    assertThat(auditEvents("PASSWORD_RESET_COMPLETED", user.id())).hasSize(1);
  }

  @Test
  void resetLinkExpiresAfterFifteenMinutes() throws Exception {
    AppUser user = activeUser(Role.AGENT);
    browser().post("/api/v1/auth/password-reset/request", emailJson(user.email()));
    String token = mailer.last(user.email(), "password-reset").token();

    clock.advance(Duration.ofMinutes(15).plusSeconds(1));

    MockHttpServletResponse r =
        browser()
            .post(
                "/api/v1/auth/password-reset/confirm", resetJson(token, "Copper-Meadow-Kettle-58"));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("invalid_link");
  }

  @Test
  void aNewerResetLinkVoidsTheOlderOne() throws Exception {
    AppUser user = activeUser(Role.CUSTOMER);
    browser().post("/api/v1/auth/password-reset/request", emailJson(user.email()));
    String first = mailer.last(user.email(), "password-reset").token();
    browser().post("/api/v1/auth/password-reset/request", emailJson(user.email()));
    String second = mailer.last(user.email(), "password-reset").token();
    assertThat(second).isNotEqualTo(first);

    assertThat(
            browser()
                .post(
                    "/api/v1/auth/password-reset/confirm",
                    resetJson(first, "Copper-Meadow-Kettle-58"))
                .getStatus())
        .isEqualTo(400);
    assertThat(
            browser()
                .post(
                    "/api/v1/auth/password-reset/confirm",
                    resetJson(second, "Copper-Meadow-Kettle-58"))
                .getStatus())
        .isEqualTo(204);
  }

  @Test
  void aWeakPasswordDoesNotSpendTheResetLink() throws Exception {
    AppUser user = activeUser(Role.CUSTOMER);
    browser().post("/api/v1/auth/password-reset/request", emailJson(user.email()));
    String token = mailer.last(user.email(), "password-reset").token();

    MockHttpServletResponse shortOne =
        browser().post("/api/v1/auth/password-reset/confirm", resetJson(token, "short"));
    assertThat(shortOne.getStatus()).isEqualTo(400);
    assertThat(shortOne.getContentAsString()).contains("weak_password").contains("12 characters");
    MockHttpServletResponse breached =
        browser().post("/api/v1/auth/password-reset/confirm", resetJson(token, "Password1234"));
    assertThat(breached.getContentAsString()).contains("weak_password").contains("data breach");

    assertThat(
            browser()
                .post(
                    "/api/v1/auth/password-reset/confirm",
                    resetJson(token, "Copper-Meadow-Kettle-58"))
                .getStatus())
        .isEqualTo(204);
  }

  @Test
  void resetMailsArePerAddressCappedSilently() throws Exception {
    AppUser user = activeUser(Role.AGENT);
    for (int i = 0; i < 5; i++) {
      assertThat(
              browser()
                  .post("/api/v1/auth/password-reset/request", emailJson(user.email()))
                  .getStatus())
          .isEqualTo(202);
    }
    assertThat(mailer.sentTo(user.email())).hasSize(3);
  }

  @Test
  void malformedAndForeignTokensAreRefused() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER);
    browser().post("/api/v1/auth/magic-link/request", emailJson(customer.email()));
    String magic = mailer.last(customer.email(), "magic-link").token();

    for (String token : new String[] {"", "abc", magic.substring(1) + "A", magic}) {
      // A magic-link token is not a reset token (purpose is checked), and junk is junk.
      MockHttpServletResponse r =
          browser()
              .post(
                  "/api/v1/auth/password-reset/confirm",
                  resetJson(token, "Copper-Meadow-Kettle-58"));
      assertThat(r.getStatus()).as(token).isEqualTo(400);
    }
  }

  // --- magic link ---

  @Test
  void onlyActiveCustomersGetAMagicLinkAndEveryoneGetsTheSameAnswer() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER);
    AppUser agent = activeUser(Role.AGENT);
    AppUser admin = activeUser(Role.ADMIN);

    List<MockHttpServletResponse> answers =
        List.of(
            browser().post("/api/v1/auth/magic-link/request", emailJson(customer.email())),
            browser().post("/api/v1/auth/magic-link/request", emailJson(agent.email())),
            browser().post("/api/v1/auth/magic-link/request", emailJson(admin.email())),
            browser().post("/api/v1/auth/magic-link/request", emailJson(uniqueEmail("ghost"))));

    for (MockHttpServletResponse r : answers) {
      assertThat(r.getStatus()).isEqualTo(202);
      assertThat(r.getContentAsString()).isEqualTo(answers.get(0).getContentAsString());
    }
    assertThat(mailer.sent())
        .extracting(RecordingSystemMailer.Sent::to)
        .containsExactly(customer.email());
    List<Map<String, Object>> supporterAudit =
        auditEventsForEmail("MAGIC_LINK_REQUESTED", agent.email());
    assertThat(supporterAudit).hasSize(1);
    assertThat(supporterAudit.get(0).get("details").toString()).contains("not_sent");
  }

  @Test
  void magicLinkSignsTheCustomerInOnce() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER);
    browser().post("/api/v1/auth/magic-link/request", emailJson(customer.email()));
    RecordingSystemMailer.Sent mail = mailer.last(customer.email(), "magic-link");
    assertThat(mail.link()).startsWith("https://support.example.test/magic-link#token=");

    Browser b = browser();
    MockHttpServletResponse r = b.post("/api/v1/auth/magic-link/consume", tokenJson(mail.token()));
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(b.cookie(Browser.SESSION_COOKIE)).isNotNull();
    assertThat(b.get("/api/v1/auth/me").getContentAsString()).contains(customer.email());
    assertThat(b.get("/api/v1/portal/anything").getStatus()).isEqualTo(404);

    MockHttpServletResponse replay =
        browser().post("/api/v1/auth/magic-link/consume", tokenJson(mail.token()));
    assertThat(replay.getStatus()).isEqualTo(400);
    assertThat(replay.getContentAsString()).contains("invalid_link");
  }

  @Test
  void magicLinkExpiresAfterFifteenMinutes() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER);
    browser().post("/api/v1/auth/magic-link/request", emailJson(customer.email()));
    String token = mailer.last(customer.email(), "magic-link").token();
    clock.advance(Duration.ofMinutes(16));

    assertThat(browser().post("/api/v1/auth/magic-link/consume", tokenJson(token)).getStatus())
        .isEqualTo(400);
  }

  @Test
  void aMagicLinkStopsWorkingIfTheCustomerIsDeactivated() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER);
    browser().post("/api/v1/auth/magic-link/request", emailJson(customer.email()));
    String token = mailer.last(customer.email(), "magic-link").token();
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", customer.id());

    assertThat(browser().post("/api/v1/auth/magic-link/consume", tokenJson(token)).getStatus())
        .isEqualTo(400);
  }

  @Test
  void aResetCompletedVoidsOutstandingMagicLinks() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER);
    browser().post("/api/v1/auth/magic-link/request", emailJson(customer.email()));
    String magic = mailer.last(customer.email(), "magic-link").token();
    browser().post("/api/v1/auth/password-reset/request", emailJson(customer.email()));
    String reset = mailer.last(customer.email(), "password-reset").token();

    browser()
        .post("/api/v1/auth/password-reset/confirm", resetJson(reset, "Copper-Meadow-Kettle-58"));

    assertThat(browser().post("/api/v1/auth/magic-link/consume", tokenJson(magic)).getStatus())
        .isEqualTo(400);
  }
}
