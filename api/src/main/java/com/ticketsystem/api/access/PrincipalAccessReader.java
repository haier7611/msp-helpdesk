package com.ticketsystem.api.access;

import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the signed-in user's own access facts (role, status, security_version, organisation,
 * supporter organisations) so {@link AccessScopeFilter} can build the request's {@link
 * AccessScope}. This is the bootstrap of scoping and therefore cannot itself take a scope: it only
 * ever reads the row of the user the session belongs to, and returns no client data.
 */
@Component
public class PrincipalAccessReader {

  /** What the filter needs about the session's user, read fresh on every request. */
  public record PrincipalAccess(
      long userId,
      Role role,
      UserStatus status,
      int securityVersion,
      Long organisationId,
      boolean allOrganisations,
      Set<Long> supporterOrganisationIds) {

    public AccessScope toScope() {
      return switch (role) {
        case CUSTOMER -> AccessScope.customer(userId, organisationId);
        case ADMIN -> AccessScope.admin(userId);
        case AGENT -> AccessScope.agent(userId, allOrganisations, supporterOrganisationIds);
      };
    }
  }

  private final JdbcClient jdbc;

  public PrincipalAccessReader(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<PrincipalAccess> read(long userId) {
    Optional<PrincipalAccess> base =
        jdbc.sql(
                "SELECT id, role, status, security_version, organisation_id, all_organisations"
                    + " FROM app_user WHERE id = ?")
            .param(userId)
            .query(
                (rs, n) ->
                    new PrincipalAccess(
                        rs.getLong("id"),
                        Role.valueOf(rs.getString("role")),
                        UserStatus.valueOf(rs.getString("status")),
                        rs.getInt("security_version"),
                        rs.getObject("organisation_id", Long.class),
                        rs.getBoolean("all_organisations"),
                        Set.of()))
            .optional();
    if (base.isEmpty() || base.get().role() != Role.AGENT || base.get().allOrganisations()) {
      return base;
    }
    List<Long> orgs =
        jdbc.sql("SELECT organisation_id FROM supporter_organisation WHERE user_id = ?")
            .param(userId)
            .query(Long.class)
            .list();
    PrincipalAccess p = base.get();
    return Optional.of(
        new PrincipalAccess(
            p.userId(),
            p.role(),
            p.status(),
            p.securityVersion(),
            p.organisationId(),
            false,
            Set.copyOf(orgs)));
  }
}
