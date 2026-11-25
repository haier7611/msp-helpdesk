package com.ticketsystem.api.mail.ticketmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.signature.SignatureLogo;
import com.ticketsystem.api.signature.SignatureTemplate;
import com.ticketsystem.api.signature.clientdata.SignatureTemplateRepository;
import com.ticketsystem.api.ticket.Impact;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.TicketTestBase;
import com.ticketsystem.api.ticket.Urgency;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Bug fix 2026-11-22: the logo uploaded in Admin - Company signature is the inline {@code
 * cid:signature-logo} part of outgoing ticket mail (it used to be the generated placeholder), in
 * both E: modes. Real services, outbox and Postgres; synthetic data only.
 */
class SignatureLogoInMailIntegrationTest extends TicketTestBase {

  @DynamicPropertySource
  static void supportAddress(DynamicPropertyRegistry registry) {
    registry.add("app.mail.from-address", () -> "support@logo-mail.example.com");
  }

  @Autowired SignatureTemplateRepository templates;
  @Autowired SignatureLogo placeholder;
  @Autowired SupportAddresses addresses;

  SignatureTemplate original;

  @BeforeEach
  void remember() {
    original = templates.get(admin);
    jdbc.update("DELETE FROM outbox_message");
  }

  @AfterEach
  void restore() {
    templates.saveTemplate(admin, original, Instant.now());
    templates.saveLogo(admin, original.logo(), Instant.now());
  }

  static byte[] uploadedPng() throws Exception {
    BufferedImage img = new BufferedImage(60, 20, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setColor(new Color(0xC0392B));
    g.fillRect(0, 0, 60, 20);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  /** A public reply on a fresh agent-opened ticket; returns its inline signature-logo part. */
  Map<String, Object> replyLogoPart() {
    Created c =
        tickets.create(
            acmeAgent,
            new NewTicket(
                acme,
                acmeCustomerUser.id(),
                null,
                generalType,
                null,
                Impact.JUST_ME,
                Urgency.PARTLY,
                TicketSource.AGENT,
                "Logo check",
                "<p>Opening</p>",
                List.of(),
                null,
                null,
                null,
                null));
    tickets.addPublicReply(acmeAgent, c.ticketId(), "<p>Answer</p>", List.of(), null);
    Long mailId =
        jdbc.queryForObject(
            "SELECT max(id) FROM outbox_message WHERE ticket_id = ? AND kind = 'client_facing'",
            Long.class,
            c.ticketId());
    List<Map<String, Object>> parts =
        jdbc.queryForList(
            "SELECT content_type, inline, content FROM outbox_part WHERE outbox_message_id = ?"
                + " AND content_id = 'signature-logo'",
            mailId);
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).get("inline")).isEqualTo(true);
    assertThat(parts.get(0).get("content_type")).isEqualTo("image/png");
    return parts.get(0);
  }

  @Test
  void anUploadedLogoIsTheInlineLogoOfAPublicReply() throws Exception {
    byte[] png = uploadedPng();
    templates.saveLogo(admin, new SignatureTemplate.Logo(png, 150, 50), Instant.now());

    assertThat((byte[]) replyLogoPart().get("content")).isEqualTo(png);
  }

  @Test
  void withoutAnUploadThePlaceholderIsUsed() {
    templates.saveLogo(admin, null, Instant.now());

    assertThat((byte[]) replyLogoPart().get("content"))
        .isEqualTo(placeholder.png(templates.get(admin).brandColour()));
  }

  @Test
  void supportMailboxModeKeepsTheUploadedLogoAndSmallPrint() throws Exception {
    byte[] png = uploadedPng();
    templates.saveLogo(admin, new SignatureTemplate.Logo(png, 150, 50), Instant.now());
    SignatureTemplate t = templates.get(admin);
    templates.saveTemplate(
        admin,
        new SignatureTemplate(
            t.companyName(),
            t.address(),
            t.brandColour(),
            SignatureTemplate.EmailMode.SUPPORT_MAILBOX,
            "",
            t.logoAlt(),
            "Confidential - synthetic",
            null),
        Instant.now());

    assertThat((byte[]) replyLogoPart().get("content")).isEqualTo(png);
    SignatureTemplate resolved =
        new RendererSupporterSignatures(null, templates, placeholder, null, addresses)
            .template(admin);
    assertThat(resolved.logo()).isNotNull();
    assertThat(resolved.logo().png()).isEqualTo(png);
    assertThat(resolved.confidentialityLine()).isEqualTo("Confidential - synthetic");
    assertThat(resolved.supportEmail()).isNotBlank();
  }
}
