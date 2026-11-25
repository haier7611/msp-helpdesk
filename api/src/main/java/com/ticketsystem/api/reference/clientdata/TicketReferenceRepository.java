package com.ticketsystem.api.reference.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import com.ticketsystem.api.reference.TicketReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ticket_reference}: every reference ever issued, one PRIMARY per ticket plus aliases (spec
 * section 6). Every read is filtered by the scope inside the query; triage rows (no organisation)
 * are visible to supporters and system jobs only.
 */
@Repository
public class TicketReferenceRepository {

  public enum Kind {
    PRIMARY,
    ALIAS
  }

  /** Why a row was issued. */
  public enum Reason {
    /** The ticket's first reference. */
    ISSUED,
    /** A new primary when a triage ticket was assigned to an organisation. */
    TRIAGE,
    /** A new primary when a ticket moved to another organisation. */
    MOVED,
    /** A duplicate's source reference, re-pointed at its target. */
    DUPLICATE
  }

  /** One stored reference. {@code organisationId} is null while the ticket is in triage. */
  public record ReferenceRow(
      TicketReference reference, long ticketId, Long organisationId, Kind kind, Reason reason) {}

  private static final String COLUMNS = "prefix, number, ticket_id, organisation_id, kind, reason";

  private final JdbcClient jdbc;

  public TicketReferenceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Stores a new reference row. The scope must see {@code organisationId} and the insert only
   * matches a ticket the scope sees (404 otherwise). The ticket's own organisation is not compared
   * with {@code organisationId}: on a move the new primary is issued before the ticket row moves.
   */
  public void insert(
      AccessScope scope,
      TicketReference reference,
      long ticketId,
      Long organisationId,
      Kind kind,
      Reason reason,
      Instant now) {
    requireWritable(scope, organisationId);
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "INSERT INTO ticket_reference (prefix, number, ticket_id, issued_ticket_id,"
                            + " organisation_id, kind, reason, created_at) SELECT :prefix,"
                            + " :number, :ticket, :ticket, :org, :kind, :reason, :now WHERE "
                            + ScopeSql.ticketVisible(scope, ":ticket"))
                    .param("prefix", reference.prefix())
                    .param("number", reference.number())
                    .param("ticket", ticketId)
                    .param("org", organisationId)
                    .param("kind", kind.name())
                    .param("reason", reason.name())
                    .param("now", Timestamp.from(now)),
                scope)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
  }

  /** Looks up one reference (primary or alias); empty when unknown or not visible. */
  public Optional<ReferenceRow> find(AccessScope scope, TicketReference reference) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT "
                        + COLUMNS
                        + " FROM ticket_reference WHERE prefix = :prefix AND number = :number AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id"))
                .param("prefix", reference.prefix())
                .param("number", reference.number()),
            scope)
        .query(TicketReferenceRepository::map)
        .optional();
  }

  /** Every reference of a ticket, primary first; empty list when not visible. */
  public List<ReferenceRow> listForTicket(AccessScope scope, long ticketId) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT "
                        + COLUMNS
                        + " FROM ticket_reference WHERE ticket_id = :ticket AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id")
                        + " ORDER BY (kind = 'PRIMARY') DESC, id")
                .param("ticket", ticketId),
            scope)
        .query(TicketReferenceRepository::map)
        .list();
  }

  /**
   * Moves every reference of a ticket to {@code newOrganisationId} and turns the current primary
   * into an alias, in one statement. Only rows the scope can see are touched; returns the count.
   */
  public int demoteAndMove(AccessScope scope, long ticketId, long newOrganisationId) {
    scope.require(newOrganisationId);
    return ScopeSql.bind(
            jdbc.sql(
                    "UPDATE ticket_reference SET kind = 'ALIAS', organisation_id = :newOrg"
                        + " WHERE ticket_id = :ticket AND "
                        + ScopeSql.organisationOrTriage(scope, "organisation_id"))
                .param("newOrg", newOrganisationId)
                .param("ticket", ticketId),
            scope)
        .update();
  }

  /**
   * Re-points every reference that resolves to {@code sourceTicketId} (its primary and aliases) at
   * {@code targetTicketId} as aliases with reason DUPLICATE. Both tickets must be in {@code
   * organisationId}, which the scope must see. Returns the rows changed.
   */
  public int repointToDuplicateTarget(
      AccessScope scope, long sourceTicketId, long targetTicketId, long organisationId) {
    scope.require(organisationId);
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "UPDATE ticket_reference SET ticket_id = :target, kind = 'ALIAS', reason ="
                            + " 'DUPLICATE' WHERE ticket_id = :source AND organisation_id = :org"
                            + " AND "
                            + ScopeSql.organisation(scope, "organisation_id")
                            + " AND "
                            + ScopeSql.ticketInOrganisation(scope, ":target", "org"))
                    .param("target", targetTicketId)
                    .param("source", sourceTicketId)
                    .param("org", organisationId),
                scope)
            .update();
    if (n == 0 && !ScopeSql.isTicketInOrganisation(jdbc, scope, targetTicketId, organisationId)) {
      throw AccessScope.notFound();
    }
    return n;
  }

  /**
   * The reference to show for each ticket: its primary, or for a ticket without one (a duplicate's
   * source, whose references now resolve to the target) the last reference issued to it. Scoped by
   * the ticket's own organisation, so a ticket the scope cannot see is absent from the map.
   */
  public Map<Long, TicketReference> displayReferences(
      AccessScope scope, Collection<Long> ticketIds) {
    if (ticketIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, TicketReference> out = new HashMap<>();
    ScopeSql.bind(
            jdbc.sql(
                    "SELECT t.id AS tid, r.prefix, r.number FROM ticket t JOIN LATERAL (SELECT"
                        + " prefix, number FROM ticket_reference x WHERE x.ticket_id = t.id OR"
                        + " x.issued_ticket_id = t.id ORDER BY (x.ticket_id = t.id AND x.kind ="
                        + " 'PRIMARY') DESC, (x.issued_ticket_id = t.id) DESC, x.id DESC LIMIT 1)"
                        + " r ON TRUE WHERE t.id IN (:ids) AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id"))
                .param("ids", ticketIds),
            scope)
        .query(
            (rs, n) -> {
              out.put(
                  rs.getLong("tid"),
                  new TicketReference(rs.getString("prefix"), rs.getLong("number")));
              return null;
            })
        .list();
    return out;
  }

  /** Has any reference been issued for the organisation? (Blocks deleting it.) */
  public boolean anyForOrganisation(AccessScope scope, long organisationId) {
    scope.require(organisationId);
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM ticket_reference WHERE organisation_id = :org OR prefix"
                + " IN (SELECT prefix FROM organisation_prefix WHERE organisation_id = :org))")
        .param("org", organisationId)
        .query(Boolean.class)
        .single();
  }

  private static void requireWritable(AccessScope scope, Long organisationId) {
    if (organisationId == null) {
      scope.requireSupporterOrSystem();
    } else {
      scope.require(organisationId);
    }
  }

  private static ReferenceRow map(ResultSet rs, int row) throws SQLException {
    return new ReferenceRow(
        new TicketReference(rs.getString("prefix"), rs.getLong("number")),
        rs.getLong("ticket_id"),
        rs.getObject("organisation_id", Long.class),
        Kind.valueOf(rs.getString("kind")),
        Reason.valueOf(rs.getString("reason")));
  }
}
