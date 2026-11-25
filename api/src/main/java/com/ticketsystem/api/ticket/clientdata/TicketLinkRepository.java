package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ticket_link}: related, duplicate and follow-up links between two tickets of one
 * organisation, stored once per pair and read from either side.
 */
@Repository
public class TicketLinkRepository {

  public enum LinkKind {
    RELATED,
    DUPLICATE,
    FOLLOW_UP
  }

  /** A link as seen from one ticket: the other ticket and the kind. */
  public record LinkRow(long otherTicketId, LinkKind kind, Instant createdAt) {}

  private final JdbcClient jdbc;

  public TicketLinkRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Links two tickets of {@code organisationId}; returns false if that link already existed. 404
   * unless both tickets are in that organisation and the scope sees them (checked by the insert).
   */
  public boolean link(
      AccessScope scope,
      long organisationId,
      long ticketA,
      long ticketB,
      LinkKind kind,
      Long actorUserId,
      Instant now) {
    scope.require(organisationId);
    boolean added =
        ScopeSql.bind(
                    jdbc.sql(
                            "INSERT INTO ticket_link (organisation_id, low_ticket_id,"
                                + " high_ticket_id, kind, created_by_user_id, created_at) SELECT"
                                + " :org, :low, :high, :kind, :actor, :now WHERE "
                                + ScopeSql.ticketInOrganisation(scope, ":low", "org")
                                + " AND "
                                + ScopeSql.ticketInOrganisation(scope, ":high", "org")
                                + " ON CONFLICT ON CONSTRAINT ticket_link_uk DO NOTHING")
                        .param("org", organisationId)
                        .param("low", Math.min(ticketA, ticketB))
                        .param("high", Math.max(ticketA, ticketB))
                        .param("kind", kind.name())
                        .param("actor", actorUserId)
                        .param("now", Timestamp.from(now)),
                    scope)
                .update()
            == 1;
    if (!added
        && !(ScopeSql.isTicketInOrganisation(jdbc, scope, ticketA, organisationId)
            && ScopeSql.isTicketInOrganisation(jdbc, scope, ticketB, organisationId))) {
      throw AccessScope.notFound();
    }
    return added;
  }

  /** Every link of a ticket, from its side; empty when not visible. */
  public List<LinkRow> listForTicket(AccessScope scope, long ticketId) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT CASE WHEN low_ticket_id = :t THEN high_ticket_id ELSE low_ticket_id"
                        + " END AS other, kind, created_at FROM ticket_link WHERE (low_ticket_id"
                        + " = :t OR high_ticket_id = :t) AND "
                        + ScopeSql.organisation(scope, "organisation_id")
                        + " ORDER BY id")
                .param("t", ticketId),
            scope)
        .query(
            (rs, n) ->
                new LinkRow(
                    rs.getLong("other"),
                    LinkKind.valueOf(rs.getString("kind")),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /**
   * Removes a ticket's links when it changes organisation (the linked tickets stay in the old
   * organisation, and no link may span two organisations). Returns the removed links, from the
   * moved ticket's side (each one is recorded in both tickets' history).
   */
  public List<LinkRow> removeOnMove(
      AccessScope scope, long ticketId, Long oldOrganisationId, long newOrganisationId) {
    scope.require(newOrganisationId);
    if (oldOrganisationId == null) {
      return List.of();
    }
    scope.require(oldOrganisationId);
    return jdbc.sql(
            "DELETE FROM ticket_link WHERE (low_ticket_id = :t OR high_ticket_id = :t) AND"
                + " organisation_id = :old RETURNING CASE WHEN low_ticket_id = :t THEN"
                + " high_ticket_id ELSE low_ticket_id END AS other, kind, created_at")
        .param("t", ticketId)
        .param("old", oldOrganisationId)
        .query(
            (rs, n) ->
                new LinkRow(
                    rs.getLong("other"),
                    LinkKind.valueOf(rs.getString("kind")),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }
}
