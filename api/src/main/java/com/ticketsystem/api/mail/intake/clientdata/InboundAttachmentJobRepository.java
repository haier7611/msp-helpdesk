package com.ticketsystem.api.mail.intake.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code inbound_attachment_job} (the second step of ticket first, files after): the files of an
 * email whose ticket or comment is already committed and which the attachment worker still has to
 * check, store and bind. SYSTEM scope only (the poller, the restore it files, and the worker); the
 * agent view reads the pending state through the ticket's own scoped repository, never here.
 */
@Repository
public class InboundAttachmentJobRepository {

  /** What one job must do. Exactly one of {@code publicCommentId} / {@code internalNoteId}. */
  public record NewJob(
      long inboundMailId,
      long ticketId,
      Long publicCommentId,
      Long internalNoteId,
      Long ownerUserId,
      boolean replyOnly) {}

  /** A job claimed by a worker; {@code attempts} includes this one. */
  public record Claimed(
      long id,
      long inboundMailId,
      long ticketId,
      Long publicCommentId,
      Long internalNoteId,
      Long ownerUserId,
      boolean replyOnly,
      int attempts) {}

  /** The raw email a job reads its files from; {@code raw} null once purged or erased. */
  public record RawMail(Long mailboxId, byte[] raw) {}

  private final JdbcClient jdbc;

  public InboundAttachmentJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Records a job in the caller's transaction (the mail's own); returns its id. */
  public long insert(AccessScope scope, NewJob j, Instant now) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql(
            "INSERT INTO inbound_attachment_job (inbound_mail_id, ticket_id, public_comment_id,"
                + " internal_note_id, owner_user_id, reply_only, next_attempt_at, created_at)"
                + " VALUES (:mail, :ticket, :comment, :note, :owner, :reply, :now, :now)"
                + " RETURNING id")
        .param("mail", j.inboundMailId())
        .param("ticket", j.ticketId())
        .param("comment", j.publicCommentId())
        .param("note", j.internalNoteId())
        .param("owner", j.ownerUserId())
        .param("reply", j.replyOnly())
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  /**
   * Claims a job for one attempt (own statement, auto-commit): a PENDING job that is due, or a
   * RUNNING one whose lease ran out (its worker died). Empty when another worker holds it or it is
   * finished.
   */
  public Optional<Claimed> claim(AccessScope scope, long id, Instant now, Duration lease) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql(
            "UPDATE inbound_attachment_job SET status = 'RUNNING', attempts = attempts + 1,"
                + " lease_until = :until WHERE id = :id AND ((status = 'PENDING' AND"
                + " next_attempt_at <= :now) OR (status = 'RUNNING' AND lease_until < :now))"
                + " RETURNING id, inbound_mail_id, ticket_id, public_comment_id, internal_note_id,"
                + " owner_user_id, reply_only, attempts")
        .param("id", id)
        .param("now", Timestamp.from(now))
        .param("until", Timestamp.from(now.plus(lease)))
        .query(InboundAttachmentJobRepository::mapClaimed)
        .optional();
  }

  /** Ids of jobs a worker may claim now, oldest first. */
  public List<Long> due(AccessScope scope, Instant now, int limit) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql(
            "SELECT id FROM inbound_attachment_job WHERE (status = 'PENDING' AND next_attempt_at"
                + " <= :now) OR (status = 'RUNNING' AND lease_until < :now) ORDER BY id LIMIT"
                + " :limit")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(Long.class)
        .list();
  }

  /** The raw email of a job (SYSTEM scope; the mail log is installation-wide). */
  public Optional<RawMail> raw(AccessScope scope, long inboundMailId) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql("SELECT mailbox_id, raw_eml FROM inbound_mail WHERE id = :id")
        .param("id", inboundMailId)
        .query((rs, n) -> new RawMail(rs.getObject("mailbox_id", Long.class), rs.getBytes(2)))
        .optional();
  }

  /** Done: the row is deleted (the comment now carries the files and the final body). */
  public void finish(AccessScope scope, long id) {
    IntakeScopes.requireSystem(scope);
    jdbc.sql("DELETE FROM inbound_attachment_job WHERE id = :id").param("id", id).update();
  }

  /** Try again at {@code next} (a transient failure). */
  public void retryAt(AccessScope scope, long id, Instant next) {
    IntakeScopes.requireSystem(scope);
    jdbc.sql(
            "UPDATE inbound_attachment_job SET status = 'PENDING', lease_until = NULL,"
                + " next_attempt_at = :next WHERE id = :id AND status = 'RUNNING'")
        .param("id", id)
        .param("next", Timestamp.from(next))
        .update();
  }

  /** Given up: kept as FAILED for the record; the comment no longer shows "processing". */
  public void fail(AccessScope scope, long id, Instant now) {
    IntakeScopes.requireSystem(scope);
    jdbc.sql(
            "UPDATE inbound_attachment_job SET status = 'FAILED', lease_until = NULL,"
                + " finished_at = :now WHERE id = :id")
        .param("id", id)
        .param("now", Timestamp.from(now))
        .update();
  }

  private static Claimed mapClaimed(ResultSet rs, int n) throws SQLException {
    return new Claimed(
        rs.getLong("id"),
        rs.getLong("inbound_mail_id"),
        rs.getLong("ticket_id"),
        rs.getObject("public_comment_id", Long.class),
        rs.getObject("internal_note_id", Long.class),
        rs.getObject("owner_user_id", Long.class),
        rs.getBoolean("reply_only"),
        rs.getInt("attempts"));
  }
}
