package com.ticketsystem.api.ticket.portal.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.mail.inbound.HtmlText;
import com.ticketsystem.api.ticket.Impact;
import com.ticketsystem.api.ticket.RequestTypeService;
import com.ticketsystem.api.ticket.RequestTypeService.PortalRequestType;
import com.ticketsystem.api.ticket.Urgency;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import com.ticketsystem.api.ticket.portal.PortalTicketService;
import com.ticketsystem.api.ticket.portal.PortalTicketService.PortalNewTicket;
import com.ticketsystem.api.ticket.portal.PortalTicketService.Receipt;
import com.ticketsystem.api.ticket.web.AttachmentResponses;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The customer portal's ticket routes (raise-only): request types for the form, upload a file for
 * the form, and raise the ticket. Nothing here returns a ticket, a comment or an attachment - an
 * upload answers only with the new file's id, name and size, and raising answers only with the
 * reference. A repeat of the same form ({@code idempotencyKey}) answers with the first ticket's
 * reference. The route inventory test fails if a route is added here.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalTicketController {

  record RaiseRequest(
      Long requestTypeId,
      String subject,
      String descriptionHtml,
      Impact impact,
      Urgency urgency,
      List<UUID> attachmentIds,
      UUID idempotencyKey) {}

  /** What an upload answers in the portal (no type detection details, no URL). */
  record PortalUpload(UUID id, String filename, long size) {}

  private final PortalTicketService portal;
  private final RequestTypeService requestTypes;
  private final AttachmentService attachments;

  public PortalTicketController(
      PortalTicketService portal, RequestTypeService requestTypes, AttachmentService attachments) {
    this.portal = portal;
    this.requestTypes = requestTypes;
    this.attachments = attachments;
  }

  @GetMapping("/request-types")
  List<PortalRequestType> requestTypes(AccessScope scope) {
    return requestTypes.forPortal(scope);
  }

  @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  PortalUpload upload(AccessScope scope, @RequestPart("file") MultipartFile file) {
    var u = attachments.upload(scope, file.getOriginalFilename(), AttachmentResponses.read(file));
    return new PortalUpload(u.id(), u.filename(), u.size());
  }

  @PostMapping("/tickets")
  @ResponseStatus(HttpStatus.CREATED)
  Receipt raise(AccessScope scope, @RequestBody RaiseRequest r) {
    if (r.requestTypeId() == null || r.impact() == null || r.urgency() == null) {
      throw ApiException.invalidRequest("Choose a request type, impact and urgency.");
    }
    if (r.subject() == null || r.subject().isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "subject_required", "Enter a subject.");
    }
    if (r.descriptionHtml() == null || HtmlText.toPlainText(r.descriptionHtml()).isBlank()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "description_required", "Describe the problem.");
    }
    return portal.raise(
        scope,
        new PortalNewTicket(
            r.requestTypeId(),
            r.subject(),
            r.descriptionHtml(),
            r.impact(),
            r.urgency(),
            r.attachmentIds() == null ? List.of() : r.attachmentIds()),
        r.idempotencyKey());
  }
}
