package com.ticketsystem.api.organisation.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.organisation.OrganisationService;
import com.ticketsystem.api.organisation.OrganisationService.OrganisationView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organisations for the agent UI (read only): only the organisations the agent is assigned to; any
 * other id answers 404 (security test 1). Agents cannot create, rename or delete organisations.
 */
@RestController
@RequestMapping("/api/v1/agent/organisations")
public class AgentOrganisationController {

  private final OrganisationService organisations;

  public AgentOrganisationController(OrganisationService organisations) {
    this.organisations = organisations;
  }

  @GetMapping
  List<OrganisationView> list(AccessScope scope) {
    return organisations.list(scope);
  }

  @GetMapping("/{id}")
  OrganisationView get(AccessScope scope, @PathVariable long id) {
    return organisations.get(scope, id);
  }
}
