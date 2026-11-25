package com.ticketsystem.api.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.OrganisationTestBase;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.signature.SignatureProfile;
import com.ticketsystem.api.signature.SignatureRenderer;
import com.ticketsystem.api.signature.clientdata.SignatureTemplateRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * My settings (WP-11a, spec sections 5 and 7): profile and signature fields, the server-rendered
 * preview (escaping, identical to the renderer), the E: line switch, photo upload with re-encode,
 * preferences, notifications and out of office. Every endpoint acts on the caller only.
 */
class MySettingsIntegrationTest extends OrganisationTestBase {

  private static final String ME = "/api/v1/agent/me";

  @Autowired SignatureRenderer renderer;
  @Autowired SignatureTemplateRepository templates;

  @AfterEach
  void restoreTemplate() {
    jdbc.update("UPDATE signature_template SET email_mode = 'SUPPORTER' WHERE id = 1");
  }

  private static String profileJson(
      String name, String title, String phone, String mobile, String line) {
    return "{\"displayName\":"
        + Browser.q(name)
        + ",\"jobTitle\":"
        + Browser.q(title)
        + ",\"phone\":"
        + Browser.q(phone)
        + ",\"mobile\":"
        + Browser.q(mobile)
        + ",\"freeLine\":"
        + Browser.q(line)
        + "}";
  }

  @Test
  void customersCannotUseMySettings() throws Exception {
    Browser b = signedIn(activeUser(Role.CUSTOMER));
    assertThat(b.get(ME + "/profile").getStatus()).isEqualTo(403);
    assertThat(b.put(ME + "/profile", profileJson("X", "", "", "", "")).getStatus()).isEqualTo(403);
    assertThat(b.post(ME + "/signature-preview", profileJson("X", "", "", "", "")).getStatus())
        .isEqualTo(403);
  }

  @Test
  void profileIsSavedValidatedAndOnlyTheCallersOwn() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    AppUser other = activeUser(Role.AGENT);
    Browser b = signedIn(agent);
    MockHttpServletResponse saved =
        b.put(
            ME + "/profile",
            profileJson(
                " Alex Tran ",
                "Service Desk Engineer",
                "+00 000 000 000",
                "",
                "Microsoft 365 specialist"));
    assertThat(saved.getStatus()).isEqualTo(200);
    JsonNode p = json(saved.getContentAsString());
    assertThat(p.get("displayName").asString()).isEqualTo("Alex Tran");
    assertThat(p.get("email").asString()).isEqualTo(agent.email());
    assertThat(p.get("hasPhoto").asBoolean()).isFalse();
    assertThat(auditEvents("PROFILE_UPDATED", agent.id())).hasSize(1);

