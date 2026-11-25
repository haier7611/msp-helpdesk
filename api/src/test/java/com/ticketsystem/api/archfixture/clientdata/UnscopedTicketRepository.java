package com.ticketsystem.api.archfixture.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.util.List;

/**
 * Test-only negative fixture for ArchitectureTest: a client-data repository with one unscoped
 * method ({@link #findAllTickets}). The architecture rule must reject it. Test sources only, and
 * deliberately not annotated, so it never becomes a Spring bean.
 */
public class UnscopedTicketRepository {

  /** Scoped - allowed. */
  public List<Long> findTickets(AccessScope scope) {
    return List.of();
  }

  /** Unscoped - the rule must fail on this. */
  public List<Long> findAllTickets() {
    return List.of();
  }
}
