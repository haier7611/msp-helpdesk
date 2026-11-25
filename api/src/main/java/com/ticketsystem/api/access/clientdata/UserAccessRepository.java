package com.ticketsystem.api.access.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.UserAccess;
import com.ticketsystem.api.auth.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Which organisation a customer belongs to and which organisations an agent may work. Admin only.
 * Every change bumps the user's security_version, which ends their other sessions (C4).
 */
@Repository
public class UserAccessRepository {

  private final JdbcClient jdbc;

  public UserAccessRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UserAccess> find(AccessScope scope, long userId) {
    scope.requireAdmin();
    Optional<UserAccess> base =
        jdbc.sql(
                "SELECT id, role, organisation_id, all_organisations FROM app_user WHERE id ="
                    + " :id")
            .param("id", userId)
            .query(
                (rs, n) ->
                    new UserAccess(
                        rs.getLong("id"),
                        Role.valueOf(rs.getString("role")),
                        rs.getObject("organisation_id", Long.class),
                        rs.getBoolean("all_organisations"),
                        List.of()))
            .optional();
    if (base.isEmpty() || base.get().role() != Role.AGENT || base.get().allOrganisations()) {
      return base;
    }
    List<Long> orgs =
        jdbc.sql(
                "SELECT organisation_id FROM supporter_organisation WHERE user_id = :id ORDER BY"
                    + " organisation_id")
            .param("id", userId)
            .query(Long.class)
            .list();
    UserAccess a = base.get();
    return Optional.of(new UserAccess(a.userId(), a.role(), a.organisationId(), false, orgs));
  }

  /** Locks the user row (admin only). */
  public Optional<Role> lockRole(AccessScope scope, long userId) {
    scope.requireAdmin();
    return jdbc.sql("SELECT role FROM app_user WHERE id = :id FOR UPDATE")
        .param("id", userId)
        .query(String.class)
        .optional()
        .map(Role::valueOf);
  }

  /** The user's email address (empty when erased or missing); admin only. */
  public Optional<String> email(AccessScope scope, long userId) {
    scope.requireAdmin();
    return jdbc.sql("SELECT email FROM app_user WHERE id = :id AND email IS NOT NULL")
        .param("id", userId)
        .query(String.class)
        .optional();
  }

  /** Moves a customer to another organisation; returns rows changed (0 if not a customer). */
  public int setCustomerOrganisation(
      AccessScope scope, long userId, long organisationId, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "UPDATE app_user SET organisation_id = :org, security_version = security_version + 1,"
                + " updated_at = :now WHERE id = :id AND role = 'CUSTOMER'")
        .param("org", organisationId)
        .param("now", Timestamp.from(now))
        .param("id", userId)
        .update();
  }

  /**
   * Sets an agent's organisations: every organisation, or exactly {@code organisationIds}. Returns
   * rows changed (0 if the user is not an agent). A missing organisation id fails the foreign key.
   */
  public int setAgentAccess(
      AccessScope scope, long userId, boolean all, Set<Long> organisationIds, Instant now) {
    scope.requireAdmin();
    int changed =
        jdbc.sql(
                "UPDATE app_user SET all_organisations = :all, security_version ="
                    + " security_version + 1, updated_at = :now WHERE id = :id AND role = 'AGENT'")
            .param("all", all)
            .param("now", Timestamp.from(now))
            .param("id", userId)
            .update();
    if (changed == 0) {
      return 0;
    }
    jdbc.sql("DELETE FROM supporter_organisation WHERE user_id = :id").param("id", userId).update();
    if (!all) {
      for (long org : organisationIds) {
        jdbc.sql(
                "INSERT INTO supporter_organisation (user_id, organisation_id, created_at)"
                    + " VALUES (:id, :org, :now)")
            .param("id", userId)
            .param("org", org)
            .param("now", Timestamp.from(now))
            .update();
      }
    }
    return changed;
  }
}
