package com.ticketsystem.api.contact.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Customer accounts ("contacts") as supporters see them in the agent UI. Every read is limited to
 * customers of organisations the scope sees, in SQL; supporters only.
 */
@Repository
public class ContactRepository {

  /** One contact with its organisation's name. */
  public record ContactRow(
      long id,
      String name,
      String email,
      long organisationId,
      String organisationName,
      String status) {}

  /** Who already holds an email address (any role), for the create and triage checks. */
  public record AccountRow(long id, String role, Long organisationId, String status) {}

  private static final String SELECT =
      "SELECT u.id, u.name, u.email, u.organisation_id, o.name AS organisation_name, u.status"
          + " FROM app_user u JOIN organisation o ON o.id = u.organisation_id"
          + " WHERE u.role = 'CUSTOMER' AND u.status IN ('ACTIVE', 'INVITED') AND ";

  private final JdbcClient jdbc;

  public ContactRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Contacts matching {@code search} (name or email) in one organisation or all visible ones. */
  public List<ContactRow> search(
      AccessScope scope, String search, Long organisationId, int limit, int offset) {
    Filter f = filter(scope, search, organisationId);
    JdbcClient.StatementSpec spec =
        jdbc.sql(SELECT + f.sql + " ORDER BY lower(u.name), u.id LIMIT :limit OFFSET :offset")
            .param("limit", limit)
            .param("offset", offset);
    return ScopeSql.bind(f.bind(spec), scope).query(ContactRepository::map).list();
  }

  /** How many contacts match (same filter as {@link #search}). */
  public long count(AccessScope scope, String search, Long organisationId) {
    Filter f = filter(scope, search, organisationId);
    return ScopeSql.bind(
            f.bind(
                jdbc.sql(
                    "SELECT count(*) FROM app_user u WHERE u.role = 'CUSTOMER' AND u.status IN"
                        + " ('ACTIVE', 'INVITED') AND "
                        + f.sql)),
            scope)
        .query(Long.class)
        .single();
  }

  /** One contact; empty when missing, not a customer or in an organisation the scope cannot see. */
  public Optional<ContactRow> find(AccessScope scope, long id) {
    requireSupporter(scope);
    return ScopeSql.bind(
            jdbc.sql(SELECT + "u.id = :id AND " + ScopeSql.organisation(scope, "u.organisation_id"))
                .param("id", id),
            scope)
        .query(ContactRepository::map)
        .optional();
  }

  /**
   * The account holding {@code email} (normalised), of any role or organisation, or empty. Used
   * only to refuse a duplicate or reuse a same-organisation contact; the caller never shows another
   * organisation's details.
   */
  public Optional<AccountRow> accountByEmail(AccessScope scope, String email) {
    requireSupporter(scope);
    return jdbc.sql(
            "SELECT id, role, organisation_id, status FROM app_user WHERE lower(email) ="
                + " lower(:email)")
        .param("email", email)
        .query(
            (rs, n) ->
                new AccountRow(
                    rs.getLong("id"),
                    rs.getString("role"),
                    rs.getObject("organisation_id", Long.class),
                    rs.getString("status")))
        .optional();
  }

  /**
   * A new customer of {@code organisationId}, status INVITED with no password, so they cannot sign
   * in until invited. DuplicateKeyException when the email is taken.
   */
  public long insert(
      AccessScope scope, long organisationId, String email, String name, Instant now) {
    requireSupporter(scope);
    scope.require(organisationId);
    return jdbc.sql(
            "INSERT INTO app_user (email, name, role, status, organisation_id, created_at,"
                + " updated_at) VALUES (:email, :name, 'CUSTOMER', 'INVITED', :org, :now, :now)"
                + " RETURNING id")
        .param("email", email)
        .param("name", name)
        .param("org", organisationId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  private record Filter(String sql, String search, Long organisationId) {
    JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec) {
      if (search != null) {
        spec = spec.param("search", search);
      }
      if (organisationId != null) {
        spec = spec.param("org", organisationId);
      }
      return spec;
    }
  }

  private static Filter filter(AccessScope scope, String search, Long organisationId) {
    requireSupporter(scope);
    StringBuilder sql = new StringBuilder(ScopeSql.organisation(scope, "u.organisation_id"));
    String like = null;
    if (organisationId != null) {
      scope.require(organisationId);
      sql.append(" AND u.organisation_id = :org");
    }
    if (search != null && !search.isBlank()) {
      like =
          "%" + search.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      sql.append(" AND (u.name ILIKE :search OR u.email ILIKE :search)");
    }
    return new Filter(sql.toString(), like, organisationId);
  }

  private static void requireSupporter(AccessScope scope) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
  }

  private static ContactRow map(ResultSet rs, int row) throws SQLException {
    return new ContactRow(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("email"),
        rs.getLong("organisation_id"),
        rs.getString("organisation_name"),
        rs.getString("status"));
  }
}
