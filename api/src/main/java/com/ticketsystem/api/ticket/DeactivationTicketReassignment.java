package com.ticketsystem.api.ticket;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.supporter.TicketReassignment;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.QueueFilter;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deactivation wizard's ticket hook on tickets: a supporter's open tickets (New, Open, Pending
 * on client, Pending on vendor - not Resolved or Closed) are moved to the chosen supporter, or to
 * Unassigned. Each move goes through {@link TicketService#reassignKeepingStatus}, so it is scoped
 * by the acting admin's {@link AccessScope}, writes the ticket's assignee history and publishes the
 * usual event, but never picks the ticket up: the status stays as it was, even when the admin moves
 * the tickets to themselves. A ticket in an organisation the chosen supporter cannot work goes to
 * Unassigned instead (the usual assignee rule). Runs in the deactivation transaction.
 */
@Component
public class DeactivationTicketReassignment implements TicketReassignment {

  static final int PAGE = 200;

  private final TicketRepository tickets;
  private final TicketPeopleRepository people;
  private final TicketService ticketService;
  private final SlaConfigService slaConfig;

  public DeactivationTicketReassignment(
      TicketRepository tickets,
      TicketPeopleRepository people,
      TicketService ticketService,
      SlaConfigService slaConfig) {
    this.slaConfig = slaConfig;
    this.tickets = tickets;
    this.people = people;
    this.ticketService = ticketService;
  }

  @Override
  @Transactional(readOnly = true)
  public int openTicketCount(AccessScope scope, long supporterId) {
    return openTickets(scope, supporterId).size();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int reassignOpenTickets(
      AccessScope scope, long fromSupporterId, Long toSupporterId, long actorUserId) {
    scope.requireAdmin();
    int moved = 0;
    for (TicketRow t : openTickets(scope, fromSupporterId)) {
      Long target =
          toSupporterId != null
                  && people.isActiveSupporterFor(scope, toSupporterId, t.organisationId())
              ? toSupporterId
              : null;
      ticketService.reassignKeepingStatus(scope, t.id(), target);
      moved++;
    }
    // A deactivated supporter is cleared from every default-assignee setting,
    // in the same transaction as the deactivation.
    slaConfig.clearDefaultAssignee(scope, fromSupporterId);
    return moved;
  }

  private List<TicketRow> openTickets(AccessScope scope, long supporterId) {
    List<TicketRow> out = new ArrayList<>();
    Long before = null;
    while (true) {
      List<TicketRow> page =
          tickets.list(
              scope,
              new QueueFilter(null, null, false, supporterId, false, false, false, before, PAGE));
      for (TicketRow t : page) {
        if (t.status() != TicketStatus.RESOLVED && t.status() != TicketStatus.CLOSED) {
          out.add(t);
        }
      }
      if (page.size() < PAGE) {
        return out;
      }
      before = page.get(page.size() - 1).id();
    }
  }
}
