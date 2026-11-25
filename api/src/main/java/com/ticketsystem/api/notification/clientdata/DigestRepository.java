package com.ticketsystem.api.notification.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The daily digest: the open tickets a supporter should look at, read through that supporter's own
 * {@link AccessScope}, and the once-per-local-day log.
 */
@Repository
public class DigestRepository {

  /** A ticket line in the digest. */
  public record DigestTicket(
      long id,
      long organisationId,
      String subject,
      Priority priority,
      TicketStatus status,
      String slaWarning,
      boolean mine) {}

  private final JdbcClient jdbc;

  public DigestRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Open tickets assigned to the scope's supporter, plus at-risk or breached tickets of any
   * organisation the scope sees; breaching first, then oldest.
   */
  public List<DigestTicket> openAndBreaching(AccessScope scope, int limit) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    long me = scope.principalId().orElseThrow(AccessScope::notFound);
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT id, organisation_id, subject, priority, status, sla_warning,"
                        + " (assignee_user_id = :me) AS mine FROM ticket WHERE organisation_id IS"
                        + " NOT NULL AND status NOT IN ('RESOLVED', 'CLOSED') AND"
                        + " (assignee_user_id = :me OR sla_warning <> 'NONE') AND "
                        + ScopeSql.organisation(scope, "organisation_id")
                        + " ORDER BY (sla_warning = 'BREACHED') DESC, (sla_warning = 'AT_RISK')"
                        + " DESC, created_at, id LIMIT :limit")
                .param("me", me)
                .param("limit", limit),
            scope)
        .query(
            (rs, n) ->
                new DigestTicket(
                    rs.getLong("id"),
                    rs.getLong("organisation_id"),
                    rs.getString("subject"),
                    Priority.valueOf(rs.getString("priority")),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getString("sla_warning"),
                    rs.getBoolean("mine")))
        .list();
  }

  /**
   * Records the digest of {@code localDate} for the user.
   *
   * @return false when it was already sent (another runner, or a repeat run)
   */
  public boolean claim(AccessScope scope, long userId, LocalDate localDate, Instant now) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
    return jdbc.sql(
                "INSERT INTO notification_digest_log (user_id, local_date, sent_at) VALUES (:u,"
                    + " :d, :now) ON CONFLICT DO NOTHING")
            .param("u", userId)
            .param("d", Date.valueOf(localDate))
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }
}
