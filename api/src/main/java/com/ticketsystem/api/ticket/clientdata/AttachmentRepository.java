package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code attachment}. A pending attachment belongs only to the user who uploaded it. A bound one is
 * authorised on every read through attachment -> comment -> ticket -> organisation inside the SQL;
 * an attachment of an internal note is returned only to supporter and system scopes, and never when
 * the caller asks for public attachments only.
 */
@Repository
public class AttachmentRepository {

  public enum Visibility {
    /** Uploaded, not yet on a comment: visible to its uploader only. */
    PENDING,
    PUBLIC,
    INTERNAL
  }

  public record AttachmentRow(
      long id,
      UUID publicId,
      UUID storageKey,
      UUID thumbnailKey,
      Long uploaderUserId,
      String filename,
      long sizeBytes,
      String contentType,
      boolean image,
      boolean inline,
      Visibility visibility,
      Long ticketId,
      Long commentId,
      Instant createdAt) {}

  /** A stored upload, before binding. */
  public record NewAttachment(
      UUID publicId,
      UUID storageKey,
      UUID thumbnailKey,
      String filename,
      long sizeBytes,
      String contentType,
      boolean image) {}

  private static final String SELECT =
      "SELECT a.id, a.public_id, a.storage_key, a.thumbnail_key, a.uploader_user_id, a.filename,"
          + " a.size_bytes, a.content_type, a.is_image, a.inline, a.status, a.created_at,"
          + " a.public_comment_id, a.internal_note_id, COALESCE(pc.ticket_id, n.ticket_id) AS"
          + " comment_ticket_id FROM attachment a LEFT JOIN public_comment pc ON pc.id ="
          + " a.public_comment_id LEFT JOIN internal_note n ON n.id = a.internal_note_id LEFT JOIN"
          + " ticket t ON t.id = COALESCE(pc.ticket_id, n.ticket_id) ";

  private final JdbcClient jdbc;

  public AttachmentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Stores a pending upload owned by the scope's user; expires at {@code expiresAt}. A SYSTEM scope
   * (the mail poller) stores it without an uploader; only a SYSTEM scope can bind it.
   */
  public long insertPending(AccessScope scope, NewAttachment a, Instant now, Instant expiresAt) {
    Long uploader = scope.isSystem() ? null : uploaderId(scope).orElseThrow(AccessScope::notFound);
    return jdbc.sql(
            "INSERT INTO attachment (public_id, storage_key, thumbnail_key, uploader_user_id,"
                + " filename, size_bytes, content_type, is_image, status, created_at, expires_at)"
                + " VALUES (:pid, :key, :thumb, :uploader, :name, :size, :type, :image,"
                + " 'PENDING', :now, :expires) RETURNING id")
        .param("pid", a.publicId())
        .param("key", a.storageKey())
        .param("thumb", a.thumbnailKey())
        .param("uploader", uploader)
        .param("name", a.filename())
        .param("size", a.sizeBytes())
        .param("type", a.contentType())
        .param("image", a.image())
        .param("now", Timestamp.from(now))
        .param("expires", Timestamp.from(expiresAt))
        .query(Long.class)
        .single();
  }

  /**
   * The scope's own unexpired pending uploads among {@code publicIds}, locked for binding. Ids that
   * are someone else's, bound already or expired are simply absent.
   */
  public List<AttachmentRow> lockOwnPending(
      AccessScope scope, Collection<UUID> publicIds, Instant now) {
    Optional<Long> uploader = uploaderId(scope);
    if (publicIds.isEmpty() || (uploader.isEmpty() && !scope.isSystem())) {
      return List.of();
    }
    return jdbc.sql(
            SELECT
                + "WHERE a.public_id IN (:ids) AND a.status = 'PENDING' AND "
                + ownerCondition(scope)
                + " AND a.expires_at > :now FOR UPDATE OF a")
        .param("ids", publicIds)
        .param("uploader", uploader.orElse(-1L))
        .param("now", Timestamp.from(now))
        .query(AttachmentRepository::map)
        .list();
  }

