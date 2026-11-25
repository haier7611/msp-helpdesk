package com.ticketsystem.api.sla.ticket;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.MailKind;
import com.ticketsystem.api.mail.MailOutbox;
import com.ticketsystem.api.mail.OutboundEmail;
import com.ticketsystem.api.mail.SystemEmail;
import com.ticketsystem.api.mail.SystemEmailRenderer;
import com.ticketsystem.api.mail.inbound.AutomatedMailDetector;
import com.ticketsystem.api.mail.ticketmail.SupportAddresses;
import com.ticketsystem.api.mail.ticketmail.TicketMailComposer;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two client emails owned by the SLA timers in the client email matrix: the Pending-on-client
 * reminder and the auto-close "Closed" notice. (The "Resolved" email, also after an auto-resolve,
 * is sent by the ticket mail listener from the {@code StatusChanged -> RESOLVED} event.) No SLA
 * information, ever. Never sent to automated senders (no-reply, mailer-daemon), nor on a ticket
 * opened by a bulk or automated sender ({@code ticket_mail_suppression}). Reply-To is the support
 * address (the admin-configured mailbox, else {@code MAIL_FROM_ADDRESS}). Queued in the caller's
 * transaction.
 */
@Component
public class ClientNoticeMailer {

  private final SystemEmailRenderer renderer;
  private final MailOutbox outbox;
  private final SupportAddresses addresses;
  private final TicketMailComposer ticketMail;

  public ClientNoticeMailer(
      SystemEmailRenderer renderer,
      MailOutbox outbox,
      SupportAddresses addresses,
      TicketMailComposer ticketMail) {
    this.renderer = renderer;
    this.outbox = outbox;
    this.addresses = addresses;
    this.ticketMail = ticketMail;
  }

  /** The addresses that may receive an automatic client email. */
  static List<String> deliverable(List<String> recipients) {
    return recipients.stream()
        .filter(a -> a != null && !a.isBlank())
        .filter(a -> !AutomatedMailDetector.isAutomatedAddress(a))
        .distinct()
        .toList();
  }

  /**
   * Would {@link #send} queue an email for these recipients (a deliverable address left, the ticket
   * not opened by a bulk or automated sender)?
   */
  public boolean wouldSend(AccessScope scope, List<String> recipients, long ticketId) {
    return !deliverable(recipients).isEmpty()
        && !ticketMail.isAutomaticMailSuppressed(scope, ticketId);
  }

  /**
   * @return true when an email was queued (false when no deliverable recipient is left, or the
   *     ticket was opened by a bulk or automated sender)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean send(
      AccessScope scope,
      SystemEmail template,
      List<String> recipients,
      long ticketId,
      String reference,
      String ticketSubject) {
    List<String> to = deliverable(recipients);
    if (to.isEmpty() || ticketMail.isAutomaticMailSuppressed(scope, ticketId)) {
      return false;
    }
    SystemEmailRenderer.Rendered r =
        renderer.render(
            template,
            Map.of(
                "ticketSubject",
                ticketSubject == null || ticketSubject.isBlank() ? "your request" : ticketSubject));
    OutboundEmail.Builder b =
        OutboundEmail.builder(MailKind.CLIENT_FACING)
            .to(to.subList(0, 1))
            .cc(to.subList(1, to.size()))
            .subject(r.subject())
            .body(r.html(), r.text())
            .ticketId(ticketId);
    if (reference != null) {
      b.reference(reference);
    }
    addresses.support(scope).ifPresent(b::replyTo);
    outbox.enqueue(b.build());
    return true;
  }
}
