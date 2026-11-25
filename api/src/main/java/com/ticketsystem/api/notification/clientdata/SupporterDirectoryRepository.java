package com.ticketsystem.api.notification.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who may be told about an organisation's tickets: active supporters (agents and admins) who can
 * work it - admins and all-organisation agents always, restricted agents only for their listed
 * organisations. Supporter or SYSTEM scope that sees the organisation.
 */
@Repository
public class SupporterDirectoryRepository {

  /** An active supporter. */
  public record SupporterRow(long userId, String name, String email) {}

  private final JdbcClient jdbc;

  public SupporterDirectoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Every active supporter who can work {@code organisationId}, by id. */
  public List<SupporterRow> activeFor(AccessScope scope, long organisationId) {
    scope.requireSupporterOrSystem();
    scope.require(organisationId);
    return jdbc.sql(
            "SELECT u.id, u.name, u.email FROM app_user u WHERE u.status = 'ACTIVE' AND (u.role ="
                + " 'ADMIN' OR (u.role = 'AGENT' AND (u.all_organisations OR EXISTS (SELECT 1 FROM"
                + " supporter_organisation s WHERE s.user_id = u.id AND s.organisation_id ="
                + " :org)))) ORDER BY u.id")
        .param("org", organisationId)
        .query((rs, n) -> new SupporterRow(rs.getLong(1), rs.getString(2), rs.getString(3)))
        .list();
  }

  /**
   * The subset of {@code userIds} who are active supporters able to work the organisation; for a
   * triage ticket ({@code organisationId} null) every active supporter qualifies (triage is visible
   * to every supporter).
   */
  public List<SupporterRow> activeAmong(
      AccessScope scope, Long organisationId, Collection<Long> userIds) {
    if (userIds.isEmpty()) {
      return List.of();
    }
    List<SupporterRow> all =
        organisationId == null ? allActiveSupporters(scope) : activeFor(scope, organisationId);
    return all.stream().filter(s -> userIds.contains(s.userId())).toList();
  }

  private List<SupporterRow> allActiveSupporters(AccessScope scope) {
    scope.requireSupporterOrSystem();
    return jdbc.sql(
            "SELECT id, name, email FROM app_user WHERE status = 'ACTIVE' AND role IN ('AGENT',"
                + " 'ADMIN') ORDER BY id")
        .query((rs, n) -> new SupporterRow(rs.getLong(1), rs.getString(2), rs.getString(3)))
        .list();
  }

  /** Every active supporter (for the daily digest). SYSTEM only. */
  public List<SupporterRow> allActive(AccessScope scope) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
    return jdbc.sql(
            "SELECT id, name, email FROM app_user WHERE status = 'ACTIVE' AND role IN ('AGENT',"
                + " 'ADMIN') ORDER BY id")
        .query((rs, n) -> new SupporterRow(rs.getLong(1), rs.getString(2), rs.getString(3)))
        .list();
  }
}
