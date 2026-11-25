package com.ticketsystem.api.organisation.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The per-organisation client email switches, stored on {@code organisation_sla_settings} (one row
 * per organisation; a missing row reads as all on). The Pending reminder switch is the existing
 * {@code pending_reminder_enabled} column (V61), so the SLA timers and this screen share one stored
 * value. Reads need a supporter or SYSTEM scope that sees the organisation; writes need an admin
 * (or SYSTEM). Anything else answers 404.
 */
@Repository
public class ClientEmailSettingsRepository {

  /** The four switches as stored. */
  public record SwitchesRow(
      boolean ticketReceived,
      boolean resolvedNotice,
      boolean closedNotice,
      boolean pendingReminder) {}

  private final JdbcClient jdbc;

  public ClientEmailSettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<SwitchesRow> find(AccessScope scope, long organisationId) {
    if (!scope.isSupporter() && !scope.isSystem()) {
      throw AccessScope.notFound();
    }
    scope.require(organisationId);
    return jdbc.sql(
            "SELECT ticket_received_email_enabled, resolved_email_enabled, closed_email_enabled,"
                + " pending_reminder_enabled FROM organisation_sla_settings WHERE organisation_id"
                + " = :org")
        .param("org", organisationId)
        .query(
            (rs, n) ->
                new SwitchesRow(
                    rs.getBoolean("ticket_received_email_enabled"),
                    rs.getBoolean("resolved_email_enabled"),
                    rs.getBoolean("closed_email_enabled"),
                    rs.getBoolean("pending_reminder_enabled")))
        .optional();
  }

  /** Inserts or replaces the four switches; every other setting on the row is kept. */
  public void save(AccessScope scope, long organisationId, SwitchesRow s, Instant now) {
    if (!scope.isAdmin() && !scope.isSystem()) {
      throw AccessScope.notFound();
    }
    scope.require(organisationId);
    jdbc.sql(
            "INSERT INTO organisation_sla_settings (organisation_id,"
                + " ticket_received_email_enabled, resolved_email_enabled, closed_email_enabled,"
                + " pending_reminder_enabled, updated_at) VALUES (:org, :received, :resolved,"
                + " :closed, :reminder, :now) ON CONFLICT (organisation_id) DO UPDATE SET"
                + " ticket_received_email_enabled = EXCLUDED.ticket_received_email_enabled,"
                + " resolved_email_enabled = EXCLUDED.resolved_email_enabled,"
                + " closed_email_enabled = EXCLUDED.closed_email_enabled,"
                + " pending_reminder_enabled = EXCLUDED.pending_reminder_enabled,"
                + " updated_at = EXCLUDED.updated_at")
        .param("org", organisationId)
        .param("received", s.ticketReceived())
        .param("resolved", s.resolvedNotice())
        .param("closed", s.closedNotice())
        .param("reminder", s.pendingReminder())
        .param("now", Timestamp.from(now))
        .update();
  }
}
