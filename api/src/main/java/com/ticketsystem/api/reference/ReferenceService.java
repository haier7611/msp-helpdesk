package com.ticketsystem.api.reference;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.reference.clientdata.TicketReferenceRepository;
import com.ticketsystem.api.reference.clientdata.TicketReferenceRepository.Kind;
import com.ticketsystem.api.reference.clientdata.TicketReferenceRepository.Reason;
import com.ticketsystem.api.reference.clientdata.TicketReferenceRepository.ReferenceRow;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issued references and their aliases: a reference, once issued, resolves for ever. A ticket has
 * one primary reference and any number of aliases - its old primary after a triage assignment or a
 * move to another organisation, and a duplicate's references, which are re-pointed at the target.
 * Resolution is scoped: a reference of an organisation the caller may not see does not resolve.
 *
 * <p>The ticket code calls {@link #issue} and {@link #assignOrganisation} from the ticket
 * transaction, holding the ticket's row lock so two moves of one ticket cannot interleave (the
 * partial unique index "one primary per ticket" is the database backstop); the ticket table and its
 * foreign key arrive there.
 */
@Service
public class ReferenceService {

  /**
   * A resolved reference.
   *
   * @param matched the reference that was looked up (may be an alias)
   * @param primary the ticket's current primary reference
   * @param organisationId the ticket's organisation, null while in triage
   */
  public record Resolved(
      long ticketId, Long organisationId, TicketReference matched, TicketReference primary) {

    public boolean isAlias() {
      return !matched.equals(primary);
    }
  }

  private final CounterService counters;
  private final TicketReferenceRepository references;
  private final Clock clock;

  public ReferenceService(
      CounterService counters, TicketReferenceRepository references, Clock clock) {
    this.counters = counters;
    this.references = references;
    this.clock = clock;
  }

  /**
   * Issues the first (primary) reference of a new ticket: from the organisation's counter, or
   * {@code TRI-} when {@code organisationId} is null (triage). Runs in the caller's transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public TicketReference issue(AccessScope scope, Long organisationId, long ticketId) {
    TicketReference reference =
        organisationId == null ? counters.nextTriage(scope) : counters.next(scope, organisationId);
    references.insert(
        scope, reference, ticketId, organisationId, Kind.PRIMARY, Reason.ISSUED, clock.instant());
    return reference;
  }

  /**
   * Moves a ticket to {@code newOrganisationId} (triage assignment or a change of organisation): a
   * new primary from the new organisation's counter, and every earlier reference stays as an alias.
   * 404 when the scope cannot see the ticket or the new organisation. Assigning a ticket to the
   * organisation it is already in changes nothing.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public TicketReference assignOrganisation(
      AccessScope scope, long ticketId, long newOrganisationId) {
    scope.require(newOrganisationId);
    ReferenceRow current = primaryRow(scope, ticketId).orElseThrow(AccessScope::notFound);
    if (current.organisationId() != null && current.organisationId() == newOrganisationId) {
      return current.reference();
    }
    Reason reason = current.organisationId() == null ? Reason.TRIAGE : Reason.MOVED;
    TicketReference fresh = counters.next(scope, newOrganisationId);
    references.demoteAndMove(scope, ticketId, newOrganisationId);
    references.insert(
        scope, fresh, ticketId, newOrganisationId, Kind.PRIMARY, reason, clock.instant());
    return fresh;
  }

  /**
   * Resolves typed or emailed text such as {@code nwd-1} or {@code TRI-007} to its ticket. Empty
   * when the text is not a reference, was never issued, or belongs to an organisation the scope may
   * not see (the caller cannot tell these apart).
   */
  @Transactional(readOnly = true)
  public Optional<Resolved> resolve(AccessScope scope, String text) {
    return ReferenceFormat.parse(text).flatMap(parsed -> resolveParsed(scope, parsed));
  }

  /**
   * Resolves a reference candidate found in an email subject ({@code ReferenceCandidate}: prefix
   * and number). The display form is built here, in the reference module. Same visibility rules as
   * {@link #resolve(AccessScope, String)}.
   */
  @Transactional(readOnly = true)
  public Optional<Resolved> resolve(AccessScope scope, String prefix, long number) {
    if (prefix == null || number < 1) {
      return Optional.empty();
    }
    return ReferenceFormat.parse(prefix + "-" + number)
        .flatMap(parsed -> resolveParsed(scope, parsed));
  }

  private Optional<Resolved> resolveParsed(AccessScope scope, TicketReference parsed) {
    Optional<ReferenceRow> row = references.find(scope, parsed);
    if (row.isEmpty()) {
      return Optional.empty();
    }
    return primaryRow(scope, row.get().ticketId())
        .map(
            primary ->
                new Resolved(
                    primary.ticketId(),
                    primary.organisationId(),
                    row.get().reference(),
                    primary.reference()));
  }

  /**
   * Makes every reference of {@code sourceTicketId} an alias of {@code targetTicketId} (mark as
   * duplicate): mail or a search quoting the source's references then lands on the target. Both
   * tickets are in {@code organisationId} (duplicates stay within one organisation). Runs in the
   * caller's transaction, which holds both tickets' row locks.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void aliasToDuplicateTarget(
      AccessScope scope, long sourceTicketId, long targetTicketId, long organisationId) {
    references.repointToDuplicateTarget(scope, sourceTicketId, targetTicketId, organisationId);
  }

  /**
   * The reference to display for each visible ticket: its primary, or for a duplicate's source the
   * last reference issued to it (which now resolves to the target).
   */
  @Transactional(readOnly = true)
  public Map<Long, TicketReference> displayReferences(
      AccessScope scope, Collection<Long> ticketIds) {
    return references.displayReferences(scope, ticketIds);
  }

  /** Every reference of a ticket, primary first (empty when not visible). */
  @Transactional(readOnly = true)
  public List<TicketReference> referencesOf(AccessScope scope, long ticketId) {
    return references.listForTicket(scope, ticketId).stream().map(ReferenceRow::reference).toList();
  }

  private Optional<ReferenceRow> primaryRow(AccessScope scope, long ticketId) {
    return references.listForTicket(scope, ticketId).stream()
        .filter(r -> r.kind() == Kind.PRIMARY)
        .findFirst();
  }
}
