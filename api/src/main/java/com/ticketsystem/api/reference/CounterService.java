package com.ticketsystem.api.reference;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.reference.clientdata.PrefixRepository;
import com.ticketsystem.api.reference.clientdata.TicketCounterRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues ticket numbers from the per-organisation counters. Must run inside the caller's
 * transaction ({@link Propagation#MANDATORY}): the counter row stays locked until that transaction
 * ends, so concurrent tickets queue instead of sharing a number, and a rolled-back ticket also
 * rolls back its number - numbers are never reused and rollbacks leave no gaps.
 */
@Service
public class CounterService {

  private final TicketCounterRepository counters;
  private final PrefixRepository prefixes;
  private final Clock clock;

  public CounterService(TicketCounterRepository counters, PrefixRepository prefixes, Clock clock) {
    this.counters = counters;
    this.prefixes = prefixes;
    this.clock = clock;
  }

  /**
   * The next reference of {@code organisationId}, under its active prefix. 404 when the scope may
   * not see the organisation (or it does not exist).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public TicketReference next(AccessScope scope, long organisationId) {
    long number =
        counters
            .takeNext(scope, organisationId, clock.instant())
            .orElseThrow(AccessScope::notFound);
    // Read after taking the row lock: a prefix change locks the same row first, so this sees
    // the prefix that is current once any concurrent change has committed.
    String prefix = prefixes.activePrefix(scope, organisationId).orElseThrow(AccessScope::notFound);
    return new TicketReference(prefix, number);
  }

  /** The next {@code TRI-} reference for the triage queue (supporters and system jobs). */
  @Transactional(propagation = Propagation.MANDATORY)
  public TicketReference nextTriage(AccessScope scope) {
    return new TicketReference(
        ReferenceFormat.TRIAGE_PREFIX, counters.takeNextTriage(scope, clock.instant()));
  }
}
