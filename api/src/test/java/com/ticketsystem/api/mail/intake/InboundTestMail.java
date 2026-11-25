package com.ticketsystem.api.mail.intake;

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
import java.io.ByteArrayOutputStream;
import java.util.Properties;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Synthetic inbound mails for the ADR-039 attachment job tests: a multipart mail with DMARC pass
 * from the trusted test server, HTML parts, inline images and files. The same shapes as the WP-07b
 * tests build.
 */
final class InboundTestMail {

  private InboundTestMail() {}

  static MimeMessage multipart(String from, String to, String subject, MimeBodyPart... parts)
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

  static byte[] png() throws Exception {
    return image("png", BufferedImage.TYPE_INT_RGB);
  }

  static byte[] jpeg() throws Exception {
    return image("jpeg", BufferedImage.TYPE_INT_RGB);
  }

  private static byte[] image(String format, int type) throws Exception {
    BufferedImage img = new BufferedImage(40, 20, type);
    Graphics2D g = img.createGraphics();
    g.setColor(Color.BLUE);
    g.fillRect(0, 0, 40, 20);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, format, out);
    return out.toByteArray();
  }
}
