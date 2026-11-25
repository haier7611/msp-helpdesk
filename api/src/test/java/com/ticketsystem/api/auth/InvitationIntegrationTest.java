package com.ticketsystem.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invitations (C7, spec 7 and 11a, ADR-046): no code, password only, expiry, single use, re-issue.
 */
class InvitationIntegrationTest extends AuthIntegrationTestBase {

  private static final JsonMapper JSON = new JsonMapper();

  private record Invite(long userId, String email, String token, Instant expiresAt) {}

  private Invite invite(Browser admin, Role role) throws Exception {
    String email = uniqueEmail("new-" + role.name().toLowerCase());
    MockHttpServletResponse r =
        admin.post(
            "/api/v1/admin/invitations",
            "{\"email\":"
                + Browser.q(email)
                + ",\"name\":\"Robin Example\",\"role\":\""
                + role.name()
                + "\""
                + (role == Role.CUSTOMER
                    ? ",\"organisationId\":" + newOrganisation("Invite Clinic")
                    : "")
                + "}");
    assertThat(r.getStatus()).isEqualTo(201);
    JsonNode body = JSON.readTree(r.getContentAsString());
    // ADR-046: the response never carries a code.
    assertThat(body.has("code")).isFalse();
    String type = role.isSupporter() ? "supporter-invitation" : "customer-invitation";
    RecordingSystemMailer.Sent mail = mailer.last(email, type);
    return new Invite(
        body.get("userId").asLong(),
        email,
        mail.token(),
        Instant.parse(body.get("expiresAt").asString()));
  }

  private static String acceptJson(String token, String password) {
    return "{\"token\":" + Browser.q(token) + ",\"password\":" + Browser.q(password) + "}";
  }

  @Test
  void supporterInvitationIsAcceptedWithThePasswordOnly() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    Invite inv = invite(admin, Role.AGENT);

