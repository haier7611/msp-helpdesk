package com.ticketsystem.api.organisation.clientemail.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.organisation.clientemail.ClientEmailSwitches;
import com.ticketsystem.api.organisation.clientemail.ClientEmailSwitches.Settings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Organisations - "Client emails". Admins only (the {@code /api/v1/admin} tree; the service
 * checks again); an organisation the admin may not see answers 404. No step-up. Every change is
 * audit-logged.
 */
@RestController
@RequestMapping("/api/v1/admin/organisations/{id}/client-emails")
public class AdminClientEmailController {

  /** All four switches are required (a missing one is a 400, never read as off). */
  public record SwitchesRequest(
      @NotNull Boolean ticketReceived,
      @NotNull Boolean resolvedNotice,
      @NotNull Boolean closedNotice,
      @NotNull Boolean pendingReminder) {}

  private final ClientEmailSwitches switches;

  public AdminClientEmailController(ClientEmailSwitches switches) {
    this.switches = switches;
  }

  @GetMapping
  Settings get(AccessScope scope, @PathVariable long id) {
    return switches.get(scope, id);
  }

  @PutMapping
  Settings save(
      AccessScope scope,
      @PathVariable long id,
      @Validated @RequestBody SwitchesRequest body,
      HttpServletRequest request) {
    return switches.save(
        scope,
        id,
        new Settings(
            body.ticketReceived(),
            body.resolvedNotice(),
            body.closedNotice(),
            body.pendingReminder()),
        request.getRemoteAddr());
  }
}
