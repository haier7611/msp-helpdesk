package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code system_entry}: hidden entries such as a stored auto-reply or "Not assigned to ...: out of
 * office". Supporters only, like internal notes.
 */
@Repository
public class SystemEntryRepository {

  public record SystemEntryRow(
      long id, long ticketId, String kind, String text, Instant createdAt) {}

  private final JdbcClient jdbc;

  public SystemEntryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long insert(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      String kind,
      String text,
      Instant now) {
    TicketScopes.requireSupporterOrSystem(scope, organisationId);
    return ScopeSql.bind(
            jdbc.sql(
                    "INSERT INTO system_entry (ticket_id, kind, body_text, created_at) SELECT"
                        + " :ticket, :kind, :text, :now WHERE "
                        + ScopeSql.ticketVisible(scope, ":ticket")
                        + " RETURNING id")
                .param("ticket", ticketId)
                .param("kind", kind)
                .param("text", text)
                .param("now", Timestamp.from(now)),
            scope)
        .query(Long.class)
        .optional()
        .orElseThrow(AccessScope::notFound);
  }

  /** Oldest first; always empty for customer and API-key scopes. */
  public List<SystemEntryRow> listForTicket(AccessScope scope, long ticketId) {
    if (!scope.isSupporter() && !scope.isSystem()) {
      return List.of();
    }
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT e.id, e.ticket_id, e.kind, e.body_text, e.created_at FROM system_entry"
                        + " e JOIN ticket t ON t.id = e.ticket_id WHERE e.ticket_id = :ticket AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id")
                        + " ORDER BY e.id")
                .param("ticket", ticketId),
            scope)
        .query(
            (rs, n) ->
                new SystemEntryRow(
                    rs.getLong("id"),
                    rs.getLong("ticket_id"),
                    rs.getString("kind"),
                    rs.getString("body_text"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }
}
