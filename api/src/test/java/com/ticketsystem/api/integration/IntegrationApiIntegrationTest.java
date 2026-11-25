package com.ticketsystem.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.integration.ApiKeyService.CreatedKey;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketTestBase;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * WP-08 integrations API: key lifecycle (create with step-up, shown once, hashed, revoke, expiry),
 * authentication failures, rate limit, body cap, alert dedup and the ADR-024 reopen/follow-up
 * rules, resolved alerts, organisation isolation and the absence of any read route. Synthetic
 * organisations ("Acme Dental", "Beta Clinic") and a synthetic "Example Monitor" only.
 */
class IntegrationApiIntegrationTest extends TicketTestBase {

  static final String PATH = "/api/v1/integrations/tickets";

  @Autowired ApiKeyService keyService;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  private String acmeKey;
  private long acmeKeyId;
  private String betaKey;
  private int ip = 1;

  @BeforeEach
  void keys() {
    CreatedKey a = keyService.create(admin, acme, "Example Monitor", null, null);
    acmeKey = a.secret();
    acmeKeyId = a.key().id();
    betaKey = keyService.create(admin, beta, "Example Monitor", null, null).secret();
  }

  private MockHttpServletResponse call(String key, String json) throws Exception {
    return call(HttpMethod.POST, PATH, key, json);
  }

  private MockHttpServletResponse call(HttpMethod method, String path, String key, String json)
      throws Exception {
    String addr = "203.0.113." + (ip++ % 250 + 1);
    MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, path);
    b.with(
        r -> {
          r.setRemoteAddr(addr);
          r.setSecure(true);
          return r;
        });
    if (key != null) {
      b.header(HttpHeaders.AUTHORIZATION, "Bearer " + key);
    }
    if (json != null) {
      b.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mvc.perform(b).andReturn().getResponse();
  }

  private static String alert(String externalId, String title, boolean resolved) {
    return "{"
        + (externalId == null ? "" : "\"externalId\":" + Browser.q(externalId) + ",")
        + "\"title\":"
        + Browser.q(title)
        + ",\"description\":\"<p>CPU above 95%</p>\""
        + (resolved ? ",\"resolved\":true" : "")
        + "}";
  }

  private JsonNode ok(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isIn(200, 201);
    return json(r.getContentAsString());
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  // ----------------------------------------------------------------------------------------------
  // Creating tickets

  @Test
  void aValidKeyCreatesAnAlertTicketInItsOwnOrganisationAndAnswersOnlyTheReference()
      throws Exception {
    MockHttpServletResponse r = call(acmeKey, alert("disk-1", "Disk almost full", false));
    assertThat(r.getStatus()).isEqualTo(201);
    JsonNode o = json(r.getContentAsString());
    Set<String> fields = new TreeSet<>();
    o.propertyNames().forEach(fields::add);
    assertThat(fields).containsExactly("reference", "result", "ticketId");
    assertThat(o.get("result").asString()).isEqualTo("CREATED");
    assertThat(o.get("reference").asString()).startsWith(activePrefixOf(acme) + "-");
    long id = o.get("ticketId").asLong();
    Map<String, Object> t =
        jdbc.queryForMap(
            "SELECT t.organisation_id, t.source, t.status, t.external_id, t.created_by_api_key_id,"
                + " rt.name AS type, t.priority, t.reporter_user_id FROM ticket t"
                + " JOIN request_type rt ON rt.id = t.request_type_id WHERE t.id = ?",
            id);
    assertThat(t)
        .containsEntry("organisation_id", acme)
        .containsEntry("source", "API")
        .containsEntry("status", "NEW")
        .containsEntry("external_id", "disk-1")
        .containsEntry("created_by_api_key_id", acmeKeyId)
        .containsEntry("type", "Alert")
        .containsEntry("priority", "P2");
    assertThat(t.get("reporter_user_id")).isNull();
    assertThat(
            jdbc.queryForMap(
                "SELECT author_kind, author_api_key_id FROM public_comment WHERE ticket_id = ?",
                id))
        .containsEntry("author_kind", "API_KEY")
        .containsEntry("author_api_key_id", acmeKeyId);
    // No client email for API alerts while the organisation has not opted in (default off).
    assertThat(count("SELECT count(*) FROM outbox_message WHERE ticket_id = ?", id)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM organisation_sla_settings WHERE organisation_id = ?"
                    + " AND api_alert_client_emails",
                acme))
        .isZero();
    // last used is recorded.
    assertThat(
            jdbc.queryForObject(
                "SELECT last_used_at IS NOT NULL FROM api_key WHERE id = ?",
                Boolean.class,
                acmeKeyId))
        .isTrue();
  }

