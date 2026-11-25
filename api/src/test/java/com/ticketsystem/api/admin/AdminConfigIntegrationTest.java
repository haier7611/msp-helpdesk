package com.ticketsystem.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.OrganisationTestBase;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.mail.SupportName;
import com.ticketsystem.api.signature.LogoProcessor;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * WP-11b admin configuration backend: request types, the company signature template with logo
 * upload and live preview, branding (support name in From), the audit log viewer and CSV export,
 * the public-domain list, and the SLA "resolution before first response" failure path. Each test
 * covers the role gate (agents 403), step-up where the spec asks for it, validation that changes
 * nothing, and the audit rows (field names only, never values or secrets).
 */
class AdminConfigIntegrationTest extends OrganisationTestBase {

  private static final String TYPES = "/api/v1/admin/request-types";
  private static final String TEMPLATE = "/api/v1/admin/signature-template";
  private static final String BRANDING = "/api/v1/admin/branding";
  private static final String AUDIT = "/api/v1/admin/audit";

  @Autowired SupportName supportName;

  private AppUser admin;
  private Browser adminBrowser;
  private Map<String, Object> savedTemplate;
  private List<Long> savedOrder;

  @BeforeEach
  void signIn() throws Exception {
    admin = activeUser(Role.ADMIN);
    adminBrowser = signedIn(admin);
    savedTemplate = jdbc.queryForMap("SELECT * FROM signature_template WHERE id = 1");
    savedOrder =
        jdbc.queryForList("SELECT id FROM request_type ORDER BY sort_order, id", Long.class);
  }

  /** The template row and request type order are shared by every test: put them back. */
  @AfterEach
  void restore() {
    jdbc.update(
        "UPDATE signature_template SET company_name = ?, address = ?, brand_colour = ?,"
            + " email_mode = ?, support_email = ?, logo_alt = ?, confidentiality_line = ?,"
            + " logo_png = NULL, logo_width = NULL, logo_height = NULL, support_name = NULL"
            + " WHERE id = 1",
        savedTemplate.get("company_name"),
        savedTemplate.get("address"),
        savedTemplate.get("brand_colour"),
        savedTemplate.get("email_mode"),
        savedTemplate.get("support_email"),
        savedTemplate.get("logo_alt"),
        savedTemplate.get("confidentiality_line"));
    int order = 10;
    for (Long id : savedOrder) {
      jdbc.update("UPDATE request_type SET sort_order = ? WHERE id = ?", order, id);
      order += 10;
    }
  }

  private void staleStepUp() {
    clock.advance(Duration.ofMinutes(11));
  }

  private void stepUp(Browser b, AppUser who) throws Exception {
    assertThat(
            b.post("/api/v1/auth/step-up", "{\"code\":" + Browser.q(totp(who)) + "}").getStatus())
        .isEqualTo(200);
  }

  private List<Map<String, Object>> auditOf(String type) {
    return jdbc.queryForList(
        "SELECT actor_user_id, details::text AS details FROM audit_event"
            + " WHERE type = ? AND actor_user_id = ? ORDER BY id",
        type,
        admin.id());
  }

  private static String typeJson(
      String name, String description, String priority, boolean visible, boolean archived) {
    return "{\"name\":"
        + Browser.q(name)
        + ",\"description\":"
        + Browser.q(description)
        + ",\"defaultPriority\":"
        + Browser.q(priority)
        + ",\"portalVisible\":"
        + visible
        + ",\"archived\":"
        + archived
        + "}";
  }

  private static String unique(String name) {
    return name + " " + UUID.randomUUID().toString().replace("-", "");
  }

  // Role gate ------------------------------------------------------------------------------------

  @Test
  void agentsAndCustomersGet403OnTheNewAdminEndpoints() throws Exception {
    for (Role r : new Role[] {Role.AGENT, Role.CUSTOMER}) {
      Browser b = signedIn(activeUser(r));
      for (String path :
          List.of(
              TYPES,
              TEMPLATE,
              BRANDING,
              AUDIT,
              AUDIT + "/csv",
              "/api/v1/admin/email/public-domains",
              "/api/v1/admin/email/block-rules",
              "/api/v1/admin/email/mailboxes")) {
        assertThat(b.get(path).getStatus()).as(r + " GET " + path).isEqualTo(403);
      }
      assertThat(b.post(TYPES, typeJson("X", "", "P3", true, false)).getStatus()).isEqualTo(403);
      assertThat(b.post(TEMPLATE + "/preview", "{}").getStatus()).isEqualTo(403);
      assertThat(b.put(BRANDING, "{}").getStatus()).isEqualTo(403);
    }
  }

