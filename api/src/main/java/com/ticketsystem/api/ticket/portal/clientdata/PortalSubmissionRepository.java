package com.ticketsystem.api.ticket.portal.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code portal_submission}: the New ticket form's duplicate-submit guard. Rows belong to the
 * customer who submitted them and are only ever read back by that customer, inside their own
 * organisation.
 */
@Repository
public class PortalSubmissionRepository {

  private final JdbcClient jdbc;

  public PortalSubmissionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Claims {@code key} for the customer. True when this request owns it; false when an earlier
   * request (possibly still running - this waits for it to commit) already did.
   */
  public boolean claim(AccessScope scope, long organisationId, UUID key, Instant now) {
    scope.require(organisationId);
    long userId = customerId(scope);
    return jdbc.sql(
                "INSERT INTO portal_submission (organisation_id, user_id, idempotency_key,"
                    + " created_at) VALUES (?, ?, ?, ?)"
                    + " ON CONFLICT (user_id, idempotency_key) DO NOTHING")
            .params(organisationId, userId, key, Timestamp.from(now))
            .update()
        == 1;
  }

  /**
   * Records the ticket an owned key produced. 404 unless the ticket is in {@code organisationId}
   * and the scope sees it (checked by the update itself).
   */
  public void bind(AccessScope scope, long organisationId, UUID key, long ticketId) {
    scope.require(organisationId);
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "UPDATE portal_submission SET ticket_id = :ticket WHERE user_id = :user"
                            + " AND idempotency_key = :key AND organisation_id = :org AND "
                            + ScopeSql.ticketInOrganisation(scope, ":ticket", "org"))
                    .param("ticket", ticketId)
                    .param("user", customerId(scope))
                    .param("key", key)
                    .param("org", organisationId),
                scope)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
  }

  /** The ticket an earlier submission with {@code key} produced, if it finished. */
  public Optional<Long> ticketOf(AccessScope scope, long organisationId, UUID key) {
    scope.require(organisationId);
    return jdbc.sql(
            "SELECT ticket_id FROM portal_submission WHERE user_id = ? AND idempotency_key = ?"
                + " AND organisation_id = ? AND ticket_id IS NOT NULL")
        .params(customerId(scope), key, organisationId)
        .query(Long.class)
        .optional();
  }

  private static long customerId(AccessScope scope) {
    if (scope.kind() != AccessScope.Kind.CUSTOMER) {
      throw AccessScope.notFound();
    }
    return scope.principalId().orElseThrow(AccessScope::notFound);
  }
}
