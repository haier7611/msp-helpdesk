package com.ticketsystem.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * First-admin setup (C8), the audit table's database grants (C14) and log hygiene: the setup token
 * and link tokens never appear in the application output.
 */
@ExtendWith(OutputCaptureExtension.class)
class SetupAndAuditIntegrationTest extends AuthIntegrationTestBase {

  @BeforeEach
  void noAdmins() {
    // Owner role: other test classes create admins; setup needs a system without one. Since WP-04
    // admins can be referenced by tickets, so they are demoted rather than deleted.
    TestcontainersConfiguration.ownerJdbc()
        .update("UPDATE app_user SET role = 'AGENT' WHERE role = 'ADMIN'");
  }

  private static String setupJson(String token, String email, String password) {
    return "{\"token\":"
        + Browser.q(token)
        + ",\"email\":"
        + Browser.q(email)
        + ",\"name\":\"Alex Admin\",\"password\":"
        + Browser.q(password)
        + "}";
  }

  @Test
  void setupWorksOnceThenIsRefusedForGood(CapturedOutput output) throws Exception {
    assertThat(browser().get("/api/v1/auth/setup").getContentAsString())
        .isEqualTo("{\"available\":true}");

    String email = uniqueEmail("first-admin");
    MockHttpServletResponse created =
        browser().post("/api/v1/auth/setup", setupJson(SETUP_TOKEN, email, GOOD_PASSWORD));
    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(created.getContentAsString()).contains("\"role\":\"ADMIN\"");
    AppUser admin = users.findByEmail(email).orElseThrow();
    assertThat(admin.status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(mailer.last(email, "first-admin-setup").link())
        .isEqualTo("https://support.example.test/sign-in");
    assertThat(auditEvents("SETUP_COMPLETED", admin.id())).hasSize(1);

    // Refused once an admin exists, even with the right token.
    MockHttpServletResponse second =
        browser()
            .post(
                "/api/v1/auth/setup", setupJson(SETUP_TOKEN, uniqueEmail("second"), GOOD_PASSWORD));
    assertThat(second.getStatus()).isEqualTo(404);
    assertThat(second.getContentAsString()).contains("setup_unavailable");
    assertThat(browser().get("/api/v1/auth/setup").getContentAsString())
        .isEqualTo("{\"available\":false}");

    // The new admin can sign in and use the admin tree.
    Browser b = signedIn(admin);
    assertThat(b.get("/api/v1/admin/anything").getStatus()).isEqualTo(404);

    assertThat(output.getAll()).doesNotContain(SETUP_TOKEN).doesNotContain(GOOD_PASSWORD);
  }

  @Test
  void aWrongSetupTokenIsRefusedAndAudited(CapturedOutput output) throws Exception {
    String guess = "guess-SYNTHETIC-aaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    MockHttpServletResponse r =
        browser()
            .post("/api/v1/auth/setup", setupJson(guess, uniqueEmail("intruder"), GOOD_PASSWORD));

    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(r.getContentAsString()).contains("invalid_setup_token").doesNotContain(SETUP_TOKEN);
    assertThat(users.anyAdmin()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE type = 'SETUP_FAILED'"
                    + " AND details->>'reason' = 'wrong_token'",
                Integer.class))
        .isPositive();
    assertThat(output.getAll()).doesNotContain(SETUP_TOKEN).doesNotContain(guess);
  }

  @Test
  void setupStillChecksThePasswordPolicy() throws Exception {
    MockHttpServletResponse r =
        browser()
            .post(
                "/api/v1/auth/setup",
                setupJson(SETUP_TOKEN, uniqueEmail("weak"), "passwordpassword"));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("weak_password");
    assertThat(users.anyAdmin()).isFalse();
  }

  @Test
  void setupAttemptsArePerAddressCapped() throws Exception {
    Browser b = browser();
    int last = 0;
    for (int i = 0; i < 6; i++) {
      last =
          b.post(
                  "/api/v1/auth/setup",
                  setupJson(
                      "guess-" + i + "-SYNTHETIC-bbbbbbbbbbbbbbbbbbbbb",
                      uniqueEmail("x"),
                      GOOD_PASSWORD))
              .getStatus();
    }
    assertThat(last).isEqualTo(429);
  }

  @Test
  void linkTokensNeverAppearInTheLog(CapturedOutput output) throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER);
    browser()
        .post("/api/v1/auth/magic-link/request", "{\"email\":" + Browser.q(customer.email()) + "}");
    browser()
        .post(
            "/api/v1/auth/password-reset/request",
            "{\"email\":" + Browser.q(customer.email()) + "}");
    String magic = mailer.last(customer.email(), "magic-link").token();
    String reset = mailer.last(customer.email(), "password-reset").token();
    browser().post("/api/v1/auth/magic-link/consume", "{\"token\":" + Browser.q(magic) + "}");

    assertThat(output.getAll()).doesNotContain(magic).doesNotContain(reset);
  }

  @Test
  void theApplicationRoleCanInsertAndReadButNeverChangeTheAuditLog() {
    jdbc.update(
        "INSERT INTO audit_event (occurred_at, type, details) VALUES (now(), 'TEST', '{}'::jsonb)");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isPositive();

    assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET type = 'X'"))
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event"))
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> jdbc.update("TRUNCATE audit_event"))
        .rootCause()
        .hasMessageContaining("permission denied");
  }
}