  // Request types --------------------------------------------------------------------------------

  @Test
  void requestTypesCanBeAddedRenamedDescribedAndArchivedWithAudit() throws Exception {
    String name = unique("Printer");
    MockHttpServletResponse created =
        adminBrowser.post(TYPES, typeJson(name, "Printers and scanners", "P4", true, false));
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    JsonNode t = json(created.getContentAsString());
    long id = t.get("id").asLong();
    assertThat(t.get("description").asString()).isEqualTo("Printers and scanners");
    assertThat(t.get("system").asBoolean()).isFalse();
    assertThat(auditOf("REQUEST_TYPE_CREATED").getLast().get("details").toString())
        .contains("\"request_type\": " + id);

    // Duplicate names (any case) are refused and change nothing.
    MockHttpServletResponse dup =
        adminBrowser.post(TYPES, typeJson(name.toUpperCase(), "", "P3", true, false));
    assertThat(dup.getStatus()).isEqualTo(409);
    assertThat(json(dup.getContentAsString()).get("error").asString()).isEqualTo("duplicate_name");

    // A type clients can choose cannot start at P1.
    assertThat(
            adminBrowser.post(TYPES, typeJson(unique("Urgent"), "", "P1", true, false)).getStatus())
        .isEqualTo(400);

    String renamed = unique("Printing");
    MockHttpServletResponse updated =
        adminBrowser.put(TYPES + "/" + id, typeJson(renamed, "Printers", "P3", true, false));
    assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
    String details = auditOf("REQUEST_TYPE_UPDATED").getLast().get("details").toString();
    assertThat(details).contains("name,description,default_priority").doesNotContain(renamed);

    // Agents see it; after archiving they no longer do, but the type is kept.
    Browser agent = signedIn(activeUser(Role.AGENT));
    assertThat(agent.get("/api/v1/agent/request-types").getContentAsString()).contains(renamed);
    assertThat(
            adminBrowser
                .put(TYPES + "/" + id, typeJson(renamed, "Printers", "P3", true, true))
                .getStatus())
        .isEqualTo(200);
    assertThat(agent.get("/api/v1/agent/request-types").getContentAsString())
        .doesNotContain(renamed);
    assertThat(adminBrowser.get(TYPES).getContentAsString()).contains(renamed);
    assertThat(auditOf("REQUEST_TYPE_UPDATED").getLast().get("details").toString())
        .contains("archived");
  }

  @Test
  void theAlertTypeKeepsItsNameStaysHiddenAndCannotBeArchived() throws Exception {
    long alert =
        jdbc.queryForObject("SELECT id FROM request_type WHERE lower(name) = 'alert'", Long.class);
    String priority =
        jdbc.queryForObject(
            "SELECT default_priority FROM request_type WHERE id = ?", String.class, alert);
    for (String body :
        List.of(
            typeJson("Alarm", "", priority, false, false),
            typeJson("Alert", "", priority, true, false),
            typeJson("Alert", "", priority, false, true))) {
      MockHttpServletResponse r = adminBrowser.put(TYPES + "/" + alert, body);
      assertThat(r.getStatus()).as(body).isEqualTo(409);
      assertThat(json(r.getContentAsString()).get("error").asString()).isEqualTo("system_type");
    }
    assertThat(
            jdbc.queryForObject("SELECT name FROM request_type WHERE id = ?", String.class, alert))
        .isEqualTo("Alert");
  }

  @Test
  void aTypeUsedBySenderRulesCannotBeArchived() throws Exception {
    long id =
        json(adminBrowser
                .post(TYPES, typeJson(unique("Monitoring"), "", "P2", false, false))
                .getContentAsString())
            .get("id")
            .asLong();
    long org = newOrganisation("Example Clinic");
    jdbc.update(
        "INSERT INTO sender_rule (kind, value, organisation_id, request_type_id, created_at)"
            + " VALUES ('ADDRESS', ?, ?, ?, now())",
        "alerts-" + UUID.randomUUID().toString().replace("-", "") + "@monitor.example",
        org,
        id);
    MockHttpServletResponse r =
        adminBrowser.put(TYPES + "/" + id, typeJson("Monitoring x", "", "P2", false, true));
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(json(r.getContentAsString()).get("error").asString())
        .isEqualTo("in_use_by_sender_rules");
  }

