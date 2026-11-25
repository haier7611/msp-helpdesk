package com.ticketsystem.api.ticket.agent.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import com.ticketsystem.api.ticket.attachment.AttachmentService.Uploaded;
import com.ticketsystem.api.ticket.web.AttachmentResponses;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachment upload, download and thumbnail for supporters - the only routes that serve attachment
 * bytes (customers have none). Every download re-checks attachment -> comment -> ticket ->
 * organisation through the request's AccessScope.
 */
@RestController
@RequestMapping("/api/v1/agent/attachments")
public class AgentAttachmentController {

  private final AttachmentService attachments;

  public AgentAttachmentController(AttachmentService attachments) {
    this.attachments = attachments;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  Uploaded upload(AccessScope scope, @RequestPart("file") MultipartFile file) {
    return attachments.upload(scope, file.getOriginalFilename(), AttachmentResponses.read(file));
  }

  @GetMapping("/{id}")
  ResponseEntity<InputStreamResource> download(AccessScope scope, @PathVariable UUID id) {
    return AttachmentResponses.serve(attachments.download(scope, id));
  }

  @GetMapping("/{id}/thumbnail")
  ResponseEntity<InputStreamResource> thumbnail(AccessScope scope, @PathVariable UUID id) {
    return AttachmentResponses.serve(attachments.thumbnail(scope, id));
  }
}
