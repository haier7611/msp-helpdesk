package com.ticketsystem.api.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.TestcontainersConfiguration;
import com.ticketsystem.api.retention.job.RetentionJob;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.TicketTestBase;
import com.ticketsystem.api.ticket.attachment.BlobStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WP-12 retention (spec section 10): each step deletes exactly the rows past their period, at the
 * boundary, and leaves newer ones; tickets go with everything that belongs to them, including the
 * attachment files; the audit log keeps its 1-year floor and stays append-only for the app role.
 */
@ExtendWith(OutputCaptureExtension.class)
class RetentionJobIntegrationTest extends TicketTestBase {

  @Autowired RetentionJob job;
  @Autowired BlobStore blobs;

  private final JdbcTemplate owner = TestcontainersConfiguration.ownerJdbc();

  private Instant monthsAgo(int months, int extraDays) {
    return clock
        .instant()
        .atZone(ZoneOffset.UTC)
        .minusMonths(months)
        .minusDays(extraDays)
        .toInstant();
  }

  private void close(long ticketId, Instant at) {
    jdbc.update(
        "UPDATE ticket SET status = 'CLOSED', status_changed_at = ? WHERE id = ?",
        Timestamp.from(at),
        ticketId);
  }

  private int count(String sql, Object... args) {
    return owner.queryForObject(sql, Integer.class, args);
  }

  private boolean ticketExists(long id) {
    return count("SELECT count(*) FROM ticket WHERE id = ?", id) == 1;
  }