  @Test
  void reorderNeedsEveryTypeOnceAndIsAudited() throws Exception {
    List<Long> ids = new ArrayList<>(savedOrder);
    assertThat(
            adminBrowser
                .put(TYPES + "/order", "{\"ids\":" + ids.subList(1, ids.size()) + "}")
                .getStatus())
        .isEqualTo(400);
    java.util.Collections.reverse(ids);
    MockHttpServletResponse r = adminBrowser.put(TYPES + "/order", "{\"ids\":" + ids + "}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(
            jdbc.queryForList("SELECT id FROM request_type ORDER BY sort_order, id", Long.class)
                .getFirst())
        .isEqualTo(ids.getFirst());
    assertThat(auditOf("REQUEST_TYPES_REORDERED")).isNotEmpty();
  }

  // SLA failure path -----------------------------------------------------------------------------

  @Test
  void resolutionShorterThanFirstResponseIsRefusedAndChangesNothing() throws Exception {
    long org = newOrganisation("Example Dental");
    jdbc.update(
        "INSERT INTO organisation_sla_settings (organisation_id, updated_at) VALUES (?, now())"
            + " ON CONFLICT DO NOTHING",
        org);
    String before =
        adminBrowser.get("/api/v1/admin/organisations/" + org + "/sla").getContentAsString();
    String bad =
        "{\"pendingOnVendorPauses\":false,\"targets\":{"
            + "\"P1\":{\"firstResponse\":{\"amount\":30,\"unit\":\"MINUTES\"},\"resolution\":{\"amount\":4,\"unit\":\"HOURS\"},\"clock\":\"TWENTY_FOUR_SEVEN\"},"
            + "\"P2\":{\"firstResponse\":{\"amount\":2,\"unit\":\"DAYS\"},\"resolution\":{\"amount\":8,\"unit\":\"HOURS\"},\"clock\":\"BUSINESS_HOURS\"},"
            + "\"P3\":{\"firstResponse\":{\"amount\":4,\"unit\":\"HOURS\"},\"resolution\":{\"amount\":3,\"unit\":\"DAYS\"},\"clock\":\"BUSINESS_HOURS\"},"
            + "\"P4\":{\"firstResponse\":{\"amount\":1,\"unit\":\"DAYS\"},\"resolution\":{\"amount\":10,\"unit\":\"DAYS\"},\"clock\":\"BUSINESS_HOURS\"}}}";
    MockHttpServletResponse r =
        adminBrowser.put("/api/v1/admin/organisations/" + org + "/sla/policies/default", bad);
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(json(r.getContentAsString()).get("message").asString())
        .contains("P2")
        .contains("cannot be shorter than the first response");
    assertThat(adminBrowser.get("/api/v1/admin/organisations/" + org + "/sla").getContentAsString())
        .isEqualTo(before);
  }

  // Company signature template -------------------------------------------------------------------

  private static String templateJson(String company, String address, String mode, String line) {
    return "{\"companyName\":"
        + Browser.q(company)
        + ",\"address\":"
        + Browser.q(address)
        + ",\"brandColour\":\"#1F4E79\",\"emailMode\":"
        + Browser.q(mode)
        + ",\"supportEmail\":\"support@helpdesk.example\",\"logoAlt\":\"Example MSP logo\""
        + ",\"confidentialityLine\":"
        + Browser.q(line)
        + "}";
  }

  @Test
  void savingTheTemplateNeedsStepUpIsAuditedAndShowsInEverySupportersSignature() throws Exception {
    String body =
        templateJson("Example MSP", "2 Example Road, Example Town", "SUPPORT_MAILBOX", "");
    staleStepUp();
    MockHttpServletResponse refused = adminBrowser.put(TEMPLATE, body);
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).contains("step_up_required");
    assertThat(
            jdbc.queryForObject(
                "SELECT address FROM signature_template WHERE id = 1", String.class))
        .isEqualTo(savedTemplate.get("address"));

    stepUp(adminBrowser, admin);
    MockHttpServletResponse saved = adminBrowser.put(TEMPLATE, body);
    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    JsonNode t = json(saved.getContentAsString());
    assertThat(t.get("address").asString()).isEqualTo("2 Example Road, Example Town");
    assertThat(t.get("brandColour").asString()).isEqualTo("#1F4E79");
    assertThat(t.get("logoUploaded").asBoolean()).isFalse();
    String details = auditOf("SIGNATURE_TEMPLATE_CHANGED").getLast().get("details").toString();
    assertThat(details)
        .contains("address")
        .contains("brand_colour")
        .contains("email_mode")
        .doesNotContain("Example Road");

