package com.ticketsystem.api.auth.entra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import com.ticketsystem.api.security.SecurityGoLive;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * WP-10 sign-in against a local Microsoft identity platform: spec section 12 security test 2 (wrong
 * tenant with a matching email rejected and nothing linked; an MFA claim from a non-MSP tenant
 * still asks for TOTP; links stored as (tid, oid); supporters never auto-link at sign-in), the
 * customer auto-link rule, every ID token check, outages, deactivation and the off switch.
 */
@ExtendWith(OutputCaptureExtension.class)
@SecurityGoLive({2})
class MicrosoftSignInIntegrationTest extends EntraTestBase {

  private static UUID newTenant() {
    return UUID.randomUUID();
  }

  /** A customer in an organisation whose allowed tenant is {@code tenant} and domain verified. */
  private AppUser customerIn(UUID tenant, String domain) {
    long org = newOrganisation("Acme Dental");
    setOrganisationTenant(org, tenant);
    jdbc.update(
        "INSERT INTO organisation_domain (domain, organisation_id, created_at) VALUES (?, ?, now())",
        domain,
        org);
    long id =
        users.insertActive(
            "pat-" + UUID.randomUUID().toString().replace("-", "") + "@" + domain,
            "Pat Example",
            Role.CUSTOMER,
            org,
            goodPasswordHash(),
            clock.instant());
    return users.findById(id).orElseThrow();
  }

  private static String uniqueDomain() {
    return "acme-" + UUID.randomUUID().toString().replace("-", "") + ".example";
  }

  // ------------------------------------------------------------------- security test 2 (spec 12)

