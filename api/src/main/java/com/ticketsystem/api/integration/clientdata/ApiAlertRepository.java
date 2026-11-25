package com.ticketsystem.api.integration.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.TicketStatus;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Alert dedup support for the integrations API: the key's organisation is the boundary of every
 * query (API-key scope only), and only API tickets ({@code source = 'API'}) are considered.
 */
@Repository
public class ApiAlertRepository {

  /** The newest API ticket for an external id. */
  public record AlertTicket(long ticketId, TicketStatus status) {}

  private final JdbcClient jdbc;

  public ApiAlertRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Serialises alerts with the same external id in one organisation for the rest of the transaction
   * (two simultaneous alerts produce one ticket).
   */
  public void lockExternalId(AccessScope scope, String externalId) {
    long org = organisation(scope);
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("api-alert:" + org + ":" + externalId)
        .query((rs, n) -> 1)
        .list();
  }

  public Optional<AlertTicket> newest(AccessScope scope, String externalId) {
    long org = organisation(scope);
    return jdbc.sql(
            "SELECT id, status FROM ticket WHERE organisation_id = ? AND source = 'API'"
                + " AND external_id = ? ORDER BY id DESC LIMIT 1")
        .params(org, externalId)
        .query((rs, n) -> new AlertTicket(rs.getLong(1), TicketStatus.valueOf(rs.getString(2))))
        .optional();
  }

  /**
   * Records which key raised the ticket, sets its external id (follow-ups) and marks the key as
   * author of its API comments.
   */
  public void stampKey(AccessScope scope, long ticketId, String externalId) {
    long org = organisation(scope);
    long keyId = scope.principalId().orElseThrow(AccessScope::notFound);
    int n =
        jdbc.sql(
                "UPDATE ticket SET created_by_api_key_id = COALESCE(created_by_api_key_id, ?),"
                    + " external_id = COALESCE(external_id, ?) WHERE id = ? AND organisation_id = ?"
                    + " AND source = 'API'")
            .params(keyId, externalId, ticketId, org)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
    stampComments(scope, ticketId);
  }

  /** Marks this key as the author of the ticket's API comments that have no key yet. */
  public void stampComments(AccessScope scope, long ticketId) {
    long org = organisation(scope);
    long keyId = scope.principalId().orElseThrow(AccessScope::notFound);
    jdbc.sql(
            "UPDATE public_comment c SET author_api_key_id = ? FROM ticket t WHERE c.ticket_id ="
                + " t.id AND t.id = ? AND t.organisation_id = ? AND c.author_kind = 'API_KEY'"
                + " AND c.author_api_key_id IS NULL")
        .params(keyId, ticketId, org)
        .update();
  }

  /** The organisation's opt-in for client emails about API alerts (default off). */
  public boolean clientEmailsOptedIn(AccessScope scope) {
    long org = organisation(scope);
    return jdbc.sql(
            "SELECT api_alert_client_emails FROM organisation_sla_settings WHERE organisation_id = ?")
        .param(org)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  private static long organisation(AccessScope scope) {
    if (scope.kind() != AccessScope.Kind.API_KEY) {
      throw AccessScope.notFound();
    }
    return scope.organisationIdsOrAll().orElseThrow(AccessScope::notFound).iterator().next();
  }
}
