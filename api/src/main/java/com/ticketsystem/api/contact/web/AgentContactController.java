package com.ticketsystem.api.contact.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.contact.ContactService;
import com.ticketsystem.api.contact.ContactService.ContactPage;
import com.ticketsystem.api.contact.ContactService.ContactView;
import com.ticketsystem.api.contact.ContactService.Invited;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Customers screen API: search, create and invite contacts of the organisations the supporter
 * works. Role gate: the /api/v1/agent tree; scoping in the service.
 */
@RestController
@RequestMapping("/api/v1/agent/contacts")
public class AgentContactController {

  record CreateContactRequest(Long organisationId, String name, String email, Boolean invite) {}

  private final ContactService contacts;

  public AgentContactController(ContactService contacts) {
    this.contacts = contacts;
  }

  @GetMapping
  ContactPage search(
      AccessScope scope,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Long organisationId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int limit) {
    return contacts.search(scope, q, organisationId, page, limit);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ContactView create(
      AccessScope scope, @RequestBody CreateContactRequest r, HttpServletRequest request) {
    return contacts.create(
        scope,
        r.organisationId(),
        r.name(),
        r.email(),
        Boolean.TRUE.equals(r.invite()),
        request.getRemoteAddr());
  }

  @PostMapping("/{id}/invitation")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Invited invite(AccessScope scope, @PathVariable long id, HttpServletRequest request) {
    return contacts.invite(scope, id, request.getRemoteAddr());
  }
}
