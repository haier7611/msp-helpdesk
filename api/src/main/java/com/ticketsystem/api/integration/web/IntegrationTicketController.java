package com.ticketsystem.api.integration.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.integration.IntegrationTicketService;
import com.ticketsystem.api.integration.IntegrationTicketService.Alert;
import com.ticketsystem.api.integration.IntegrationTicketService.Outcome;
import com.ticketsystem.api.integration.IntegrationTicketService.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The integrations API: one write-only route for monitoring tools, authenticated by an API key
 * ({@code Authorization: Bearer tsk_...}) in its own security chain. There is deliberately no read
 * route: an API key can never read a ticket. The organisation is the key's.
 */
@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationTicketController {

  /** The alert payload. Any other field (an organisation, a priority) is ignored. */
  record AlertRequest(String externalId, String title, String description, Boolean resolved) {}

  private final IntegrationTicketService service;

  public IntegrationTicketController(IntegrationTicketService service) {
    this.service = service;
  }

  @PostMapping("/tickets")
  ResponseEntity<Outcome> alert(AccessScope scope, @RequestBody AlertRequest r) {
    Outcome o =
        service.receive(
            scope,
            new Alert(
                r.externalId(), r.title(), r.description(), Boolean.TRUE.equals(r.resolved())));
    boolean created = o.result() == Result.CREATED || o.result() == Result.FOLLOW_UP_CREATED;
    return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(o);
  }
}