  /**
   * Comments of a received email whose files are still being processed ("Processing
   * attachments..."). {@code publicCommentIds} for public comments; {@code internalNoteIds} only
   * when asked for and the scope is a supporter or system.
   */
  public record EmailFilesPending(
      java.util.Set<Long> publicCommentIds, java.util.Set<Long> internalNoteIds) {}

  /**
   * The comments of {@code ticketId} with files still being processed. Scoped through the ticket's
   * organisation; empty when the ticket is not visible.
   */
  public EmailFilesPending emailFilesPending(
      AccessScope scope, long ticketId, boolean includeInternal) {
    java.util.Set<Long> comments = new java.util.HashSet<>();
    java.util.Set<Long> notes = new java.util.HashSet<>();
    boolean internal = includeInternal && (scope.isSupporter() || scope.isSystem());
    com.ticketsystem.api.access.ScopeSql.bind(
            jdbc.sql(
                    "SELECT j.public_comment_id, j.internal_note_id FROM inbound_attachment_job j"
                        + " JOIN ticket t ON t.id = j.ticket_id WHERE j.ticket_id = :ticket AND"
                        + " j.status IN ('PENDING', 'RUNNING') AND "
                        + com.ticketsystem.api.access.ScopeSql.organisationOrTriage(
                            scope, "t.organisation_id"))
                .param("ticket", ticketId),
            scope)
        .query(
            (rs, n) -> {
              Long c = rs.getObject(1, Long.class);
              Long note = rs.getObject(2, Long.class);
              if (c != null) {
                comments.add(c);
              } else if (note != null && internal) {
                notes.add(note);
              }
              return Boolean.TRUE;
            })
        .list();
    return new EmailFilesPending(java.util.Set.copyOf(comments), java.util.Set.copyOf(notes));
  }

  /** Binds a pending attachment to a public comment of a ticket in {@code organisationId}. */
  public void bindToPublicComment(
      AccessScope scope,
      Long organisationId,
      long attachmentId,
      long ticketId,
      long commentId,
      boolean inline) {
    TicketScopes.requireWritable(scope, organisationId);
    bind(scope, attachmentId, ticketId, "public_comment_id", commentId, inline);
  }

  /** Binds a pending attachment to an internal note (supporters and system only). */
  public void bindToInternalNote(
      AccessScope scope,
      Long organisationId,
      long attachmentId,
      long ticketId,
      long noteId,
      boolean inline) {
    TicketScopes.requireSupporterOrSystem(scope, organisationId);
    bind(scope, attachmentId, ticketId, "internal_note_id", noteId, inline);
  }

