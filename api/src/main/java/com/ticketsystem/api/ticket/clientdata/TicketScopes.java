package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;

/**
 * Write checks shared by the ticket repositories. These are early checks on the organisation the
 * caller passed in (and the kind of scope); every write also binds the scope in its own SQL ({@link
 * com.ticketsystem.api.access.ScopeSql#ticketVisible}), so a wrong organisation id cannot widen it.
 */
final class TicketScopes {

  private TicketScopes() {}

  /**
   * A write to a ticket in {@code organisationId}: 404 unless the scope sees it; a triage ticket
   * (null) needs a supporter or system scope.
   */
  static void requireWritable(AccessScope scope, Long organisationId) {
    if (organisationId == null) {
      scope.requireSupporterOrSystem();
    } else {
      scope.require(organisationId);
    }
  }

  /** Supporter-only data (internal notes, system entries, history): 404 for anyone else. */
  static void requireSupporterOrSystem(AccessScope scope, Long organisationId) {
    if (!scope.isSupporter() && !scope.isSystem()) {
      throw AccessScope.notFound();
    }
    requireWritable(scope, organisationId);
  }
}
