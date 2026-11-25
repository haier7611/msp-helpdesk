package com.ticketsystem.api.supporter;

import com.ticketsystem.api.access.AccessScope;

/**
 * The deactivation wizard's ticket hook: step 1 shows how many open tickets the supporter has, step
 * 2 moves them to another supporter or to Unassigned. The ticket module provides the Spring bean
 * ({@code ticket.DeactivationTicketReassignment}); when no bean exists (a slice without tickets)
 * {@link #NONE} is used: no tickets, nothing to move.
 *
 * <p>Both methods receive the acting admin's scope (every organisation) and run inside the caller's
 * transaction: {@link #reassignOpenTickets} is called in the same transaction that deactivates the
 * supporter, so a failure rolls the deactivation back too.
 */
public interface TicketReassignment {

  /** Open tickets (any status before Resolved) assigned to the supporter. */
  int openTicketCount(AccessScope scope, long supporterId);

  /**
   * Moves every open ticket of {@code fromSupporterId} to {@code toSupporterId}, or to Unassigned
   * when that is null, and records the change on each ticket. Returns how many were moved. The
   * target has already been checked to be another active supporter.
   */
  int reassignOpenTickets(
      AccessScope scope, long fromSupporterId, Long toSupporterId, long actorUserId);

  /** Used while no ticket module provides a bean: no tickets exist. */
  TicketReassignment NONE =
      new TicketReassignment() {
        @Override
        public int openTicketCount(AccessScope scope, long supporterId) {
          return 0;
        }

        @Override
        public int reassignOpenTickets(
            AccessScope scope, long fromSupporterId, Long toSupporterId, long actorUserId) {
          return 0;
        }
      };
}
