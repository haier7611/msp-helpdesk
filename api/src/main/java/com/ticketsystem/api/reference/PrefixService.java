package com.ticketsystem.api.reference;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.reference.clientdata.PrefixRepository;
import com.ticketsystem.api.reference.clientdata.PrefixRepository.PrefixRow;
import com.ticketsystem.api.reference.clientdata.TicketCounterRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organisation ticket prefixes. Admin only. A prefix is unique across every active, retired and
 * reserved prefix, compared case-insensitively. Changing a prefix retires the old one - reserved to
 * that organisation for ever, so no other organisation can take it - and the counter carries on, so
 * the sequence continues under the new prefix. An organisation may switch back to one of its own
 * retired prefixes.
 */
@Service
public class PrefixService {

  /** The outcome of a change, for the audit log. */
  public record Change(String oldPrefix, String newPrefix, boolean changed) {}

  private final PrefixRepository prefixes;
  private final TicketCounterRepository counters;
  private final Clock clock;

  public PrefixService(PrefixRepository prefixes, TicketCounterRepository counters, Clock clock) {
    this.prefixes = prefixes;
    this.counters = counters;
    this.clock = clock;
  }

  /** Checks and upper-cases a prefix, or throws 400 with a plain message. */
  public static String requireValid(String raw) {
    return switch (ReferenceFormat.checkOrganisationPrefix(raw)) {
      case ReferenceFormat.PrefixCheck.Valid v -> v.prefix();
      case ReferenceFormat.PrefixCheck.Invalid i ->
          throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_prefix", i.message());
    };
  }

  /**
   * Sets up a new organisation's prefix and counter, in the transaction that creates it. 409 when
   * the prefix is taken.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public String setUp(AccessScope scope, long organisationId, String raw) {
    scope.requireAdmin();
    String prefix = requireValid(raw);
    insertActive(scope, organisationId, prefix);
    counters.create(scope, organisationId, clock.instant());
    return prefix;
  }

  /** Changes an organisation's prefix (the organisation row is already locked by the caller). */
  @Transactional(propagation = Propagation.MANDATORY)
  public Change change(AccessScope scope, long organisationId, String raw) {
    scope.requireAdmin();
    String prefix = requireValid(raw);
    // Lock the counter row first: tickets being issued for this organisation finish (or wait)
    // before the prefix flips, so a new number never pairs with a half-changed prefix.
    counters.lock(scope, organisationId);
    String old = prefixes.activePrefix(scope, organisationId).orElseThrow(AccessScope::notFound);
    if (old.equals(prefix)) {
      return new Change(old, prefix, false);
    }
    Optional<Long> holder = prefixes.holder(scope, prefix);
    prefixes.retireActive(scope, organisationId);
    if (holder.isPresent() && holder.get() == organisationId) {
      prefixes.reactivate(scope, organisationId, prefix);
    } else if (holder.isPresent()) {
      throw taken(prefix, holder.get());
    } else {
      insertActive(scope, organisationId, prefix);
    }
    return new Change(old, prefix, true);
  }

  /** Every prefix of the organisation, newest first. */
  public List<PrefixRow> history(AccessScope scope, long organisationId) {
    return prefixes.listForOrganisation(scope, organisationId);
  }

  /** The active prefix of a visible organisation. */
  public Optional<String> activePrefix(AccessScope scope, long organisationId) {
    return prefixes.activePrefix(scope, organisationId);
  }

  private void insertActive(AccessScope scope, long organisationId, String prefix) {
    Optional<Long> holder = prefixes.holder(scope, prefix);
    if (holder.isPresent()) {
      throw taken(prefix, holder.get());
    }
    try {
      prefixes.insertActive(scope, organisationId, prefix, clock.instant());
    } catch (DuplicateKeyException e) {
      // Another admin took it between the check and the insert; the transaction is aborted, so
      // answer without asking the database who won.
      throw taken(prefix, -1L);
    }
  }

  private static ApiException taken(String prefix, long holder) {
    String message =
        holder == 0
            ? prefix + " is reserved. Choose another prefix."
            : prefix
                + " is already used, or was used before, by another organisation. Choose another"
                + " prefix.";
    return new ApiException(HttpStatus.CONFLICT, "prefix_taken", message);
  }
}