  private void bind(
      AccessScope scope,
      long attachmentId,
      long ticketId,
      String column,
      long commentId,
      boolean inline) {
    long uploader = scope.isSystem() ? -1L : uploaderId(scope).orElseThrow(AccessScope::notFound);
    String commentTable = "internal_note_id".equals(column) ? "internal_note" : "public_comment";
    // The ticket must be one the scope sees and the comment must belong to it, both checked here.
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "UPDATE attachment a SET status = 'BOUND', ticket_id = :ticket, "
                            + column
                            + " = :comment, inline = :inline, expires_at = NULL WHERE a.id = :id"
                            + " AND a.status = 'PENDING' AND "
                            + ownerCondition(scope)
                            + " AND "
                            + ScopeSql.ticketVisible(scope, ":ticket")
                            + " AND EXISTS (SELECT 1 FROM "
                            + commentTable
                            + " bc WHERE bc.id = :comment AND bc.ticket_id = :ticket)")
                    .param("ticket", ticketId)
                    .param("comment", commentId)
                    .param("inline", inline)
                    .param("id", attachmentId)
                    .param("uploader", uploader),
                scope)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
  }

  /**
   * One attachment for download or thumbnail: the uploader's own pending upload, or a bound one
   * whose comment's ticket the scope sees - and, for an internal note's attachment, only for
   * supporter and system scopes. Empty otherwise (the caller answers 404).
   */
  public Optional<AttachmentRow> findForDownload(AccessScope scope, UUID publicId) {
    long pendingOwner = uploaderId(scope).orElse(-1L);
    boolean supporter = scope.isSupporter() || scope.isSystem();
    return ScopeSql.bind(
            jdbc.sql(
                    SELECT
                        + "WHERE a.public_id = :pid AND ((a.status = 'PENDING' AND"
                        + " a.uploader_user_id = :owner) OR (a.status = 'BOUND' AND t.id IS NOT"
                        + " NULL AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id")
                        + (supporter ? "" : " AND a.internal_note_id IS NULL")
                        + "))")
                .param("pid", publicId)
                .param("owner", pendingOwner),
            scope)
        .query(AttachmentRepository::map)
        .optional();
  }

  /**
   * Bound attachments of a ticket's comments, oldest first. With {@code includeInternal} false (the
   * public read model) internal-note attachments are never returned, whatever the scope; with true
   * they are returned to supporter and system scopes only.
   */
  public List<AttachmentRow> listForTicket(
      AccessScope scope, long ticketId, boolean includeInternal) {
    boolean internal = includeInternal && (scope.isSupporter() || scope.isSystem());
    return ScopeSql.bind(
            jdbc.sql(
                    SELECT
                        + "WHERE a.status = 'BOUND' AND t.id = :ticket AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id")
                        + (internal ? "" : " AND a.internal_note_id IS NULL")
                        + " ORDER BY a.id")
                .param("ticket", ticketId),
            scope)
        .query(AttachmentRepository::map)
        .list();
  }

  /** Expired pending uploads (cleanup job, SYSTEM scope only). */
  public List<AttachmentRow> expiredPending(AccessScope scope, Instant now, int limit) {
    requireSystem(scope);
    return jdbc.sql(
            SELECT + "WHERE a.status = 'PENDING' AND a.expires_at <= :now ORDER BY a.id LIMIT :n")
        .param("now", Timestamp.from(now))
        .param("n", limit)
        .query(AttachmentRepository::map)
        .list();
  }

  /** Deletes an expired pending row (cleanup job, SYSTEM scope only). */
  public boolean deleteExpiredPending(AccessScope scope, long attachmentId, Instant now) {
    requireSystem(scope);
    return jdbc.sql(
                "DELETE FROM attachment WHERE id = :id AND status = 'PENDING' AND expires_at <="
                    + " :now")
            .param("id", attachmentId)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  private static void requireSystem(AccessScope scope) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
  }

  /**
   * Whose pending files a scope may claim and bind: a user their own; the SYSTEM scope only the
   * files it stored itself from inbound email (no uploader). Binds {@code :uploader}.
   */
  private static String ownerCondition(AccessScope scope) {
    return scope.isSystem() ? "a.uploader_user_id IS NULL" : "a.uploader_user_id = :uploader";
  }

  /** Uploads belong to users (customers, supporters); API keys have no uploads. */
  private static Optional<Long> uploaderId(AccessScope scope) {
    if (scope.kind() == AccessScope.Kind.CUSTOMER || scope.kind() == AccessScope.Kind.SUPPORTER) {
      return scope.principalId();
    }
    return Optional.empty();
  }

  private static AttachmentRow map(ResultSet rs, int row) throws SQLException {
    Long pc = rs.getObject("public_comment_id", Long.class);
    Long note = rs.getObject("internal_note_id", Long.class);
    Visibility v =
        "PENDING".equals(rs.getString("status"))
            ? Visibility.PENDING
            : note != null ? Visibility.INTERNAL : Visibility.PUBLIC;
    return new AttachmentRow(
        rs.getLong("id"),
        rs.getObject("public_id", UUID.class),
        rs.getObject("storage_key", UUID.class),
        rs.getObject("thumbnail_key", UUID.class),
        rs.getObject("uploader_user_id", Long.class),
        rs.getString("filename"),
        rs.getLong("size_bytes"),
        rs.getString("content_type"),
        rs.getBoolean("is_image"),
        rs.getBoolean("inline"),
        v,
        rs.getObject("comment_ticket_id", Long.class),
        note != null ? note : pc,
        rs.getTimestamp("created_at").toInstant());
  }
}