  @Test
  void theBodyCannotChooseAnotherOrganisationOrPriority() throws Exception {
    int betaBefore = count("SELECT count(*) FROM ticket WHERE organisation_id = ?", beta);
    String body =
        "{\"title\":\"Ping lost\",\"organisationId\":"
            + beta
            + ",\"organisation_id\":"
            + beta
            + ",\"priority\":\"P1\",\"status\":\"RESOLVED\",\"source\":\"AGENT\"}";
    long id = ok(call(acmeKey, body)).get("ticketId").asLong();
    assertThat(
            jdbc.queryForMap(
                "SELECT organisation_id, priority, status FROM ticket WHERE id = ?", id))
        .containsEntry("organisation_id", acme)
        .containsEntry("priority", "P2")
        .containsEntry("status", "NEW");
    assertThat(count("SELECT count(*) FROM ticket WHERE organisation_id = ?", beta))
        .isEqualTo(betaBefore);
  }

  @Test
  void alertTextIsSanitisedAndAScriptInTheTitleIsStoredInert() throws Exception {
    String body =
        "{\"title\":\"<script>alert(1)</script> down\","
            + "\"description\":\"<p onclick=\\\"x()\\\">Host</p><script>steal()</script>\"}";
    long id = ok(call(acmeKey, body)).get("ticketId").asLong();
    String subject =
        jdbc.queryForObject("SELECT subject FROM ticket WHERE id = ?", String.class, id);
    assertThat(subject)
        .isEqualTo("<script>alert(1)</script> down"); // plain text, escaped on display
    String comment =
        jdbc.queryForObject(
            "SELECT body_html FROM public_comment WHERE ticket_id = ?", String.class, id);
    assertThat(comment).contains("Host").doesNotContain("script").doesNotContain("onclick");
  }

  @Test
  void invalidPayloadsAre400AndDoNotEchoTheInput() throws Exception {
    MockHttpServletResponse malformed = call(acmeKey, "{\"title\": \"SECRET-ECHO-CHECK");
    assertThat(malformed.getStatus()).isEqualTo(400);
    assertThat(malformed.getContentAsString()).doesNotContain("SECRET-ECHO-CHECK");
    assertThat(call(acmeKey, "{\"title\":\"  \"}").getStatus()).isEqualTo(400);
    assertThat(call(acmeKey, "{\"title\":\"" + "t".repeat(251) + "\"}").getStatus()).isEqualTo(400);
    assertThat(
            call(acmeKey, "{\"title\":\"t\",\"externalId\":\"" + "e".repeat(201) + "\"}")
                .getStatus())
        .isEqualTo(400);
  }

  // ----------------------------------------------------------------------------------------------
  // Dedup (spec 4 flow 3, ADR-024)