  @Test
  void wrongTenantTokenWithMatchingEmailIsRejectedAndNothingIsLinked() throws Exception {
    UUID acmeTenant = newTenant();
    String domain = uniqueDomain();
    AppUser customer = customerIn(acmeTenant, domain);
    UUID otherTenant = newTenant();
    UUID oid = UUID.randomUUID();

    Browser b = browser();
    Started s = startSignIn(b);
    Map<String, Object> c = claims(s, otherTenant, oid);
    c.put("email", customer.email());
    c.put("xms_edov", true);
    c.put("amr", List.of("pwd", "mfa"));

    assertThat(target(callback(b, s, c))).isEqualTo("/sign-in?microsoft=not_linked");
    assertThat(links(otherTenant, oid)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM external_identity WHERE user_id = ?",
                Integer.class,
                customer.id()))
        .isZero();
    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
    assertThat(auditEvents("SIGN_IN_FAILED", null))
        .anySatisfy(
            e ->
                assertThat(e.get("details").toString())
                    .contains("\"method\": \"microsoft\"")
                    .contains("not_linked")
                    .contains(otherTenant.toString()));
  }

  @Test
  void anMfaClaimFromANonMspTenantNeverCountsAsMfa() throws Exception {
    // The rule itself (ADR-009 C2).
    var config = new EntraSettingsService.Config(true, CLIENT_ID, MSP_TENANT, 1, "x");
    var foreign =
        new EntraClient.IdToken(
            newTenant(), UUID.randomUUID(), List.of("pwd", "mfa"), null, false, 0);
    var msp =
        new EntraClient.IdToken(MSP_TENANT, UUID.randomUUID(), List.of("mfa"), null, false, 0);
    var mspNoMfa =
        new EntraClient.IdToken(MSP_TENANT, UUID.randomUUID(), List.of("pwd"), null, false, 0);
    assertThat(MicrosoftSignInService.entraMfaCounts(foreign, config)).isFalse();
    assertThat(MicrosoftSignInService.entraMfaCounts(msp, config)).isTrue();
    assertThat(MicrosoftSignInService.entraMfaCounts(mspNoMfa, config)).isFalse();

    // End to end: a supporter whose link is in another tenant (for example the MSP tenant was
    // changed after linking) is refused outright, MFA claim or not; never signed in.
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    UUID foreignTenant = newTenant();
    UUID oid = UUID.randomUUID();
    link(agent, foreignTenant, oid);
    Browser b = browser();
    Started s = startSignIn(b);
    Map<String, Object> c = claims(s, foreignTenant, oid);
    c.put("amr", List.of("pwd", "mfa"));
    assertThat(target(callback(b, s, c))).isEqualTo("/sign-in?microsoft=failed");
    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
  }

  @Test
  void anMfaClaimFromTheMspTenantSignsASupporterStraightIn() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    UUID oid = UUID.randomUUID();
    link(agent, MSP_TENANT, oid);
    Browser b = browser();
    Started s = startSignIn(b);
    assertThat(s.tenantSegment()).isEqualTo("organizations");
    Map<String, Object> c = claims(s, MSP_TENANT, oid);
    c.put("amr", List.of("pwd", "mfa"));

    assertThat(target(callback(b, s, c))).isEqualTo("/");
    MockHttpServletResponse me = b.get("/api/v1/auth/me");
    assertThat(me.getStatus()).isEqualTo(200);
    assertThat(json(me).get("authLevel").asString()).isEqualTo("FULL");
    assertThat(json(me).get("id").asLong()).isEqualTo(agent.id());
    assertThat(auditEvents("SIGN_IN_SUCCEEDED", agent.id()))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains("entra_mfa"));
    // Entra MFA is not a step-up: sensitive actions still ask for a code.
    assertThat(b.post("/api/v1/auth/mfa/recovery-codes", "{}").getStatus()).isEqualTo(403);
  }

  @Test
  void withoutAnMfaClaimASupporterStillOwesTheTotpStep() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    UUID oid = UUID.randomUUID();
    link(agent, MSP_TENANT, oid);
    Browser b = browser();
    Started s = startSignIn(b);

    assertThat(target(callback(b, s, claims(s, MSP_TENANT, oid)))).isEqualTo("/mfa/verify");
    assertThat(json(b.get("/api/v1/auth/me")).get("authLevel").asString())
        .isEqualTo("MFA_REQUIRED");
    assertThat(b.get("/api/v1/agent/organisations").getStatus()).isEqualTo(403);
    MockHttpServletResponse verified =
        b.post("/api/v1/auth/mfa/verify", "{\"code\":" + Browser.q(totp(agent)) + "}");
    assertThat(verified.getStatus()).isEqualTo(200);
    assertThat(b.get("/api/v1/agent/organisations").getStatus()).isEqualTo(200);
  }

  @Test
  void aSupporterIsNeverAutoLinkedAtSignInEvenWithAMatchingEmail() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    UUID oid = UUID.randomUUID();
    Browser b = browser();
    Started s = startSignIn(b);
    Map<String, Object> c = claims(s, MSP_TENANT, oid);
    c.put("email", agent.email());
    c.put("xms_edov", true);
    c.put("acct", 0);
    c.put("amr", List.of("mfa"));

    assertThat(target(callback(b, s, c))).isEqualTo("/sign-in?microsoft=not_linked");
    assertThat(links(MSP_TENANT, oid)).isZero();
    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
  }

  // ---------------------------------------------------------------------- customers

  @Test
  void aCustomerAutoLinksOnlyFromTheirOrganisationsTenantWithAVerifiedDomain() throws Exception {
    UUID tenant = newTenant();
    String domain = uniqueDomain();
    AppUser customer = customerIn(tenant, domain);
    UUID oid = UUID.randomUUID();
    Browser b = browser();
    Started s = startSignIn(b);
    Map<String, Object> c = claims(s, tenant, oid);
    c.put("email", customer.email().toUpperCase());
    c.put("xms_edov", true);
    c.put("acct", 0);

    assertThat(target(callback(b, s, c))).isEqualTo("/");
    assertThat(json(b.get("/api/v1/auth/me")).get("id").asLong()).isEqualTo(customer.id());
    // Stored as (tid, oid), nothing else.
    assertThat(
            jdbc.queryForMap(
                "SELECT user_id, tenant_id, object_id FROM external_identity WHERE user_id = ?",
                customer.id()))
        .containsEntry("tenant_id", tenant)
        .containsEntry("object_id", oid);
    assertThat(auditEvents("MICROSOFT_LINKED", customer.id()))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains("auto"));

    // Next time the (tid, oid) alone finds the user, whatever the email says now.
    Browser again = browser();
    Started s2 = startSignIn(again);
    Map<String, Object> c2 = claims(s2, tenant, oid);
    c2.put("email", "renamed@elsewhere.example");
    assertThat(target(callback(again, s2, c2))).isEqualTo("/");
  }

  @Test
  void customerAutoLinkNeedsEveryCondition() throws Exception {
    UUID tenant = newTenant();
    String domain = uniqueDomain();
    AppUser customer = customerIn(tenant, domain);
    for (Map.Entry<String, Object> spoil :
        List.<Map.Entry<String, Object>>of(
            Map.entry("xms_edov", false), Map.entry("acct", 1), Map.entry("email", "nobody@x.y"))) {
      UUID oid = UUID.randomUUID();
      Browser b = browser();
      Started s = startSignIn(b);
      Map<String, Object> c = claims(s, tenant, oid);
      c.put("email", customer.email());
      c.put("xms_edov", true);
      c.put(spoil.getKey(), spoil.getValue());
      assertThat(target(callback(b, s, c)))
          .as(spoil.toString())
          .isEqualTo("/sign-in?microsoft=not_linked");
      assertThat(links(tenant, oid)).isZero();
    }
    // Domain not verified for the organisation: same answer.
    jdbc.update("DELETE FROM organisation_domain WHERE domain = ?", domain);
    UUID oid = UUID.randomUUID();
    Browser b = browser();
    Started s = startSignIn(b);
    Map<String, Object> c = claims(s, tenant, oid);
    c.put("email", customer.email());
    c.put("xms_edov", true);
    assertThat(target(callback(b, s, c))).isEqualTo("/sign-in?microsoft=not_linked");
  }

  @Test
  void aCustomerSignsInOnlyWithTheTenantSetOnTheirOwnOrganisation() throws Exception {
    UUID acme = newTenant();
    UUID beta = newTenant();
    AppUser customer = customerIn(acme, uniqueDomain());
    customerIn(beta, uniqueDomain());
    UUID oid = UUID.randomUUID();
    // A link in Beta's tenant (say the organisation's tenant was changed) is refused.
    link(customer, beta, oid);
    Browser b = browser();
    Started s = startSignIn(b);
    assertThat(target(callback(b, s, claims(s, beta, oid)))).isEqualTo("/sign-in?microsoft=failed");
    assertThat(auditEvents("SIGN_IN_FAILED", customer.id()))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains("tenant_not_allowed"));
  }

  // ---------------------------------------------------------------------- token checks

  @Test
  void forgedExpiredMisaddressedOrReplayedTokensFailGenerically(CapturedOutput output)
      throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    UUID oid = UUID.randomUUID();
    link(agent, MSP_TENANT, oid);
    Map<String, java.util.function.Consumer<Map<String, Object>>> spoilers =
        Map.of(
            "token_audience", c -> c.put("aud", UUID.randomUUID().toString()),
            "token_expired",
                c -> c.put("exp", java.util.Date.from(clock.instant().minusSeconds(600))),
            "token_nonce", c -> c.put("nonce", "not-the-nonce"),
            "token_issuer",
                c -> c.put("iss", "https://login.microsoftonline.com/" + MSP_TENANT + "/v2.0"),
            "token_not_yet_valid",
                c -> c.put("nbf", java.util.Date.from(clock.instant().plusSeconds(900))),
            "token_object", c -> c.remove("oid"));
    for (var entry : spoilers.entrySet()) {
      Browser b = browser();
      Started s = startSignIn(b);
      Map<String, Object> c = claims(s, MSP_TENANT, oid);
      c.put("amr", List.of("mfa"));
      entry.getValue().accept(c);
      assertThat(target(callback(b, s, c)))
          .as(entry.getKey())
          .isEqualTo("/sign-in?microsoft=failed");
      assertThat(b.get("/api/v1/auth/me").getStatus()).as(entry.getKey()).isEqualTo(401);
      assertThat(auditEvents("SIGN_IN_FAILED", null))
          .as(entry.getKey())
          .anySatisfy(e -> assertThat(e.get("details").toString()).contains(entry.getKey()));
    }

    // Signed with a key Microsoft never published.
    Browser forged = browser();
    Started s = startSignIn(forged);
    Map<String, Object> c = claims(s, MSP_TENANT, oid);
    String forgedToken = FAKE.sign(c, true);
    assertThat(target(callback(forged, s, forgedToken))).isEqualTo("/sign-in?microsoft=failed");
    assertThat(forged.get("/api/v1/auth/me").getStatus()).isEqualTo(401);

    // A state replayed (or never issued) is refused before Microsoft is even called.
    Browser b = browser();
    Started good = startSignIn(b);
    Map<String, Object> ok = claims(good, MSP_TENANT, oid);
    ok.put("amr", List.of("mfa"));
    String token = FAKE.sign(ok, false);
    assertThat(target(callback(b, good, token))).isEqualTo("/");
    int calls = FAKE.requests().size();
    assertThat(target(callback(b, good, token))).isEqualTo("/sign-in?microsoft=failed");
    Browser stranger = browser();
    startSignIn(stranger);
    assertThat(
            target(
                stranger.get(
                    "/api/v1/auth/microsoft/callback?state=" + good.state() + "&code=anything")))
        .isEqualTo("/sign-in?microsoft=failed");
    assertThat(FAKE.requests()).hasSize(calls);

    // Nothing secret or token-bearing in the logs or the audit rows.
    assertThat(output.getAll())
        .doesNotContain(SECRET)
        .doesNotContain(token)
        .doesNotContain(forgedToken);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE details::text LIKE ? OR details::text LIKE ?",
                Integer.class,
                "%" + SECRET + "%",
                "%" + oid + "%"))
        .isZero();
  }

  @Test
  void aCallbackWithoutAPendingRequestOrWithAMicrosoftErrorFails() throws Exception {
    Browser b = browser();
    assertThat(target(b.get("/api/v1/auth/microsoft/callback?state=x&code=y")))
        .isEqualTo("/sign-in?microsoft=failed");
    Started s = startSignIn(b);
    assertThat(
            target(
                b.get(
                    "/api/v1/auth/microsoft/callback?state="
                        + s.state()
                        + "&error=access_denied&error_description=AADSTS65004")))
        .isEqualTo("/sign-in?microsoft=cancelled");
  }

  private Integer auditRowsFrom(String ip) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_event WHERE client_ip = ?", Integer.class, ip);
  }

  @Test
  void anUnauthenticatedCallbackFloodIsThrottledBeforeAnyAuditRowIsWritten() throws Exception {
    // Audit M1: the callback is unauthenticated; the per-IP sign-in cap (30 a minute) runs first.
    String ip = "203.0.113.31";
    Browser b = browser().ip(ip);
    for (int i = 1; i <= 30; i++) {
      assertThat(target(b.get("/api/v1/auth/microsoft/callback?state=bogus-" + i)))
          .as("callback %d", i)
          .isEqualTo("/sign-in?microsoft=failed");
    }
    Integer rows = auditRowsFrom(ip);
    assertThat(rows).isEqualTo(30);
    for (int i = 31; i <= 40; i++) {
      assertThat(target(b.get("/api/v1/auth/microsoft/callback?state=bogus-" + i)))
          .as("callback %d", i)
          .isEqualTo("/sign-in?microsoft=throttled");
    }
    assertThat(auditRowsFrom(ip)).isEqualTo(rows);
    // Starting a sign-in shares the same cap.
    assertThat(b.post("/api/v1/auth/microsoft/sign-in", "{}").getStatus()).isEqualTo(429);
  }

  @Test
  void aLinkCallbackIsThrottledToo() throws Exception {
    String ip = "203.0.113.32";
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(agent, browser().ip(ip));
    Started s = startLink(b);
    int used = 0;
    while (used < 100 && rateLimiter.tryConsume(RateLimiter.Scope.SIGN_IN_IP, ip)) {
      used++;
    }
    Integer rows = auditRowsFrom(ip);
    UUID oid = UUID.randomUUID();
    assertThat(target(callback(b, s, claims(s, MSP_TENANT, oid))))
        .isEqualTo("/agent/settings/security?microsoft=throttled");
    assertThat(auditRowsFrom(ip)).isEqualTo(rows);
    assertThat(links(MSP_TENANT, oid)).isZero();
  }

  @Test
  void aSupporterWithoutTheAcctClaimIsRefusedAsIfAGuest() throws Exception {
    // Audit L2: a missing acct claim fails closed for the guest rule.
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    UUID oid = UUID.randomUUID();
    link(agent, MSP_TENANT, oid);
    Browser b = browser();
    Started s = startSignIn(b);
    Map<String, Object> c = claims(s, MSP_TENANT, oid);
    c.remove("acct");
    c.put("amr", List.of("pwd", "mfa"));
    assertThat(target(callback(b, s, c))).isEqualTo("/sign-in?microsoft=failed");
    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
    assertThat(auditEvents("SIGN_IN_FAILED", agent.id()))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains("tenant_not_allowed"));

    // Linking from settings is refused the same way.
    AppUser other = activeUser(Role.AGENT);
    Browser ob = signedIn(other);
    Started ls = startLink(ob);
    UUID otherOid = UUID.randomUUID();
    Map<String, Object> lc = claims(ls, MSP_TENANT, otherOid);
    lc.remove("acct");
    assertThat(target(callback(ob, ls, lc)))
        .isEqualTo("/agent/settings/security?microsoft=wrong_tenant");
    assertThat(links(MSP_TENANT, otherOid)).isZero();
  }

  @Test
  void aPendingRequestExpiresAfterTenMinutes() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    UUID oid = UUID.randomUUID();
    link(agent, MSP_TENANT, oid);
    Browser b = browser();
    Started s = startSignIn(b);
    clock.advance(Duration.ofMinutes(11));
    assertThat(target(callback(b, s, claims(s, MSP_TENANT, oid))))
        .isEqualTo("/sign-in?microsoft=failed");
  }

  @Test
  void anOutageSaysUseYourPassword(CapturedOutput output) throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    UUID oid = UUID.randomUUID();
    link(agent, MSP_TENANT, oid);
    FAKE.mode(FakeEntra.Mode.OUTAGE);
    Browser b = browser();
    Started s = startSignIn(b);
    assertThat(target(callback(b, s, claims(s, MSP_TENANT, oid))))
        .isEqualTo("/sign-in?microsoft=unavailable");
    assertThat(output.getAll()).doesNotContain(SECRET);
  }

  @Test
  void aDeactivatedUserCannotSignInAndDeactivationEndsAnSsoSession() throws Exception {
    AppUser customer = customerIn(newTenant(), uniqueDomain());
    UUID tenant =
        jdbc.queryForObject(
            "SELECT entra_tenant_id FROM organisation WHERE id = ?",
            UUID.class,
            organisationOf(customer));
    UUID oid = UUID.randomUUID();
    link(customer, tenant, oid);
    Browser b = browser();
    Started s = startSignIn(b);
    assertThat(target(callback(b, s, claims(s, tenant, oid)))).isEqualTo("/");
    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(200);

    jdbc.update(
        "UPDATE app_user SET status = 'DEACTIVATED', security_version = security_version + 1"
            + " WHERE id = ?",
        customer.id());
    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(401);

    Browser again = browser();
    Started s2 = startSignIn(again);
    assertThat(target(callback(again, s2, claims(s2, tenant, oid))))
        .isEqualTo("/sign-in?microsoft=failed");
  }

  // ---------------------------------------------------------------------- the switch

  @Test
  void offByDefaultAndWhileOffNothingStarts() throws Exception {
    // Migration default.
    assertThat(
            com.ticketsystem.api.TestcontainersConfiguration.ownerJdbc()
                .queryForObject(
                    "SELECT column_default FROM information_schema.columns WHERE table_name ="
                        + " 'entra_settings' AND column_name = 'enabled'",
                    String.class))
        .isEqualTo("false");

    configure(false);
    Browser b = browser();
    assertThat(json(b.get("/api/v1/auth/microsoft")).get("enabled").asBoolean()).isFalse();
    MockHttpServletResponse r = b.post("/api/v1/auth/microsoft/sign-in", "{}");
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(r.getContentAsString()).contains("microsoft_disabled");

    // Switched off after a flow started: the callback refuses.
    configure(true);
    assertThat(json(b.get("/api/v1/auth/microsoft")).get("enabled").asBoolean()).isTrue();
    Started s = startSignIn(b);
    jdbc.update("UPDATE entra_settings SET enabled = FALSE WHERE id = 1");
    assertThat(target(callback(b, s, claims(s, MSP_TENANT, UUID.randomUUID()))))
        .isEqualTo("/sign-in?microsoft=disabled");
  }
}
