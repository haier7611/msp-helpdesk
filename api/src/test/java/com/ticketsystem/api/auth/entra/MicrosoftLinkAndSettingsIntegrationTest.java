package com.ticketsystem.api.auth.entra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * WP-10: linking from a signed-in session (step-up for supporters, tenant allow list, one user per
 * (tid, oid)), unlinking, and Admin - Sign-in - Microsoft (admin only, step-up, write-only secret,
 * Test sign-in before the switch, secret expiry warning, organisation tenants).
 */
class MicrosoftLinkAndSettingsIntegrationTest extends EntraTestBase {

  private static final String BASE = "/api/v1/admin/sign-in/microsoft";

  @Autowired EntraSettingsService settingsService;

  /** Moves past the sign-in code's 10-minute step-up. */
  private void stepUpExpires() {
    clock.advance(Duration.ofMinutes(11));
  }

  private String details(String clientId, String secret, String expires, String tenant) {
    return "{\"clientId\":"
        + Browser.q(clientId)
        + ",\"clientSecret\":"
        + Browser.q(secret)
        + ",\"secretExpiresOn\":"
        + Browser.q(expires)
        + ",\"mspTenantId\":"
        + Browser.q(tenant)
        + "}";
  }

  private String inDays(long days) {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(days).toString();
  }

  // ---------------------------------------------------------------------- linking

  @Test
  void aSupporterLinksFromSettingsWithStepUpAndTheLinkIsTidAndOid() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(agent);
    stepUpExpires();
    MockHttpServletResponse noStepUp = b.post("/api/v1/auth/microsoft/link", "{}");
    assertThat(noStepUp.getStatus()).isEqualTo(403);
    assertThat(noStepUp.getContentAsString()).contains("step_up_required");

