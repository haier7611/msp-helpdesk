package com.ticketsystem.api.customer.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Admin - Customers: customer accounts across every organisation. Admin only - every method calls
 * {@link AccessScope#requireAdmin()} first (admins see every organisation, so there is no
 * organisation filter to add); the scope parameter keeps the class under the clientdata rules. Only
 * {@code role = 'CUSTOMER'} rows are ever read or changed: an agent's or admin's id answers empty.
 */
@Repository
public class AdminCustomerRepository {

  /** List filter for the status column; ERASED is derived from {@code erased_at}. */
  public enum StatusFilter {
    ACTIVE,
    INVITED,
    DEACTIVATED,
    ERASED
  }

  /** List order. */
  public enum Sort {
    NAME,
    ORGANISATION,
    LAST_SIGN_IN
  }

  /** One customer as the list shows it. {@code email} is null once erased. */
  public record CustomerRow(
      long id,
      String name,
      String email,
      long organisationId,
      String organisationName,
      String status,
      boolean erased,
      Instant lastSignInAt,
      Instant createdAt) {
    @Override
    public String toString() {
      return "CustomerRow[id=" + id + ", status=" + status + "]";
    }
  }

  /** The extra facts the detail panel shows. */
  public record CustomerFacts(
      boolean passwordSet,
      boolean microsoftLinked,
      long ticketsRaised,
      Instant invitationExpiresAt) {}

  /** The fields an action needs, read with a row lock. */
  public record LockedCustomer(
      long id,
      String name,
      String email,
      long organisationId,
      String status,
      boolean passwordSet,
      boolean erased) {
    @Override
    public String toString() {
      return "LockedCustomer[id=" + id + ", status=" + status + "]";
    }
  }

  // Last sign-in from the audit log, as Admin - Agents does (kept 1 year by retention).
  private static final String SELECT =
      "SELECT u.id, u.name, u.email, u.organisation_id, o.name AS organisation_name, u.status,"
          + " u.erased_at, u.created_at, s.last_sign_in"
          + " FROM app_user u JOIN organisation o ON o.id = u.organisation_id"
          + " LEFT JOIN LATERAL (SELECT max(a.occurred_at) AS last_sign_in FROM audit_event a"
          + "   WHERE a.target_user_id = u.id AND a.type = 'SIGN_IN_SUCCEEDED') s ON TRUE"
          + " WHERE u.role = 'CUSTOMER'";

  private final JdbcClient jdbc;

  public AdminCustomerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One page of customers. {@code status} null = everyone except erased customers (erased customers
   * are hidden by default).
   */
  public List<CustomerRow> search(
      AccessScope scope,
      String search,
      Long organisationId,
      StatusFilter status,
      Sort sort,
      int limit,
      int offset) {
    scope.requireAdmin();
    Map<String, Object> params = new HashMap<>();
    String where = where(search, organisationId, status, params);
    String order =
        switch (sort == null ? Sort.NAME : sort) {
          case NAME -> " ORDER BY lower(u.name), u.id";
          case ORGANISATION -> " ORDER BY lower(o.name), o.id, lower(u.name), u.id";
          case LAST_SIGN_IN -> " ORDER BY s.last_sign_in DESC NULLS LAST, lower(u.name), u.id";
        };
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbc.sql(SELECT + where + order + " LIMIT :limit OFFSET :offset")
        .params(params)
        .query(AdminCustomerRepository::map)
        .list();
  }

