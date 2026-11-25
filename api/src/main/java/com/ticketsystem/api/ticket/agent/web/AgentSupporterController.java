package com.ticketsystem.api.ticket.agent.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.ticket.agent.AgentTicketQueryService;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.SupporterOption;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Active supporters for the agent UI's assignee picker. Names and emails only. */
@RestController
@RequestMapping("/api/v1/agent/supporters")
public class AgentSupporterController {

  private final AgentTicketQueryService queries;

  public AgentSupporterController(AgentTicketQueryService queries) {
    this.queries = queries;
  }

  @GetMapping
  List<SupporterOption> list(
      AccessScope scope, @RequestParam(required = false) Long organisationId) {
    return queries.supporters(scope, organisationId);
  }
}