    stepUp(b, agent);
    Started s = startLink(b);
    assertThat(s.tenantSegment()).isEqualTo(MSP_TENANT.toString());
    UUID oid = UUID.randomUUID();
    Map<String, Object> c = claims(s, MSP_TENANT, oid);
    c.put("email", "a-completely-different-address@elsewhere.example");
    assertThat(target(callback(b, s, c))).isEqualTo("/agent/settings/security?microsoft=linked");
    assertThat(
            jdbc.queryForMap(
                "SELECT tenant_id, object_id FROM external_identity WHERE user_id = ?", agent.id()))
        .containsEntry("tenant_id", MSP_TENANT)
        .containsEntry("object_id", oid);
    MockHttpServletResponse status = b.get("/api/v1/auth/microsoft/link");
    assertThat(json(status).get("linked").asBoolean()).isTrue();
    assertThat(json(status).get("tenantId").asString()).isEqualTo(MSP_TENANT.toString());
    assertThat(status.getContentAsString()).doesNotContain(oid.toString());
    assertThat(auditEvents("MICROSOFT_LINKED", agent.id())).hasSize(1);
    // Still signed in as the same user.
    assertThat(json(b.get("/api/v1/auth/me")).get("id").asLong()).isEqualTo(agent.id());
  }

  @Test
  void linkingNeedsASignedInSessionAndTheAllowedTenant() throws Exception {
    Browser anonymous = browser();
    assertThat(anonymous.post("/api/v1/auth/microsoft/link", "{}").getStatus()).isEqualTo(401);
    assertThat(anonymous.get("/api/v1/auth/microsoft/link").getStatus()).isEqualTo(401);

    // A supporter coming back from another tenant is refused (MSP tenant only).
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(agent);
    Started s = startLink(b);
    UUID other = UUID.randomUUID();
    UUID oid = UUID.randomUUID();
    assertThat(target(callback(b, s, claims(s, other, oid))))
        .isEqualTo("/agent/settings/security?microsoft=wrong_tenant");
    assertThat(links(other, oid)).isZero();

    // A guest account in the MSP tenant is refused for supporters.
    Started s2 = startLink(b);
    Map<String, Object> guest = claims(s2, MSP_TENANT, oid);
    guest.put("acct", 1);
    assertThat(target(callback(b, s2, guest)))
        .isEqualTo("/agent/settings/security?microsoft=wrong_tenant");

    // A customer whose organisation has no tenant cannot start; with one, only that tenant.
    AppUser customer = activeUser(Role.CUSTOMER);
    Browser cb = signedIn(customer);
    MockHttpServletResponse none = cb.post("/api/v1/auth/microsoft/link", "{}");
    assertThat(none.getStatus()).isEqualTo(409);
    assertThat(none.getContentAsString()).contains("microsoft_not_available");
    UUID orgTenant = UUID.randomUUID();
    setOrganisationTenant(organisationOf(customer), orgTenant);
    Started cs = startLink(cb);
    assertThat(cs.tenantSegment()).isEqualTo(orgTenant.toString());
    assertThat(target(callback(cb, cs, claims(cs, MSP_TENANT, UUID.randomUUID()))))
        .isEqualTo("/portal/account?microsoft=wrong_tenant");
    Started cs2 = startLink(cb);
    assertThat(target(callback(cb, cs2, claims(cs2, orgTenant, UUID.randomUUID()))))
        .isEqualTo("/portal/account?microsoft=linked");
  }

  @Test
  void aSecondUserCannotLinkTheSameMicrosoftAccount() throws Exception {
    AppUser first = activeUser(Role.AGENT);
    AppUser second = activeUser(Role.AGENT);
    UUID oid = UUID.randomUUID();
    link(first, MSP_TENANT, oid);
    Browser b = signedIn(second);
    Started s = startLink(b);
    assertThat(target(callback(b, s, claims(s, MSP_TENANT, oid))))
        .isEqualTo("/agent/settings/security?microsoft=linked_elsewhere");
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM external_identity WHERE tenant_id = ? AND object_id = ?",
                Long.class,
                MSP_TENANT,
                oid))
        .isEqualTo(first.id());
    // And a user with a link must unlink before linking another.
    Browser f = signedIn(first);
    assertThat(f.post("/api/v1/auth/microsoft/link", "{}").getStatus()).isEqualTo(409);
  }

  @Test
  void theLinkCallbackMustComeBackToTheSameSignedInUser() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(agent);
    Started s = startLink(b);
    b.post("/api/v1/auth/sign-out", "{}");
    UUID oid = UUID.randomUUID();
    MockHttpServletResponse r = callback(b, s, claims(s, MSP_TENANT, oid));
    assertThat(r.getStatus()).isEqualTo(302);
    assertThat(links(MSP_TENANT, oid)).isZero();
  }

  @Test
  void unlinkNeedsStepUpForSupportersAndThePasswordKeepsWorking() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    link(agent, MSP_TENANT, UUID.randomUUID());
    Browser b = signedIn(agent);
    stepUpExpires();
    assertThat(b.delete("/api/v1/auth/microsoft/link").getStatus()).isEqualTo(403);
    stepUp(b, agent);
    assertThat(b.delete("/api/v1/auth/microsoft/link").getStatus()).isEqualTo(204);
    assertThat(b.delete("/api/v1/auth/microsoft/link").getStatus()).isEqualTo(404);
    assertThat(auditEvents("MICROSOFT_UNLINKED", agent.id())).hasSize(1);
    assertThat(
            browser()
                .post("/api/v1/auth/sign-in", signInJson(agent.email(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(200);

    // Customers have no TOTP: unlink needs no step-up.
    UUID tenant = UUID.randomUUID();
    AppUser customer = activeUser(Role.CUSTOMER);
    setOrganisationTenant(organisationOf(customer), tenant);
    link(customer, tenant, UUID.randomUUID());
    Browser cb = signedIn(customer);
    stepUpExpires();
    assertThat(cb.delete("/api/v1/auth/microsoft/link").getStatus()).isEqualTo(204);
  }

  // ---------------------------------------------------------------------- admin settings

  @Test
  void onlyAdminsReachTheSettingsAndEveryChangeNeedsStepUp() throws Exception {
    Browser agent = signedIn(activeUser(Role.AGENT));
    assertThat(agent.get(BASE).getStatus()).isEqualTo(403);
    assertThat(
            agent
                .put(BASE, details(CLIENT_ID, "x".repeat(20), inDays(200), MSP_TENANT.toString()))
                .getStatus())
        .isEqualTo(403);
    assertThat(agent.put(BASE + "/enabled", "{\"enabled\":false}").getStatus()).isEqualTo(403);
    assertThat(agent.post(BASE + "/test", "{}").getStatus()).isEqualTo(403);
    Browser customer = signedIn(activeUser(Role.CUSTOMER));
    assertThat(customer.get(BASE).getStatus()).isEqualTo(403);

    AppUser adminUser = activeUser(Role.ADMIN);
    Browser admin = signedIn(adminUser);
    stepUpExpires();
    assertThat(admin.get(BASE).getStatus()).isEqualTo(200);
    for (MockHttpServletResponse r :
        List.of(
            admin.put(BASE, details(CLIENT_ID, null, inDays(200), MSP_TENANT.toString())),
            admin.put(BASE + "/enabled", "{\"enabled\":false}"),
            admin.put(
                BASE + "/organisations/" + newOrganisation("Beta Clinic"),
                "{\"tenantId\":null}"))) {
      assertThat(r.getStatus()).isEqualTo(403);
      assertThat(r.getContentAsString()).contains("step_up_required");
    }
  }

  @Test
  void theSecretIsWriteOnlyAndEncrypted() throws Exception {
    AppUser adminUser = activeUser(Role.ADMIN);
    Browser admin = signedIn(adminUser);
    String newSecret = "rotated-SYNTHETIC-secret-value-0002";
    MockHttpServletResponse saved =
        admin.put(BASE, details(CLIENT_ID, newSecret, inDays(400), MSP_TENANT.toString()));
    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    MockHttpServletResponse read = admin.get(BASE);
    for (MockHttpServletResponse r : List.of(saved, read)) {
      assertThat(r.getContentAsString())
          .doesNotContain(newSecret)
          .doesNotContain(SECRET)
          .doesNotContainIgnoringCase("ciphertext");
      assertThat(json(r).get("settings").get("secretSet").asBoolean()).isTrue();
      assertThat(json(r).get("redirectUri").asString())
          .isEqualTo("https://support.example.test/api/v1/auth/microsoft/callback");
    }
    byte[] stored =
        jdbc.queryForObject(
            "SELECT secret_ciphertext FROM entra_settings WHERE id = 1", byte[].class);
    assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(newSecret);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE details::text LIKE ?",
                Integer.class,
                "%" + newSecret + "%"))
        .isZero();
    assertThat(auditEvents("ENTRA_SETTINGS_CHANGED", null))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains("secret"));

    // A new secret alone keeps it on but asks for a new test.
    JsonNodeView v = new JsonNodeView(json(read));
    assertThat(v.bool("enabled")).isTrue();
    assertThat(v.bool("tested")).isFalse();

    // A new client ID turns it off.
    MockHttpServletResponse moved =
        admin.put(
            BASE, details(UUID.randomUUID().toString(), null, inDays(400), MSP_TENANT.toString()));
    assertThat(new JsonNodeView(json(moved)).bool("enabled")).isFalse();
    assertThat(auditEvents("ENTRA_DISABLED", null))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains("settings_changed"));

    // Bad input never echoes the value.
    MockHttpServletResponse bad =
        admin.put(BASE, details("not-a-guid", "short", inDays(10), MSP_TENANT.toString()));
    assertThat(bad.getStatus()).isEqualTo(400);
    MockHttpServletResponse shortSecret =
        admin.put(BASE, details(CLIENT_ID, "s3cr3t!", inDays(10), MSP_TENANT.toString()));
    assertThat(shortSecret.getStatus()).isEqualTo(400);
    assertThat(shortSecret.getContentAsString()).doesNotContain("s3cr3t!");
  }

  @Test
  void theSwitchNeedsAPassedTestSignInOfTheCurrentDetails() throws Exception {
    jdbc.update(
        "UPDATE entra_settings SET enabled = FALSE, tested_version = NULL, tested_at = NULL"
            + " WHERE id = 1");
    AppUser adminUser = activeUser(Role.ADMIN);
    Browser admin = signedIn(adminUser);
    MockHttpServletResponse early = admin.put(BASE + "/enabled", "{\"enabled\":true}");
    assertThat(early.getStatus()).isEqualTo(409);
    assertThat(early.getContentAsString()).contains("microsoft_test_required");

    // Wrong tenant: the admin signed in with an account outside the MSP tenant.
    Started wrong = startTest(admin);
    assertThat(wrong.tenantSegment()).isEqualTo(MSP_TENANT.toString());
    assertThat(target(callback(admin, wrong, claims(wrong, UUID.randomUUID(), UUID.randomUUID()))))
        .isEqualTo("/admin/sign-in?test=failed&code=wrong_tenant");

    // A wrong secret: the AADSTS code is shown to the admin, nothing else.
    FAKE.mode(FakeEntra.Mode.BAD_SECRET);
    Started rejected = startTest(admin);
    assertThat(target(callback(admin, rejected, claims(rejected, MSP_TENANT, UUID.randomUUID()))))
        .isEqualTo("/admin/sign-in?test=failed&code=AADSTS7000215");
    FAKE.mode(FakeEntra.Mode.NORMAL);
    assertThat(auditEvents("ENTRA_TEST_FAILED", null)).hasSizeGreaterThanOrEqualTo(2);

    // A pass with the MSP tenant; it signs nobody in and creates no link.
    UUID oid = UUID.randomUUID();
    Started ok = startTest(admin);
    assertThat(target(callback(admin, ok, claims(ok, MSP_TENANT, oid))))
        .isEqualTo("/admin/sign-in?test=passed");
    assertThat(links(MSP_TENANT, oid)).isZero();
    assertThat(json(admin.get("/api/v1/auth/me")).get("id").asLong()).isEqualTo(adminUser.id());
    assertThat(auditEvents("ENTRA_TEST_SUCCEEDED", null)).isNotEmpty();

    MockHttpServletResponse on = admin.put(BASE + "/enabled", "{\"enabled\":true}");
    assertThat(on.getStatus()).isEqualTo(200);
    assertThat(new JsonNodeView(json(on)).bool("enabled")).isTrue();
    assertThat(auditEvents("ENTRA_ENABLED", null)).isNotEmpty();

    // Turning it off keeps links.
    AppUser agent = activeUser(Role.AGENT);
    link(agent, MSP_TENANT, UUID.randomUUID());
    assertThat(admin.put(BASE + "/enabled", "{\"enabled\":false}").getStatus()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM external_identity WHERE user_id = ?",
                Integer.class,
                agent.id()))
        .isEqualTo(1);
  }

  @Test
  void aTestThatStartedBeforeASettingsChangeDoesNotCount() throws Exception {
    AppUser adminUser = activeUser(Role.ADMIN);
    Browser admin = signedIn(adminUser);
    Started s = startTest(admin);
    configure(false); // the details change (version goes up) while the admin is at Microsoft
    assertThat(target(callback(admin, s, claims(s, MSP_TENANT, UUID.randomUUID()))))
        .isEqualTo("/admin/sign-in?test=failed&code=failed");
  }

  @Test
  void secretExpiryWarnsThirtyDaysAhead() throws Exception {
    jdbc.update("UPDATE entra_settings SET secret_expires_on = current_date + 31 WHERE id = 1");
    assertThat(settingsService.secretExpiry().state())
        .isEqualTo(EntraSettingsService.ExpiryState.OK);
    assertThat(settingsService.dueExpiryWarning()).isEmpty();

    jdbc.update(
        "UPDATE entra_settings SET secret_expires_on = ? WHERE id = 1",
        java.sql.Date.valueOf(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(30)));
    var soon = settingsService.dueExpiryWarning();
    assertThat(soon).isPresent();
    assertThat(soon.get().state()).isEqualTo(EntraSettingsService.ExpiryState.SOON);
    assertThat(soon.get().daysLeft()).isEqualTo(30);
    settingsService.markExpiryWarningSent();
    assertThat(settingsService.dueExpiryWarning()).isEmpty();
    clock.advance(Duration.ofDays(8));
    assertThat(settingsService.dueExpiryWarning()).isPresent();

    clock.advance(Duration.ofDays(30));
    assertThat(settingsService.secretExpiry().state())
        .isEqualTo(EntraSettingsService.ExpiryState.EXPIRED);

    // The admin screen carries the same state for its banner.
    clock.reset();
    Browser admin = signedIn(activeUser(Role.ADMIN));
    assertThat(json(admin.get(BASE)).get("settings").get("secretExpiry").get("state").asString())
        .isEqualTo("SOON");
  }

  @Test
  void organisationTenantsChangeOnlyWithStepUpAndAreAudited() throws Exception {
    AppUser adminUser = activeUser(Role.ADMIN);
    Browser admin = signedIn(adminUser);
    long org = newOrganisation("Acme Dental");
    String tenant = UUID.randomUUID().toString();
    MockHttpServletResponse set =
        admin.put(BASE + "/organisations/" + org, "{\"tenantId\":" + Browser.q(tenant) + "}");
    assertThat(set.getStatus()).as(set.getContentAsString()).isEqualTo(200);
    assertThat(json(set).get("tenantId").asString()).isEqualTo(tenant);
    assertThat(auditEvents("ORGANISATION_ENTRA_TENANT_CHANGED", null))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains(tenant));
    assertThat(admin.put(BASE + "/organisations/" + org, "{\"tenantId\":\"nope\"}").getStatus())
        .isEqualTo(400);
    assertThat(admin.put(BASE + "/organisations/999999999", "{\"tenantId\":null}").getStatus())
        .isEqualTo(404);

    // The general organisation edit asks for step-up too when the tenant changes, not otherwise.
    stepUpExpires();
    String name = "Acme Dental " + UUID.randomUUID().toString().replace("-", "");
    MockHttpServletResponse sameTenant =
        admin.put(
            "/api/v1/admin/organisations/" + org,
            "{\"name\":"
                + Browser.q(name)
                + ",\"timezone\":\"UTC\",\"entraTenantId\":"
                + Browser.q(tenant)
                + "}");
    assertThat(sameTenant.getStatus()).isEqualTo(200);
    MockHttpServletResponse otherTenant =
        admin.put(
            "/api/v1/admin/organisations/" + org,
            "{\"name\":" + Browser.q(name) + ",\"timezone\":\"UTC\",\"entraTenantId\":null}");
    assertThat(otherTenant.getStatus()).isEqualTo(403);
    assertThat(otherTenant.getContentAsString()).contains("step_up_required");
  }

  /** Reads settings fields from the admin page body. */
  private record JsonNodeView(tools.jackson.databind.JsonNode page) {
    boolean bool(String field) {
      return page.get("settings").get(field).asBoolean();
    }
  }
}
