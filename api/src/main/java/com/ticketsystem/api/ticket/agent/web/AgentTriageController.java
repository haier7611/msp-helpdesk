package com.ticketsystem.api.ticket.agent.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.ticket.agent.TriageService;
import com.ticketsystem.api.ticket.agent.TriageService.TriageOptions;
import com.ticketsystem.api.ticket.agent.TriageService.TriageResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Email triage assignment with the "remember" ticks. */
@RestController
@RequestMapping("/api/v1/agent/tickets")
public class AgentTriageController {

  record TriageAssignmentRequest(Long organisationId, Boolean saveContact, Boolean addDomain) {}

  private final TriageService triage;

  public AgentTriageController(TriageService triage) {
    this.triage = triage;
  }

  @GetMapping("/{id}/triage-options")
  TriageOptions options(AccessScope scope, @PathVariable long id) {
    return triage.options(scope, id);
  }

  @PostMapping("/{id}/triage-assignment")
  TriageResult assign(
      AccessScope scope,
      @PathVariable long id,
      @RequestBody TriageAssignmentRequest r,
      HttpServletRequest request) {
    return triage.assign(
        scope,
        id,
        r.organisationId(),
        Boolean.TRUE.equals(r.saveContact()),
        Boolean.TRUE.equals(r.addDomain()),
        request.getRemoteAddr());
  }
}
