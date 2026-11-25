package com.ticketsystem.api.auth.entra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.AuthIntegrationTestBase;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.crypto.DataCipher;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WP-10 test base: the real application against {@link FakeEntra} (a local Microsoft identity
 * platform), with Microsoft sign-in configured and switched on before each test. Tenants, object
 * ids, the client ID and the secret are synthetic.
 */
public abstract class EntraTestBase extends AuthIntegrationTestBase {

  protected static final FakeEntra FAKE = new FakeEntra();

  protected static final String CLIENT_ID = "0b3c9b4e-1f00-4d6a-9c11-5e2f7a8d9e01";

  /** Synthetic, test-only client secret (not a real credential). */
  protected static final String SECRET = "test-only-entra-secret-SYNTHETIC-0001";

  protected static final UUID MSP_TENANT = UUID.fromString("11111111-aaaa-4bbb-8ccc-000000000001");

  protected static final JsonMapper JSON = new JsonMapper();

  @DynamicPropertySource
  static void entraAuthority(DynamicPropertyRegistry registry) {
    registry.add("app.entra.authority", FAKE::authority);
    // FakeEntra is plain HTTP on 127.0.0.1; production refuses any non-https authority.
    registry.add("app.entra.allow-insecure-loopback-authority", () -> "true");
    registry.add("app.entra.timeout", () -> "3s");
  }

  @BeforeEach
  void configureMicrosoftSignIn() {
    FAKE.reset();
    FAKE.expect(CLIENT_ID, SECRET);
    configure(true);
  }

  /** Saves complete, tested details straight in the database; {@code enabled} sets the switch. */
  protected void configure(boolean enabled) {
    DataCipher.Sealed sealed =
        dataCipher.encrypt(
            SECRET.getBytes(StandardCharsets.UTF_8), EntraSettingsService.SECRET_AAD);
    jdbc.update(
        "UPDATE entra_settings SET client_id = ?::uuid, msp_tenant_id = ?, secret_ciphertext = ?,"
            + " secret_key_id = ?, secret_expires_on = current_date + 365,"
            + " settings_version = settings_version + 1, tested_version = settings_version + 1,"
            + " tested_at = now(), expiry_warning_sent_at = NULL, enabled = ?, updated_at = now()"
            + " WHERE id = 1",
        CLIENT_ID,
        MSP_TENANT,
        sealed.ciphertext(),
        sealed.keyId(),
        enabled);
  }

  /** A flow started by the app: what it put in the Microsoft URL. */
  protected record Started(String state, String nonce, String challenge, String tenantSegment) {}

  protected Started startSignIn(Browser b) throws Exception {
    MockHttpServletResponse r = b.post("/api/v1/auth/microsoft/sign-in", "{}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return parse(r);
  }

  protected Started startLink(Browser b) throws Exception {
    MockHttpServletResponse r = b.post("/api/v1/auth/microsoft/link", "{}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return parse(r);
  }

  protected Started startTest(Browser b) throws Exception {
    MockHttpServletResponse r = b.post("/api/v1/admin/sign-in/microsoft/test", "{}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return parse(r);
  }

  protected Started parse(MockHttpServletResponse r) throws Exception {
    String url = JSON.readTree(r.getContentAsString()).get("authorizationUrl").asString();
    assertThat(url).startsWith(FAKE.authority() + "/");
    URI uri = URI.create(url);
    var q = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
    assertThat(dec(q.getFirst("client_id"))).isEqualTo(CLIENT_ID);
    assertThat(dec(q.getFirst("code_challenge_method"))).isEqualTo("S256");
    assertThat(dec(q.getFirst("response_mode"))).isEqualTo("query");
    assertThat(dec(q.getFirst("redirect_uri")))
        .isEqualTo("https://support.example.test/api/v1/auth/microsoft/callback");
    String segment = uri.getPath().split("/")[1];
    return new Started(
        dec(q.getFirst("state")),
        dec(q.getFirst("nonce")),
        dec(q.getFirst("code_challenge")),
        segment);
  }

  private static String dec(String v) {
    return v == null ? null : URLDecoder.decode(v, StandardCharsets.UTF_8);
  }

  /** Standard claims for (tid, oid) answering {@code s}. */
  protected Map<String, Object> claims(Started s, UUID tid, UUID oid) {
    return FAKE.claims(CLIENT_ID, tid, oid, s.nonce(), clock.instant());
  }

  /** Microsoft sends the browser back with a code that redeems to these claims. */
  protected MockHttpServletResponse callback(Browser b, Started s, Map<String, Object> claims)
      throws Exception {
    return callback(b, s, FAKE.sign(claims, false));
  }

  protected MockHttpServletResponse callback(Browser b, Started s, String idToken)
      throws Exception {
    String code = "code-" + UUID.randomUUID();
    FAKE.issue(code, idToken, s.challenge());
    return b.get("/api/v1/auth/microsoft/callback?state=" + s.state() + "&code=" + code);
  }

  /** The redirect target path and query, relative to the public base URL. */
  protected static String target(MockHttpServletResponse r) {
    assertThat(r.getStatus()).isEqualTo(302);
    String location = r.getHeader("Location");
    assertThat(location).startsWith("https://support.example.test/");
    return location.substring("https://support.example.test".length());
  }

  protected Integer links(UUID tid, UUID oid) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM external_identity WHERE tenant_id = ? AND object_id = ?",
        Integer.class,
        tid,
        oid);
  }

  protected void link(AppUser user, UUID tid, UUID oid) {
    jdbc.update(
        "INSERT INTO external_identity (user_id, tenant_id, object_id, linked_at)"
            + " VALUES (?, ?, ?, now())",
        user.id(),
        tid,
        oid);
  }

  protected void setOrganisationTenant(long organisationId, UUID tenant) {
    jdbc.update("UPDATE organisation SET entra_tenant_id = ? WHERE id = ?", tenant, organisationId);
  }

  protected Long organisationOf(AppUser user) {
    return jdbc.queryForObject(
        "SELECT organisation_id FROM app_user WHERE id = ?", Long.class, user.id());
  }

  protected void stepUp(Browser b, AppUser user) throws Exception {
    MockHttpServletResponse r =
        b.post("/api/v1/auth/step-up", "{\"code\":" + Browser.q(totp(user)) + "}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
  }

  protected static JsonNode json(MockHttpServletResponse r) throws Exception {
    return JSON.readTree(r.getContentAsString());
  }
}
