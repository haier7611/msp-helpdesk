package com.ticketsystem.api.ticket.admin.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.ticket.agent.SavedReplyService;
import com.ticketsystem.api.ticket.agent.SavedReplyService.SavedReplyView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Saved replies: the shared replies every supporter sees in the "/" picker. Personal
 * replies are never listed or changed here. Admin-only tree; the service checks the admin scope
 * again; every change is audit-logged.
 */
@RestController
@RequestMapping("/api/v1/admin/saved-replies")
public class AdminSavedReplyController {

  public record SavedReplyBody(
      @Size(max = 400) String title, @Size(max = 40_000) String bodyHtml) {}

  private final SavedReplyService service;

  public AdminSavedReplyController(SavedReplyService service) {
    this.service = service;
  }

  @GetMapping
  List<SavedReplyView> list(AccessScope scope) {
    return service.listShared(scope);
  }

  @PostMapping
  ResponseEntity<SavedReplyView> create(
      AccessScope scope, @Validated @RequestBody SavedReplyBody body, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createShared(scope, body.title(), body.bodyHtml(), request.getRemoteAddr()));
  }

  @PutMapping("/{id}")
  SavedReplyView update(
      AccessScope scope,
      @PathVariable long id,
      @Validated @RequestBody SavedReplyBody body,
      HttpServletRequest request) {
    return service.updateShared(scope, id, body.title(), body.bodyHtml(), request.getRemoteAddr());
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(
      AccessScope scope, @PathVariable long id, HttpServletRequest request) {
    service.deleteShared(scope, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }
}
