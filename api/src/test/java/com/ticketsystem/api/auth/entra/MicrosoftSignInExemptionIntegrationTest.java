package com.ticketsystem.api.auth.entra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.security.SecurityGoLive;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * WP-15 (ADR-031) with Microsoft sign-in: an engineer with a two-step exemption that applies now
 * completes Microsoft sign-in without the set-up or code step (the session is tied to the
 * exemption, and the sign-in is not logged as Entra MFA); once the exemption has ended or been
 * revoked, Microsoft sign-in asks for two-step again.
 */
@SecurityGoLive({4})
class MicrosoftSignInExemptionIntegrationTest extends EntraTestBase {

  private static final String AGENT_TREE = "/api/v1/agent/x";

  @BeforeEach
  void wholeSeconds() {
    clock.set(clock.instant().truncatedTo(ChronoUnit.SECONDS));
  }

  private static String exemption(long id) {
    return "/api/v1/admin/supporters/" + id + "/security/mfa-exemption";
  }

  private Instant exempt(Browser admin, AppUser agent, Duration period) throws Exception {
    Instant endsAt = clock.instant().plus(period);
    MockHttpServletResponse r =
        admin.put(
            exemption(agent.id()),
            "{\"reason\":\"Phone lost\",\"endsAt\":" + Browser.q(endsAt.toString()) + "}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return endsAt;
  }

  /** A Microsoft sign-in from the MSP tenant without an MFA claim; returns the redirect path. */
  private String microsoftSignIn(Browser b, UUID oid) throws Exception {
    Started s = startSignIn(b);
    return target(callback(b, s, claims(s, MSP_TENANT, oid)));
  }

  @Test
  void anExemptEngineerCompletesMicrosoftSignInWithoutTheTwoStepStep() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT); // never set up two-step
    UUID oid = UUID.randomUUID();
    link(agent, MSP_TENANT, oid);
    Instant endsAt = exempt(signedIn(admin), agent, Duration.ofHours(2));

    Browser b = browser();
    assertThat(microsoftSignIn(b, oid)).isEqualTo("/");
    MockHttpServletResponse me = b.get("/api/v1/auth/me");
    assertThat(json(me).get("authLevel").asString()).isEqualTo("FULL");
    assertThat(json(me).get("mfaExemptUntil").asString()).isEqualTo(endsAt.toString());
    assertThat(b.get(AGENT_TREE).getStatus()).isEqualTo(404);

    // Logged as an exemption sign-in, never as Entra MFA.
    List<Map<String, Object>> signIns = auditEvents("SIGN_IN_SUCCEEDED", agent.id());
    assertThat(signIns).hasSize(1);
    assertThat(signIns.get(0).get("details").toString())
        .contains("microsoft")
        .doesNotContain("entra_mfa");
    assertThat(auditEvents("MFA_SIGN_IN_SUCCEEDED", agent.id()).get(0).get("details").toString())
        .contains("exemption");

    // The session is tied to the exemption: it ends on the first request after the end time.
    clock.advance(Duration.ofHours(2).plusMinutes(1));
    assertThat(b.get(AGENT_TREE).getStatus()).isEqualTo(401);
    assertThat(auditEvents("SESSION_INVALIDATED", agent.id()).toString())
        .contains("mfa_exemption_ended");

    // And Microsoft sign-in asks for the set-up step again.
    assertThat(microsoftSignIn(browser(), oid)).isEqualTo("/mfa/enrol");
    assertThat(auditEvents("MFA_EXEMPTION_EXPIRED", agent.id())).hasSize(1);
  }

  @Test
  void afterARevokeMicrosoftSignInAsksForTheCodeAgain() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    UUID oid = UUID.randomUUID();
    link(agent, MSP_TENANT, oid);
    Browser a = signedIn(admin);
    exempt(a, agent, Duration.ofDays(5));

    Browser b = browser();
    assertThat(microsoftSignIn(b, oid)).isEqualTo("/");
    assertThat(a.delete(exemption(agent.id())).getStatus()).isEqualTo(204);
    assertThat(b.get(AGENT_TREE).getStatus()).isEqualTo(401);

    Browser again = browser();
    assertThat(microsoftSignIn(again, oid)).isEqualTo("/mfa/verify");
    assertThat(json(again.get("/api/v1/auth/me")).get("authLevel").asString())
        .isEqualTo("MFA_REQUIRED");
    assertThat(again.get(AGENT_TREE).getStatus()).isEqualTo(403);
  }

  @Test
  void entraMfaFromTheMspTenantTakesPrecedenceAndTheSessionIsNotTiedToTheExemption()
      throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    UUID oid = UUID.randomUUID();
    link(agent, MSP_TENANT, oid);
    exempt(signedIn(admin), agent, Duration.ofHours(1));

    Browser b = browser();
    Started s = startSignIn(b);
    Map<String, Object> c = claims(s, MSP_TENANT, oid);
    c.put("amr", List.of("pwd", "mfa"));
    assertThat(target(callback(b, s, c))).isEqualTo("/");
    assertThat(auditEvents("SIGN_IN_SUCCEEDED", agent.id()).get(0).get("details").toString())
        .contains("entra_mfa");
    assertThat(auditEvents("MFA_SIGN_IN_SUCCEEDED", agent.id())).isEmpty();

    clock.advance(Duration.ofHours(1).plusMinutes(1));
    assertThat(b.get(AGENT_TREE).getStatus()).isEqualTo(404);
  }
}
