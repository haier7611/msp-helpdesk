package com.ticketsystem.api.mail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * SMTP delivery with Spring's {@link JavaMailSenderImpl} (Jakarta Mail / Angus Mail), password auth
 * over STARTTLS or implicit TLS. Every message is multipart/alternative (plain text, then HTML),
 * keeps the Message-ID stored at enqueue, and is marked {@code Auto-Submitted: auto-generated}.
 *
 * <p>Deliberately not a Spring bean of type JavaMailSender: Boot would otherwise add an SMTP health
 * check to /actuator/health, and an SMTP outage must not take the api down (mail just retries).
 */
class SmtpMailTransport implements MailTransport {

  static final String AUTO_SUBMITTED = "auto-generated";

  /** Asks Exchange/Outlook not to send out-of-office or auto-replies back. */
  static final String AUTO_RESPONSE_SUPPRESS = "OOF, AutoReply";

  private final JavaMailSenderImpl sender;
  private final String fromAddress;

  SmtpMailTransport(MailSettings settings) {
    this(
        requireEnabled(settings).fromAddress(),
        settings.host(),
        settings.port(),
        settings.security(),
        settings.username(),
        settings.password(),
        false,
        settings.connectTimeout(),
        settings.readTimeout());
  }

  /**
   * The admin-configured mailbox: From is the mailbox address; password or XOAUTH2 (the access
   * token in place of the password); the environment's timeouts.
   */
  SmtpMailTransport(SmtpAccount account, MailSettings settings) {
    this(
        account.fromAddress(),
        account.host(),
        account.port(),
        account.security(),
        account.username(),
        account.secret(),
        account.xoauth2(),
        settings.connectTimeout(),
        settings.readTimeout());
  }

  private SmtpMailTransport(
      String fromAddress,
      String host,
      int port,
      MailSettings.Security security,
      String username,
      String password,
      boolean xoauth2,
      Duration connectTimeout,
      Duration readTimeout) {
    this.fromAddress = fromAddress;
    this.sender = new JavaMailSenderImpl();
    boolean implicitTls = security == MailSettings.Security.TLS;
    String protocol = implicitTls ? "smtps" : "smtp";
    sender.setProtocol(protocol);
    sender.setHost(host);
    sender.setPort(port);
    sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
    if (username != null) {
      sender.setUsername(username);
      sender.setPassword(password);
    }
    Properties props = new Properties();
    String p = "mail." + protocol + ".";
    props.setProperty(p + "auth", String.valueOf(username != null));
    props.setProperty(p + "connectiontimeout", millis(connectTimeout));
    props.setProperty(p + "timeout", millis(readTimeout));
    props.setProperty(p + "writetimeout", millis(readTimeout));
    props.setProperty(p + "from", fromAddress);
    if (security == MailSettings.Security.STARTTLS) {
      // Required, not opportunistic: never fall back to plain text if STARTTLS is missing.
      props.setProperty(p + "starttls.enable", "true");
      props.setProperty(p + "starttls.required", "true");
    }
    if (security != MailSettings.Security.NONE) {
      props.setProperty(p + "ssl.checkserveridentity", "true");
      props.setProperty(p + "ssl.protocols", "TLSv1.3 TLSv1.2");
    }
    if (xoauth2) {
      // Microsoft 365 / Google SMTP AUTH with an OAuth2 access token (SASL XOAUTH2) only.
      props.setProperty(p + "auth.mechanisms", "XOAUTH2");
      props.setProperty(p + "auth.xoauth2.disable", "false");
      props.setProperty(p + "auth.login.disable", "true");
      props.setProperty(p + "auth.plain.disable", "true");
    }
    // Protocol tracing would print credentials (AUTH lines) and bodies: always off.
    props.setProperty("mail.debug", "false");
    sender.setJavaMailProperties(props);
  }

  private static MailSettings requireEnabled(MailSettings settings) {
    if (!settings.enabled()) {
      throw new IllegalArgumentException("SMTP is not configured");
    }
    return settings;
  }

  /** The From address this transport sends with (tests). */
  String fromAddress() {
    return fromAddress;
  }

  /** The Jakarta Mail session properties (tests). */
  Properties properties() {
    return sender.getJavaMailProperties();
  }

  /**
   * Connects, starts TLS as configured, signs in and quits without sending (the Test connection
   * button). Throws what a send would throw, for {@link SendFailure#classify}.
   */
  void testConnection() throws jakarta.mail.MessagingException {
    sender.testConnection();
  }