    // A supporter's own signature (the WP-11a renderer, also used for outbound mail) changes too.
    Browser agent = signedIn(activeUser(Role.AGENT));
    JsonNode sig = json(agent.get("/api/v1/agent/me/signature").getContentAsString());
    assertThat(sig.get("fullText").asString())
        .contains("A: 2 Example Road, Example Town")
        .contains("E: support@helpdesk.example");
    assertThat(sig.get("fullHtml").asString()).contains("color:#1F4E79");
  }

  @Test
  void thePreviewRendersUnsavedValuesEscapedAndStoresNothing() throws Exception {
    MockHttpServletResponse r =
        adminBrowser.post(
            TEMPLATE + "/preview",
            templateJson("<b>Example</b> & Co", "<script>x</script>", "SUPPORTER", "Private"));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode p = json(r.getContentAsString());
    assertThat(p.get("fullHtml").asString())
        .contains("&lt;script&gt;x&lt;/script&gt;")
        .doesNotContain("<script>")
        .contains("Alex Tran")
        .contains("src=\"data:image/png;base64,")
        .contains(">Private</td>");
    assertThat(p.has("shortHtml")).isFalse();
    assertThat(p.get("fullText").asString()).contains("E: alex.tran@helpdesk.example");
    assertThat(
            jdbc.queryForObject(
                "SELECT company_name FROM signature_template WHERE id = 1", String.class))
        .isEqualTo(savedTemplate.get("company_name"));

    MockHttpServletResponse bad =
        adminBrowser.post(
            TEMPLATE + "/preview",
            templateJson("Example MSP", "", "SUPPORTER", "").replace("#1F4E79", "#FFFF00"));
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(bad.getContentAsString()).contains("too light");
  }

  private MockHttpServletResponse uploadLogo(Browser b, byte[] body) throws Exception {
    if (b.cookie(Browser.CSRF_COOKIE) == null) {
      b.get("/api/v1/auth/csrf");
    }
    return b.perform(
        MockMvcRequestBuilders.put(TEMPLATE + "/logo").contentType("image/png").content(body),
        HttpMethod.PUT);
  }

  private static byte[] png(int w, int h) throws Exception {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        img.setRGB(x, y, x < w / 3 ? new Color(0x1F4E79).getRGB() : Color.WHITE.getRGB());
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  @Test
  void theLogoIsReEncodedStoredSmallAndShownInThePreviewAndRefusalsChangeNothing()
      throws Exception {
    staleStepUp();
    assertThat(uploadLogo(adminBrowser, png(900, 300)).getStatus()).isEqualTo(403);
    stepUp(adminBrowser, admin);

    MockHttpServletResponse svg =
        uploadLogo(
            adminBrowser,
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x</script></svg>"
                .getBytes(StandardCharsets.UTF_8));
    assertThat(svg.getStatus()).isEqualTo(400);
    assertThat(json(svg.getContentAsString()).get("error").asString()).isEqualTo("invalid_logo");
    MockHttpServletResponse big =
        uploadLogo(adminBrowser, new byte[LogoProcessor.MAX_UPLOAD_BYTES + 10]);
    assertThat(big.getStatus()).isEqualTo(413);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM signature_template WHERE logo_png IS NOT NULL", Long.class))
        .isZero();

    MockHttpServletResponse ok = uploadLogo(adminBrowser, png(900, 300));
    assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
    JsonNode t = json(ok.getContentAsString());
    assertThat(t.get("logoUploaded").asBoolean()).isTrue();
    assertThat(t.get("logoWidth").asInt()).isEqualTo(150);
    assertThat(t.get("logoHeight").asInt()).isEqualTo(50);
    Integer stored =
        jdbc.queryForObject(
            "SELECT octet_length(logo_png) FROM signature_template WHERE id = 1", Integer.class);
    assertThat(stored).isLessThanOrEqualTo(30 * 1024);
    assertThat(auditOf("SIGNATURE_LOGO_CHANGED").getLast().get("details").toString())
        .contains("uploaded");

    String preview =
        json(adminBrowser
                .post(TEMPLATE + "/preview", templateJson("Example MSP", "", "SUPPORTER", ""))
                .getContentAsString())
            .get("fullHtml")
            .asString();
    assertThat(preview)
        .contains(t.get("logoDataUri").asString())
        .contains("width=\"150\" height=\"50\"");

    MockHttpServletResponse removed = adminBrowser.delete(TEMPLATE + "/logo");
    assertThat(removed.getStatus()).isEqualTo(200);
    assertThat(json(removed.getContentAsString()).get("logoUploaded").asBoolean()).isFalse();
    assertThat(auditOf("SIGNATURE_LOGO_CHANGED").getLast().get("details").toString())
        .contains("removed");
  }

  // Branding -------------------------------------------------------------------------------------

  @Test
  void theSupportNameIsUsedInFromAfterStepUpAndAnEmptyNameGoesBackToTheDefault() throws Exception {
    String defaultName = supportName.get();
    String body = "{\"supportName\":\"Example Helpdesk Team\",\"brandColour\":\"#2B5797\"}";
    staleStepUp();
    assertThat(adminBrowser.put(BRANDING, body).getStatus()).isEqualTo(403);
    stepUp(adminBrowser, admin);
    MockHttpServletResponse saved = adminBrowser.put(BRANDING, body);
    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    JsonNode b = json(saved.getContentAsString());
    assertThat(b.get("supportName").asString()).isEqualTo("Example Helpdesk Team");
    assertThat(b.get("supportNameCustom").asBoolean()).isTrue();
    assertThat(supportName.get()).isEqualTo("Example Helpdesk Team");
    assertThat(auditOf("BRANDING_CHANGED").getLast().get("details").toString())
        .contains("support_name")
        .doesNotContain("Example Helpdesk Team");

    assertThat(
            adminBrowser
                .put(
                    BRANDING,
                    "{\"supportName\":\"Evil\\r\\nBcc: x@example.test\",\"brandColour\":\"#2B5797\"}")
                .getStatus())
        .isEqualTo(400);
    assertThat(supportName.get()).isEqualTo("Example Helpdesk Team");

    assertThat(
            adminBrowser
                .put(BRANDING, "{\"supportName\":\"\",\"brandColour\":\"#2B5797\"}")
                .getStatus())
        .isEqualTo(200);
    assertThat(supportName.get()).isEqualTo(defaultName);
  }

  // Audit log ------------------------------------------------------------------------------------

  @Test
  void theAuditViewerFiltersAndTheCsvIsFormulaSafeAndAudited() throws Exception {
    long org = newOrganisation("Example Vets");
    String marker = "=HYPERLINK(\"http://evil.example\")";
    jdbc.update(
        "INSERT INTO audit_event (occurred_at, type, actor_user_id, client_ip, details)"
            + " VALUES (?, 'SIGN_IN_FAILED', ?, '198.51.100.9', ?::jsonb)",
        java.sql.Timestamp.from(clock.instant()),
        admin.id(),
        "{\"email\":" + Browser.q(marker) + ",\"organisation\":" + org + "}");

    JsonNode page =
        json(
            adminBrowser
                .get(AUDIT + "?type=SIGN_IN_FAILED&organisationId=" + org)
                .getContentAsString());
    assertThat(page.get("events").size()).isEqualTo(1);
    JsonNode e = page.get("events").get(0);
    assertThat(e.get("actorEmail").asString()).isEqualTo(admin.email());
    assertThat(e.get("details").get("email").asString()).isEqualTo(marker);
    assertThat(page.get("types").toString()).contains("AUDIT_LOG_EXPORTED");

    String actorPart = admin.email().substring(0, admin.email().indexOf('@'));
    assertThat(
            json(adminBrowser.get(AUDIT + "?actor=" + actorPart).getContentAsString())
                .get("events")
                .size())
        .isGreaterThanOrEqualTo(1);
    assertThat(
            json(adminBrowser
                    .get(AUDIT + "?actor=nobody-" + UUID.randomUUID())
                    .getContentAsString())
                .get("events")
                .size())
        .isZero();
    // A wildcard typed as the actor matches nothing special.
    assertThat(
            json(adminBrowser
                    .get(AUDIT + "?actor=%25&type=SIGN_IN_FAILED&organisationId=" + org)
                    .getContentAsString())
                .get("events")
                .size())
        .isZero();

    MockHttpServletResponse csv =
        adminBrowser.get(AUDIT + "/csv?type=SIGN_IN_FAILED&organisationId=" + org);
    assertThat(csv.getStatus()).isEqualTo(200);
    assertThat(csv.getHeader("Content-Disposition")).contains("attachment");
    String text = csv.getContentAsString(StandardCharsets.UTF_8);
    assertThat(text).startsWith("occurred_at_utc,event,actor_email");
    // No cell may start with a formula character; the detail is written as key=value.
    assertThat(text).contains("email==HYPERLINK").doesNotContain(",=").doesNotContain(",\"=");
    String exported = auditOf("AUDIT_LOG_EXPORTED").getLast().get("details").toString();
    assertThat(exported)
        .contains("\"rows\": 1")
        .contains("SIGN_IN_FAILED")
        .contains("\"organisation\": " + org);

    // The actor search text (an email address) is never written to the export's audit details.
    adminBrowser.get(AUDIT + "/csv?actor=" + actorPart + "&type=SIGN_IN_FAILED");
    String withActor = auditOf("AUDIT_LOG_EXPORTED").getLast().get("details").toString();
    assertThat(withActor).doesNotContain(actorPart).doesNotContain("\"actor\"");

    // The log keeps one year; bad ranges and unknown types are refused.
    String old = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(400).toString();
    assertThat(adminBrowser.get(AUDIT + "?from=" + old).getStatus()).isEqualTo(400);
    assertThat(adminBrowser.get(AUDIT + "?from=2026-02-02&to=2026-01-01").getStatus())
        .isEqualTo(400);
    assertThat(adminBrowser.get(AUDIT + "?type=NOT_A_TYPE").getStatus()).isEqualTo(400);
  }

  @Test
  void sharedSavedRepliesAreManagedByAdminsSeenByEverySupporterAndPersonalOnesStayPrivate()
      throws Exception {
    String title = unique("Password reset steps");
    MockHttpServletResponse created =
        adminBrowser.post(
            "/api/v1/admin/saved-replies",
            "{\"title\":"
                + Browser.q(title)
                + ",\"bodyHtml\":\"<p>Hi {client_first_name}</p><script>x</script>\"}");
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    JsonNode reply = json(created.getContentAsString());
    long id = reply.get("id").asLong();
    assertThat(reply.get("shared").asBoolean()).isTrue();
    assertThat(reply.get("bodyHtml").asString()).doesNotContain("<script");
    assertThat(auditOf("SHARED_SAVED_REPLY_CHANGED").getLast().get("details").toString())
        .contains("created")
        .doesNotContain(title);

    // Every supporter sees it in the "/" picker list.
    AppUser agent = activeUser(Role.AGENT);
    Browser agentBrowser = signedIn(agent);
    assertThat(agentBrowser.get("/api/v1/agent/saved-replies").getContentAsString())
        .contains(title);
    assertThat(agentBrowser.post("/api/v1/admin/saved-replies", "{}").getStatus()).isEqualTo(403);

    // An agent's personal reply never appears in, or can be changed through, the admin list.
    String mine = unique("My own reply");
    long personal =
        json(agentBrowser
                .post(
                    "/api/v1/agent/saved-replies",
                    "{\"title\":" + Browser.q(mine) + ",\"bodyHtml\":\"<p>x</p>\"}")
                .getContentAsString())
            .get("id")
            .asLong();
    assertThat(adminBrowser.get("/api/v1/admin/saved-replies").getContentAsString())
        .contains(title)
        .doesNotContain(mine);
    assertThat(
            adminBrowser
                .put(
                    "/api/v1/admin/saved-replies/" + personal,
                    "{\"title\":\"Taken\",\"bodyHtml\":\"<p>y</p>\"}")
                .getStatus())
        .isEqualTo(404);
    assertThat(adminBrowser.delete("/api/v1/admin/saved-replies/" + personal).getStatus())
        .isEqualTo(404);

    assertThat(
            adminBrowser
                .put(
                    "/api/v1/admin/saved-replies/" + id,
                    "{\"title\":\"\",\"bodyHtml\":\"<p>y</p>\"}")
                .getStatus())
        .isEqualTo(400);
    assertThat(adminBrowser.delete("/api/v1/admin/saved-replies/" + id).getStatus()).isEqualTo(204);
    assertThat(agentBrowser.get("/api/v1/agent/saved-replies").getContentAsString())
        .doesNotContain(title);
  }

  @Test
  void thePublicDomainListIsShownReadOnly() throws Exception {
    MockHttpServletResponse r = adminBrowser.get("/api/v1/admin/email/public-domains");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentAsString()).contains("\"gmail.com\"").contains("\"outlook.com\"");
  }
}
