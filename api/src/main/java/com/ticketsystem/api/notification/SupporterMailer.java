package com.ticketsystem.api.notification;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AuthProperties;
import com.ticketsystem.api.mail.MailKind;
import com.ticketsystem.api.mail.MailOutbox;
import com.ticketsystem.api.mail.OutboundEmail;
import com.ticketsystem.api.mail.SystemEmail;
import com.ticketsystem.api.mail.SystemEmailRenderer;
import com.ticketsystem.api.mail.ticketmail.SupportAddresses;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent-only emails (kind INTERNAL, "(Internal)" in the subject): notifications and the daily
 * digest. Reply-To is the plus-addressed internal mailbox ({@code support+internal@}) of the
 * support address - the admin-configured mailbox, else the bootstrap {@code MAIL_FROM_ADDRESS} - so
 * a supporter replying to a notification can never reach a client (such replies are stored as
 * internal notes). Queued in the caller's transaction.
 */
@Component
public class SupporterMailer {

  private final SystemEmailRenderer renderer;
  private final MailOutbox outbox;
  private final SupportAddresses addresses;
  private final String baseUrl;

  public SupporterMailer(
      SystemEmailRenderer renderer,
      MailOutbox outbox,
      SupportAddresses addresses,
      AuthProperties auth) {
    this.renderer = renderer;
    this.outbox = outbox;
    this.addresses = addresses;
    String base = auth.publicBaseUrl().toString();
    this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  public String ticketLink(long ticketId) {
    return baseUrl + "/agent/tickets/" + ticketId;
  }

  public String queueLink() {
    return baseUrl + "/agent";
  }

  /** One notification email about a ticket. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void sendNotification(
      AccessScope scope,
      String to,
      String recipientName,
      String reference,
      long ticketId,
      String headline,
      String ticketSubject) {
    SystemEmailRenderer.Rendered r =
        renderer.render(
            SystemEmail.SUPPORTER_NOTIFICATION,
            Map.of(
                "recipientName", blankTo(recipientName, "there"),
                "headline", headline,
                "ticketSubject", blankTo(ticketSubject, "(no subject)"),
                "link", ticketLink(ticketId)));
    OutboundEmail.Builder b =
        OutboundEmail.builder(MailKind.INTERNAL)
            .to(to)
            .subject(headline)
            .body(r.html(), r.text())
            .ticketId(ticketId);
    if (reference != null) {
      b.reference(reference);
    }
    addresses.internal(scope).ifPresent(b::replyTo);
    outbox.enqueue(b.build());
  }

  /** The daily digest. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void sendDigest(
      AccessScope scope, String to, String recipientName, String summary, String items) {
    SystemEmailRenderer.Rendered r =
        renderer.render(
            SystemEmail.DAILY_DIGEST,
            Map.of(
                "recipientName", blankTo(recipientName, "there"),
                "summary", summary,
                "items", blankTo(items, "Nothing to show."),
                "link", queueLink()));
    OutboundEmail.Builder b =
        OutboundEmail.builder(MailKind.INTERNAL)
            .to(to)
            .subject(r.subject())
            .body(r.html(), r.text());
    addresses.internal(scope).ifPresent(b::replyTo);
    outbox.enqueue(b.build());
  }

  private static String blankTo(String s, String fallback) {
    return s == null || s.isBlank() ? fallback : s;
  }
}
