package com.ticketsystem.api.sla.ticket;

import com.ticketsystem.api.TestcontainersConfiguration;
import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.notification.NotificationKind;
import com.ticketsystem.api.notification.job.DailyDigestJob;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.sla.config.SlaConfigTypes.HolidayRow;
import com.ticketsystem.api.sla.job.SlaMinuteJob;
import com.ticketsystem.api.sla.report.SlaReportService;
import com.ticketsystem.api.ticket.Impact;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.TicketTestBase;
import com.ticketsystem.api.ticket.Urgency;
import com.ticketsystem.api.ticket.assignment.AutoAssignmentService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Helpers for the WP-09 integration tests: an organisation on Europe/London with the WP-09a fixture
 * holidays (so the hand-calculated fixture table applies unchanged), London local times for the
 * controllable clock, and direct reads of the stored SLA columns, notifications and outbox rows.
 *
 * <p>WP-13: these tests run the installation-wide SLA minute job and daily digest with the clock
 * moved weeks ahead, so they use their own database in the shared container (one Spring context for
 * all SLA classes). In the shared database those jobs processed every ticket the other classes had
 * left behind, which took about 6 minutes of a CI build.
 */
@TestPropertySource(
    properties = TestcontainersConfiguration.DATABASE_PROPERTY + "=ticketsys_it_sla")
public abstract class SlaTestBase extends TicketTestBase {

  protected static final ZoneId LONDON = ZoneId.of("Europe/London");

  @Autowired protected SlaConfigService slaConfig;
  @Autowired protected TicketSlaService slaService;
  @Autowired protected PendingTimersService timers;
  @Autowired protected SlaMinuteJob minuteJob;
  @Autowired protected DailyDigestJob digestJob;
  @Autowired protected AutoAssignmentService autoAssignment;
  @Autowired protected SlaReportService reports;

  protected static final AccessScope SYSTEM = AccessScope.system("WP-09 test");

  /** London local time such as {@code 2026-11-21T23:00} as an instant (as in SlaFixtures). */
  protected static Instant at(String localDateTime) {
    return ZonedDateTime.of(LocalDateTime.parse(localDateTime), LONDON).toInstant();
  }

  protected void clockAt(String localDateTime) {
    clock.set(at(localDateTime));
  }

  /** A London organisation seeded with the ADR-017 defaults and the WP-09a fixture holidays. */
  protected long londonOrganisation(String name) {
    long org = newOrganisation(name);
    jdbc.update("UPDATE organisation SET timezone = 'Europe/London' WHERE id = ?", org);
    slaConfig.seedDefaults(admin, org);
    slaConfig.replaceHolidays(
        admin,
        org,
        List.of(
            new HolidayRow(LocalDate.parse("2026-12-25"), "Christmas Day"),
            new HolidayRow(LocalDate.parse("2026-12-28"), "Boxing Day (substitute)"),
            new HolidayRow(LocalDate.parse("2027-01-01"), "New Year's Day")),
        null);
    return org;
  }

  /** A ticket raised by an admin in {@code org} with {@code priority} (null = request type's). */
  protected long ticketIn(long org, AppUser reporter, Priority priority) {
    Created c =
        tickets.create(
            admin,
            new NewTicket(
                org,
                reporter.id(),
                null,
                generalType,
                priority,
                Impact.JUST_ME,
                Urgency.PARTLY,
                TicketSource.AGENT,
                "Laptop will not start",
                "<p>Black screen since this morning.</p>",
                List.of(),
                null,
                null,
                null,
                null));
    return c.ticketId();
  }

  protected Map<String, Object> sla(long ticketId) {
    return jdbc.queryForMap(
        "SELECT sla_clock, first_response_due_at, first_response_state, first_responded_at,"
            + " first_response_used_minutes, first_response_target_minutes, resolution_due_at,"
            + " resolution_state, resolution_used_minutes, resolved_at, sla_paused_minutes,"
            + " sla_warning, sla_policy_fallback, sla_next_check_at FROM ticket WHERE id = ?",
        ticketId);
  }

  /** London local minute of a stored timestamp column, or "-". */
  protected static String local(Object timestamp) {
    if (timestamp == null) {
      return "-";
    }
    Instant i = ((java.sql.Timestamp) timestamp).toInstant();
    return LocalDateTime.ofInstant(i, LONDON).toString();
  }

  protected int notifications(long ticketId, NotificationKind kind, long userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification WHERE ticket_id = ? AND kind = ? AND user_id = ?",
        Integer.class,
        ticketId,
        kind.name(),
        userId);
  }

  protected int notifications(long ticketId, NotificationKind kind) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification WHERE ticket_id = ? AND kind = ?",
        Integer.class,
        ticketId,
        kind.name());
  }

  protected List<Map<String, Object>> outbox(long ticketId, String kind) {
    return jdbc.queryForList(
        "SELECT subject, to_addresses, cc_addresses, html_body, text_body, reply_to FROM"
            + " outbox_message WHERE ticket_id = ? AND kind = ? ORDER BY id",
        ticketId,
        kind);
  }

  /** Stores a WP-11a notification choice (event names as in V40, e.g. SLA_WARNING). */
  protected void choose(long userId, String event, boolean email, boolean inApp) {
    jdbc.update(
        "INSERT INTO supporter_notification (user_id, event, email, in_app) VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (user_id, event) DO UPDATE SET email = EXCLUDED.email, in_app ="
            + " EXCLUDED.in_app",
        userId,
        event,
        email,
        inApp);
  }

  /** Stores WP-11a preferences: digest on/off, timezone, out of office (dates may be null). */
  protected void preferences(
      long userId, boolean digest, String zone, boolean away, String startsOn, String endsOn) {
    jdbc.update(
        "INSERT INTO supporter_preferences (user_id, timezone, daily_digest, out_of_office,"
            + " ooo_starts_on, ooo_ends_on, updated_at) VALUES (?, ?, ?, ?, CAST(? AS DATE),"
            + " CAST(? AS DATE), now()) ON CONFLICT (user_id) DO UPDATE SET timezone ="
            + " EXCLUDED.timezone, daily_digest = EXCLUDED.daily_digest, out_of_office ="
            + " EXCLUDED.out_of_office, ooo_starts_on = EXCLUDED.ooo_starts_on, ooo_ends_on ="
            + " EXCLUDED.ooo_ends_on",
        userId,
        zone,
        digest,
        away,
        startsOn,
        endsOn);
  }

  protected void recalc(long ticketId) {
    slaService.recalculateInNewTransaction(SYSTEM, ticketId);
  }
}