    assertThat(inv.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(48)));
    RecordingSystemMailer.Sent mail = mailer.last(inv.email(), "supporter-invitation");
    assertThat(mail.link()).startsWith("https://support.example.test/accept-invitation#token=");
    // No code is stored for a new invitation; the token itself is stored only as a hash.
    assertThat(
            jdbc.queryForObject(
                "SELECT code_hash FROM auth_token WHERE user_id = ?", String.class, inv.userId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT token_hash FROM auth_token WHERE user_id = ?", String.class, inv.userId()))
        .hasSize(64)
        .isNotEqualTo(inv.token());

    MockHttpServletResponse inspect =
        browser()
            .post("/api/v1/auth/invitations/inspect", "{\"token\":" + Browser.q(inv.token()) + "}");
    assertThat(inspect.getStatus()).isEqualTo(200);
    assertThat(inspect.getContentAsString()).doesNotContain("codeRequired");

    MockHttpServletResponse ok =
        browser().post("/api/v1/auth/invitations/accept", acceptJson(inv.token(), GOOD_PASSWORD));
    assertThat(ok.getStatus()).isEqualTo(204);
    assertThat(users.findById(inv.userId()).orElseThrow().status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(auditEvents("INVITATION_ACCEPTED", inv.userId())).hasSize(1);
    assertThat(
            browser()
                .post("/api/v1/auth/sign-in", signInJson(inv.email(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(200);

    MockHttpServletResponse replay =
        browser()
            .post(
                "/api/v1/auth/invitations/accept", acceptJson(inv.token(), "Other-Lamp-Basket-19"));
    assertThat(replay.getStatus()).isEqualTo(400);
    assertThat(replay.getContentAsString()).contains("invalid_link");
  }

  @Test
  void anOlderClientSendingACodeFieldIsStillAccepted() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    Invite inv = invite(admin, Role.ADMIN);

    MockHttpServletResponse ok =
        browser()
            .post(
                "/api/v1/auth/invitations/accept",
                "{\"token\":"
                    + Browser.q(inv.token())
                    + ",\"code\":\"123456\",\"password\":"
                    + Browser.q(GOOD_PASSWORD)
                    + "}");
    assertThat(ok.getStatus()).isEqualTo(204);
    assertThat(users.findById(inv.userId()).orElseThrow().status()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  void anInvitationIssuedWithACodeBeforeAdr046IsAcceptedWithoutIt() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    Invite inv = invite(admin, Role.AGENT);
    // Simulate a row written before ADR-046: a stored code hash on the token.
    jdbc.update(
        "UPDATE auth_token SET code_hash = ? WHERE user_id = ?", "ab".repeat(32), inv.userId());

    assertThat(
            browser()
                .post(
                    "/api/v1/auth/invitations/inspect",
                    "{\"token\":" + Browser.q(inv.token()) + "}")
                .getStatus())
        .isEqualTo(200);
    MockHttpServletResponse ok =
        browser().post("/api/v1/auth/invitations/accept", acceptJson(inv.token(), GOOD_PASSWORD));
    assertThat(ok.getStatus()).isEqualTo(204);
    assertThat(users.findById(inv.userId()).orElseThrow().status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(auditEvents("INVITATION_CODE_FAILED", inv.userId())).isEmpty();
  }

  @Test
  void aWrongLinkIsRefused() throws Exception {
    MockHttpServletResponse r =
        browser()
            .post("/api/v1/auth/invitations/accept", acceptJson("A".repeat(43), GOOD_PASSWORD));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("invalid_link");
  }

  @Test
  void supporterInvitationExpiresAfter48Hours() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    Invite inv = invite(admin, Role.AGENT);
    clock.advance(Duration.ofHours(48).plusSeconds(1));

    MockHttpServletResponse r =
        browser().post("/api/v1/auth/invitations/accept", acceptJson(inv.token(), GOOD_PASSWORD));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("invalid_link");
  }

  @Test
  void customerInvitationHasNoCodeAndLastsSevenDays() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    Invite inv = invite(admin, Role.CUSTOMER);

    assertThat(inv.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
    assertThat(
            browser()
                .post(
                    "/api/v1/auth/invitations/inspect",
                    "{\"token\":" + Browser.q(inv.token()) + "}")
                .getContentAsString())
        .doesNotContain("codeRequired");

    clock.advance(Duration.ofDays(6).plusHours(23));
    assertThat(
            browser()
                .post("/api/v1/auth/invitations/accept", acceptJson(inv.token(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(204);
  }

  @Test
  void customerInvitationIsRefusedAfterSevenDays() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    Invite inv = invite(admin, Role.CUSTOMER);
    clock.advance(Duration.ofDays(7).plusSeconds(1));

    assertThat(
            browser()
                .post("/api/v1/auth/invitations/accept", acceptJson(inv.token(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void aWeakPasswordDoesNotSpendTheInvitation() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    Invite inv = invite(admin, Role.AGENT);

    MockHttpServletResponse weak =
        browser().post("/api/v1/auth/invitations/accept", acceptJson(inv.token(), "iloveyou1234"));
    assertThat(weak.getStatus()).isEqualTo(400);
    assertThat(weak.getContentAsString()).contains("weak_password");

    assertThat(
            browser()
                .post("/api/v1/auth/invitations/accept", acceptJson(inv.token(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(204);
  }

  @Test
  void reInvitingVoidsTheOlderLink() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    Invite first = invite(admin, Role.AGENT);
    MockHttpServletResponse again =
        admin.post(
            "/api/v1/admin/invitations",
            "{\"email\":"
                + Browser.q(first.email())
                + ",\"name\":\"Robin Example\",\"role\":\"AGENT\"}");
    assertThat(again.getStatus()).isEqualTo(201);
    String secondToken = mailer.last(first.email(), "supporter-invitation").token();
    assertThat(JSON.readTree(again.getContentAsString()).has("code")).isFalse();

    assertThat(
            browser()
                .post("/api/v1/auth/invitations/accept", acceptJson(first.token(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(400);
    assertThat(
            browser()
                .post("/api/v1/auth/invitations/accept", acceptJson(secondToken, GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(204);
  }

  /**
   * ADR-046 risk note: with no code, sending a supporter invitation must always need step-up - also
   * on this older WP-02b route, not only on /api/v1/admin/supporters/invitations.
   */
  @Test
  void anAgentOrAdminInvitationHereNeedsAFreshStepUpButACustomerOneDoesNot() throws Exception {
    AppUser adminUser = activeUser(Role.ADMIN);
    Browser admin = signedIn(adminUser);
    // Past the 10-minute step-up window that the sign-in code opened.
    clock.advance(Duration.ofMinutes(11));

    for (Role role : new Role[] {Role.AGENT, Role.ADMIN}) {
      String email = uniqueEmail("stale-" + role.name().toLowerCase());
      MockHttpServletResponse refused =
          admin.post(
              "/api/v1/admin/invitations",
              "{\"email\":"
                  + Browser.q(email)
                  + ",\"name\":\"Robin Example\",\"role\":\""
                  + role.name()
                  + "\"}");
      assertThat(refused.getStatus()).as(role.name()).isEqualTo(403);
      assertThat(refused.getContentAsString()).contains("step_up_required");
      assertThat(users.findByEmail(email)).as(role.name()).isEmpty();
      assertThat(mailer.sentTo(email)).as(role.name()).isEmpty();
    }

    // Customers are invited without step-up, as before.
    Invite customer = invite(admin, Role.CUSTOMER);
    assertThat(customer.token()).isNotNull();

    // After a fresh step-up the agent invitation goes through.
    assertThat(
            admin
                .post("/api/v1/auth/step-up", "{\"code\":" + Browser.q(totp(adminUser)) + "}")
                .getStatus())
        .isEqualTo(200);
    Invite agent = invite(admin, Role.AGENT);
    assertThat(users.findById(agent.userId()).orElseThrow().status()).isEqualTo(UserStatus.INVITED);
  }

  @Test
  void anExistingAccountCannotBeInvitedAgain() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    AppUser existing = activeUser(Role.CUSTOMER);
    MockHttpServletResponse r =
        admin.post(
            "/api/v1/admin/invitations",
            "{\"email\":" + Browser.q(existing.email()) + ",\"name\":\"X\",\"role\":\"AGENT\"}");
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(mailer.sentTo(existing.email())).isEmpty();
  }

  @Test
  void invitationCreationIsAudited() throws Exception {
    AppUser adminUser = activeUser(Role.ADMIN);
    Invite inv = invite(signedIn(adminUser), Role.CUSTOMER);
    var events = auditEvents("INVITATION_CREATED", inv.userId());
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("actor_user_id")).isEqualTo(adminUser.id());
    assertThat(events.get(0).get("details").toString()).doesNotContain(inv.token());
  }

  @Test
  void invalidInviteRequestsAreRefused() throws Exception {
    Browser admin = signedIn(activeUser(Role.ADMIN));
    assertThat(
            admin
                .post(
                    "/api/v1/admin/invitations",
                    "{\"email\":\"nope\",\"name\":\"X\",\"role\":\"AGENT\"}")
                .getStatus())
        .isEqualTo(400);
    assertThat(
            admin
                .post(
                    "/api/v1/admin/invitations",
                    "{\"email\":"
                        + Browser.q(uniqueEmail("x"))
                        + ",\"name\":\"X\",\"role\":\"ROOT\"}")
                .getStatus())
        .isEqualTo(400);
  }
}
