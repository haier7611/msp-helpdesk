package com.ticketsystem.api.notification.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import com.ticketsystem.api.notification.NotificationKind;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code notification}: the bell (the Notification centre). A supporter reads and marks only their
 * own notifications, and only those about organisations their scope still sees (the scope is
 * applied in SQL, so access removed later hides old notifications too). Customers and API keys get
 * nothing. Rows are written by the notification service with a SYSTEM or supporter scope.
 */
@Repository
public class NotificationRepository {

  /** One notification as listed (ticket subject read live through the scope). */
  public record NotificationRow(
      long id,
      NotificationKind kind,
      String detail,
      Long ticketId,
      Long organisationId,
      String ticketSubject,
      Instant createdAt,
      Instant readAt) {}

  private final JdbcClient jdbc;

  public NotificationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Stores a notification; a repeat with the same {@code dedupeKey} for the same user is ignored.
   * The insert only matches when the ticket is in {@code organisationId} (null = triage) and the
   * scope sees it; 404 otherwise, so a notice can never carry another organisation's ticket.
   *
   * @return true when a row was written
   */
  public boolean insert(
      AccessScope scope,
      long userId,
      Long organisationId,
      long ticketId,
      NotificationKind kind,
      String detail,
      String dedupeKey,
      Instant now) {
    scope.requireSupporterOrSystem();
    if (organisationId != null) {
      scope.require(organisationId);
    }
    boolean added =
        ScopeSql.bind(
                    jdbc.sql(
                            "INSERT INTO notification (user_id, organisation_id, ticket_id, kind,"
                                + " detail, dedupe_key, created_at) SELECT :user, :org, :ticket,"
                                + " :kind, :detail, :dedupe, :now WHERE "
                                + ScopeSql.ticketInOrganisation(scope, ":ticket", "org")
                                + " ON CONFLICT (user_id, dedupe_key) WHERE dedupe_key IS NOT"
                                + " NULL DO NOTHING")
                        .param("user", userId)
                        .param("org", organisationId)
                        .param("ticket", ticketId)
                        .param("kind", kind.name())
                        .param("detail", detail)
                        .param("dedupe", dedupeKey)
                        .param("now", Timestamp.from(now)),
                    scope)
                .update()
            == 1;
    if (!added && !ScopeSql.isTicketInOrganisation(jdbc, scope, ticketId, organisationId)) {
      throw AccessScope.notFound();
    }
    return added;
  }

  /** The caller's notifications, newest first. */
  public List<NotificationRow> listMine(AccessScope scope, Long beforeId, int limit) {
    long me = requireMe(scope);
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT n.id, n.kind, n.detail, n.ticket_id, n.organisation_id, n.created_at,"
                        + " n.read_at, t.subject FROM notification n LEFT JOIN ticket t ON t.id ="
                        + " n.ticket_id WHERE n.user_id = :me AND (CAST(:before AS BIGINT) IS NULL"
                        + " OR n.id < :before) AND "
                        + ScopeSql.organisationOrTriage(scope, "n.organisation_id")
                        + " ORDER BY n.id DESC LIMIT :limit")
                .param("me", me)
                .param("before", beforeId)
                .param("limit", limit),
            scope)
        .query(
            (rs, n) -> {
              Timestamp read = rs.getTimestamp("read_at");
              return new NotificationRow(
                  rs.getLong("id"),
                  NotificationKind.valueOf(rs.getString("kind")),
                  rs.getString("detail"),
                  rs.getObject("ticket_id", Long.class),
                  rs.getObject("organisation_id", Long.class),
                  rs.getString("subject"),
                  rs.getTimestamp("created_at").toInstant(),
                  read == null ? null : read.toInstant());
            })
        .list();
  }

  public long unreadCount(AccessScope scope) {
    long me = requireMe(scope);
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT count(*) FROM notification n WHERE n.user_id = :me AND n.read_at IS"
                        + " NULL AND "
                        + ScopeSql.organisationOrTriage(scope, "n.organisation_id"))
                .param("me", me),
            scope)
        .query(Long.class)
        .single();
  }

  /** Marks one of the caller's notifications read; false when it is not theirs or not visible. */
  public boolean markRead(AccessScope scope, long id, Instant now) {
    long me = requireMe(scope);
    return ScopeSql.bind(
                jdbc.sql(
                        "UPDATE notification n SET read_at = COALESCE(n.read_at, :now) WHERE n.id"
                            + " = :id AND n.user_id = :me AND "
                            + ScopeSql.organisationOrTriage(scope, "n.organisation_id"))
                    .param("now", Timestamp.from(now))
                    .param("id", id)
                    .param("me", me),
                scope)
            .update()
        == 1;
  }

  public int markAllRead(AccessScope scope, Instant now) {
    long me = requireMe(scope);
    return ScopeSql.bind(
            jdbc.sql(
                    "UPDATE notification n SET read_at = :now WHERE n.user_id = :me AND n.read_at"
                        + " IS NULL AND "
                        + ScopeSql.organisationOrTriage(scope, "n.organisation_id"))
                .param("now", Timestamp.from(now))
                .param("me", me),
            scope)
        .update();
  }

  private static long requireMe(AccessScope scope) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    return scope.principalId().orElseThrow(AccessScope::notFound);
  }
}