  private boolean fileExists(UUID key) {
    try (var in = blobs.open(key)) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** An Acme ticket with an uploaded file, an internal note and a participant. */
  private Created richTicket() {
    UUID upload = upload(admin, "notes.txt", "synthetic notes".getBytes(StandardCharsets.UTF_8));
    Created c =
        tickets.create(
            admin,
            new NewTicket(
                acme,
                acmeCustomerUser.id(),
                null,
                generalType,
                null,
                null,
                null,
                TicketSource.AGENT,
                "Retention fixture",
                "<p>Synthetic.</p>",
                List.of(upload),
                null,
                null,
                null,
                null));
    jdbc.update(
        "INSERT INTO internal_note (ticket_id, author_kind, author_user_id, body_html, created_at)"
            + " VALUES (?, 'SUPPORTER', ?, '<p>note</p>', now())",
        c.ticketId(),
        adminUser.id());
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, email, created_at) VALUES (?, ?, now())",
        c.ticketId(),
        "cc-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test");
    jdbc.update(
        "INSERT INTO inbound_mail (message_id, raw_sha256, raw_size, raw_purge_after,"
            + " received_at, outcome, ticket_id) VALUES (?, ?, 0, now(), now(), 'CLIENT_REPLY', ?)",
        "<" + UUID.randomUUID() + "@example.test>",
        "0".repeat(64),
        c.ticketId());
    return c;
  }

  private UUID storageKeyOf(long ticketId) {
    return jdbc.queryForObject(
        "SELECT storage_key FROM attachment WHERE ticket_id = ?", UUID.class, ticketId);
  }

  @Test
  void closedTicketsPastTheirOrganisationsPeriodGoWithEverythingTheyOwn() {
    Created old = richTicket();
    UUID oldFile = storageKeyOf(old.ticketId());
    close(old.ticketId(), monthsAgo(36, 1));
    Created young = richTicket();
    UUID youngFile = storageKeyOf(young.ticketId());
    close(young.ticketId(), monthsAgo(36, -1));
    Created openOld = acmeTicket();
    jdbc.update(
        "UPDATE ticket SET status_changed_at = ? WHERE id = ?",
        Timestamp.from(monthsAgo(60, 0)),
        openOld.ticketId());
    // A newer ticket that follows up the old one survives and loses only the link.
    Created followUp = acmeTicket();
    jdbc.update(
        "UPDATE ticket SET follow_up_of_id = ? WHERE id = ?", old.ticketId(), followUp.ticketId());
    // An organisation with a 12-month period.
    jdbc.update("UPDATE organisation SET retention_months = 12 WHERE id = ?", beta);
    Created betaOld = betaTicket();
    close(betaOld.ticketId(), monthsAgo(12, 1));
    Created betaYoung = betaTicket();
    close(betaYoung.ticketId(), monthsAgo(11, 0));
    assertThat(fileExists(oldFile)).isTrue();

    RetentionJob.Report r = job.runOnce(false);

    assertThat(r).isNotNull();
    assertThat(r.tickets()).isGreaterThanOrEqualTo(2);
    assertThat(ticketExists(old.ticketId())).isFalse();
    assertThat(ticketExists(betaOld.ticketId())).isFalse();
    assertThat(ticketExists(young.ticketId())).isTrue();
    assertThat(ticketExists(betaYoung.ticketId())).isTrue();
    assertThat(ticketExists(openOld.ticketId())).isTrue();
    assertThat(ticketExists(followUp.ticketId())).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT follow_up_of_id FROM ticket WHERE id = ?", Long.class, followUp.ticketId()))
        .isNull();
    for (String table :
        List.of(
            "public_comment",
            "internal_note",
            "system_entry",
            "ticket_event",
            "ticket_participant",
            "attachment",
            "inbound_mail",
            "ticket_reference")) {
      assertThat(count("SELECT count(*) FROM " + table + " WHERE ticket_id = ?", old.ticketId()))
          .as(table)
          .isZero();
    }
    assertThat(count("SELECT count(*) FROM public_comment WHERE ticket_id = ?", young.ticketId()))
        .isPositive();
    assertThat(fileExists(oldFile)).isFalse();
    assertThat(fileExists(youngFile)).isTrue();
    assertThat(
            count(
                "SELECT count(*) FROM audit_event WHERE type = 'RETENTION_RUN' AND details->>'kind'"
                    + " = 'tickets' AND occurred_at >= now() - interval '1 minute'"))
        .isPositive();
  }

  @Test
  void auditOutboxAndTokensHaveTheirOwnPeriods() {
    Instant now = clock.instant();
    long oldAudit = insertAudit(now.minus(Duration.ofDays(366)));
    long youngAudit = insertAudit(now.minus(Duration.ofDays(364)));
    long oldSent = insertOutbox("sent", now.minus(Duration.ofDays(91)));
    long youngSent = insertOutbox("sent", now.minus(Duration.ofDays(89)));
    long oldQueued = insertOutbox("queued", now.minus(Duration.ofDays(200)));
    long oldToken = insertToken(now.minus(Duration.ofDays(31)));
    long youngToken = insertToken(now.minus(Duration.ofDays(29)));

    job.runOnce(false);

    assertThat(count("SELECT count(*) FROM audit_event WHERE id = ?", oldAudit)).isZero();
    assertThat(count("SELECT count(*) FROM audit_event WHERE id = ?", youngAudit)).isOne();
    assertThat(count("SELECT count(*) FROM outbox_message WHERE id = ?", oldSent)).isZero();
    assertThat(count("SELECT count(*) FROM outbox_message WHERE id = ?", youngSent)).isOne();
    assertThat(count("SELECT count(*) FROM outbox_message WHERE id = ?", oldQueued)).isOne();
    assertThat(count("SELECT count(*) FROM auth_token WHERE id = ?", oldToken)).isZero();
    assertThat(count("SELECT count(*) FROM auth_token WHERE id = ?", youngToken)).isOne();
  }

  @Test
  void theAuditLogStaysAppendOnlyAndKeepsAOneYearFloor() {
    long young = insertAudit(Instant.now().minus(Duration.ofDays(30)));
    // The application role still cannot delete audit rows itself (C14).
    assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE id = ?", young))
        .isInstanceOf(DataAccessException.class);
    // Even asked for "everything before tomorrow", the function keeps the last year.
    Integer purged =
        jdbc.queryForObject(
            "SELECT purge_audit_events(?, 50000)",
            Integer.class,
            Timestamp.from(Instant.now().plus(Duration.ofDays(1))));
    assertThat(purged).isNotNull();
    assertThat(count("SELECT count(*) FROM audit_event WHERE id = ?", young)).isOne();
  }

  @Test
  void aDryRunCountsLogsAndDeletesNothing(CapturedOutput output) {
    Created old = acmeTicket();
    close(old.ticketId(), monthsAgo(40, 0));
    long oldSent = insertOutbox("sent", clock.instant().minus(Duration.ofDays(120)));
    int tickets = count("SELECT count(*) FROM ticket");
    int outbox = count("SELECT count(*) FROM outbox_message");
    int audit = count("SELECT count(*) FROM audit_event");

    RetentionJob.Report r = job.runOnce(true);

    assertThat(r.dryRun()).isTrue();
    assertThat(r.tickets()).isPositive();
    assertThat(r.outbox()).isPositive();
    assertThat(ticketExists(old.ticketId())).isTrue();
    assertThat(count("SELECT count(*) FROM outbox_message WHERE id = ?", oldSent)).isOne();
    // Nothing at all went, and no RETENTION_RUN event claims it did.
    assertThat(count("SELECT count(*) FROM ticket")).isEqualTo(tickets);
    assertThat(count("SELECT count(*) FROM outbox_message")).isEqualTo(outbox);
    assertThat(count("SELECT count(*) FROM audit_event")).isEqualTo(audit);
    // The switch is visible at start, and the counts it would delete are logged.
    assertThat(output).contains("retention: dry run, nothing will be deleted");
    assertThat(output)
        .contains(
            "retention (dry run, nothing deleted): "
                + r.tickets()
                + " ticket(s), 0 attachment file(s), "
                + r.auditEvents()
                + " audit event(s), "
                + r.outbox()
                + " outbox row(s)");
    job.runOnce(false);
    assertThat(ticketExists(old.ticketId())).isFalse();
  }

  @Test
  void aTicketCanBeDeletedWhileTheMailLogStillPointsAtIt() {
    // AUD-13 Low: the inbound_mail and suppression foreign keys no longer block a delete.
    Created c = richTicket();
    owner.update(
        "INSERT INTO ticket_mail_suppression (ticket_id, reason, created_at) VALUES (?, 'BULK',"
            + " now())",
        c.ticketId());
    long mail =
        owner.queryForObject(
            "SELECT id FROM inbound_mail WHERE ticket_id = ?", Long.class, c.ticketId());
    close(c.ticketId(), monthsAgo(48, 0));
    // Remove everything but the mail log and suppression rows, then the ticket itself.
    for (String t :
        List.of(
            "attachment",
            "public_comment",
            "internal_note",
            "system_entry",
            "ticket_event",
            "ticket_participant",
            "ticket_reference")) {
      owner.update("DELETE FROM " + t + " WHERE ticket_id = ?", c.ticketId());
    }
    owner.update(
        "UPDATE ticket SET follow_up_of_id = NULL WHERE follow_up_of_id = ?", c.ticketId());
    owner.update("DELETE FROM ticket WHERE id = ?", c.ticketId());
    assertThat(
            owner.queryForObject(
                "SELECT ticket_id FROM inbound_mail WHERE id = ?", Long.class, mail))
        .isNull();
    assertThat(
            count("SELECT count(*) FROM ticket_mail_suppression WHERE ticket_id = ?", c.ticketId()))
        .isZero();
  }

  private long insertAudit(Instant at) {
    return owner.queryForObject(
        "INSERT INTO audit_event (occurred_at, type) VALUES (?, 'SIGN_OUT') RETURNING id",
        Long.class,
        Timestamp.from(at));
  }

  private long insertOutbox(String status, Instant at) {
    return owner.queryForObject(
        "INSERT INTO outbox_message (message_id, kind, to_addresses, from_name, subject,"
            + " html_body, text_body, status, created_at, sent_at) VALUES (?, 'system',"
            + " 'someone@example.test', 'Example', 'Synthetic', '<p>x</p>', 'x', ?, ?, ?)"
            + " RETURNING id",
        Long.class,
        "<" + UUID.randomUUID() + "@example.test>",
        status,
        Timestamp.from(at),
        "sent".equals(status) ? Timestamp.from(at) : null);
  }

  private long insertToken(Instant expiresAt) {
    return owner.queryForObject(
        "INSERT INTO auth_token (purpose, user_id, token_hash, created_at, expires_at) VALUES"
            + " ('PASSWORD_RESET', ?, ?, ?, ?) RETURNING id",
        Long.class,
        acmeCustomerUser.id(),
        UUID.randomUUID().toString().replace("-", "") + "0".repeat(32),
        Timestamp.from(expiresAt.minus(Duration.ofMinutes(15))),
        Timestamp.from(expiresAt));
  }
}
