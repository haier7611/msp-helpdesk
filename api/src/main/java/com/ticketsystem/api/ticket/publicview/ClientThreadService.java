package com.ticketsystem.api.ticket.publicview;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.AttachmentRow;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public read model: what a client may ever see of a ticket - its reference, subject, public
 * comments and their attachments. Never internal notes, internal attachments, system entries,
 * history, assignee, priority or SLA data (a client sees nothing about SLA). For the client emails
 * (which render one comment at a time); there is no customer endpoint that returns it (portal is
 * raise-only). This package may not use internal-note or agent classes (ArchUnit).
 */
@Service
public class ClientThreadService {

  public record ClientAttachment(
      UUID id, String filename, long size, String contentType, boolean image, boolean inline) {}

  public record ClientComment(
      long id,
      String authorKind,
      Long authorUserId,
      String bodyHtml,
      boolean receivedByEmail,
      Instant createdAt,
      List<ClientAttachment> attachments) {}

  public record ClientThread(
      long ticketId, String reference, String subject, List<ClientComment> comments) {}

  private final TicketRepository tickets;
  private final PublicCommentRepository publicComments;
  private final AttachmentService attachments;
  private final ReferenceService references;

  public ClientThreadService(
      TicketRepository tickets,
      PublicCommentRepository publicComments,
      AttachmentService attachments,
      ReferenceService references) {
    this.tickets = tickets;
    this.publicComments = publicComments;
    this.attachments = attachments;
    this.references = references;
  }

  @Transactional(readOnly = true)
  public ClientThread thread(AccessScope scope, long ticketId) {
    TicketRow t = tickets.find(scope, ticketId).orElseThrow(AccessScope::notFound);
    TicketReference ref = references.displayReferences(scope, List.of(t.id())).get(t.id());
    // includeInternal = false: internal-note files are excluded in SQL whatever the scope.
    List<AttachmentRow> files = attachments.listForTicket(scope, t.id(), false);
    List<ClientComment> comments =
        publicComments.listForTicket(scope, t.id()).stream()
            .map(
                c ->
                    new ClientComment(
                        c.id(),
                        c.authorKind().name(),
                        c.authorUserId(),
                        c.bodyHtml(),
                        c.receivedByEmail(),
                        c.createdAt(),
                        files.stream()
                            .filter(a -> a.commentId() != null && a.commentId() == c.id())
                            .map(
                                a ->
                                    new ClientAttachment(
                                        a.publicId(),
                                        a.filename(),
                                        a.sizeBytes(),
                                        a.contentType(),
                                        a.image(),
                                        a.inline()))
                            .toList()))
            .toList();
    return new ClientThread(t.id(), ref == null ? null : ref.toString(), t.subject(), comments);
  }
}
