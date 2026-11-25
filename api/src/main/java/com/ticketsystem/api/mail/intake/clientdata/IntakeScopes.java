package com.ticketsystem.api.mail.intake.clientdata;

import com.ticketsystem.api.access.AccessScope;

/**
 * Scope checks for the email intake tables. Mailboxes, the block list, sender rules, settings and
 * the inbound mail log are installation-wide configuration and mail that belongs to no organisation
 * yet: only admins (through the admin API) and the SYSTEM scope (the poller and its jobs) may touch
 * them. Everyone else gets 404, like every other unseen row.
 */
final class IntakeScopes {

  private IntakeScopes() {}

  static void requireAdminOrSystem(AccessScope scope) {
    if (!scope.isSystem() && !scope.isAdmin()) {
      throw AccessScope.notFound();
    }
  }

  static void requireSystem(AccessScope scope) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
  }
}
