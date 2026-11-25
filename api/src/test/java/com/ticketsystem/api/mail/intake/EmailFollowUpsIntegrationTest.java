package com.ticketsystem.api.mail.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.OutboxWorker;
import com.ticketsystem.api.mail.SystemEmail;
import com.ticketsystem.api.mail.TestGreenMail;
import com.ticketsystem.api.mail.intake.MailboxService.MailboxRequest;
import com.ticketsystem.api.mail.intake.poller.ImapPoller;
import com.ticketsystem.api.signature.SignatureProfile;
import com.ticketsystem.api.signature.SignatureRenderer;
import com.ticketsystem.api.signature.SignatureTemplate;
import com.ticketsystem.api.signature.clientdata.SignatureTemplateRepository;
import com.ticketsystem.api.sla.ticket.ClientNoticeMailer;
import com.ticketsystem.api.ticket.TicketService.ClientReply;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketTestBase;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.AttachmentRow;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.Visibility;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WP-07b end to end: GreenMail (IMAP and SMTP, in-process on 127.0.0.1), the real poller,
 * processor, ticket and attachment services, outbox worker and Postgres (Testcontainers). Every
 * mail, name and address is synthetic.
 */
class EmailFollowUpsIntegrationTest extends TicketTestBase {

  static final String SUPPORT = EmailInOutIntegrationTest.SUPPORT;
  static final String INTERNAL = "support+internal@acmemsp.example";
  static final String PASSWORD = EmailInOutIntegrationTest.IMAP_PASSWORD;

  /** The ports GreenMail actually bound (set in {@link #startMail()}). */
  static int imapPort;

  static int smtpPort;

  static GreenMail greenMail;
  static GreenMailUser supportUser;

  @Autowired ImapPoller poller;
  @Autowired MailboxService mailboxService;
  @Autowired OutboxWorker worker;
  @Autowired SignatureRenderer renderer;
  @Autowired SignatureTemplateRepository templates;
  @Autowired ClientNoticeMailer clientNotices;
  @Autowired EmailRulesService rulesService;
  @Autowired TransactionTemplate tx;

  long mailboxId;

  @BeforeAll
  static void startMail() {
    TestGreenMail.Started started =
        TestGreenMail.start(ServerSetup.PROTOCOL_IMAP, ServerSetup.PROTOCOL_SMTP);
    greenMail = started.greenMail();
    imapPort = started.port(ServerSetup.PROTOCOL_IMAP);
    smtpPort = started.port(ServerSetup.PROTOCOL_SMTP);
    supportUser = greenMail.setUser(SUPPORT, SUPPORT, PASSWORD);
  }

  @AfterAll
  static void stopMail() {
    greenMail.stop();
  }

  @BeforeEach
  void mailbox() throws Exception {
    greenMail.purgeEmailFromAllMailboxes();
    mailboxId = saveMailbox(null);
    jdbc.update("UPDATE mailbox SET enabled = FALSE WHERE id <> ?", mailboxId);
    jdbc.update("UPDATE email_settings SET supporter_reply_mode = 'INTERNAL_ONLY'");
    jdbc.update("DELETE FROM mail_block_rule");
    poller.pollOnce();
  }

  /** Other test classes share the database: never leave a sending mailbox behind. */
  @AfterEach
  void noSendingMailbox() {
    jdbc.update(
        "UPDATE mailbox SET smtp_host = NULL, smtp_port = NULL, smtp_security = NULL,"
            + " smtp_last_error = NULL, smtp_same_sign_in = TRUE, smtp_username = NULL,"
            + " smtp_secret_ciphertext = NULL, smtp_secret_key_id = NULL");
  }

