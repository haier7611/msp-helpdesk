package com.ticketsystem.api.mail.intake.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code inbound_mail}: the inbound mail log with the raw {@code .eml}, and the Blocked mail log as
 * its BLOCKED rows. Admin and SYSTEM scopes only.
 */
@Repository
public class InboundMailRepository {

  /**
   * A row to write once a mail has been processed. {@code authenticated}: the mail passed DMARC
   * from a trusted authserv-id; the per-sender cap and fold-in are counted per class.
   */
  public record NewInboundMail(
      Long mailboxId,
      Long uidValidity,
      Long uid,
      String messageId,
      String rawSha256,
      long rawSize,
      byte[] raw,
      Instant rawPurgeAfter,
      String fromAddress,
      String subject,
      Instant receivedAt,
      String outcome,
      String reason,
      Long ticketId,
      boolean newTicket,
      boolean authenticated,
      String blockRuleKind,
      String blockRule) {

    /** The same row carrying the raw (or, for a too-large mail, truncated) message. */
    public NewInboundMail withRaw(byte[] bytes) {
      return new NewInboundMail(
          mailboxId,
          uidValidity,
          uid,
          messageId,
          rawSha256,
          rawSize,
          bytes,
          rawPurgeAfter,
          fromAddress,
          subject,
          receivedAt,
          outcome,
          reason,
          ticketId,
          newTicket,
          authenticated,
          blockRuleKind,
          blockRule);
    }
  }

  /** A Blocked mail log entry (no raw content). */
  public record BlockedMailRow(
      long id,
      String fromAddress,
      String subject,
      Instant receivedAt,
      String ruleKind,
      String rule,
      Instant restoredAt,
      Long ticketId) {}

  /** A blocked mail locked for restore, with its raw bytes. */
  /**
   * {@code rawSize} is the size of the whole mail; larger than {@code raw} for a truncated copy.
   */
  public record BlockedForRestore(
      long id, Long mailboxId, String messageId, byte[] raw, long rawSize) {}

  private final JdbcClient jdbc;

  public InboundMailRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public boolean existsMessageId(AccessScope scope, String messageId) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM inbound_mail WHERE message_id = :m AND outcome <>"
                + " 'DUPLICATE')")
        .param("m", messageId)
        .query(Boolean.class)
        .single();
  }

  /**
   * Writes the log row. Returns empty when a row with the same Message-ID (or the same mailbox,
   * UIDVALIDITY and UID) already exists: the mail was processed before. The caller treats that as a
   * conflict. DUPLICATE rows may repeat a Message-ID (partial unique index).
   */
  public Optional<Long> insert(AccessScope scope, NewInboundMail m) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql(
            "INSERT INTO inbound_mail (mailbox_id, uid_validity, uid, message_id, raw_sha256,"
                + " raw_size, raw_eml, raw_purge_after, from_address, subject, received_at,"
                + " outcome, reason, ticket_id, new_ticket, authenticated, block_rule_kind,"
                + " block_rule) VALUES (:mb, :v, :uid, :mid, :sha, :size, :raw, :purge, :from,"
                + " :subject, :at, :outcome, :reason, :ticket, :newTicket, :auth, :ruleKind,"
                + " :rule) ON CONFLICT DO NOTHING RETURNING id")
        .param("mb", m.mailboxId())
        .param("v", m.uidValidity())
        .param("uid", m.uid())
        .param("mid", m.messageId())
        .param("sha", m.rawSha256())
        .param("size", m.rawSize())
        .param("raw", m.raw())
        .param("purge", Timestamp.from(m.rawPurgeAfter()))
        .param("from", m.fromAddress())
        .param("subject", m.subject())
        .param("at", Timestamp.from(m.receivedAt()))
        .param("outcome", m.outcome())
        .param("reason", m.reason())
        .param("ticket", m.ticketId())
        .param("newTicket", m.newTicket())
        .param("auth", m.authenticated())
        .param("ruleKind", m.blockRuleKind())
        .param("rule", m.blockRule())
        .query(Long.class)
        .optional();
  }

  /**
   * New tickets this sender opened by email since {@code since} (the 20-per-hour cap), counting
   * only mails of the same authentication class.
   */
  public int newTicketsFrom(
      AccessScope scope, String fromAddress, Instant since, boolean authenticated) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql(
            "SELECT count(*) FROM inbound_mail WHERE new_ticket AND from_address = :from AND"
                + " authenticated = :auth AND received_at >= :since")
        .param("from", fromAddress)
        .param("auth", authenticated)
        .param("since", Timestamp.from(since))
        .query(Integer.class)
        .single();
  }

  /**
   * The ticket of the sender's most recent new-ticket mail of the same authentication class (the
   * fold-in target).
   */
  public Optional<Long> latestNewTicketOf(
      AccessScope scope, String fromAddress, boolean authenticated) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql(
            "SELECT ticket_id FROM inbound_mail WHERE new_ticket AND from_address = :from AND"
                + " authenticated = :auth AND ticket_id IS NOT NULL ORDER BY received_at DESC, id"
                + " DESC LIMIT 1")
        .param("from", fromAddress)
        .param("auth", authenticated)
        .query(Long.class)
        .optional();
  }

  /** The Blocked mail log, newest first. */
  public List<BlockedMailRow> blocked(AccessScope scope, int limit) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT id, from_address, subject, received_at, block_rule_kind, block_rule,"
                + " restored_at, ticket_id FROM inbound_mail WHERE outcome = 'BLOCKED' ORDER BY"
                + " received_at DESC, id DESC LIMIT :limit")
        .param("limit", limit)
        .query(
            (rs, n) ->
                new BlockedMailRow(
                    rs.getLong("id"),
                    rs.getString("from_address"),
                    rs.getString("subject"),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getString("block_rule_kind"),
                    rs.getString("block_rule"),
                    MailboxRepository.instant(rs, "restored_at"),
                    rs.getObject("ticket_id", Long.class)))
        .list();
  }

  /** A blocked, not yet restored mail with its raw bytes, locked for the restore. */
  public Optional<BlockedForRestore> lockBlocked(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT id, mailbox_id, message_id, raw_eml, raw_size FROM inbound_mail WHERE id = :id AND"
                + " outcome = 'BLOCKED' AND restored_at IS NULL AND raw_eml IS NOT NULL FOR"
                + " UPDATE")
        .param("id", id)
        .query(
            (rs, n) ->
                new BlockedForRestore(
                    rs.getLong("id"),
                    rs.getObject("mailbox_id", Long.class),
                    rs.getString("message_id"),
                    rs.getBytes("raw_eml"),
                    rs.getLong("raw_size")))
        .optional();
  }

  public void markRestored(
      AccessScope scope, long id, Long userId, long ticketId, boolean newTicket, Instant now) {
    scope.requireAdmin();
    jdbc.sql(
            "UPDATE inbound_mail SET restored_at = :now, restored_by_user_id = :u, ticket_id ="
                + " :t, new_ticket = :nt WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("u", userId)
        .param("t", ticketId)
        .param("nt", newTicket)
        .param("id", id)
        .update();
  }

  /** Clears raw {@code .eml} past its purge time (90 days). */
  public int purgeRaw(AccessScope scope, Instant now) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql(
            "UPDATE inbound_mail SET raw_eml = NULL WHERE raw_eml IS NOT NULL AND raw_purge_after"
                + " < :now")
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Deletes Blocked mail log rows older than {@code cutoff} (30 days). */
  public int purgeBlocked(AccessScope scope, Instant cutoff) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql("DELETE FROM inbound_mail WHERE outcome = 'BLOCKED' AND received_at < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }
}
