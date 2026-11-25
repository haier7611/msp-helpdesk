package com.ticketsystem.api.mail.intake.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Small installation-wide intake state: {@code email_settings} (the supporter email reply mode),
 * {@code mail_poller_lock} (one poller at a time, C11) and {@code mailbox_probe} (test connection).
 * Admin and SYSTEM scopes only.
 */
@Repository
public class IntakeStateRepository {

  /** A test-connection probe. */
  public record ProbeRow(long id, long mailboxId, Instant expiresAt, Instant seenAt) {}

  private final JdbcClient jdbc;

  public IntakeStateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public String supporterReplyMode(AccessScope scope) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql("SELECT supporter_reply_mode FROM email_settings WHERE id = 1")
        .query(String.class)
        .single();
  }

  public void setSupporterReplyMode(AccessScope scope, String mode, Long userId, Instant now) {
    scope.requireAdmin();
    jdbc.sql(
            "UPDATE email_settings SET supporter_reply_mode = :m, updated_by_user_id = :u,"
                + " updated_at = :now WHERE id = 1")
        .param("m", mode)
        .param("u", userId)
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * Takes or renews the poller lease for {@code owner}: succeeds when the lease is free, expired or
   * already this owner's. The conditional UPDATE is atomic, so two pollers can never both hold it.
   */
  public boolean tryLock(AccessScope scope, String name, String owner, Duration lease) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql(
                "UPDATE mail_poller_lock SET locked_by = :owner, locked_until = now() +"
                    + " make_interval(secs => CAST(:secs AS double precision)) WHERE name = :name"
                    + " AND (locked_until < now() OR locked_by = :owner)")
            .param("owner", owner)
            .param("secs", lease.toMillis() / 1000.0)
            .param("name", name)
            .update()
        == 1;
  }

  public void unlock(AccessScope scope, String name, String owner) {
    IntakeScopes.requireSystem(scope);
    jdbc.sql(
            "UPDATE mail_poller_lock SET locked_by = NULL, locked_until = now() WHERE name = :name"
                + " AND locked_by = :owner")
        .param("name", name)
        .param("owner", owner)
        .update();
  }

  public long insertProbe(
      AccessScope scope, long mailboxId, String tokenHash, Instant now, Instant expiresAt) {
    scope.requireAdmin();
    return jdbc.sql(
            "INSERT INTO mailbox_probe (mailbox_id, token_hash, created_at, expires_at) VALUES"
                + " (:mb, :h, :now, :exp) RETURNING id")
        .param("mb", mailboxId)
        .param("h", tokenHash)
        .param("now", Timestamp.from(now))
        .param("exp", Timestamp.from(expiresAt))
        .query(Long.class)
        .single();
  }

  /** Marks an outstanding, unexpired probe of this mailbox as seen; false when there is none. */
  public boolean markProbeSeen(AccessScope scope, long mailboxId, String tokenHash, Instant now) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql(
                "UPDATE mailbox_probe SET seen_at = :now WHERE mailbox_id = :mb AND token_hash ="
                    + " :h AND seen_at IS NULL AND expires_at > :now")
            .param("now", Timestamp.from(now))
            .param("mb", mailboxId)
            .param("h", tokenHash)
            .update()
        == 1;
  }

  public Optional<ProbeRow> findProbe(AccessScope scope, long mailboxId, long probeId) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT id, mailbox_id, expires_at, seen_at FROM mailbox_probe WHERE id = :id AND"
                + " mailbox_id = :mb")
        .param("id", probeId)
        .param("mb", mailboxId)
        .query(
            (rs, n) ->
                new ProbeRow(
                    rs.getLong("id"),
                    rs.getLong("mailbox_id"),
                    rs.getTimestamp("expires_at").toInstant(),
                    MailboxRepository.instant(rs, "seen_at")))
        .optional();
  }

  public int purgeProbes(AccessScope scope, Instant cutoff) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql("DELETE FROM mailbox_probe WHERE expires_at < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }
}