  @Test
  void aRepeatAddsACommentInsteadOfANewTicket() throws Exception {
    long id = ok(call(acmeKey, alert("cpu-7", "CPU high", false))).get("ticketId").asLong();
    JsonNode again = ok(call(acmeKey, alert("cpu-7", "CPU high again", false)));
    assertThat(again.get("result").asString()).isEqualTo("UPDATED");
    assertThat(again.get("ticketId").asLong()).isEqualTo(id);
    assertThat(
            count(
                "SELECT count(*) FROM ticket WHERE organisation_id = ? AND external_id = ?",
                acme,
                "cpu-7"))
        .isOne();
    assertThat(count("SELECT count(*) FROM public_comment WHERE ticket_id = ?", id)).isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM public_comment WHERE ticket_id = ? AND author_api_key_id = ?",
                id,
                acmeKeyId))
        .isEqualTo(2);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.NEW);
  }

  @Test
  void twoConcurrentAlertsWithOneExternalIdMakeOneTicket() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      Callable<Integer> c = () -> call(acmeKey, alert("race-1", "Flapping", false)).getStatus();
      List<Future<Integer>> fs = List.of(pool.submit(c), pool.submit(c), pool.submit(c));
      for (Future<Integer> f : fs) {
        assertThat(f.get()).isIn(200, 201);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            count(
                "SELECT count(*) FROM ticket WHERE organisation_id = ? AND external_id = ?",
                acme,
                "race-1"))
        .isOne();
  }

  @Test
  void aRepeatOnAResolvedTicketReopensItToOpen() throws Exception {
    long id = ok(call(acmeKey, alert("mem-2", "Memory", false))).get("ticketId").asLong();
    forceStatus(id, TicketStatus.RESOLVED);
    JsonNode r = ok(call(acmeKey, alert("mem-2", "Memory again", false)));
    assertThat(r.get("result").asString()).isEqualTo("REOPENED");
    assertThat(statusOf(id)).isEqualTo(TicketStatus.OPEN);
  }

  @Test
  void aRepeatOnAPendingTicketReopensItToOpenSoTheSlaClockResumes() throws Exception {
    long id = ok(call(acmeKey, alert("pend-9", "Queue stuck", false))).get("ticketId").asLong();
    forceStatus(id, TicketStatus.PENDING_ON_CLIENT);
    ok(call(acmeKey, alert("pend-9", "Queue still stuck", false)));
    assertThat(statusOf(id)).isEqualTo(TicketStatus.OPEN);
  }

  private void optIn(boolean on) {
    jdbc.update(
        "INSERT INTO organisation_sla_settings (organisation_id, api_alert_client_emails,"
            + " updated_at) VALUES (?, ?, now()) ON CONFLICT (organisation_id) DO UPDATE SET"
            + " api_alert_client_emails = EXCLUDED.api_alert_client_emails",
        acme,
        on);
  }

  /** The alert ticket has a reporter, so a client mail would have a recipient if allowed. */
  private long alertTicketWithReporter(String externalId) throws Exception {
    long id = ok(call(acmeKey, alert(externalId, "Disk", false))).get("ticketId").asLong();
    jdbc.update("UPDATE ticket SET reporter_user_id = ? WHERE id = ?", acmeCustomerUser.id(), id);
    return id;
  }

  @Test
  void withTheOptInOffAnApiTicketSendsNoClientMailEvenWithAReporter() throws Exception {
    optIn(false);
    long id = alertTicketWithReporter("off-1");
    ok(call(acmeKey, alert("off-1", "Disk ok", true)));
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
    assertThat(count("SELECT count(*) FROM outbox_message WHERE ticket_id = ?", id)).isZero();
  }

  @Test
  void withTheOptInOnAResolvedApiTicketIsMailedToTheClient() throws Exception {
    optIn(true);
    long id = alertTicketWithReporter("on-1");
    ok(call(acmeKey, alert("on-1", "Disk ok", true)));
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
    assertThat(
            count(
                "SELECT count(*) FROM outbox_message WHERE ticket_id = ? AND kind ="
                    + " 'client_facing'",
                id))
        .isOne();
  }

  @Test
  void aRepeatAfterClosedCreatesALinkedFollowUpThatTakesOverTheExternalId() throws Exception {
    long id = ok(call(acmeKey, alert("ups-3", "UPS on battery", false))).get("ticketId").asLong();
    forceStatus(id, TicketStatus.CLOSED);
    MockHttpServletResponse r = call(acmeKey, alert("ups-3", "UPS on battery again", false));
    assertThat(r.getStatus()).isEqualTo(201);
    JsonNode o = json(r.getContentAsString());
    assertThat(o.get("result").asString()).isEqualTo("FOLLOW_UP_CREATED");
    long followUp = o.get("ticketId").asLong();
    assertThat(followUp).isNotEqualTo(id);
    assertThat(
            jdbc.queryForMap(
                "SELECT follow_up_of_id, external_id, source, organisation_id,"
                    + " created_by_api_key_id FROM ticket WHERE id = ?",
                followUp))
        .containsEntry("follow_up_of_id", id)
        .containsEntry("external_id", "ups-3")
        .containsEntry("source", "API")
        .containsEntry("organisation_id", acme)
        .containsEntry("created_by_api_key_id", acmeKeyId);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.CLOSED);
    // The next repeat lands on the follow-up.
    JsonNode next = ok(call(acmeKey, alert("ups-3", "Still on battery", false)));
    assertThat(next.get("ticketId").asLong()).isEqualTo(followUp);
    assertThat(next.get("result").asString()).isEqualTo("UPDATED");
  }

  @Test
  void aResolvedAlertResolvesTheTicketWithoutAClientEmail() throws Exception {
    long id = ok(call(acmeKey, alert("http-4", "Website down", false))).get("ticketId").asLong();
    JsonNode r = ok(call(acmeKey, alert("http-4", "Website up", true)));
    assertThat(r.get("result").asString()).isEqualTo("RESOLVED");
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
    assertThat(count("SELECT count(*) FROM outbox_message WHERE ticket_id = ?", id)).isZero();
    // A resolved alert with nothing open changes nothing.
    JsonNode none = ok(call(acmeKey, alert("never-seen", "All good", true)));
    assertThat(none.get("result").asString()).isEqualTo("IGNORED");
    assertThat(
            count(
                "SELECT count(*) FROM ticket WHERE organisation_id = ? AND external_id = ?",
                acme,
                "never-seen"))
        .isZero();
  }

  @Test
  void alertsWithoutAnExternalIdAreAlwaysNewTickets() throws Exception {
    long a = ok(call(acmeKey, alert(null, "Backup failed", false))).get("ticketId").asLong();
    long b = ok(call(acmeKey, alert(null, "Backup failed", false))).get("ticketId").asLong();
    assertThat(a).isNotEqualTo(b);
  }

  // ----------------------------------------------------------------------------------------------
  // Isolation (security test 1, key part)

  @Test
  void organisationsNeverShareTicketsEvenWithTheSameExternalId() throws Exception {
    long a = ok(call(acmeKey, alert("shared-9", "Alert", false))).get("ticketId").asLong();
    JsonNode b = ok(call(betaKey, alert("shared-9", "Alert", false)));
    assertThat(b.get("result").asString()).isEqualTo("CREATED");
    assertThat(b.get("ticketId").asLong()).isNotEqualTo(a);
    assertThat(
            jdbc.queryForObject(
                "SELECT organisation_id FROM ticket WHERE id = ?",
                Long.class,
                b.get("ticketId").asLong()))
        .isEqualTo(beta);
    assertThat(count("SELECT count(*) FROM public_comment WHERE ticket_id = ?", a)).isOne();
  }

  @Test
  void anApiKeyNeverMatchesTicketsFromOtherChannels() throws Exception {
    // An agent ticket that happens to carry the same external id is not an API alert.
    long agentTicket = acmeTicket().ticketId();
    jdbc.update("UPDATE ticket SET external_id = 'agent-x' WHERE id = ?", agentTicket);
    JsonNode r = ok(call(acmeKey, alert("agent-x", "Alert", false)));
    assertThat(r.get("result").asString()).isEqualTo("CREATED");
    assertThat(r.get("ticketId").asLong()).isNotEqualTo(agentTicket);
    assertThat(count("SELECT count(*) FROM public_comment WHERE ticket_id = ?", agentTicket))
        .isOne();
  }

  @Test
  void thereIsNoReadRouteAndAKeyOpensNothingElse() throws Exception {
    Set<String> routes = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String p : info.getPatternValues()) {
        if (p.startsWith("/api/v1/integrations")) {
          for (RequestMethod m : info.getMethodsCondition().getMethods()) {
            routes.add(m + " " + p);
          }
          if (info.getMethodsCondition().getMethods().isEmpty()) {
            routes.add("ANY " + p);
          }
        }
      }
    }
    assertThat(routes).containsExactly("POST /api/v1/integrations/tickets");
    long id = ok(call(acmeKey, alert("read-1", "Alert", false))).get("ticketId").asLong();
    assertThat(call(HttpMethod.GET, PATH, acmeKey, null).getStatus()).isIn(403, 405);
    assertThat(call(HttpMethod.GET, PATH + "/" + id, acmeKey, null).getStatus()).isIn(403, 404);
    // The key is not a session: every other tree answers 401.
    for (String path :
        List.of(
            "/api/v1/agent/tickets",
            "/api/v1/agent/tickets/" + id,
            "/api/v1/portal/request-types",
            "/api/v1/admin/organisations",
            "/api/v1/auth/me")) {
      assertThat(call(HttpMethod.GET, path, acmeKey, null).getStatus()).as(path).isEqualTo(401);
    }
  }

  @Test
  void aSessionCookieDoesNotAuthenticateTheIntegrationsTree() throws Exception {
    Browser admin = signedIn(adminUser);
    MockHttpServletResponse r = admin.post(PATH, alert("cookie-1", "From a browser", false));
    assertThat(r.getStatus()).isEqualTo(401);
  }

  // ----------------------------------------------------------------------------------------------
  // Authentication, rate limit, body cap

  @Test
  void unknownExpiredRevokedAndMalformedKeysGetTheSame401() throws Exception {
    String expired =
        keyService
            .create(admin, acme, "Old monitor", clock.instant().plus(Duration.ofMinutes(5)), null)
            .secret();
    clock.advance(Duration.ofMinutes(6));
    CreatedKey revoked = keyService.create(admin, acme, "Revoked monitor", null, null);
    keyService.revoke(admin, acme, revoked.key().id(), null);
    String wrongSecret = acmeKey.substring(0, acmeKey.length() - 4) + "AAAA";
    String unknown = "tsk_zzzzzzzz_" + "A".repeat(43);
    String body = alert(null, "x", false);
    List<String> bodies =
        List.of(
            call(expired, body).getContentAsString(),
            call(revoked.secret(), body).getContentAsString(),
            call(wrongSecret, body).getContentAsString(),
            call(unknown, body).getContentAsString(),
            call("not-a-key", body).getContentAsString(),
            call(null, body).getContentAsString());
    assertThat(Set.copyOf(bodies)).hasSize(1);
    assertThat(call(expired, body).getStatus()).isEqualTo(401);
    assertThat(call(revoked.secret(), body).getStatus()).isEqualTo(401);
    assertThat(call(null, body).getStatus()).isEqualTo(401);
    // The valid key still works.
    assertThat(call(acmeKey, body).getStatus()).isEqualTo(201);
  }

  @Test
  void the61stRequestInAMinuteGets429WithRetryAfter() throws Exception {
    String body = alert("rate-unknown", "ok", true); // IGNORED: cheap, creates nothing
    for (int i = 0; i < 60; i++) {
      assertThat(call(acmeKey, body).getStatus()).as("request " + (i + 1)).isEqualTo(200);
    }
    MockHttpServletResponse r = call(acmeKey, body);
    assertThat(r.getStatus()).isEqualTo(429);
    assertThat(Long.parseLong(r.getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
    // Another key is not affected.
    assertThat(call(betaKey, body).getStatus()).isEqualTo(200);
  }

  @Test
  void aBodyOver256KilobytesGets413() throws Exception {
    String big = "{\"title\":\"big\",\"description\":\"" + "x".repeat(256 * 1024) + "\"}";
    assertThat(call(acmeKey, big).getStatus()).isEqualTo(413);
    assertThat(call(null, big).getStatus()).isEqualTo(413);
    String fits = "{\"title\":\"fits\",\"description\":\"" + "x".repeat(150_000) + "\"}";
    assertThat(call(acmeKey, fits).getStatus()).isEqualTo(201);
  }

  // ----------------------------------------------------------------------------------------------
  // Key lifecycle through the admin API

  @Test
  void adminCreatesAKeyWithStepUpSeesItOnceAndOnlyItsHashIsStored() throws Exception {
    Browser a = signedIn(adminUser); // the sign-in TOTP counts as step-up for 10 minutes
    String base = "/api/v1/admin/organisations/" + acme + "/api-keys";
    MockHttpServletResponse created = a.post(base, "{\"name\":\"Example Monitor 2\"}");
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    assertThat(created.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    JsonNode c = json(created.getContentAsString());
    String secret = c.get("secret").asString();
    assertThat(secret).matches("tsk_[a-z0-9]{8}_[A-Za-z0-9_-]{43}");
    long id = c.get("key").get("id").asLong();
    assertThat(c.get("key").get("prefix").asString()).isEqualTo(secret.substring(0, 12));

    // Stored as SHA-256 only: nothing in the row is the key.
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM api_key WHERE id = ?", id);
    assertThat(row.values().toString()).doesNotContain(secret.substring(13));
    byte[] hash = (byte[]) row.get("secret_hash");
    assertThat(HexFormat.of().formatHex(hash))
        .isEqualTo(HexFormat.of().formatHex(ApiKeys.hash(secret)));

    // The list never shows the secret.
    String list = a.get(base).getContentAsString();
    assertThat(list).contains("Example Monitor 2").doesNotContain(secret.substring(13));
    assertThat(list.toLowerCase(Locale.ROOT)).doesNotContain("hash").doesNotContain("secret");

    // Audit without the key.
    List<Map<String, Object>> audits =
        jdbc.queryForList(
            "SELECT details::text AS d FROM audit_event WHERE type = 'API_KEY_CREATED'"
                + " AND details->>'api_key' = ?",
            Long.toString(id));
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0).get("d").toString()).doesNotContain(secret.substring(13));

    // The key works, then revoke stops it at once.
    assertThat(call(secret, alert(null, "From key 2", false)).getStatus()).isEqualTo(201);
    MockHttpServletResponse revoked = a.delete(base + "/" + id);
    assertThat(revoked.getStatus()).isEqualTo(200);
    assertThat(json(revoked.getContentAsString()).get("status").asString()).isEqualTo("REVOKED");
    assertThat(call(secret, alert(null, "After revoke", false)).getStatus()).isEqualTo(401);
    assertThat(
            count(
                "SELECT count(*) FROM audit_event WHERE type = 'API_KEY_REVOKED'"
                    + " AND details->>'api_key' = ?",
                Long.toString(id)))
        .isOne();
  }

  @Test
  void creatingAKeyNeedsAFreshStepUp() throws Exception {
    Browser a = signedIn(adminUser);
    clock.advance(Duration.ofMinutes(11));
    MockHttpServletResponse r =
        a.post("/api/v1/admin/organisations/" + acme + "/api-keys", "{\"name\":\"Late\"}");
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(r.getContentAsString()).contains("step_up_required");
  }

  @Test
  void agentsAndCustomersCannotManageKeysAndOtherOrganisationsKeysAreHidden() throws Exception {
    String base = "/api/v1/admin/organisations/" + acme + "/api-keys";
    assertThat(signedIn(acmeAgentUser).get(base).getStatus()).isEqualTo(403);
    assertThat(signedIn(acmeCustomerUser).get(base).getStatus()).isEqualTo(403);
    // An admin revoking acme's key through beta's path gets 404.
    Browser a = signedIn(adminUser);
    assertThat(
            a.delete("/api/v1/admin/organisations/" + beta + "/api-keys/" + acmeKeyId).getStatus())
        .isEqualTo(404);
    assertThat(call(acmeKey, alert(null, "still valid", false)).getStatus()).isEqualTo(201);
  }

  @Test
  void expiryMustBeInTheFuture() throws Exception {
    Browser a = signedIn(adminUser);
    MockHttpServletResponse r =
        a.post(
            "/api/v1/admin/organisations/" + acme + "/api-keys",
            "{\"name\":\"Past\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}");
    assertThat(r.getStatus()).isEqualTo(400);
  }

  // ----------------------------------------------------------------------------------------------
  // OpenAPI

  @Test
  @SuppressWarnings("unchecked")
  void everyIntegrationAndApiKeyEndpointIsDocumented() throws Exception {
    Set<String> registered = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String p : info.getPatternValues()) {
        if (p.startsWith("/api/v1/integrations") || p.contains("/api-keys")) {
          for (RequestMethod m : info.getMethodsCondition().getMethods()) {
            registered.add(m.name().toLowerCase(Locale.ROOT) + " " + p);
          }
        }
      }
    }
    Map<String, Object> doc;
    try (InputStream in = getClass().getResourceAsStream("/openapi/integrations.yaml")) {
      doc = new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    Set<String> documented = new TreeSet<>();
    ((Map<String, Map<String, Object>>) doc.get("paths"))
        .forEach(
            (path, ops) ->
                ops.keySet().stream()
                    .filter(k -> Set.of("get", "post", "put", "delete", "patch").contains(k))
                    .forEach(k -> documented.add(k + " " + path)));
    assertThat(registered).hasSize(4);
    assertThat(documented).isEqualTo(registered);
  }
}
