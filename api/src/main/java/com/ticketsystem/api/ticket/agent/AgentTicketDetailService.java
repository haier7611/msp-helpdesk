package com.ticketsystem.api.ticket.agent;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.ticket.SlaPanel;
import com.ticketsystem.api.sla.ticket.SlaPanelService;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The agent ticket page: the detail, then the live SLA panel, each in its own transaction one after
 * the other.
 *
 * <p>Deliberately not transactional itself ({@link Propagation#NEVER}): the detail's read-only
 * transaction commits and returns its pool connection before the panel's transaction takes one, so
 * a page view never holds two connections at once (no pool deadlock under concurrent views), and a
 * panel failure cannot mark the detail's transaction rollback-only.
 */
@Service
public class AgentTicketDetailService {

  private static final Logger log = LoggerFactory.getLogger(AgentTicketDetailService.class);

  private final AgentTicketQueryService queries;
  private final SlaPanelService slaPanels;

  public AgentTicketDetailService(AgentTicketQueryService queries, SlaPanelService slaPanels) {
    this.queries = queries;
    this.slaPanels = slaPanels;
  }

  /**
   * The ticket page with its SLA panel (null when it cannot be built). Triage tickets have one too
   * (the default policy and hours in the MSP timezone).
   */
  @Transactional(propagation = Propagation.NEVER)
  public TicketDetail detail(AccessScope scope, long ticketId) {
    TicketDetail detail = queries.detail(scope, ticketId);
    return detail.withSlaPanel(slaPanel(scope, ticketId));
  }

  /**
   * The SLA panel, or null when it cannot be worked out: a failure here (a broken calendar, an
   * engine error) must never stop the agent opening the ticket, so it is logged and the page hides
   * the panel. Access errors cannot happen here - the ticket was already read in this scope.
   */
  private SlaPanel slaPanel(AccessScope scope, long ticketId) {
    try {
      return slaPanels.panel(scope, ticketId).orElse(null);
    } catch (RuntimeException e) {
      log.warn("SLA panel for ticket {} could not be calculated; hidden", ticketId, e);
      return null;
    }
  }
}