  /** How many customers match (same filter as {@link #search}). */
  public long count(AccessScope scope, String search, Long organisationId, StatusFilter status) {
    scope.requireAdmin();
    Map<String, Object> params = new HashMap<>();
    String where = where(search, organisationId, status, params);
    return jdbc.sql("SELECT count(*) FROM app_user u WHERE u.role = 'CUSTOMER'" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  /** One customer; empty when missing or not a customer. */
  public Optional<CustomerRow> find(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql(SELECT + " AND u.id = :id")
        .param("id", id)
        .query(AdminCustomerRepository::map)
        .optional();
  }

  /** Sign-in methods, tickets raised and a pending invitation's end. */
  public CustomerFacts facts(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT (u.password_hash IS NOT NULL) AS password_set,"
                + " EXISTS (SELECT 1 FROM external_identity x WHERE x.user_id = u.id) AS microsoft,"
                + " (SELECT count(*) FROM ticket t WHERE t.reporter_user_id = u.id) AS tickets,"
                + " (SELECT max(t.expires_at) FROM auth_token t WHERE t.user_id = u.id"
                + "   AND t.purpose = 'INVITATION' AND t.used_at IS NULL AND t.revoked_at IS NULL)"
                + "   AS invitation_expires_at"
                + " FROM app_user u WHERE u.id = :id AND u.role = 'CUSTOMER'")
        .param("id", id)
        .query(
            (rs, n) ->
                new CustomerFacts(
                    rs.getBoolean("password_set"),
                    rs.getBoolean("microsoft"),
                    rs.getLong("tickets"),
                    instant(rs, "invitation_expires_at")))
        .single();
  }

  /** Locks a customer row for an action; empty when missing or not a customer. */
  public Optional<LockedCustomer> lock(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT id, name, email, organisation_id, status, password_hash IS NOT NULL AS pw,"
                + " erased_at IS NOT NULL AS erased FROM app_user WHERE id = :id AND role ="
                + " 'CUSTOMER' FOR UPDATE")
        .param("id", id)
        .query(
            (rs, n) ->
                new LockedCustomer(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getLong("organisation_id"),
                    rs.getString("status"),
                    rs.getBoolean("pw"),
                    rs.getBoolean("erased")))
        .optional();
  }

  /** Sets the status of a customer that is not erased; returns rows changed. */
  public int setStatus(AccessScope scope, long id, String from, String to, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "UPDATE app_user SET status = :to, updated_at = :now WHERE id = :id AND role ="
                + " 'CUSTOMER' AND status = :from AND erased_at IS NULL")
        .param("to", to)
        .param("from", from)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /**
   * Moves a customer to another organisation. The caller bumps security_version (which ends their
   * sessions) in the same transaction.
   */
  public int setOrganisation(AccessScope scope, long id, long organisationId, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "UPDATE app_user SET organisation_id = :org, updated_at = :now WHERE id = :id AND"
                + " role = 'CUSTOMER' AND erased_at IS NULL")
        .param("org", organisationId)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** Revokes every unused link (invitation, reset, magic link) of the customer. */
  public int revokeUnusedTokens(AccessScope scope, long id, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "UPDATE auth_token SET revoked_at = :now WHERE user_id = :id AND used_at IS NULL"
                + " AND revoked_at IS NULL")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** Does the organisation exist? */
  public boolean organisationExists(AccessScope scope, long organisationId) {
    scope.requireAdmin();
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM organisation WHERE id = :id)")
        .param("id", organisationId)
        .query(Boolean.class)
        .single();
  }

  private static String where(
      String search, Long organisationId, StatusFilter status, Map<String, Object> params) {
    StringBuilder sql = new StringBuilder();
    if (organisationId != null) {
      sql.append(" AND u.organisation_id = :org");
      params.put("org", organisationId);
    }
    if (status == null) {
      sql.append(" AND u.erased_at IS NULL");
    } else if (status == StatusFilter.ERASED) {
      sql.append(" AND u.erased_at IS NOT NULL");
    } else {
      sql.append(" AND u.erased_at IS NULL AND u.status = :status");
      params.put("status", status.name());
    }
    if (search != null && !search.isBlank()) {
      String like =
          "%" + search.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      sql.append(" AND (u.name ILIKE :search OR u.email ILIKE :search)");
      params.put("search", like);
    }
    return sql.toString();
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static CustomerRow map(ResultSet rs, int n) throws SQLException {
    return new CustomerRow(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("email"),
        rs.getLong("organisation_id"),
        rs.getString("organisation_name"),
        rs.getString("status"),
        rs.getTimestamp("erased_at") != null,
        instant(rs, "last_sign_in"),
        instant(rs, "created_at"));
  }
}