  @Override
  public void send(OutboxMessage m) throws MessagingException, UnsupportedEncodingException {
    sender.send(toMime(sender.getSession(), m));
  }

  MimeMessage toMime(Session session, OutboxMessage m)
      throws MessagingException, UnsupportedEncodingException {
    MimeMessage msg = new FixedIdMimeMessage(session, m.messageId());
    msg.setFrom(new InternetAddress(fromAddress, m.fromName(), StandardCharsets.UTF_8.name()));
    msg.setRecipients(Message.RecipientType.TO, addresses(m.to()));
    if (!m.cc().isEmpty()) {
      msg.setRecipients(Message.RecipientType.CC, addresses(m.cc()));
    }
    if (m.replyTo() != null) {
      msg.setReplyTo(addresses(List.of(m.replyTo())));
    }
    msg.setSubject(m.subject(), StandardCharsets.UTF_8.name());
    msg.setSentDate(new Date());
    msg.setHeader("Auto-Submitted", AUTO_SUBMITTED);
    msg.setHeader("X-Auto-Response-Suppress", AUTO_RESPONSE_SUPPRESS);
    m.extraHeaders()
        .forEach(
            (name, value) -> {
              try {
                msg.setHeader(name, value);
              } catch (MessagingException e) {
                throw new IllegalStateException(e);
              }
            });

    MimeBodyPart text = new MimeBodyPart();
    text.setText(m.textBody(), StandardCharsets.UTF_8.name(), "plain");
    MimeBodyPart html = new MimeBodyPart();
    html.setText(m.htmlBody(), StandardCharsets.UTF_8.name(), "html");
    MimeMultipart alternative = new MimeMultipart("alternative");
    alternative.addBodyPart(text);
    alternative.addBodyPart(html);
    List<MailPart> inline = m.parts().stream().filter(MailPart::inline).toList();
    List<MailPart> attached = m.parts().stream().filter(p -> !p.inline()).toList();
    if (inline.isEmpty() && attached.isEmpty()) {
      msg.setContent(alternative);
    } else {
      // multipart/mixed( multipart/related( alternative, inline images ), attachments ): the
      // signature logo and reply images show through cid: without loading remote content.
      MimeMultipart body = alternative;
      if (!inline.isEmpty()) {
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart alt = new MimeBodyPart();
        alt.setContent(alternative);
        related.addBodyPart(alt);
        for (MailPart p : inline) {
          related.addBodyPart(binaryPart(p, MimeBodyPart.INLINE));
        }
        body = related;
      }
      if (attached.isEmpty()) {
        msg.setContent(body);
      } else {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart main = new MimeBodyPart();
        main.setContent(body);
        mixed.addBodyPart(main);
        for (MailPart p : attached) {
          mixed.addBodyPart(binaryPart(p, MimeBodyPart.ATTACHMENT));
        }
        msg.setContent(mixed);
      }
    }
    msg.saveChanges();
    return msg;
  }

  private static MimeBodyPart binaryPart(MailPart p, String disposition) throws MessagingException {
    MimeBodyPart part = new MimeBodyPart();
    part.setDataHandler(
        new jakarta.activation.DataHandler(
            new jakarta.mail.util.ByteArrayDataSource(p.content(), p.contentType())));
    part.setDisposition(disposition);
    try {
      part.setFileName(
          jakarta.mail.internet.MimeUtility.encodeText(
              p.filename(), StandardCharsets.UTF_8.name(), null));
    } catch (UnsupportedEncodingException e) {
      part.setFileName("file");
    }
    if (p.contentId() != null) {
      part.setContentID("<" + p.contentId() + ">");
    }
    part.setHeader("Content-Transfer-Encoding", "base64");
    return part;
  }

  private static InternetAddress[] addresses(List<String> values) throws MessagingException {
    InternetAddress[] out = new InternetAddress[values.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = new InternetAddress(values.get(i), true);
    }
    return out;
  }

  private static String millis(Duration d) {
    return String.valueOf(d.toMillis());
  }

  /** Keeps the Message-ID generated at enqueue; Jakarta Mail would otherwise make a new one. */
  static final class FixedIdMimeMessage extends MimeMessage {
    private final String messageId;

    FixedIdMimeMessage(Session session, String messageId) {
      super(session);
      this.messageId = messageId;
    }

    @Override
    protected void updateMessageID() throws MessagingException {
      setHeader("Message-ID", messageId);
    }
  }
}
