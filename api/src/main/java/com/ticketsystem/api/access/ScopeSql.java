package com.ticketsystem.api.access;

import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Turns an {@link AccessScope} into a SQL condition for repositories on client-owned data, so the
 * scope is applied inside the query rather than by filtering results afterwards. Uses the named
 * parameter {@value #PARAM}; bind it with {@link #bind}.
 */
public final class ScopeSql {

  public static final String PARAM = "scopeOrgIds";

  private ScopeSql() {}

  /**
   * {@code TRUE} for every organisation, {@code FALSE} for none, otherwise {@code <column> IN
   * (:scopeOrgIds)}. Rows with a NULL organisation (triage) never match an organisation list; use
   * {@link #organisationOrTriage} where triage rows are wanted.
   */
  public static String organisation(AccessScope scope, String column) {
    Optional<Set<Long>> ids = scope.organisationIdsOrAll();
    if (ids.isEmpty()) {
      return "TRUE";
    }
    if (ids.get().isEmpty()) {
      return "FALSE";
    }
    return column + " IN (:" + PARAM + ")";
  }

  /** Like {@link #organisation} but also matches NULL-organisation rows for supporters/system. */
  public static String organisationOrTriage(AccessScope scope, String column) {
    String orgs = organisation(scope, column);
    if (!scope.canSeeTriage()) {
      return orgs;
    }
    return "(" + orgs + " OR " + column + " IS NULL)";
  }

  /**
   * A condition that ticket {@code ticketIdExpr} (a column such as {@code c.ticket_id} or a named
   * parameter such as {@code :ticket}) is one this scope sees: its organisation is in the scope, or
   * it is in triage and the scope sees triage. A write to a ticket or one of its child rows puts
   * this in its own WHERE clause, so the organisation is checked by the statement that writes and
   * never only against an organisation id the caller passed in. Bind with {@link #bind}.
   */
  public static String ticketVisible(AccessScope scope, String ticketIdExpr) {
    return "EXISTS (SELECT 1 FROM ticket scope_t WHERE scope_t.id = "
        + ticketIdExpr
        + " AND "
        + organisationOrTriage(scope, "scope_t.organisation_id")
        + ")";
  }

  /**
   * Like {@link #ticketVisible} and the ticket is in the organisation held by the named parameter
   * {@code orgParam} (null = triage). For writes that store that organisation id next to the ticket
   * id (links, notifications, portal submissions), so the two cannot disagree.
   */
  public static String ticketInOrganisation(
      AccessScope scope, String ticketIdExpr, String orgParam) {
    return "EXISTS (SELECT 1 FROM ticket scope_t WHERE scope_t.id = "
        + ticketIdExpr
        + " AND scope_t.organisation_id IS NOT DISTINCT FROM CAST(:"
        + orgParam
        + " AS bigint) AND "
        + organisationOrTriage(scope, "scope_t.organisation_id")
        + ")";
  }

  /**
   * Does ticket {@code ticketId} exist and does this scope see it? For telling "not found" (404)
   * apart from "nothing to change" after a scope-bound write changed no row.
   */
  public static boolean isTicketVisible(JdbcClient jdbc, AccessScope scope, long ticketId) {
    return bind(
            jdbc.sql("SELECT " + ticketVisible(scope, ":scopeTicketId"))
                .param("scopeTicketId", ticketId),
            scope)
        .query(Boolean.class)
        .single();
  }

  /** Like {@link #isTicketVisible} and the ticket is in {@code organisationId} (null = triage). */
  public static boolean isTicketInOrganisation(
      JdbcClient jdbc, AccessScope scope, long ticketId, Long organisationId) {
    return bind(
            jdbc.sql("SELECT " + ticketInOrganisation(scope, ":scopeTicketId", "scopeTicketOrg"))
                .param("scopeTicketId", ticketId)
                .param("scopeTicketOrg", organisationId),
            scope)
        .query(Boolean.class)
        .single();
  }

  /** Binds the organisation list when the condition uses it. */
  public static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, AccessScope scope) {
    Optional<Set<Long>> ids = scope.organisationIdsOrAll();
    if (ids.isPresent() && !ids.get().isEmpty()) {
      return spec.param(PARAM, ids.get());
    }
    return spec;
  }
}
