package com.ticketsystem.api.ticket.portal;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.ticket.Impact;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.Urgency;
import com.ticketsystem.api.ticket.portal.clientdata.PortalSubmissionRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer portal's only ticket action: raise a ticket for your own organisation. The answer is
 * just the new reference - customers never read tickets, comments or attachments online. This
 * package may not use agent or internal-note classes (ArchUnit).
 *
 * <p>The ticket and the duplicate-submit guard row are written in one transaction. The "ticket
 * created" confirmation email is queued by the TicketMailListener (source PORTAL), so exactly one
 * is sent.
 */
@Service
public class PortalTicketService {

  /** What the customer typed in the New ticket form. */
  public record PortalNewTicket(
      long requestTypeId,
      String subject,
      String descriptionHtml,
      Impact impact,
      Urgency urgency,
      List<UUID> attachmentIds) {}

  /** The only thing the portal returns about a ticket. */
  public record Receipt(String reference) {}

  private final TicketService tickets;
  private final PortalSubmissionRepository submissions;
  private final ReferenceService references;
  private final Clock clock;

  public PortalTicketService(
      TicketService tickets,
      PortalSubmissionRepository submissions,
      ReferenceService references,
      Clock clock) {
    this.tickets = tickets;
    this.submissions = submissions;
    this.references = references;
    this.clock = clock;
  }

  /**
   * Raises the ticket. Organisation and reporter come from the scope only. With an {@code
   * idempotencyKey} a repeat of the same submission answers with the first ticket's reference and
   * creates nothing.
   */
  @Transactional
  public Receipt raise(AccessScope scope, PortalNewTicket form, UUID idempotencyKey) {
    if (scope.kind() != AccessScope.Kind.CUSTOMER) {
      throw AccessScope.notFound();
    }
    long organisationId =
        scope.organisationIdsOrAll().orElseThrow(AccessScope::notFound).iterator().next();
    long me = scope.principalId().orElseThrow(AccessScope::notFound);
    if (idempotencyKey != null
        && !submissions.claim(scope, organisationId, idempotencyKey, clock.instant())) {
      long earlier =
          submissions
              .ticketOf(scope, organisationId, idempotencyKey)
              .orElseThrow(AccessScope::notFound);
      return new Receipt(primary(scope, earlier).toString());
    }
    Created created =
        tickets.create(
            scope,
            new NewTicket(
                organisationId,
                me,
                null,
                form.requestTypeId(),
                null,
                form.impact(),
                form.urgency(),
                TicketSource.PORTAL,
                form.subject(),
                form.descriptionHtml(),
                form.attachmentIds(),
                null,
                null,
                null,
                null));
    if (idempotencyKey != null) {
      submissions.bind(scope, organisationId, idempotencyKey, created.ticketId());
    }
    return new Receipt(created.reference().toString());
  }

  private TicketReference primary(AccessScope scope, long ticketId) {
    return references.referencesOf(scope, ticketId).stream()
        .findFirst()
        .orElseThrow(AccessScope::notFound);
  }
}
