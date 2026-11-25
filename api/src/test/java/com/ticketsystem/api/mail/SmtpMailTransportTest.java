package com.ticketsystem.api.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SmtpMailTransportTest {

  private static final String SECRET = "Pw-SYNTHETIC-never-logged-77";

  private static MailSettings settings(int port, String security, String user, String password) {
    return MailSettings.from(
        new MailProperties(
            "Example MSP Support",
            "support@helpdesk.example",
            null,
            new MailProperties.Smtp(
                "127.0.0.1",
                port,
                security,
                user,
                password,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)),
            null));
  }

  private static OutboxMessage message() {
    return new OutboxMessage(
        7L,
        "<11111111-2222-3333-4444-555555555555@helpdesk.example>",
        MailKind.CLIENT_FACING,
        null,
        List.of("sam@client.example"),
        List.of("lee@client.example"),
        "Alex Tran (Example MSP Support)",
        "support@helpdesk.example",
        "[NWD-001] Outlook keeps asking for a password",
        "<p>Héllo</p>",
        "Héllo",
        Map.of("In-Reply-To", "<prev@helpdesk.example>"),
        false,
        null,
        1,
        List.of());
  }

  @Test
  void buildsMultipartAlternativeWithStoredMessageIdAndAutoSubmitted() throws Exception {
    SmtpMailTransport transport = new SmtpMailTransport(settings(2525, "none", null, null));
    MimeMessage mime = transport.toMime(Session.getInstance(new Properties()), message());

    // Round-trip through the wire format so we test what is actually sent.
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    mime.writeTo(raw);
    MimeMessage parsed =
        new MimeMessage(
            Session.getInstance(new Properties()),
            new java.io.ByteArrayInputStream(raw.toByteArray()));

    assertThat(parsed.getMessageID())
        .isEqualTo("<11111111-2222-3333-4444-555555555555@helpdesk.example>");
    assertThat(parsed.getHeader("Auto-Submitted", null)).isEqualTo("auto-generated");
    assertThat(parsed.getHeader("X-Auto-Response-Suppress", null)).isEqualTo("OOF, AutoReply");
    assertThat(parsed.getHeader("In-Reply-To", null)).isEqualTo("<prev@helpdesk.example>");
    assertThat(parsed.getSubject()).isEqualTo("[NWD-001] Outlook keeps asking for a password");
    assertThat(parsed.getFrom()[0].toString())
        .contains("support@helpdesk.example")
        .contains("Alex Tran");
    assertThat(parsed.getReplyTo()[0].toString()).isEqualTo("support@helpdesk.example");
    assertThat(parsed.getAllRecipients()).hasSize(2);
    assertThat(parsed.getContentType()).startsWith("multipart/alternative");

    MimeMultipart mp = (MimeMultipart) parsed.getContent();
    assertThat(mp.getCount()).isEqualTo(2);
    assertThat(mp.getBodyPart(0).getContentType()).startsWith("text/plain").contains("UTF-8");
    assertThat(mp.getBodyPart(1).getContentType()).startsWith("text/html").contains("UTF-8");
    assertThat(mp.getBodyPart(0).getContent()).isEqualTo("Héllo");
    assertThat(new String(raw.toByteArray(), StandardCharsets.US_ASCII)).doesNotContain("Héllo");
  }

  @Test
  void inlineLogoAndAttachmentsBuildRelatedInsideMixed() throws Exception {
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    OutboxMessage m =
        message()
            .withParts(
                List.of(
                    new MailPart("signature-logo", "logo.png", "image/png", true, png),
                    new MailPart(
                        null, "report\u202Efdp.exe", "application/pdf", false, new byte[] {9})));
    SmtpMailTransport transport = new SmtpMailTransport(settings(2525, "none", null, null));
    MimeMessage mime = transport.toMime(Session.getInstance(new Properties()), m);
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    mime.writeTo(raw);
    MimeMessage parsed =
        new MimeMessage(
            Session.getInstance(new Properties()),
            new java.io.ByteArrayInputStream(raw.toByteArray()));

    assertThat(parsed.getContentType()).startsWith("multipart/mixed");
    MimeMultipart mixed = (MimeMultipart) parsed.getContent();
    assertThat(mixed.getCount()).isEqualTo(2);
    MimeMultipart related = (MimeMultipart) mixed.getBodyPart(0).getContent();
    assertThat(related.getContentType()).startsWith("multipart/related");
    assertThat(((MimeMultipart) related.getBodyPart(0).getContent()).getContentType())
        .startsWith("multipart/alternative");
    jakarta.mail.internet.MimeBodyPart logo =
        (jakarta.mail.internet.MimeBodyPart) related.getBodyPart(1);
    assertThat(logo.getContentID()).isEqualTo("<signature-logo>");
    assertThat(logo.getDisposition()).isEqualTo("inline");
    assertThat(logo.getContentType()).startsWith("image/png");
    assertThat(logo.getInputStream().readAllBytes()).isEqualTo(png);
    jakarta.mail.BodyPart file = mixed.getBodyPart(1);
    assertThat(file.getDisposition()).isEqualTo("attachment");
    // Bidi override characters never reach the recipient's file name (AUD-10 Low).
    assertThat(jakarta.mail.internet.MimeUtility.decodeText(file.getFileName()))
        .isEqualTo("report_fdp.exe");
  }

  @Test
  void anUnreachableServerIsATemporaryFailureAndThePasswordIsNeverLogged(CapturedOutput output)
      throws Exception {
    int closedPort = TestPorts.free(); // nothing listens there
    SmtpMailTransport transport = new SmtpMailTransport(settings(closedPort, "tls", "u", SECRET));

    assertThatThrownBy(() -> transport.send(message()))
        .satisfies(
            e -> {
              SendFailure f = SendFailure.classify(e);
              assertThat(f.permanent()).isFalse();
              assertThat(f.summary()).doesNotContain(SECRET);
            });
    assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain("Héllo");
  }

  @Test
  void refusesToBuildWithoutSettings() {
    MailSettings disabled = MailSettings.from(new MailProperties(null, null, null, null, null));
    assertThatThrownBy(() -> new SmtpMailTransport(disabled))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aConfiguredMailboxSendsFromItsOwnAddressWithXoauth2Only() {
    SmtpMailTransport transport =
        new SmtpMailTransport(
            new SmtpAccount(
                7,
                "support@mailbox.example",
                "smtp.mailbox.example",
                587,
                MailSettings.Security.STARTTLS,
                "support@mailbox.example",
                "synthetic-access-token",
                true),
            settings(587, "starttls", null, null));
    assertThat(transport.fromAddress()).isEqualTo("support@mailbox.example");
    assertThat(transport.properties())
        .containsEntry("mail.smtp.auth", "true")
        .containsEntry("mail.smtp.auth.mechanisms", "XOAUTH2")
        .containsEntry("mail.smtp.auth.plain.disable", "true")
        .containsEntry("mail.smtp.starttls.required", "true")
        .containsEntry("mail.smtp.from", "support@mailbox.example");
    assertThat(
            new SmtpAccount(7, "a@b.example", "h", 1, MailSettings.Security.TLS, "u", "tok", true)
                .toString())
        .doesNotContain("tok");
  }
}
