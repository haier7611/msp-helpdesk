package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code internal_note}: supporters only. Customer and API-key scopes read nothing and write
 * nothing here, whatever ticket id they pass; supporters only for organisations their scope sees.
 * Only {@code ..ticket.agent..} and this package may use this class (ArchUnit). There is no method
 * that copies a note into a public comment.
 */
@Repository
public class InternalNoteRepository {

  public enum AuthorKind {
    SUPPORTER,
    SYSTEM
  }

  public record InternalNoteRow(
      long id,
      long ticketId,
      AuthorKind authorKind,
      Long authorUserId,
      String bodyHtml,
      boolean receivedByEmail,
      Instant createdAt) {}

  private final JdbcClient jdbc;

  public InternalNoteRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long insert(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      AuthorKind authorKind,
      Long authorUserId,
      String bodyHtml,
      boolean receivedByEmail,
      String messageId,
      String authResults,
      Instant now) {
    TicketScopes.requireSupporterOrSystem(scope, organisationId);
    return ScopeSql.bind(
            jdbc.sql(
                    "INSERT INTO internal_note (ticket_id, author_kind, author_user_id, body_html,"
                        + " received_by_email, message_id, auth_results, created_at) SELECT"
                        + " :ticket, :kind, :user, :body, :byEmail, :messageId, :auth, :now WHERE "
                        + ScopeSql.ticketVisible(scope, ":ticket")
                        + " RETURNING id")
                .param("ticket", ticketId)
                .param("kind", authorKind.name())
                .param("user", authorUserId)
                .param("body", bodyHtml)
                .param("byEmail", receivedByEmail)
                .param("messageId", messageId)
                .param("auth", authResults)
                .param("now", Timestamp.from(now)),
            scope)
        .query(Long.class)
        .optional()
        .orElseThrow(AccessScope::notFound);
  }

  /**
   * The ticket of an emailed note ({@code received_by_email}), if the scope sees it (for the
   * attachment worker finishing that note).
   */
  public java.util.Optional<Long> ticketOfEmailNote(AccessScope scope, long noteId) {
    if (!scope.isSupporter() && !scope.isSystem()) {
      return java.util.Optional.empty();
    }
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT n.ticket_id FROM internal_note n JOIN ticket t ON t.id = n.ticket_id"
                        + " WHERE n.id = :id AND n.received_by_email AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id"))
                .param("id", noteId),
            scope)
        .query(Long.class)
        .optional();
  }

  /**
   * The final body of an emailed note once its files are stored (inline images pointing at them
   * instead of the "[image]" placeholders). The only change ever made to a note's body, and only to
   * one received by email; the caller holds the ticket's lock.
   */
  public void replaceEmailBody(
      AccessScope scope, Long organisationId, long noteId, String bodyHtml) {
    TicketScopes.requireSupporterOrSystem(scope, organisationId);
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "UPDATE internal_note n SET body_html = :body WHERE n.id = :id AND"
                            + " n.received_by_email AND "
                            + ScopeSql.ticketVisible(scope, "n.ticket_id"))
                    .param("body", bodyHtml)
                    .param("id", noteId),
                scope)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
  }

  /** The ticket's notes, oldest first; always empty for customer and API-key scopes. */
  public List<InternalNoteRow> listForTicket(AccessScope scope, long ticketId) {
    if (!scope.isSupporter() && !scope.isSystem()) {
      return List.of();
    }
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT n.id, n.ticket_id, n.author_kind, n.author_user_id, n.body_html,"
                        + " n.received_by_email, n.created_at FROM internal_note n JOIN ticket t"
                        + " ON t.id = n.ticket_id WHERE n.ticket_id = :ticket AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id")
                        + " ORDER BY n.id")
                .param("ticket", ticketId),
            scope)
        .query(InternalNoteRepository::map)
        .list();
  }

  private static InternalNoteRow map(ResultSet rs, int row) throws SQLException {
    return new InternalNoteRow(
        rs.getLong("id"),
        rs.getLong("ticket_id"),
        AuthorKind.valueOf(rs.getString("author_kind")),
        rs.getObject("author_user_id", Long.class),
        rs.getString("body_html"),
        rs.getBoolean("received_by_email"),
        rs.getTimestamp("created_at").toInstant());
  }
}
