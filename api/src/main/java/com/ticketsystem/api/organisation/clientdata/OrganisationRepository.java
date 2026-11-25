package com.ticketsystem.api.organisation.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code organisation} and {@code organisation_domain}. Every read applies the scope in the query;
 * every write requires an admin scope (only admins create, rename or delete organisations and edit
 * verified domains).
 */
@Repository
public class OrganisationRepository {

  /** One organisation row. */
  public record OrganisationRow(
      long id,
      String name,
      String timezone,
      UUID entraTenantId,
      String status,
      Instant createdAt,
      Instant updatedAt) {}

  private static final String COLUMNS =
      "id, name, timezone, entra_tenant_id, status, created_at, updated_at";

  private final JdbcClient jdbc;

  public OrganisationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The organisations the scope may see, by name. */
  public List<OrganisationRow> list(AccessScope scope) {
    return ScopeSql.bind(
            jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM organisation WHERE "
                    + ScopeSql.organisation(scope, "id")
                    + " ORDER BY lower(name)"),
            scope)
        .query(OrganisationRepository::map)
        .list();
  }

  /** One organisation; empty when it does not exist or the scope may not see it. */
  public Optional<OrganisationRow> find(AccessScope scope, long id) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT "
                        + COLUMNS
                        + " FROM organisation WHERE id = :id AND "
                        + ScopeSql.organisation(scope, "id"))
                .param("id", id),
            scope)
        .query(OrganisationRepository::map)
        .optional();
  }

  /** Like {@link #find} and locks the row for the rest of the transaction (admin only). */
  public Optional<OrganisationRow> findForUpdate(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql("SELECT " + COLUMNS + " FROM organisation WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(OrganisationRepository::map)
        .optional();
  }

  /** Inserts an organisation; DuplicateKeyException when the name is taken. */
  public long insert(
      AccessScope scope, String name, String timezone, UUID entraTenantId, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "INSERT INTO organisation (name, timezone, entra_tenant_id, created_at, updated_at)"
                + " VALUES (:name, :tz, :tenant, :now, :now) RETURNING id")
        .param("name", name)
        .param("tz", timezone)
        .param("tenant", entraTenantId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  /** Updates name, timezone and Entra tenant id; DuplicateKeyException when the name is taken. */
  public int update(
      AccessScope scope, long id, String name, String timezone, UUID entraTenantId, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "UPDATE organisation SET name = :name, timezone = :tz, entra_tenant_id = :tenant,"
                + " updated_at = :now WHERE id = :id")
        .param("name", name)
        .param("tz", timezone)
        .param("tenant", entraTenantId)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** Marks the organisation changed (after a prefix or domain edit). */
  public void touch(AccessScope scope, long id, Instant now) {
    scope.requireAdmin();
    jdbc.sql("UPDATE organisation SET updated_at = :now WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** Deletes the organisation (its prefixes, counter, domains and agent links cascade). */
  public int delete(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql("DELETE FROM organisation WHERE id = :id").param("id", id).update();
  }

  /** Number of customer accounts in the organisation (blocks deleting it). */
  public long countCustomers(AccessScope scope, long id) {
    scope.require(id);
    return jdbc.sql("SELECT count(*) FROM app_user WHERE organisation_id = :id")
        .param("id", id)
        .query(Long.class)
        .single();
  }

  /** The organisation's verified domains, sorted; empty when not visible. */
  public List<String> domains(AccessScope scope, long organisationId) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT domain FROM organisation_domain WHERE organisation_id = :org AND "
                        + ScopeSql.organisation(scope, "organisation_id")
                        + " ORDER BY domain")
                .param("org", organisationId),
            scope)
        .query(String.class)
        .list();
  }

  /**
   * The organisation a verified domain belongs to (for email matching); empty when unknown or not
   * visible.
   */
  public Optional<Long> organisationIdForDomain(AccessScope scope, String domain) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT organisation_id FROM organisation_domain WHERE domain = :domain AND "
                        + ScopeSql.organisation(scope, "organisation_id"))
                .param("domain", domain),
            scope)
        .query(Long.class)
        .optional();
  }

  /** Who holds a domain (admin only, for a clear "already used by" message). */
  public Optional<Long> domainHolder(AccessScope scope, String domain) {
    scope.requireAdmin();
    return jdbc.sql("SELECT organisation_id FROM organisation_domain WHERE domain = :domain")
        .param("domain", domain)
        .query(Long.class)
        .optional();
  }

  /** Adds a verified domain; DuplicateKeyException when any organisation already has it. */
  public void insertDomain(AccessScope scope, long organisationId, String domain, Instant now) {
    scope.requireAdmin();
    jdbc.sql(
            "INSERT INTO organisation_domain (domain, organisation_id, created_at)"
                + " VALUES (:domain, :org, :now)")
        .param("domain", domain)
        .param("org", organisationId)
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * Is the domain already any organisation's verified domain? Supporters and admins (triage
   * "remember" tick); says yes or no only, never which organisation.
   */
  public boolean domainTaken(AccessScope scope, String domain) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM organisation_domain WHERE domain = :domain)")
        .param("domain", domain)
        .query(Boolean.class)
        .single();
  }

  /**
   * Adds a verified domain from the triage "Add domain" tick: any supporter who works {@code
   * organisationId}, not only admins. The caller has refused public domains. Throws
   * DuplicateKeyException when any organisation already has it.
   */
  public void insertDomainFromTriage(
      AccessScope scope, long organisationId, String domain, Instant now) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    scope.require(organisationId);
    jdbc.sql(
            "INSERT INTO organisation_domain (domain, organisation_id, created_at)"
                + " VALUES (:domain, :org, :now)")
        .param("domain", domain)
        .param("org", organisationId)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Removes a verified domain from the organisation; returns the number removed (0 or 1). */
  public int deleteDomain(AccessScope scope, long organisationId, String domain) {
    scope.requireAdmin();
    return jdbc.sql(
            "DELETE FROM organisation_domain WHERE organisation_id = :org AND domain = :domain")
        .param("org", organisationId)
        .param("domain", domain)
        .update();
  }

  private static OrganisationRow map(ResultSet rs, int row) throws SQLException {
    return new OrganisationRow(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("timezone"),
        rs.getObject("entra_tenant_id", UUID.class),
        rs.getString("status"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
