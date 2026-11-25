package com.ticketsystem.api.supporter.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Admin - Manage supporters (C18): supporter accounts (agents and admins), their organisation
 * access, MFA state, last sign-in and audit trail. Supporters belong to no client organisation, so
 * every method is admin only ({@link AccessScope#requireAdmin()}); the scope parameter keeps the
 * class under the clientdata rules. Customers are never returned: every query is limited to {@code
 * AGENT}/{@code ADMIN}.
 */
@Repository
public class SupporterRepository {

  /** A supporter row as the list and the side panel show it. */
  public record SupporterRow(
      long id,
      String email,
      String name,
      Role role,
      UserStatus status,
      String jobTitle,
      boolean allOrganisations,
      List<Long> organisationIds,
      boolean mfaEnrolled,
      Instant lastSignInAt,
      boolean outOfOffice,
      LocalDate outOfOfficeUntil,
      Instant invitationExpiresAt,
      Instant createdAt,
      Instant mfaExemptUntil,
      String mfaExemptReason) {
    @Override
    public String toString() {
      return "SupporterRow[id=" + id + ", role=" + role + ", status=" + status + "]";
    }
  }

  /** The fields a guard or an action needs, read with a row lock. */
  public record LockedSupporter(long id, String email, String name, Role role, UserStatus status) {
    @Override
    public String toString() {
      return "LockedSupporter[id=" + id + ", role=" + role + ", status=" + status + "]";
    }
  }

  /** One audit event about or by a supporter (details are the allow-listed fields only). */
  public record ActivityRow(
      long id,
      Instant occurredAt,
      String type,
      Long actorUserId,
      String actorName,
      Long targetUserId,
      String targetName,
      String clientIp,
      String detailsJson) {}

  private static final String SELECT =
      "SELECT u.id, u.email, u.name, u.role, u.status, u.all_organisations, u.created_at,"
          + " COALESCE(p.job_title, '') AS job_title, (m.confirmed_at IS NOT NULL) AS mfa,"
          + " (SELECT max(a.occurred_at) FROM audit_event a WHERE a.target_user_id = u.id"
          + "   AND a.type = 'SIGN_IN_SUCCEEDED') AS last_sign_in,"
          + " COALESCE(pr.out_of_office, FALSE) AS ooo, pr.ooo_ends_on,"
          + " (SELECT max(t.expires_at) FROM auth_token t WHERE t.user_id = u.id"
          + "   AND t.purpose = 'INVITATION' AND t.used_at IS NULL AND t.revoked_at IS NULL)"
          + "   AS invitation_expires_at,"
          + " e.ends_at AS mfa_exempt_until, e.reason AS mfa_exempt_reason"
          + " FROM app_user u"
          + " LEFT JOIN supporter_profile p ON p.user_id = u.id"
          + " LEFT JOIN user_mfa m ON m.user_id = u.id"
          + " LEFT JOIN supporter_preferences pr ON pr.user_id = u.id"
          + " LEFT JOIN mfa_exemption e ON e.user_id = u.id"
          + " WHERE u.role IN ('AGENT', 'ADMIN')";

  private final JdbcClient jdbc;

  public SupporterRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Every supporter, optionally filtered by role and status, sorted by name. */
  public List<SupporterRow> list(AccessScope scope, Role role, UserStatus status) {
    scope.requireAdmin();
    StringBuilder sql = new StringBuilder(SELECT);
    Map<String, Object> params = new HashMap<>();
    if (role != null) {
      sql.append(" AND u.role = :role");
      params.put("role", role.name());
    }
    if (status != null) {
      sql.append(" AND u.status = :status");
      params.put("status", status.name());
    }
    sql.append(" ORDER BY lower(u.name), u.id");
    List<SupporterRow> rows =
        jdbc.sql(sql.toString()).params(params).query(SupporterRepository::map).list();
    Map<Long, List<Long>> orgs = organisationsByUser(rows);
    return rows.stream().map(r -> withOrganisations(r, orgs)).toList();
  }

  public Optional<SupporterRow> find(AccessScope scope, long id) {
    scope.requireAdmin();
    Optional<SupporterRow> row =
        jdbc.sql(SELECT + " AND u.id = :id")
            .param("id", id)
            .query(SupporterRepository::map)
            .optional();
    return row.map(r -> withOrganisations(r, organisationsByUser(List.of(r))));
  }

  /**
   * Locks every active admin row, in id order, for the rest of the transaction and returns their
   * ids. The last-admin guard takes this lock first, so two admins demoting or deactivating each
   * other run one after the other; Postgres re-checks the WHERE clause after waiting, so the second
   * sees the first one's change.
   */
  public List<Long> lockActiveAdminIds(AccessScope scope) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT id FROM app_user WHERE role = 'ADMIN' AND status = 'ACTIVE' ORDER BY id"
                + " FOR UPDATE")
        .query(Long.class)
        .list();
  }

  /** Locks one supporter row (customers are not found). */
  public Optional<LockedSupporter> lock(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT id, email, name, role, status FROM app_user WHERE id = :id"
                + " AND role IN ('AGENT', 'ADMIN') FOR UPDATE")
        .param("id", id)
        .query(
            (rs, n) ->
                new LockedSupporter(
                    rs.getLong("id"),
                    rs.getString("email"),
                    rs.getString("name"),
                    Role.valueOf(rs.getString("role")),
                    UserStatus.valueOf(rs.getString("status"))))
        .optional();
  }

  /** Is {@code id} an active supporter (a valid reassignment target)? */
  public boolean isActiveSupporter(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :id AND role IN ('AGENT', 'ADMIN')"
                + " AND status = 'ACTIVE')")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  /** Admin edit of name and job title. */
  public void updateNameAndTitle(
      AccessScope scope, long id, String name, String jobTitle, Instant now) {
    scope.requireAdmin();
    jdbc.sql(
            "UPDATE app_user SET name = :name, updated_at = :now WHERE id = :id"
                + " AND role IN ('AGENT', 'ADMIN')")
        .param("name", name)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
    jdbc.sql(
            "INSERT INTO supporter_profile (user_id, job_title, updated_at)"
                + " VALUES (:id, :title, :now)"
                + " ON CONFLICT (user_id) DO UPDATE SET job_title = :title, updated_at = :now")
        .param("id", id)
        .param("title", jobTitle)
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * Changes a supporter's role. Promotion to admin also sets "every organisation" (admins always
   * see every organisation) and clears the agent's list. The caller bumps security_version.
   */
  public void setRole(AccessScope scope, long id, Role role, Instant now) {
    scope.requireAdmin();
    if (!role.isSupporter()) {
      throw new IllegalArgumentException("supporter roles only");
    }
    jdbc.sql(
            "UPDATE app_user SET role = :role,"
                + " all_organisations = CASE WHEN :role = 'ADMIN' THEN TRUE"
                + "   ELSE all_organisations END,"
                + " updated_at = :now WHERE id = :id AND role IN ('AGENT', 'ADMIN')")
        .param("role", role.name())
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
    if (role == Role.ADMIN) {
      jdbc.sql("DELETE FROM supporter_organisation WHERE user_id = :id").param("id", id).update();
    }
  }

  /** ACTIVE <-> DEACTIVATED (never touches invited accounts). The caller bumps the version. */
  public int setStatus(AccessScope scope, long id, UserStatus from, UserStatus to, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "UPDATE app_user SET status = :to, updated_at = :now WHERE id = :id"
                + " AND role IN ('AGENT', 'ADMIN') AND status = :from")
        .param("to", to.name())
        .param("from", from.name())
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** Deletes a supporter who never accepted their invitation (revoke); tokens cascade. */
  public int deleteInvited(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql(
            "DELETE FROM app_user WHERE id = :id AND role IN ('AGENT', 'ADMIN')"
                + " AND status = 'INVITED'")
        .param("id", id)
        .update();
  }

  /** Revokes every unused link token of the user (invitation, reset); returns how many. */
  public int revokeUnusedTokens(AccessScope scope, long id, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "UPDATE auth_token SET revoked_at = :now WHERE user_id = :id AND used_at IS NULL"
                + " AND revoked_at IS NULL")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** Audit events where the supporter is the target or the actor, newest first. */
  public List<ActivityRow> activity(AccessScope scope, long id, int limit) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT e.id, e.occurred_at, e.type, e.actor_user_id, a.name AS actor_name,"
                + " e.target_user_id, t.name AS target_name, e.client_ip,"
                + " e.details::text AS details"
                + " FROM audit_event e"
                + " LEFT JOIN app_user a ON a.id = e.actor_user_id"
                + " LEFT JOIN app_user t ON t.id = e.target_user_id"
                + " WHERE e.target_user_id = :id OR e.actor_user_id = :id"
                + " ORDER BY e.occurred_at DESC, e.id DESC LIMIT :limit")
        .param("id", id)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new ActivityRow(
                    rs.getLong("id"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("type"),
                    rs.getObject("actor_user_id", Long.class),
                    rs.getString("actor_name"),
                    rs.getObject("target_user_id", Long.class),
                    rs.getString("target_name"),
                    rs.getString("client_ip"),
                    rs.getString("details")))
        .list();
  }

  private Map<Long, List<Long>> organisationsByUser(List<SupporterRow> rows) {
    List<Long> ids =
        rows.stream()
            .filter(r -> r.role() == Role.AGENT && !r.allOrganisations())
            .map(SupporterRow::id)
            .toList();
    Map<Long, List<Long>> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.sql(
            "SELECT user_id, organisation_id FROM supporter_organisation WHERE user_id IN (:ids)"
                + " ORDER BY organisation_id")
        .param("ids", ids)
        .query(
            (ResultSet rs) -> {
              out.computeIfAbsent(rs.getLong("user_id"), k -> new ArrayList<>())
                  .add(rs.getLong("organisation_id"));
            });
    return out;
  }

  private static SupporterRow withOrganisations(SupporterRow r, Map<Long, List<Long>> orgs) {
    return new SupporterRow(
        r.id(),
        r.email(),
        r.name(),
        r.role(),
        r.status(),
        r.jobTitle(),
        r.allOrganisations() || r.role() == Role.ADMIN,
        r.role() == Role.ADMIN ? List.of() : List.copyOf(orgs.getOrDefault(r.id(), List.of())),
        r.mfaEnrolled(),
        r.lastSignInAt(),
        r.outOfOffice(),
        r.outOfOfficeUntil(),
        r.invitationExpiresAt(),
        r.createdAt(),
        r.mfaExemptUntil(),
        r.mfaExemptReason());
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static SupporterRow map(ResultSet rs, int n) throws SQLException {
    java.sql.Date until = rs.getDate("ooo_ends_on");
    return new SupporterRow(
        rs.getLong("id"),
        rs.getString("email"),
        rs.getString("name"),
        Role.valueOf(rs.getString("role")),
        UserStatus.valueOf(rs.getString("status")),
        rs.getString("job_title"),
        rs.getBoolean("all_organisations"),
        List.of(),
        rs.getBoolean("mfa"),
        instant(rs, "last_sign_in"),
        rs.getBoolean("ooo"),
        until == null ? null : until.toLocalDate(),
        instant(rs, "invitation_expires_at"),
        instant(rs, "created_at"),
        instant(rs, "mfa_exempt_until"),
        rs.getString("mfa_exempt_reason"));
  }
}
