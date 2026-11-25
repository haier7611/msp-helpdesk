package com.ticketsystem.api.reference.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ticket_counter}: one row per organisation plus one for {@code TRI}. Taking a number is a
 * single {@code UPDATE ... RETURNING}, which holds the row lock until the caller's transaction ends
 * - so concurrent callers queue on the row, never share a number, and a rollback gives the number
 * back (never {@code max()+1}). Used only by the reference module's CounterService.
 */
@Repository
public class TicketCounterRepository {

  private final JdbcClient jdbc;

  public TicketCounterRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Creates an organisation's counter at 1, in the transaction that creates the organisation. */
  public void create(AccessScope scope, long organisationId, Instant now) {
    scope.requireAdmin();
    jdbc.sql(
            "INSERT INTO ticket_counter (counter_key, organisation_id, next_number, updated_at)"
                + " VALUES (:key, :org, 1, :now)")
        .param("key", "ORG-" + organisationId)
        .param("org", organisationId)
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * Takes the next number of {@code organisationId} and locks its counter row until the transaction
   * ends; empty when the scope may not see the organisation or it has no counter.
   */
  public Optional<Long> takeNext(AccessScope scope, long organisationId, Instant now) {
    if (!scope.canSee(organisationId)) {
      return Optional.empty();
    }
    return jdbc.sql(
            "UPDATE ticket_counter SET next_number = next_number + 1, updated_at = :now"
                + " WHERE organisation_id = :org RETURNING next_number - 1")
        .param("now", Timestamp.from(now))
        .param("org", organisationId)
        .query(Long.class)
        .optional();
  }

  /** Takes the next TRI number (supporters and system jobs only). */
  public long takeNextTriage(AccessScope scope, Instant now) {
    scope.requireSupporterOrSystem();
    return jdbc.sql(
            "UPDATE ticket_counter SET next_number = next_number + 1, updated_at = :now"
                + " WHERE counter_key = 'TRI' RETURNING next_number - 1")
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  /**
   * Locks an organisation's counter row without taking a number, so a prefix change and ticket
   * issuing for the same organisation run one after the other.
   */
  public void lock(AccessScope scope, long organisationId) {
    scope.requireAdmin();
    jdbc.sql("SELECT 1 FROM ticket_counter WHERE organisation_id = :org FOR UPDATE")
        .param("org", organisationId)
        .query(Integer.class)
        .optional();
  }

  /** The next number that would be issued, for the admin view; empty if not visible. */
  public Optional<Long> peekNext(AccessScope scope, long organisationId) {
    if (!scope.canSee(organisationId)) {
      return Optional.empty();
    }
    return jdbc.sql("SELECT next_number FROM ticket_counter WHERE organisation_id = :org")
        .param("org", organisationId)
        .query(Long.class)
        .optional();
  }
}
