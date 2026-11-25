package com.ticketsystem.api.ticket.agent.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.ticket.agent.SavedReplyService;
import com.ticketsystem.api.ticket.agent.SavedReplyService.Rendered;
import com.ticketsystem.api.ticket.agent.SavedReplyService.SavedReplyView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved replies for the composer's "/" picker: list shared + own, manage own personal replies, and
 * render one for a ticket. Shared replies are managed by admins in the admin area.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentSavedReplyController {

  record SavedReplyRequest(String title, String bodyHtml) {}

  private final SavedReplyService replies;

  public AgentSavedReplyController(SavedReplyService replies) {
    this.replies = replies;
  }

  @GetMapping("/saved-replies")
  List<SavedReplyView> list(AccessScope scope) {
    return replies.list(scope);
  }

  @PostMapping("/saved-replies")
  @ResponseStatus(HttpStatus.CREATED)
  SavedReplyView create(AccessScope scope, @RequestBody SavedReplyRequest r) {
    return replies.createPersonal(scope, r.title(), r.bodyHtml());
  }

  @PutMapping("/saved-replies/{id}")
  SavedReplyView update(
      AccessScope scope, @PathVariable long id, @RequestBody SavedReplyRequest r) {
    return replies.updatePersonal(scope, id, r.title(), r.bodyHtml());
  }

  @DeleteMapping("/saved-replies/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(AccessScope scope, @PathVariable long id) {
    replies.deletePersonal(scope, id);
  }

  @PostMapping("/tickets/{ticketId}/saved-replies/{replyId}/render")
  Rendered render(AccessScope scope, @PathVariable long ticketId, @PathVariable long replyId) {
    return replies.render(scope, ticketId, replyId);
  }
}
