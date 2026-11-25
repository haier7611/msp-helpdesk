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
 * {@code public_comment}: supporters' public replies and client replies. A separate table from
 * internal notes, so nothing here can ever return a note. A sent public reply cannot be edited
 * (C12): the only body update is {@link #replaceEmailBody}, which puts the stored inline images
 * into an email's comment once its files are processed. Reads are scoped through the ticket's
 * organisation.
 */
@Repository
public class PublicCommentRepository {

  public enum AuthorKind {
    SUPPORTER,
    CLIENT,
    API_KEY
  }

  public record PublicCommentRow(
      long id,
      long ticketId,
      AuthorKind authorKind,
      Long authorUserId,
      String authorEmail,
      String bodyHtml,
      boolean receivedByEmail,
      Instant createdAt) {}

  /** A new comment. {@code messageId} and {@code authResults} are for email. */
  public record NewPublicComment(
      long ticketId,
      AuthorKind authorKind,
      Long authorUserId,
      String authorEmail,
      String bodyHtml,
      boolean receivedByEmail,
      String messageId,
      String authResults) {}

  /**
   * One address of the CC an agent chose for a public reply. {@code userId} is set for a contact of
   * the ticket's organisation; {@code oneOff} = CC'd on this reply only.
   */
  public record CcRecipient(String email, Long userId, boolean oneOff) {}

  private final JdbcClient jdbc;

  public PublicCommentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Stores a comment on a ticket in {@code organisationId} (null = triage); the caller holds the
   * ticket's lock. The insert only matches a ticket the scope sees: 404 otherwise, whatever {@code
   * organisationId} says.
   */
  public long insert(AccessScope scope, Long organisationId, NewPublicComment c, Instant now) {
    TicketScopes.requireWritable(scope, organisationId);
    return ScopeSql.bind(
            jdbc.sql(
                    "INSERT INTO public_comment (ticket_id, author_kind, author_user_id,"
                        + " author_email, body_html, received_by_email, message_id, auth_results,"
                        + " created_at) SELECT :ticket, :kind, :user, :email, :body, :byEmail,"
                        + " :messageId, :auth, :now WHERE "
                        + ScopeSql.ticketVisible(scope, ":ticket")
                        + " RETURNING id")
                .param("ticket", c.ticketId())
                .param("kind", c.authorKind().name())
                .param("user", c.authorUserId())
                .param("email", c.authorEmail())
                .param("body", c.bodyHtml())
                .param("byEmail", c.receivedByEmail())
                .param("messageId", c.messageId())
                .param("auth", c.authResults())
                .param("now", Timestamp.from(now)),
            scope)
        .query(Long.class)
        .optional()
        .orElseThrow(AccessScope::notFound);
  }

  /**
   * The ticket of a comment received by email, if the scope sees it (for the attachment worker
   * finishing that comment).
   */
  public java.util.Optional<Long> ticketOfEmailComment(AccessScope scope, long commentId) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT c.ticket_id FROM public_comment c JOIN ticket t ON t.id = c.ticket_id"
                        + " WHERE c.id = :id AND c.received_by_email AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id"))
                .param("id", commentId),
            scope)
        .query(Long.class)
        .optional();
  }

  /**
   * The final body of a comment received by email once its files are stored (inline images pointing
   * at them instead of the "[image]" placeholders). Only for a comment received by email; the
   * caller holds the ticket's lock.
   */
  public void replaceEmailBody(
      AccessScope scope, Long organisationId, long commentId, String bodyHtml) {
    TicketScopes.requireWritable(scope, organisationId);
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "UPDATE public_comment c SET body_html = :body WHERE c.id = :id AND"
                            + " c.received_by_email AND "
                            + ScopeSql.ticketVisible(scope, "c.ticket_id"))
                    .param("body", bodyHtml)
                    .param("id", commentId),
                scope)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
  }

  /** The ticket's public comments, oldest first; empty when the ticket is not visible. */
  public List<PublicCommentRow> listForTicket(AccessScope scope, long ticketId) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT c.id, c.ticket_id, c.author_kind, c.author_user_id, c.author_email,"
                        + " c.body_html, c.received_by_email, c.created_at FROM public_comment c"
                        + " JOIN ticket t ON t.id = c.ticket_id WHERE c.ticket_id = :ticket AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id")
                        + " ORDER BY c.id")
                .param("ticket", ticketId),
            scope)
        .query(PublicCommentRepository::map)
        .list();
  }

  /**
   * Stores the CC the agent chose for reply {@code commentId} (on a ticket in {@code
   * organisationId} the caller holds the lock for). The email then goes to exactly this list. 404
   * when the comment is missing or on a ticket the scope does not see.
   */
  public void saveChosenCc(
      AccessScope scope, Long organisationId, long commentId, java.util.List<CcRecipient> cc) {
    TicketScopes.requireWritable(scope, organisationId);
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "UPDATE public_comment c SET cc_chosen = TRUE WHERE c.id = :id AND "
                            + ScopeSql.ticketVisible(scope, "c.ticket_id"))
                    .param("id", commentId),
                scope)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
    for (CcRecipient r : cc) {
      ScopeSql.bind(
              jdbc.sql(
                      "INSERT INTO public_comment_cc (comment_id, email, user_id, one_off) SELECT"
                          + " c.id, lower(:e), :u, :o FROM public_comment c WHERE c.id = :c AND "
                          + ScopeSql.ticketVisible(scope, "c.ticket_id")
                          + " ON CONFLICT DO NOTHING")
                  .param("c", commentId)
                  .param("e", r.email())
                  .param("u", r.userId())
                  .param("o", r.oneOff()),
              scope)
          .update();
    }
  }

  /**
   * The CC the agent chose for this reply, or empty when none was chosen (the email then goes to
   * the confirmed participants). Scoped through the ticket's organisation.
   */
  public java.util.Optional<List<CcRecipient>> chosenCc(AccessScope scope, long commentId) {
    Boolean chosen =
        ScopeSql.bind(
                jdbc.sql(
                        "SELECT c.cc_chosen FROM public_comment c JOIN ticket t ON t.id ="
                            + " c.ticket_id WHERE c.id = :id AND "
                            + ScopeSql.organisationOrTriage(scope, "t.organisation_id"))
                    .param("id", commentId),
                scope)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    if (!chosen) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        jdbc.sql(
                "SELECT email, user_id, one_off FROM public_comment_cc WHERE comment_id = :id"
                    + " ORDER BY email")
            .param("id", commentId)
            .query(
                (rs, n) ->
                    new CcRecipient(
                        rs.getString("email"),
                        rs.getObject("user_id", Long.class),
                        rs.getBoolean("one_off")))
            .list());
  }

  /** Has a public comment with this email Message-ID been stored? (Same mail twice.) */
  public boolean existsMessageId(AccessScope scope, String messageId) {
    scope.requireSupporterOrSystem();
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM public_comment WHERE message_id = :m)")
        .param("m", messageId)
        .query(Boolean.class)
        .single();
  }

  private static PublicCommentRow map(ResultSet rs, int row) throws SQLException {
    return new PublicCommentRow(
        rs.getLong("id"),
        rs.getLong("ticket_id"),
        AuthorKind.valueOf(rs.getString("author_kind")),
        rs.getObject("author_user_id", Long.class),
        rs.getString("author_email"),
        rs.getString("body_html"),
        rs.getBoolean("received_by_email"),
        rs.getTimestamp("created_at").toInstant());
  }
}
