package com.ticketsystem.api.retention.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Retention deletes. Every method needs a SYSTEM scope (the nightly job sees every organisation)
 * and works on a bounded batch so one run never holds long locks. Ticket deletes run in the
 * caller's transaction; the caller deletes the attachment files after commit.
 */
@Repository
public class RetentionRepository {

  /** Organisations without their own period, and triage tickets (no organisation): 3 years. */
  public static final int DEFAULT_RETENTION_MONTHS = 36;

  /** An attachment file to delete once the rows are gone. */
  public record BlobKeys(UUID storageKey, UUID thumbnailKey) {}

  private static final String EXPIRED_CLOSED =
      " FROM ticket t LEFT JOIN organisation o ON o.id = t.organisation_id WHERE t.status ="
          + " 'CLOSED' AND t.status_changed_at < CAST(:now AS TIMESTAMPTZ) - make_interval(months"
          + " => COALESCE(o.retention_months, "
          + DEFAULT_RETENTION_MONTHS
          + "))";

  private final JdbcClient jdbc;

  public RetentionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Closed tickets whose organisation's retention period has passed since they closed, locked
   * ({@code FOR UPDATE OF t}) until the caller's transaction ends, so a ticket cannot change status
   * between this select and {@link #deleteTickets}: the lock re-checks {@code CLOSED} on the newest
   * row version, and a ticket another transaction holds is skipped until next night.
   */
  public List<Long> expiredClosedTickets(AccessScope scope, Instant now, int limit) {
    requireSystem(scope);
    return jdbc.sql(
            "SELECT t.id"
                + EXPIRED_CLOSED
                + " ORDER BY t.id LIMIT :limit FOR UPDATE OF t SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(Long.class)
        .list();
  }

  public long countExpiredClosedTickets(AccessScope scope, Instant now) {
    requireSystem(scope);
    return jdbc.sql("SELECT count(*)" + EXPIRED_CLOSED)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  /** The files of every attachment on these tickets. */
  public List<BlobKeys> attachmentFiles(AccessScope scope, List<Long> ticketIds) {
    requireSystem(scope);
    if (ticketIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT storage_key, thumbnail_key FROM attachment WHERE ticket_id IN (:ids)")
        .param("ids", ticketIds)
        .query(
            (rs, n) ->
                new BlobKeys(
                    rs.getObject("storage_key", UUID.class),
                    rs.getObject("thumbnail_key", UUID.class)))
        .list();
  }

  /**
   * Deletes the tickets and everything that belongs to them: attachments (rows), comments, notes,
   * system entries, history, participants, links, mail log rows, outbox rows, suppression flags and
   * references. Links from surviving tickets (duplicate of, follow-up of) are cleared; a reference
   * re-pointed to a surviving ticket stays and only loses its "issued to" link. Returns how many
   * tickets were deleted.
   */
  public int deleteTickets(AccessScope scope, List<Long> ticketIds) {
    requireSystem(scope);
    if (ticketIds.isEmpty()) {
      return 0;
    }
    List<String> statements = new ArrayList<>();
    statements.add(
        "UPDATE ticket SET duplicate_of_id = NULL WHERE duplicate_of_id IN (:ids) AND id NOT IN"
            + " (:ids)");
    statements.add(
        "UPDATE ticket SET follow_up_of_id = NULL WHERE follow_up_of_id IN (:ids) AND id NOT IN"
            + " (:ids)");
    statements.add(
        "DELETE FROM ticket_link WHERE low_ticket_id IN (:ids) OR high_ticket_id IN (:ids)");
    statements.add("DELETE FROM attachment WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM public_comment WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM internal_note WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM system_entry WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM ticket_event WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM ticket_participant WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM inbound_mail WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM outbox_message WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM ticket_mail_suppression WHERE ticket_id IN (:ids)");
    statements.add(
        "UPDATE ticket_reference SET issued_ticket_id = NULL WHERE issued_ticket_id IN (:ids) AND"
            + " ticket_id NOT IN (:ids)");
    statements.add("DELETE FROM ticket_reference WHERE ticket_id IN (:ids)");
    for (String sql : statements) {
      jdbc.sql(sql).param("ids", ticketIds).update();
    }
    return jdbc.sql("DELETE FROM ticket WHERE id IN (:ids) AND status = 'CLOSED'")
        .param("ids", ticketIds)
        .update();
  }

  /** Sent or failed outbox rows (and their parts) older than {@code cutoff}. */
  public int deleteFinishedOutbox(AccessScope scope, Instant cutoff, int limit) {
    requireSystem(scope);
    return jdbc.sql(
            "DELETE FROM outbox_message WHERE id IN (SELECT id FROM outbox_message WHERE status IN"
                + " ('sent', 'failed') AND COALESCE(sent_at, created_at) < :cutoff ORDER BY id"
                + " LIMIT :limit)")
        .param("cutoff", Timestamp.from(cutoff))
        .param("limit", limit)
        .update();
  }

  public long countFinishedOutbox(AccessScope scope, Instant cutoff) {
    requireSystem(scope);
    return jdbc.sql(
            "SELECT count(*) FROM outbox_message WHERE status IN ('sent', 'failed') AND"
                + " COALESCE(sent_at, created_at) < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .query(Long.class)
        .single();
  }

  /**
   * Audit events older than {@code cutoff}, through the {@code purge_audit_events} database
   * function: the application role cannot delete audit rows itself (C14), and the function never
   * deletes anything younger than one year by the database clock.
   */
  public int purgeAudit(AccessScope scope, Instant cutoff, int limit) {
    requireSystem(scope);
    return jdbc.sql("SELECT purge_audit_events(:cutoff, :limit)")
        .param("cutoff", Timestamp.from(cutoff))
        .param("limit", limit)
        .query(Integer.class)
        .single();
  }

  public long countAudit(AccessScope scope, Instant cutoff) {
    requireSystem(scope);
    return jdbc.sql(
            "SELECT count(*) FROM audit_event WHERE occurred_at < LEAST(:cutoff, now() - INTERVAL"
                + " '365 days')")
        .param("cutoff", Timestamp.from(cutoff))
        .query(Long.class)
        .single();
  }

  /** Link tokens (invitation, reset, magic link) that expired before {@code cutoff}. */
  public int deleteExpiredTokens(AccessScope scope, Instant cutoff, int limit) {
    requireSystem(scope);
    return jdbc.sql(
            "DELETE FROM auth_token WHERE id IN (SELECT id FROM auth_token WHERE expires_at <"
                + " :cutoff ORDER BY id LIMIT :limit)")
        .param("cutoff", Timestamp.from(cutoff))
        .param("limit", limit)
        .update();
  }

  public long countExpiredTokens(AccessScope scope, Instant cutoff) {
    requireSystem(scope);
    return jdbc.sql("SELECT count(*) FROM auth_token WHERE expires_at < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .query(Long.class)
        .single();
  }

  private static void requireSystem(AccessScope scope) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
  }
}
