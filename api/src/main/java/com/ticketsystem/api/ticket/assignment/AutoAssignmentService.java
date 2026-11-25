package com.ticketsystem.api.ticket.assignment;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.notification.SupporterPreferences;
import com.ticketsystem.api.notification.clientdata.SupporterSettingsRepository.OutOfOfficeRow;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.agent.InternalNoteService;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.AssignmentReason;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auto-assignment of a new ticket: the request type's default assignee, else the organisation's,
 * else Unassigned. A default who is not an active supporter able to work the organisation
 * (deactivated, demoted, access removed) is ignored and the next one is tried. A default who is out
 * of office (today in the organisation's timezone) stops the search: the ticket stays Unassigned
 * and an internal system entry says "Not assigned to NAME: out of office until DATE". Runs once per
 * ticket ({@code auto_assign_checked_at}), only on tickets that have an organisation, are not
 * Closed and have no assignee yet; SYSTEM scope.
 */
@Service
public class AutoAssignmentService {

  /** What happened (for tests and logs). */
  public enum Result {
    ALREADY_CHECKED,
    NOT_APPLICABLE,
    NO_DEFAULT,
    ASSIGNED,
    SKIPPED_OUT_OF_OFFICE
  }

  static final String SYSTEM_ENTRY_KIND = "AUTO_ASSIGN_SKIPPED";
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

  private final TicketSlaRepository slaTickets;
  private final TicketRepository tickets;
  private final TicketPeopleRepository people;
  private final TicketService ticketService;
  private final InternalNoteService notes;
  private final SlaConfigService config;
  private final SupporterPreferences preferences;
  private final Clock clock;

  public AutoAssignmentService(
      TicketSlaRepository slaTickets,
      TicketRepository tickets,
      TicketPeopleRepository people,
      TicketService ticketService,
      InternalNoteService notes,
      SlaConfigService config,
      SupporterPreferences preferences,
      Clock clock) {
    this.slaTickets = slaTickets;
    this.tickets = tickets;
    this.people = people;
    this.ticketService = ticketService;
    this.notes = notes;
    this.config = config;
    this.preferences = preferences;
    this.clock = clock;
  }

  /**
   * Applies the rule in its own transaction (after commit of TicketCreated, and the minute job).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Result applyInNewTransaction(AccessScope scope, long ticketId) {
    return apply(scope, ticketId);
  }

  @Transactional
  public Result apply(AccessScope scope, long ticketId) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
    Optional<TicketRow> found = tickets.lock(scope, ticketId);
    if (found.isEmpty()) {
      return Result.NOT_APPLICABLE;
    }
    if (!slaTickets.claimAutoAssignment(scope, ticketId, clock.instant())) {
      return Result.ALREADY_CHECKED;
    }
    TicketRow t = found.get();
    if (t.organisationId() == null
        || t.assigneeUserId() != null
        || t.status() == TicketStatus.CLOSED) {
      return Result.NOT_APPLICABLE;
    }
    long org = t.organisationId();
    List<Candidate> candidates = new ArrayList<>();
    config
        .requestTypeDefaultAssignee(scope, t.requestTypeId())
        .ifPresent(
            u ->
                candidates.add(
                    new Candidate(u, AssignmentReason.DEFAULT_REQUEST_TYPE, t.requestTypeId())));
    Long orgDefault = config.settings(scope, org).defaultAssigneeUserId();
    if (orgDefault != null) {
      candidates.add(new Candidate(orgDefault, AssignmentReason.DEFAULT_ORGANISATION, org));
    }
    LocalDate today = clock.instant().atZone(config.zone(scope, org)).toLocalDate();
    for (Candidate c : candidates) {
      long candidate = c.userId();
      if (!people.isActiveSupporterFor(scope, candidate, org)) {
        continue;
      }
      Optional<OutOfOfficeRow> away = preferences.outOfOfficeOn(scope, candidate, today);
      if (away.isPresent()) {
        String name = people.names(scope, List.of(candidate)).getOrDefault(candidate, "agent");
        notes.addSystemEntry(
            scope,
            t.id(),
            SYSTEM_ENTRY_KIND,
            "Not assigned to "
                + name
                + ": out of office"
                + (away.get().endsOn() == null
                    ? ""
                    : " until " + away.get().endsOn().format(DATE)));
        return Result.SKIPPED_OUT_OF_OFFICE;
      }
      ticketService.assignAutomatically(scope, t.id(), candidate, c.reason(), c.reasonRefId());
      return Result.ASSIGNED;
    }
    return Result.NO_DEFAULT;
  }

  /** A default assignee and the rule it comes from (kept in the history row). */
  private record Candidate(long userId, AssignmentReason reason, long reasonRefId) {}
}