    assertThat(b.put(ME + "/profile", profileJson("", "", "", "", "")).getStatus()).isEqualTo(400);
    assertThat(b.put(ME + "/profile", profileJson("A", "", "call me", "", "")).getStatus())
        .isEqualTo(400);
    assertThat(b.put(ME + "/profile", profileJson("A\nB", "", "", "", "")).getStatus())
        .isEqualTo(400);
    // The other supporter is untouched.
    assertThat(
            jdbc.queryForObject("SELECT name FROM app_user WHERE id = ?", String.class, other.id()))
        .isEqualTo("Sam Example");
  }

  @Test
  void previewEscapesHtmlInFieldsAndDropsEmptyLines() throws Exception {
    Browser b = signedIn(activeUser(Role.AGENT));
    MockHttpServletResponse res =
        b.post(
            ME + "/signature-preview",
            profileJson("<b>x</b>", "{name}", "+00 1", "", "<script>alert(1)</script>"));
    assertThat(res.getStatus()).isEqualTo(200);
    JsonNode r = json(res.getContentAsString());
    String html = r.get("fullHtml").asString();
    assertThat(html)
        .contains("&lt;b&gt;x&lt;/b&gt;")
        .doesNotContain("<b>x</b>")
        .contains(">{name}</td>")
        .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
        .doesNotContain("<script>")
        .doesNotContain("M:</strong>")
        .contains("src=\"data:image/png;base64,");
    assertThat(r.has("shortHtml")).isFalse();
    assertThat(r.get("fullText").asString()).startsWith("<b>x</b>\n{name}");
    assertThat(r.has("shortText")).isFalse();
    // A preview never saves.
    assertThat(json(b.get(ME + "/profile").getContentAsString()).get("displayName").asString())
        .isEqualTo("Sam Example");
    assertThat(b.post(ME + "/signature-preview", profileJson("", "", "", "", "")).getStatus())
        .isEqualTo(400);
  }

  @Test
  void savedSignatureIsByteForByteWhatTheRendererGivesAndMatchesThePreview() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(agent);
    String fields =
        profileJson("Alex <Tran>", "Service Desk Engineer", "+00 000", "+00 111", "Extra & line");
    b.put(ME + "/profile", fields);
    String saved = b.get(ME + "/signature").getContentAsString();
    String preview = b.post(ME + "/signature-preview", fields).getContentAsString();
    assertThat(saved).isEqualTo(preview);

    SignatureRenderer.Rendered expected =
        renderer.render(
            new SignatureProfile(
                "Alex <Tran>",
                "Service Desk Engineer",
                "+00 000",
                "+00 111",
                "Extra & line",
                agent.email()),
            templates.get(AccessScope.admin(agent.id())),
            SignatureRenderer.LogoMode.PREVIEW);
    JsonNode s = json(saved);
    assertThat(s.get("fullHtml").asString()).isEqualTo(expected.fullHtml());
    assertThat(s.get("fullText").asString()).isEqualTo(expected.fullText());
  }

  @Test
  void eLineDefaultsToTheSupportersOwnEmailAndFollowsTheTemplateSetting() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(agent);
    assertThat(json(b.get(ME + "/signature").getContentAsString()).get("fullText").asString())
        .contains("E: " + agent.email());
    jdbc.update("UPDATE signature_template SET email_mode = 'SUPPORT_MAILBOX' WHERE id = 1");
    assertThat(json(b.get(ME + "/signature").getContentAsString()).get("fullText").asString())
        .contains("E: support@helpdesk.example")
        .doesNotContain(agent.email());
  }

  private MockHttpServletResponse upload(Browser b, byte[] body, String contentType)
      throws Exception {
    if (b.cookie(Browser.CSRF_COOKIE) == null) {
      b.get("/api/v1/auth/csrf");
    }
    return b.perform(
        MockMvcRequestBuilders.put(ME + "/photo").contentType(contentType).content(body),
        HttpMethod.PUT);
  }

  @Test
  void photoIsReEncodedWithoutExifAndUnsafeTypesAreRefused() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(agent);
    assertThat(b.get(ME + "/photo").getStatus()).isEqualTo(404);

    assertThat(upload(b, PhotoProcessorTest.jpegWithGpsExif(), "image/jpeg").getStatus())
        .isEqualTo(204);
    MockHttpServletResponse photo = b.get(ME + "/photo");
    assertThat(photo.getStatus()).isEqualTo(200);
    assertThat(photo.getContentType()).isEqualTo("image/png");
    assertThat(photo.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    byte[] png = photo.getContentAsByteArray();
    assertThat(new String(png, StandardCharsets.ISO_8859_1))
        .doesNotContain("Exif")
        .doesNotContain("SYNTHETIC-GPS-MARKER");
    var img = ImageIO.read(new ByteArrayInputStream(png));
    assertThat(img.getWidth()).isEqualTo(256);
    assertThat(img.getHeight()).isEqualTo(256);
    assertThat(json(b.get(ME + "/profile").getContentAsString()).get("hasPhoto").asBoolean())
        .isTrue();
    assertThat(auditEvents("PROFILE_PHOTO_CHANGED", agent.id())).hasSize(1);

    // SVG and an HTML file renamed and labelled as a picture are refused; the photo is kept.
    byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
    byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
    assertThat(upload(b, svg, "image/svg+xml").getStatus()).isEqualTo(400);
    assertThat(upload(b, html, "image/png").getStatus()).isEqualTo(400);
    assertThat(b.get(ME + "/photo").getContentAsByteArray()).isEqualTo(png);

    assertThat(b.delete(ME + "/photo").getStatus()).isEqualTo(204);
    assertThat(b.get(ME + "/photo").getStatus()).isEqualTo(404);
  }

  @Test
  void preferencesAreStoredAndValidated() throws Exception {
    Browser b = signedIn(activeUser(Role.AGENT));
    JsonNode d = json(b.get(ME + "/preferences").getContentAsString());
    assertThat(d.get("composerTab").asString()).isEqualTo("INTERNAL_NOTE");
    assertThat(d.get("theme").asString()).isEqualTo("SYSTEM");
    assertThat(d.get("timezone").asString()).isEqualTo("Asia/Ho_Chi_Minh");

    String ok =
        "{\"composerTab\":\"REPLY\",\"queueView\":\"unassigned\",\"timezone\":\"Europe/London\","
            + "\"dateFormat\":\"YMD\",\"theme\":\"DARK\"}";
    assertThat(b.put(ME + "/preferences", ok).getStatus()).isEqualTo(200);
    JsonNode p = json(b.get(ME + "/preferences").getContentAsString());
    assertThat(p.get("theme").asString()).isEqualTo("DARK");
    assertThat(p.get("timezone").asString()).isEqualTo("Europe/London");
    assertThat(p.get("composerTab").asString()).isEqualTo("REPLY");

    for (String bad :
        new String[] {
          ok.replace("DARK", "PURPLE"),
          ok.replace("Europe/London", "Mars/Olympus"),
          ok.replace("Europe/London", "GMT+7"),
          ok.replace("REPLY", "EMAIL"),
          ok.replace("unassigned", "<x>")
        }) {
      assertThat(b.put(ME + "/preferences", bad).getStatus()).as(bad).isEqualTo(400);
    }
  }

  @Test
  void themePersistsAcrossSignIns() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser first = signedIn(agent);
    first.put(
        ME + "/preferences",
        "{\"composerTab\":\"INTERNAL_NOTE\",\"queueView\":\"my-open\","
            + "\"timezone\":\"Asia/Ho_Chi_Minh\",\"dateFormat\":\"DMY\",\"theme\":\"LIGHT\"}");
    first.post("/api/v1/auth/sign-out", "{}");
    Browser second = signedIn(agent);
    assertThat(json(second.get(ME + "/preferences").getContentAsString()).get("theme").asString())
        .isEqualTo("LIGHT");
  }

  @Test
  void notificationsDefaultOnAndAreStored() throws Exception {
    Browser b = signedIn(activeUser(Role.AGENT));
    JsonNode d = json(b.get(ME + "/notifications").getContentAsString());
    assertThat(d.get("events")).hasSize(6);
    d.get("events")
        .forEach(
            e -> {
              assertThat(e.get("email").asBoolean()).isTrue();
              assertThat(e.get("inApp").asBoolean()).isTrue();
            });
    assertThat(d.get("dailyDigest").asBoolean()).isFalse();

    String body =
        "{\"events\":[{\"event\":\"SLA_WARNING\",\"email\":false,\"inApp\":true}],"
            + "\"dailyDigest\":true}";
    JsonNode saved = json(b.put(ME + "/notifications", body).getContentAsString());
    assertThat(saved.get("dailyDigest").asBoolean()).isTrue();
    saved
        .get("events")
        .forEach(
            e -> {
              if (e.get("event").asString().equals("SLA_WARNING")) {
                assertThat(e.get("email").asBoolean()).isFalse();
              }
            });
    assertThat(
            b.put(
                    ME + "/notifications",
                    "{\"events\":[{\"event\":\"NOPE\",\"email\":true,\"inApp\":true}],"
                        + "\"dailyDigest\":false}")
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void outOfOfficeIsStoredAsAFlagWithDates() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(agent);
    java.time.LocalDate today = java.time.LocalDate.now(clock);
    String on =
        "{\"enabled\":true,\"from\":\"" + today + "\",\"until\":\"" + today.plusDays(4) + "\"}";
    assertThat(b.put(ME + "/out-of-office", on).getStatus()).isEqualTo(200);
    JsonNode o = json(b.get(ME + "/out-of-office").getContentAsString());
    assertThat(o.get("enabled").asBoolean()).isTrue();
    assertThat(o.get("until").asString()).isEqualTo(today.plusDays(4).toString());

    assertThat(
            b.put(
                    ME + "/out-of-office",
                    "{\"enabled\":true,\"from\":\""
                        + today.plusDays(3)
                        + "\",\"until\":\""
                        + today
                        + "\"}")
                .getStatus())
        .isEqualTo(400);
    assertThat(
            b.put(ME + "/out-of-office", "{\"enabled\":true,\"from\":\"" + today + "\"}")
                .getStatus())
        .isEqualTo(400);
    // Visible to admins in the supporter list.
    Browser admin = signedIn(activeUser(Role.ADMIN));
    JsonNode v = json(admin.get("/api/v1/admin/supporters/" + agent.id()).getContentAsString());
    assertThat(v.get("outOfOffice").asBoolean()).isTrue();
    // Preferences saved later keep the out-of-office state.
    b.put(
        ME + "/preferences",
        "{\"composerTab\":\"REPLY\",\"queueView\":\"my-open\",\"timezone\":\"UTC\","
            + "\"dateFormat\":\"DMY\",\"theme\":\"SYSTEM\"}");
    assertThat(json(b.get(ME + "/out-of-office").getContentAsString()).get("enabled").asBoolean())
        .isTrue();
  }
}
