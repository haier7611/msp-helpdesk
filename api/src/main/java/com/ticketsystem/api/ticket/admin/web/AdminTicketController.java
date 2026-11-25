package com.ticketsystem.api.ticket.admin.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An admin reopens a closed ticket. The {@code /api/v1/admin} tree is admin-only (agents, engineers
 * and customers get 403), a step-up in the last 10 minutes is required, and the service checks the
 * admin scope again. Documented in {@code openapi/admin-config.yaml}.
 */
@RestController
@RequestMapping("/api/v1/admin/tickets")
public class AdminTicketController {

  record ReopenRequest(String reason) {}

  record StatusResponse(TicketStatus status) {}

  private final TicketService tickets;

  public AdminTicketController(TicketService tickets) {
    this.tickets = tickets;
  }

  @PostMapping("/{id}/reopen")
  @RequiresStepUp
  StatusResponse reopen(
      AccessScope scope,
      @PathVariable long id,
      @RequestBody(required = false) ReopenRequest body,
      HttpServletRequest request) {
    return new StatusResponse(
        tickets.adminReopen(
            scope, id, body == null ? null : body.reason(), request.getRemoteAddr()));
  }
}
