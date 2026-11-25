package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.security.SecurityGoLive;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import com.ticketsystem.api.ticket.attachment.TestImages;
import com.ticketsystem.api.ticket.attachment.job.PendingAttachmentCleanupJob;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Upload, bind, download (spec 8, ADR-009 C6, security test 3 download part): headers, EXIF
 * stripping, size cap, SVG refusal, pending ownership and expiry, visibility inheritance.
 */
@SecurityGoLive({1, 3})
class AttachmentIntegrationTest extends TicketTestBase {

  @Autowired PendingAttachmentCleanupJob cleanupJob;

  private UUID uploadOk(Browser b, String path, String name, byte[] bytes) throws Exception {
    MockHttpServletResponse r = uploadVia(b, path, name, bytes);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    return UUID.fromString(json(r.getContentAsString()).get("id").asString());
  }

  @Test
  void anHtmlFileIsServedAsADownloadWithNosniffAndASandbox() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    long id = acmeTicket().ticketId();
    UUID file = uploadOk(agent, "/api/v1/agent/attachments", "page.html", TestImages.html());
    String body = "{\"bodyHtml\":\"<p>see</p>\",\"attachmentIds\":[\"" + file + "\"]}";
    assertThat(agent.post("/api/v1/agent/tickets/" + id + "/public-replies", body).getStatus())
        .isEqualTo(201);
    MockHttpServletResponse r = agent.get("/api/v1/agent/attachments/" + file);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentType()).isEqualTo("application/octet-stream");
    assertThat(r.getHeader("Content-Disposition")).startsWith("attachment;").contains("page.html");
    assertThat(r.getHeaders("X-Content-Type-Options")).contains("nosniff");
    assertThat(r.getHeader("Content-Security-Policy")).contains("sandbox");
    assertThat(r.getHeader("Cache-Control")).contains("no-store");
    assertThat(r.getContentAsByteArray()).isEqualTo(TestImages.html());
  }

  @Test
  void aJpegWithGpsHasNoExifWhenDownloadedAndHasAThumbnail() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    long id = acmeTicket().ticketId();
    UUID img =
        uploadOk(
            agent, "/api/v1/agent/attachments", "site.jpg", TestImages.jpegWithGpsExif(80, 60));
    agent.post(
        "/api/v1/agent/tickets/" + id + "/internal-notes",
        "{\"bodyHtml\":\"<p><img src=\\\"attachment:"
            + img
            + "\\\"></p>\",\"attachmentIds\":[\""
            + img
            + "\"]}");
    MockHttpServletResponse r = agent.get("/api/v1/agent/attachments/" + img);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentType()).isEqualTo("image/jpeg");
    assertThat(r.getHeader("Content-Disposition")).startsWith("inline;");
    String bytes = new String(r.getContentAsByteArray(), StandardCharsets.ISO_8859_1);
    assertThat(bytes).doesNotContain("Exif").doesNotContain(TestImages.GPS_MARKER);
    MockHttpServletResponse thumb = agent.get("/api/v1/agent/attachments/" + img + "/thumbnail");
    assertThat(thumb.getStatus()).isEqualTo(200);
    assertThat(thumb.getHeaders("X-Content-Type-Options")).contains("nosniff");
    TicketDetail d = queries.detail(acmeAgent, id);
    assertThat(d.internalNotes().get(0).attachments())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.internal()).isTrue();
              assertThat(a.inline()).isTrue();
              assertThat(a.image()).isTrue();
            });
    assertThat(d.internalNotes().get(0).bodyHtml()).contains("attachment:" + img);
  }

  @Test
  void a21MegabyteFileIsRefusedWith413() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    MockHttpServletResponse r =
        uploadVia(agent, "/api/v1/agent/attachments", "big.bin", new byte[21 * 1024 * 1024]);
    assertThat(r.getStatus()).isEqualTo(413);
    assertThat(json(r.getContentAsString()).get("error").asString()).isEqualTo("file_too_large");
  }

  @Test
  void svgUploadsAreRefusedIncludingRenamedOnes() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    assertThat(
            uploadVia(agent, "/api/v1/agent/attachments", "logo.svg", TestImages.svg()).getStatus())
        .isEqualTo(400);
    assertThat(
            uploadVia(agent, "/api/v1/agent/attachments", "logo.png", TestImages.svg()).getStatus())
        .isEqualTo(400);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM attachment WHERE filename LIKE 'logo.%'", Integer.class))
        .isZero();
  }

  @Test
  void uploadsWithoutCsrfAreRefused() throws Exception {
    Browser agent = signedIn(acmeAgentUser).withoutCsrfHeader();
    assertThat(uploadVia(agent, "/api/v1/agent/attachments", "a.txt", "x".getBytes()).getStatus())
        .isEqualTo(403);
  }

  @Test
  void aPendingUploadBelongsOnlyToItsUploader() {
    UUID mine = upload(acmeAgent, "notes.txt", "synthetic".getBytes());
    long id = acmeTicket().ticketId();
    // Another supporter cannot attach it, see it or download it.
    assertThatThrownBy(() -> tickets.addPublicReply(admin, id, "<p>x</p>", List.of(mine), null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("attachment_unavailable"));
    assertThatThrownBy(() -> attachments.download(admin, mine)).isInstanceOf(ApiException.class);
    // The uploader can preview it while pending.
    assertThat(attachments.download(acmeAgent, mine).filename()).isEqualTo("notes.txt");
  }

  @Test
  void aBoundAttachmentCannotBeBoundAgainSoANoteFileCannotMoveToAPublicReply() {
    long id = acmeTicket().ticketId();
    UUID img = upload(acmeAgent, "secret.png", TestImages.png(30, 30));
    notes.addNote(acmeAgent, id, "<p><img src=\"attachment:" + img + "\"></p>", List.of(img));
    assertThatThrownBy(
            () ->
                tickets.addPublicReply(
                    acmeAgent,
                    id,
                    "<p><img src=\"attachment:" + img + "\"></p>",
                    List.of(img),
                    null))
        .isInstanceOf(ApiException.class);
    // Referencing the note's image from a reply without attaching it just drops the image.
    tickets.addPublicReply(
        acmeAgent, id, "<p>hi <img src=\"attachment:" + img + "\"></p>", List.of(), null);
    TicketDetail d = queries.detail(acmeAgent, id);
    assertThat(d.publicComments())
        .extracting(c -> c.bodyHtml())
        .noneMatch(b -> b.contains(img.toString()));
    assertThat(d.publicComments()).allSatisfy(c -> assertThat(c.attachments()).isEmpty());
  }

  @Test
  void expiredPendingUploadsAreDeletedByTheJob() {
    UUID stale = upload(acmeAgent, "stale.txt", "old".getBytes());
    clock.advance(Duration.ofHours(23));
    UUID fresh = upload(acmeAgent, "fresh.txt", "new".getBytes());
    clock.advance(Duration.ofHours(2));
    cleanupJob.runOnce();
    assertThat(count(stale)).isZero();
    assertThat(count(fresh)).isOne();
    long id = acmeTicket().ticketId();
    assertThatThrownBy(
            () -> tickets.addPublicReply(acmeAgent, id, "<p>x</p>", List.of(stale), null))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void theNewTicketFormTakesUploadsAndAnswersOnlyWithTheReference() throws Exception {
    Browser customer = signedIn(acmeCustomerUser);
    MockHttpServletResponse up =
        uploadVia(customer, "/api/v1/portal/attachments", "error.png", TestImages.png(50, 50));
    assertThat(up.getStatus()).isEqualTo(201);
    JsonNode u = json(up.getContentAsString());
    assertThat(u.size()).isEqualTo(3); // id, filename, size - no URL, no type
    String body =
        "{\"requestTypeId\":"
            + generalType
            + ",\"subject\":\"Cannot print\",\"descriptionHtml\":\"<p>Error shown</p>\","
            + "\"impact\":\"JUST_ME\",\"urgency\":\"PARTLY\",\"attachmentIds\":[\""
            + u.get("id").asString()
            + "\"]}";
    MockHttpServletResponse r = customer.post("/api/v1/portal/tickets", body);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    JsonNode receipt = json(r.getContentAsString());
    assertThat(receipt.size()).isEqualTo(1);
    String reference = receipt.get("reference").asString();
    assertThat(reference).startsWith(activePrefixOf(acme) + "-");
    long ticketId = references.resolve(admin, reference).orElseThrow().ticketId();
    TicketDetail d = queries.detail(admin, ticketId);
    assertThat(d.source()).isEqualTo(TicketSource.PORTAL);
    assertThat(d.organisationId()).isEqualTo(acme);
    assertThat(d.reporter().userId()).isEqualTo(acmeCustomerUser.id());
    assertThat(d.publicComments().get(0).attachments()).hasSize(1);
    // The Alert type is not offered and cannot be used from the portal.
    assertThat(customer.get("/api/v1/portal/request-types").getContentAsString())
        .doesNotContain("Alert");
    String alert =
        body.replace("\"requestTypeId\":" + generalType, "\"requestTypeId\":" + alertType)
            .replace(u.get("id").asString(), UUID.randomUUID().toString());
    assertThat(customer.post("/api/v1/portal/tickets", alert).getStatus()).isEqualTo(400);
  }

  @Test
  void aCustomerCannotUseAnotherUsersUpload() throws Exception {
    UUID agentFile = upload(acmeAgent, "x.txt", "x".getBytes());
    Browser customer = signedIn(acmeCustomerUser);
    String body =
        "{\"requestTypeId\":"
            + generalType
            + ",\"subject\":\"s\",\"descriptionHtml\":\"<p>d</p>\",\"impact\":\"JUST_ME\","
            + "\"urgency\":\"PARTLY\",\"attachmentIds\":[\""
            + agentFile
            + "\"]}";
    assertThat(customer.post("/api/v1/portal/tickets", body).getStatus()).isEqualTo(400);
    Created c = acmeTicket();
    assertThat(c.ticketId()).isPositive();
  }

  /** Fix 2026-11-20: the real Unicode file name is stored and shown, %-encoding decoded once. */
  @Test
  void uploadedFileNamesKeepUnicodeAndArePercentDecodedOnce() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    String[][] cases = {
      {
        "Übersicht Drucker Märkte Q1 bis Q2 Jahr 2026.xls",
        "Übersicht Drucker Märkte Q1 bis Q2 Jahr 2026.xls"
      },
      {
        "Übersicht%20Drucker%20Märkte%20Q1%20bis%20Q2.xls", "Übersicht Drucker Märkte Q1 bis Q2.xls"
      },
      {"50% off.pdf", "50% off.pdf"},
      {"100%.txt", "100%.txt"},
      {"a%2520b.txt", "a%20b.txt"},
      {"..%2F..%2Fetc%2Fpasswd", "passwd"},
      {"x%E2%80%AEfdp.exe", "xfdp.exe"},
    };
    for (String[] c : cases) {
      MockHttpServletResponse r =
          uploadVia(agent, "/api/v1/agent/attachments", c[0], "x".getBytes());
      assertThat(r.getStatus()).as(c[0]).isEqualTo(201);
      String id = json(r.getContentAsString()).get("id").asString();
      assertThat(json(r.getContentAsString()).get("filename").asString()).as(c[0]).isEqualTo(c[1]);
      assertThat(
              jdbc.queryForObject(
                  "SELECT filename FROM attachment WHERE public_id = ?",
                  String.class,
                  UUID.fromString(id)))
          .isEqualTo(c[1]);
    }
  }

  private int count(UUID publicId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM attachment WHERE public_id = ?", Integer.class, publicId);
  }
}
