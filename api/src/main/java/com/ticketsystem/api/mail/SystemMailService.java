package com.ticketsystem.api.mail;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders a system template and queues it in the caller's transaction. Used for invitations,
 * password resets, magic links and the first-admin confirmation.
 */
@Service
public class SystemMailService {

  private final SystemEmailRenderer renderer;
  private final MailOutbox outbox;

  SystemMailService(SystemEmailRenderer renderer, MailOutbox outbox) {
    this.renderer = renderer;
    this.outbox = outbox;
  }

  /**
   * @param tokenExpiresAt when the link in a sensitive mail stops working: the mail is not sent (or
   *     retried) after that; must be given for sensitive templates and null otherwise
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public MailOutbox.QueuedEmail send(
      SystemEmail email, String to, Map<String, String> variables, Instant tokenExpiresAt) {
    if (email.sensitive() != (tokenExpiresAt != null)) {
      throw new IllegalArgumentException(
          email.sensitive()
              ? email.templateName() + " carries a token: give its expiry"
              : email.templateName() + " carries no token: no expiry");
    }
    SystemEmailRenderer.Rendered r = renderer.render(email, variables);
    OutboundEmail.Builder b =
        OutboundEmail.builder(MailKind.SYSTEM).to(to).subject(r.subject()).body(r.html(), r.text());
    if (email.sensitive()) {
      b.sensitive(tokenExpiresAt);
    }
    return outbox.enqueue(b.build());
  }

  /**
   * Renders a ticket template (no token) and queues it as client-facing mail about one ticket:
   * {@code [reference]} is put at the start of the subject and the outbox row carries the ticket
   * id, so replies thread back. Used for the portal confirmation.
   *
   * @param subject the subject after the {@code [REF]} tag (e.g. the ticket's subject)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public MailOutbox.QueuedEmail sendTicketMail(
      SystemEmail email,
      String to,
      Map<String, String> variables,
      String reference,
      long ticketId,
      String subject) {
    if (email.sensitive()) {
      throw new IllegalArgumentException(email.templateName() + " carries a token");
    }
    SystemEmailRenderer.Rendered r = renderer.render(email, variables);
    return outbox.enqueue(
        OutboundEmail.builder(MailKind.CLIENT_FACING)
            .to(to)
            .reference(reference)
            .ticketId(ticketId)
            .subject(subject == null || subject.isBlank() ? r.subject() : subject)
            .body(r.html(), r.text())
            .build());
  }
}