  /** The support mailbox on this class's GreenMail; {@code smtpHost} null = receive only. */
  long saveMailbox(String smtpHost) {
    MailboxRequest r =
        new MailboxRequest(
            "Support",
            SUPPORT,
            true,
            "127.0.0.1",
            imapPort,
            "NONE",
            "INBOX",
            "PASSWORD",
            SUPPORT,
            PASSWORD,
            null,
            null,
            null,
            null,
            List.of(EmailInOutIntegrationTest.AUTHSERV),
            smtpHost,
            smtpHost == null ? null : smtpPort,
            smtpHost == null ? null : "NONE");
    List<Long> existing =
        jdbc.queryForList("SELECT id FROM mailbox WHERE address = ?", Long.class, SUPPORT);
    if (existing.isEmpty()) {
      return mailboxService.create(admin, r, "203.0.113.1").id();
    }
    mailboxService.update(admin, existing.get(0), r, "203.0.113.1");
    return existing.get(0);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  /** A multipart mail with DMARC pass from our trusted server. */
  MimeMessage multipart(String from, String to, String subject, MimeBodyPart... parts)
      throws Exception {
    MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
    String domain = from.substring(from.indexOf('@') + 1);
    m.addHeader(
        "Authentication-Results",
        EmailInOutIntegrationTest.AUTHSERV
            + "; spf=pass smtp.mailfrom="
            + domain
            + "; dkim=pass header.d="
            + domain
            + "; dmarc=pass header.from="
            + domain);
    m.setFrom(new InternetAddress(from, "Sam Example"));
    m.setRecipients(Message.RecipientType.TO, to);
    m.setSubject(subject);
    MimeMultipart mixed = new MimeMultipart("mixed");
    for (MimeBodyPart p : parts) {
      mixed.addBodyPart(p);
    }
    m.setContent(mixed);
    m.saveChanges();
    m.setHeader("Message-ID", "<" + UUID.randomUUID() + "@mail.example>");
    return m;
  }

  static MimeBodyPart html(String html) throws Exception {
    MimeBodyPart p = new MimeBodyPart();
    p.setText(html, "utf-8", "html");
    return p;
  }

  static MimeBodyPart related(String html, String cid, byte[] png) throws Exception {
    MimeMultipart related = new MimeMultipart("related");
    related.addBodyPart(html(html));
    MimeBodyPart image = file(null, "image/png", png);
    image.setDisposition(MimeBodyPart.INLINE);
    image.setContentID("<" + cid + ">");
    related.addBodyPart(image);
    MimeBodyPart wrapper = new MimeBodyPart();
    wrapper.setContent(related);
    return wrapper;
  }

  static MimeBodyPart file(String name, String type, byte[] bytes) throws Exception {
    MimeBodyPart p = new MimeBodyPart();
    p.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(bytes, type)));
    if (name != null) {
      p.setDisposition(MimeBodyPart.ATTACHMENT);
      p.setFileName(MimeUtility.encodeText(name, "utf-8", "B"));
    }
    return p;
  }

  void deliverAndPoll(MimeMessage m) {
    supportUser.deliver(m);
    poller.pollOnce();
  }

  static byte[] png() throws Exception {
    BufferedImage img = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(Color.BLUE);
    g.fillRect(0, 0, 20, 10);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  /** A JPEG with an EXIF (APP1) segment carrying a synthetic GPS marker string. */
  static byte[] jpegWithExif() throws Exception {
    BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(Color.ORANGE);
    g.fillRect(0, 0, 32, 32);
    g.dispose();
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
    ImageIO.write(img, "jpeg", jpeg);
    byte[] plain = jpeg.toByteArray();
    byte[] payload =
        concat(
            "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1),
            new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0},
            "GPS-SYNTHETIC-51.5007".getBytes(StandardCharsets.ISO_8859_1));
    int len = payload.length + 2;
    byte[] app1 =
        concat(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len}, payload);
    return concat(
        Arrays.copyOfRange(plain, 0, 2), app1, Arrays.copyOfRange(plain, 2, plain.length));
  }

  static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }

  static boolean contains(byte[] haystack, String needle) {
    return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
  }

  Map<String, Object> lastInbound() {
    return jdbc.queryForMap("SELECT * FROM inbound_mail ORDER BY id DESC LIMIT 1");
  }

  long ticketOf(Map<String, Object> row) {
    return ((Number) row.get("ticket_id")).longValue();
  }

  byte[] bytesOf(AttachmentRow a) throws Exception {
    try (InputStream in = attachments.download(admin, a.publicId()).content()) {
      return in.readAllBytes();
    }
  }

  String refOf(long ticketId) {
    return references.referencesOf(admin, ticketId).get(0).toString();
  }

  // ---------------------------------------------------------------------------------------------
  // 1. Inbound attachments

  @Test
  void aClientsEmailFilesBecomePublicTicketAttachmentsThroughTheUploadPipeline() throws Exception {
    byte[] exifJpeg = jpegWithExif();
    assertThat(contains(exifJpeg, "GPS-SYNTHETIC")).isTrue();
    deliverAndPoll(
        multipart(
            acmeCustomerUser.email(),
            SUPPORT,
            "Screen shows an error",
            related(
                "<p>See this:</p><p><img src=\"cid:shot1\" alt=\"screen\"></p>", "shot1", png()),
            file("photo.jpg", "image/jpeg", exifJpeg),
            file(
                "drawing.svg",
                "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8)),
            file(
                "invoice‮gpj.exe",
                "application/octet-stream",
                "synthetic text".getBytes(StandardCharsets.UTF_8))));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("CREATED");
    long t = ticketOf(row);

    List<AttachmentRow> files = attachments.listForTicket(admin, t, true);
    assertThat(files).hasSize(3).allMatch(a -> a.visibility() == Visibility.PUBLIC);
    // The same files in the public read model: a client reply is public.
    assertThat(attachments.listForTicket(acmeCustomer, t, false)).hasSize(3);

    AttachmentRow inline = files.stream().filter(AttachmentRow::inline).findFirst().orElseThrow();
    assertThat(inline.image()).isTrue();
    assertThat(inline.contentType()).isEqualTo("image/png");
    String body =
        jdbc.queryForObject(
            "SELECT body_html FROM public_comment WHERE ticket_id = ? ORDER BY id LIMIT 1",
            String.class,
            t);
    // The cid: image points at the stored file, and no index placeholder ("attachment:0") is
    // left. Match the whole placeholder up to its closing quote: a random public id starts with
    // "0" one time in 16, so a plain doesNotContain("attachment:0") failed on a correct body.
    assertThat(body)
        .contains("src=\"attachment:" + inline.publicId() + "\"")
        .doesNotContainPattern("attachment:\\d+\"");

    AttachmentRow photo =
        files.stream().filter(a -> a.filename().equals("photo.jpg")).findFirst().orElseThrow();
    assertThat(photo.contentType()).isEqualTo("image/jpeg");
    assertThat(photo.thumbnailKey()).isNotNull();
    byte[] stored = bytesOf(photo);
    assertThat(contains(stored, "GPS-SYNTHETIC")).isFalse();
    assertThat(contains(stored, "Exif")).isFalse();

    // The bidi override is gone, so the name cannot pose as "exe.jpg".
    AttachmentRow other = files.stream().filter(a -> !a.image()).findFirst().orElseThrow();
    assertThat(other.filename()).isEqualTo("invoicegpj.exe");
    assertThat(other.uploaderUserId()).isNull();

    assertThat(
            jdbc.queryForObject(
                "SELECT body_text FROM system_entry WHERE ticket_id = ? AND kind ="
                    + " 'EMAIL_ATTACHMENTS'",
                String.class,
                t))
        .startsWith("Not attached")
        .contains("drawing.svg - SVG files are not accepted");
  }

  @Test
  void aFileOver20MbIsListedAsNotAttached() throws Exception {
    byte[] big = new byte[21 * 1024 * 1024];
    Arrays.fill(big, (byte) 'a');
    deliverAndPoll(
        multipart(
            acmeCustomerUser.email(),
            SUPPORT,
            "Big log",
            html("<p>Log attached</p>"),
            file("server.log", "text/plain", big)));
    long t = ticketOf(lastInbound());
    assertThat(attachments.listForTicket(admin, t, true)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT body_text FROM system_entry WHERE ticket_id = ? AND kind ="
                    + " 'EMAIL_ATTACHMENTS'",
                String.class,
                t))
        .contains("server.log - larger than 20 MB");
  }

  @Test
  void aSupportersInternalReplyFileIsAnInternalAttachment() throws Exception {
    Created c = acmeTicket();
    deliverAndPoll(
        multipart(
            acmeAgentUser.email(),
            INTERNAL,
            "Re: [" + refOf(c.ticketId()) + "] (Internal)",
            html("<p>Log from the server</p>"),
            file("trace.txt", "text/plain", "synthetic trace".getBytes(StandardCharsets.UTF_8))));
    assertThat(lastInbound().get("outcome")).isEqualTo("NOTE");
    List<AttachmentRow> files = attachments.listForTicket(admin, c.ticketId(), true);
    assertThat(files)
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.visibility()).isEqualTo(Visibility.INTERNAL);
              assertThat(a.uploaderUserId()).isEqualTo(acmeAgentUser.id());
            });
    // Never in the public read model, never for the customer.
    assertThat(attachments.listForTicket(admin, c.ticketId(), false)).isEmpty();
    assertThat(attachments.listForTicket(acmeCustomer, c.ticketId(), true)).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // 1b. Image work budget (AUD PR #19 M3) and the remaining attachment cases (AUD L1, L4)

  /** A 1-bit single-colour PNG: a few KB of file, {@code side} x {@code side} pixels decoded. */
  static byte[] hugePng(int side) throws Exception {
    BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_BINARY);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  String attachmentsNote(long ticketId) {
    return jdbc.queryForObject(
        "SELECT body_text FROM system_entry WHERE ticket_id = ? AND kind = 'EMAIL_ATTACHMENTS'",
        String.class,
        ticketId);
  }

  static int occurrences(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      n++;
    }
    return n;
  }

  @Test
  void thirtySmallFileHugeImagesAreRefusedFromTheHeaderWithinSeconds() throws Exception {
    byte[] bomb = hugePng(5000);
    assertThat(bomb.length).isLessThan(200 * 1024);
    MimeBodyPart[] parts = new MimeBodyPart[31];
    parts[0] = html("<p>Thirty screenshots</p>");
    for (int i = 1; i <= 30; i++) {
      parts[i] = file("shot-" + i + ".png", "image/png", bomb);
    }
    supportUser.deliver(multipart(acmeCustomerUser.email(), SUPPORT, "Image bomb", parts));

    long start = System.nanoTime();
    poller.pollOnce();
    long millis = (System.nanoTime() - start) / 1_000_000;

    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("CREATED");
    long t = ticketOf(row);
    // 25 MP each: over the 12 MP email cap, refused from the header without a decode.
    assertThat(millis).isLessThan(10_000);
    assertThat(attachments.listForTicket(admin, t, true)).isEmpty();
    String note = attachmentsNote(t);
    // Refused files count towards the 20-file cap: 20 refused by the limit, 10 over the cap.
    assertThat(occurrences(note, "not attached (limit): image larger than 12 MP")).isEqualTo(20);
    assertThat(occurrences(note, "more than 20 files in one email")).isEqualTo(10);
  }

  @Test
  void imagesPastTheBudgetOfOneEmailAreNotDecoded() throws Exception {
    MimeBodyPart[] parts = new MimeBodyPart[13];
    parts[0] = html("<p>Twelve small images</p>");
    for (int i = 1; i <= 12; i++) {
      parts[i] = file("small-" + i + ".png", "image/png", png());
    }
    deliverAndPoll(multipart(acmeCustomerUser.email(), SUPPORT, "Many images", parts));
    long t = ticketOf(lastInbound());
    assertThat(attachments.listForTicket(admin, t, true))
        .hasSize(InboundAttachments.MAIL_MAX_IMAGES);
    assertThat(occurrences(attachmentsNote(t), "not attached (limit): too many or too large"))
        .isEqualTo(12 - InboundAttachments.MAIL_MAX_IMAGES);
  }

  @Test
  void imagesPastTheBudgetOfOnePollRoundAreNotDecoded() throws Exception {
    int perMail = InboundAttachments.MAIL_MAX_IMAGES;
    int mails = InboundAttachments.ROUND_MAX_IMAGES / perMail + 1;
    for (int m = 0; m < mails; m++) {
      MimeBodyPart[] parts = new MimeBodyPart[perMail + 1];
      parts[0] = html("<p>Batch " + m + "</p>");
      for (int i = 1; i <= perMail; i++) {
        parts[i] = file("batch-" + m + "-" + i + ".png", "image/png", png());
      }
      supportUser.deliver(multipart(acmeCustomerUser.email(), SUPPORT, "Batch " + m, parts));
    }
    poller.pollOnce(); // one round for all of them
    long last = ticketOf(lastInbound());
    assertThat(attachments.listForTicket(admin, last, true)).isEmpty();
    assertThat(occurrences(attachmentsNote(last), "not attached (limit): too many images arriving"))
        .isEqualTo(perMail);
    // The next round has a fresh budget.
    deliverAndPoll(
        multipart(
            acmeCustomerUser.email(),
            SUPPORT,
            "Next round",
            html("<p>One more</p>"),
            file("later.png", "image/png", png())));
    assertThat(attachments.listForTicket(admin, ticketOf(lastInbound()), true)).hasSize(1);
  }

  @Test
  void anEmailWithMoreThan20FilesStoresTwentyAndListsTheRest() throws Exception {
    MimeBodyPart[] parts = new MimeBodyPart[23];
    parts[0] = html("<p>Logs</p>");
    for (int i = 1; i <= 22; i++) {
      parts[i] =
          file("log-" + i + ".txt", "text/plain", ("line " + i).getBytes(StandardCharsets.UTF_8));
    }
    deliverAndPoll(multipart(acmeCustomerUser.email(), SUPPORT, "Lots of logs", parts));
    long t = ticketOf(lastInbound());
    assertThat(attachments.listForTicket(admin, t, true)).hasSize(20);
    String note = attachmentsNote(t);
    assertThat(note).contains("log-21.txt - more than 20 files in one email");
    assertThat(note).contains("log-22.txt - more than 20 files in one email");
    assertThat(occurrences(note, "more than 20 files")).isEqualTo(2);
  }

  @Test
  void anInlineImageUsedOnlyByTheQuotedHistoryIsNotStoredAgain() throws Exception {
    Created c = acmeTicket();
    MimeMultipart related = new MimeMultipart("related");
    related.addBodyPart(
        html(
            "<div><p>Here is the new screenshot:</p><p><img src=\"cid:new1\" alt=\"new\"></p></div>"
                + "<div class=\"gmail_quote\"><div>On Mon, Support wrote:</div><blockquote>"
                + "<p>Earlier:</p><p><img src=\"cid:old1\" alt=\"old\"></p></blockquote></div>"));
    for (String cid : List.of("new1", "old1")) {
      MimeBodyPart image = file(null, "image/png", png());
      image.setDisposition(MimeBodyPart.INLINE);
      image.setContentID("<" + cid + ">");
      related.addBodyPart(image);
    }
    MimeBodyPart wrapper = new MimeBodyPart();
    wrapper.setContent(related);
    deliverAndPoll(
        multipart(acmeCustomerUser.email(), SUPPORT, "Re: [" + refOf(c.ticketId()) + "]", wrapper));
    assertThat(lastInbound().get("outcome")).isEqualTo("CLIENT_REPLY");
    List<AttachmentRow> files = attachments.listForTicket(admin, c.ticketId(), true);
    assertThat(files).singleElement().satisfies(a -> assertThat(a.inline()).isTrue());
    String body =
        jdbc.queryForObject(
            "SELECT body_html FROM public_comment WHERE ticket_id = ? ORDER BY id DESC LIMIT 1",
            String.class,
            c.ticketId());
    assertThat(body).contains("attachment:" + files.get(0).publicId());
  }

  @Test
  void aTriageTicketsEmailFilesArePublicFilesOfItsDescription() throws Exception {
    String stranger =
        "someone@unknown-" + UUID.randomUUID().toString().substring(0, 8) + ".example";
    deliverAndPoll(
        multipart(
            stranger,
            SUPPORT,
            "Help please",
            html("<p>Report attached</p>"),
            file("report.txt", "text/plain", "synthetic".getBytes(StandardCharsets.UTF_8))));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("TRIAGE");
    long t = ticketOf(row);
    assertThat(attachments.listForTicket(admin, t, true))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.visibility()).isEqualTo(Visibility.PUBLIC);
              assertThat(a.uploaderUserId()).isNull();
            });
    // Supporters see it in the public read model; it is bound to the description comment.
    assertThat(attachments.listForTicket(admin, t, false)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM attachment a JOIN public_comment c ON c.id ="
                    + " a.public_comment_id WHERE c.ticket_id = ?",
                Integer.class,
                t))
        .isOne();
  }

  @Test
  void aRestoredBlockedMailsFilesAreTheSendersNotTheAdmins() throws Exception {
    String sender = "blocked@restore-" + UUID.randomUUID().toString().substring(0, 8) + ".example";
    jdbc.update(
        "INSERT INTO mail_block_rule (kind, value, created_at) VALUES ('ADDRESS', ?, now())",
        sender);
    deliverAndPoll(
        multipart(
            sender,
            SUPPORT,
            "Blocked with a file",
            html("<p>See file</p>"),
            file("notes.txt", "text/plain", "synthetic".getBytes(StandardCharsets.UTF_8))));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("BLOCKED");
    long t = rulesService.restore(admin, ((Number) row.get("id")).longValue());
    assertThat(attachments.listForTicket(admin, t, true))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.uploaderUserId()).isNull();
              assertThat(a.visibility()).isEqualTo(Visibility.PUBLIC);
            });
  }

  // ---------------------------------------------------------------------------------------------
  // 2. Outbound through the configured mailbox

  @Test
  void theConfiguredMailboxSendsOutboundMailAndNotesItsErrors() throws Exception {
    // ADR-034: a reserved test domain (.test) would never go through the mailbox; use a synthetic
    // address under the IANA-reserved example.com instead.
    jdbc.update(
        "UPDATE app_user SET email = ? WHERE id = ?",
        "sam-" + UUID.randomUUID() + "@client.example.com",
        acmeCustomerUser.id());
    Created c = acmeTicket();
    tickets.addPublicReply(
        acmeAgent, c.ticketId(), "<p>Sent through the mailbox</p>", List.of(), null);
    String messageId =
        jdbc.queryForObject(
            "SELECT message_id FROM outbox_message WHERE ticket_id = ? AND kind = 'client_facing'"
                + " ORDER BY id DESC LIMIT 1",
            String.class,
            c.ticketId());

    // Receive-only mailbox and no SMTP_HOST in this context: nothing can send, nothing is tried.
    assertThat(worker.runOnce()).isZero();
    assertThat(statusOf(messageId)).isEqualTo("queued");

    saveMailbox("127.0.0.1");
    worker.runOnce();
    assertThat(statusOf(messageId)).isEqualTo("sent");
    MimeMessage received =
        Arrays.stream(greenMail.getReceivedMessages())
            .filter(m -> messageId.equals(header(m, "Message-ID")))
            .findFirst()
            .orElseThrow();
    assertThat(((InternetAddress) received.getFrom()[0]).getAddress()).isEqualTo(SUPPORT);
    assertThat(
            jdbc.queryForObject(
                "SELECT smtp_last_error FROM mailbox WHERE id = ?", String.class, mailboxId))
        .isNull();
    assertThat(mailboxService.get(admin, mailboxId).sending()).isTrue();

    // A stored secret that no longer decrypts: the round waits (no fallback), the error is noted.
    jdbc.update("UPDATE mailbox SET secret_key_id = 'retired-key' WHERE id = ?", mailboxId);
    tickets.addPublicReply(acmeAgent, c.ticketId(), "<p>Second</p>", List.of(), null);
    assertThat(worker.runOnce()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT smtp_last_error FROM mailbox WHERE id = ?", String.class, mailboxId))
        .isNotBlank();

    // AUD L3: the next rounds back off quietly (no attempt, no write) instead of every 15 s.
    jdbc.update("UPDATE mailbox SET smtp_last_error = NULL WHERE id = ?", mailboxId);
    assertThat(worker.runOnce()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT smtp_last_error FROM mailbox WHERE id = ?", String.class, mailboxId))
        .isNull();

    // Fixing the mailbox ends the back-off at once: the queued reply goes out on the next round.
    saveMailbox("127.0.0.1"); // re-enters the secret
    assertThat(worker.runOnce()).isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE ticket_id = ? AND status = 'queued'",
                Integer.class,
                c.ticketId()))
        .isZero();
  }

  String statusOf(String messageId) {
    return jdbc.queryForObject(
        "SELECT status FROM outbox_message WHERE message_id = ?", String.class, messageId);
  }

  static String header(MimeMessage m, String name) {
    try {
      String[] v = m.getHeader(name);
      return v == null ? null : v[0];
    } catch (Exception e) {
      return null;
    }
  }

  @Test
  void plainTextSmtpToARemoteHostIsRefused() {
    MailboxRequest r =
        new MailboxRequest(
            "Support",
            SUPPORT,
            true,
            "127.0.0.1",
            imapPort,
            "NONE",
            "INBOX",
            "PASSWORD",
            SUPPORT,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            "smtp.remote.example",
            25,
            "NONE");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> mailboxService.update(admin, mailboxId, r, "203.0.113.1"))
        .hasMessageContaining("Plain-text SMTP");
  }

  // ---------------------------------------------------------------------------------------------
  // 3. Signature through WP-11a's renderer

  @Test
  void publicRepliesCarryTheRendererSignatureFullThenShort() {
    jdbc.update(
        "INSERT INTO supporter_profile (user_id, job_title, phone, mobile, free_line, updated_at)"
            + " VALUES (?, 'Service Desk Engineer', '+00 000 000 000', '', 'M365 <b>specialist</b>',"
            + " now()) ON CONFLICT (user_id) DO UPDATE SET job_title = EXCLUDED.job_title, phone ="
            + " EXCLUDED.phone, free_line = EXCLUDED.free_line",
        acmeAgentUser.id());
    Created c = acmeTicket();
    tickets.addPublicReply(acmeAgent, c.ticketId(), "<p>One</p>", List.of(), null);
    tickets.addPublicReply(acmeAgent, c.ticketId(), "<p>Two</p>", List.of(), null);
    List<Map<String, Object>> mails =
        jdbc.queryForList(
            "SELECT id, html_body, text_body FROM outbox_message WHERE ticket_id = ? AND kind ="
                + " 'client_facing' ORDER BY id",
            c.ticketId());
    Map<String, Object> first = mails.get(mails.size() - 2);
    Map<String, Object> second = mails.get(mails.size() - 1);

    SignatureTemplate template = templates.get(admin);
    SignatureRenderer.Rendered expected =
        renderer.render(
            new SignatureProfile(
                acmeAgentUser.name(),
                "Service Desk Engineer",
                "+00 000 000 000",
                "",
                "M365 <b>specialist</b>",
                acmeAgentUser.email()),
            template,
            SignatureRenderer.LogoMode.EMAIL);
    assertThat((String) first.get("html_body"))
        .contains(expected.fullHtml())
        .contains("mailto:" + acmeAgentUser.email())
        .contains("M365 &lt;b&gt;specialist&lt;/b&gt;");
    assertThat((String) first.get("text_body")).contains(expected.fullText());
    assertThat(
            jdbc.queryForObject(
                "SELECT content_id FROM outbox_part WHERE outbox_message_id = ?",
                String.class,
                first.get("id")))
        .isEqualTo(SignatureRenderer.LOGO_CID);
    // ADR-015 change 2026-11-20: the second reply carries the full signature and logo too.
    assertThat((String) second.get("html_body"))
        .contains(expected.fullHtml())
        .contains("cid:" + SignatureRenderer.LOGO_CID);
    assertThat((String) second.get("text_body")).contains(expected.fullText());
    assertThat(
            jdbc.queryForObject(
                "SELECT content_id FROM outbox_part WHERE outbox_message_id = ?",
                String.class,
                second.get("id")))
        .isEqualTo(SignatureRenderer.LOGO_CID);
  }

  // ---------------------------------------------------------------------------------------------
  // 4. AUD-13 Lows

  @Test
  void wp09ClientNoticesHonourSuppressionAndReplyToTheConfiguredMailbox() {
    Created suppressed = acmeTicket();
    jdbc.update(
        "INSERT INTO ticket_mail_suppression (ticket_id, reason, created_at) VALUES (?,"
            + " 'automated sender', now())",
        suppressed.ticketId());
    Created normal = acmeTicket();
    AccessScope system = AccessScope.system("test");
    Boolean sentSuppressed =
        tx.execute(
            s ->
                clientNotices.send(
                    system,
                    SystemEmail.CLIENT_TICKET_CLOSED,
                    List.of(acmeCustomerUser.email()),
                    suppressed.ticketId(),
                    refOf(suppressed.ticketId()),
                    "Subject"));
    Boolean sentNormal =
        tx.execute(
            s ->
                clientNotices.send(
                    system,
                    SystemEmail.CLIENT_TICKET_CLOSED,
                    List.of(acmeCustomerUser.email()),
                    normal.ticketId(),
                    refOf(normal.ticketId()),
                    "Subject"));
    assertThat(sentSuppressed).isFalse();
    assertThat(sentNormal).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT reply_to FROM outbox_message WHERE ticket_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                normal.ticketId()))
        .isEqualTo(SUPPORT);
  }

  @Test
  void aBlockedSendersMailOver30MbStaysBlocked() throws Exception {
    String sender = "promo@blocked-" + UUID.randomUUID().toString().substring(0, 8) + ".example";
    jdbc.update(
        "INSERT INTO mail_block_rule (kind, value, created_at) VALUES ('ADDRESS', ?, now())",
        sender);
    int mb = 1024 * 1024;
    StringBuilder body = new StringBuilder();
    while (body.length() < 3 * mb) {
      body.append("Synthetic filler text for a large message.\r\n");
    }
    supportUser.deliver(
        new MimeMessage(
            Session.getInstance(new Properties()),
            new ByteArrayInputStream(
                EmailInOutIntegrationTest.mail(sender, "Huge offer", body.toString())
                    .getBytes(StandardCharsets.UTF_8))));
    // A 2 MB cap stands in for the 30 MB one.
    poller.pollOnce("test-big", new ImapPoller.RoundLimits(200, 10L * mb, 2L * mb));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("BLOCKED");
    assertThat(row.get("block_rule")).isEqualTo(sender);
    assertThat(row.get("from_address")).isEqualTo(sender);
    assertThat(row.get("subject")).isEqualTo("Huge offer");
    assertThat(row.get("ticket_id")).isNull();
  }

  @Test
  void aTriageTicketsAssigneeHearsTheClientReplied() {
    Created t = triageTicket();
    tickets.assign(admin, t.ticketId(), acmeAgentUser.id());
    tickets.addClientReply(
        admin,
        t.ticketId(),
        new ClientReply(
            null, "unknown.sender@public-mail.example", "<p>More</p>", true, null, null));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE user_id = ? AND ticket_id = ? AND kind ="
                    + " 'CLIENT_REPLIED'",
                Integer.class,
                acmeAgentUser.id(),
                t.ticketId()))
        .isOne();
    // The internal email replies to the configured mailbox's plus address (AUD-13 Low).
    assertThat(
            jdbc.queryForObject(
                "SELECT reply_to FROM outbox_message WHERE ticket_id = ? AND kind = 'internal'"
                    + " AND to_addresses = ? ORDER BY id DESC LIMIT 1",
                String.class,
                t.ticketId(),
                acmeAgentUser.email()))
        .isEqualTo(INTERNAL);
  }
}
