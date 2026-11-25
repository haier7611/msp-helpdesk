package com.ticketsystem.api.reference.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code organisation_prefix}. Prefixes are stored upper case; uniqueness across active, retired
 * and reserved rows is enforced by the database (unique index on {@code upper(prefix)}), so two
 * admins racing for one prefix cannot both win.
 */
@Repository
public class PrefixRepository {

  /** One prefix row of an organisation. */
  public record PrefixRow(String prefix, String status, Instant createdAt) {}

  private final JdbcClient jdbc;

  public PrefixRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The organisation's active prefix; empty when not visible to the scope. */
  public Optional<String> activePrefix(AccessScope scope, long organisationId) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT prefix FROM organisation_prefix WHERE organisation_id = :org AND"
                        + " status = 'ACTIVE' AND "
                        + ScopeSql.organisation(scope, "organisation_id"))
                .param("org", organisationId),
            scope)
        .query(String.class)
        .optional();
  }

  /** Every prefix the organisation has had, newest first; empty list when not visible. */
  public List<PrefixRow> listForOrganisation(AccessScope scope, long organisationId) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT prefix, status, created_at FROM organisation_prefix"
                        + " WHERE organisation_id = :org AND "
                        + ScopeSql.organisation(scope, "organisation_id")
                        + " ORDER BY created_at DESC, prefix")
                .param("org", organisationId),
            scope)
        .query(
            (rs, n) ->
                new PrefixRow(
                    rs.getString("prefix"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /**
   * The organisation that owns {@code prefix} (active or retired); empty for TRI, an unknown prefix
   * or an organisation the scope may not see.
   */
  public Optional<Long> organisationIdForPrefix(AccessScope scope, String prefix) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT organisation_id FROM organisation_prefix WHERE upper(prefix) ="
                        + " upper(:prefix) AND organisation_id IS NOT NULL AND "
                        + ScopeSql.organisation(scope, "organisation_id"))
                .param("prefix", prefix),
            scope)
        .query(Long.class)
        .optional();
  }

  /** Who holds {@code prefix}: the owning organisation id, 0 for reserved, empty if free. */
  public Optional<Long> holder(AccessScope scope, String prefix) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT COALESCE(organisation_id, 0) FROM organisation_prefix"
                + " WHERE upper(prefix) = upper(:prefix)")
        .param("prefix", prefix)
        .query(Long.class)
        .optional();
  }

  /** Adds a new active prefix. Throws DuplicateKeyException if the prefix is taken. */
  public void insertActive(AccessScope scope, long organisationId, String prefix, Instant now) {
    scope.requireAdmin();
    jdbc.sql(
            "INSERT INTO organisation_prefix (prefix, organisation_id, status, created_at)"
                + " VALUES (:prefix, :org, 'ACTIVE', :now)")
        .param("prefix", prefix)
        .param("org", organisationId)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Retires the organisation's active prefix (it stays reserved to the organisation). */
  public int retireActive(AccessScope scope, long organisationId) {
    scope.requireAdmin();
    return jdbc.sql(
            "UPDATE organisation_prefix SET status = 'RETIRED' WHERE organisation_id = :org AND"
                + " status = 'ACTIVE'")
        .param("org", organisationId)
        .update();
  }

  /** Makes one of the organisation's own retired prefixes active again. */
  public int reactivate(AccessScope scope, long organisationId, String prefix) {
    scope.requireAdmin();
    return jdbc.sql(
            "UPDATE organisation_prefix SET status = 'ACTIVE' WHERE organisation_id = :org AND"
                + " prefix = :prefix AND status = 'RETIRED'")
        .param("org", organisationId)
        .param("prefix", prefix)
        .update();
  }
}
