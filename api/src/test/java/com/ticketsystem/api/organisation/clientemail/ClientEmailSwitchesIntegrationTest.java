package com.ticketsystem.api.organisation.clientemail;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.organisation.clientemail.ClientEmailSwitches.Settings;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.config.SlaConfigTypes.SettingsRow;
import com.ticketsystem.api.sla.ticket.PendingTimersService.Action;
import com.ticketsystem.api.sla.ticket.SlaTestBase;
import com.ticketsystem.api.ticket.Impact;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.Urgency;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TimelineEntryView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * ADR-048: per-organisation on/off switches for the four automatic client emails. Off = the email
 * is not queued and the agent timeline gets one "not sent - turned off" line; on = queued as
 * before. Defaults on (existing rows and organisations without a row). Admin API: admin only,
 * audited, the Pending reminder switch is the same stored value as the SLA timers'.
 */
class ClientEmailSwitchesIntegrationTest extends SlaTestBase {

  @Autowired ClientEmailSwitches switches;

  private long org;
  private String orgName;
  private AppUser customer;

  @BeforeEach
  void organisation() {
    org = londonOrganisation("Client Email Dental");
    orgName = jdbc.queryForObject("SELECT name FROM organisation WHERE id = ?", String.class, org);
    customer = activeUser(Role.CUSTOMER, org);
  }

  private void turn(ClientEmail off) {
    switches.save(
        admin,
        org,
        new Settings(
            off != ClientEmail.TICKET_RECEIVED,
            off != ClientEmail.RESOLVED_NOTICE,
            off != ClientEmail.CLOSED_NOTICE,
            off != ClientEmail.PENDING_REMINDER),
        null);
  }

  /** A portal ticket is raised by the client; any other by an admin. */
  private long create(TicketSource source) {
    return tickets
        .create(
            source == TicketSource.PORTAL ? AccessScope.customer(customer.id(), org) : admin,
            new NewTicket(
                org,
                customer.id(),
                null,
                generalType,
                null,
                Impact.JUST_ME,
                Urgency.PARTLY,
                source,
                "Printer offline",
                "<p>The printer on floor 2 is offline.</p>",
                List.of(),
                null,
                null,
                null,
                null))
        .ticketId();
  }

  /** A ticket set Pending on client (public reply) on Mon 23 Nov 10:00 London. */
  private long pendingOnClient() {
    clockAt("2026-11-23T09:00");
    long id = create(TicketSource.AGENT);
    clockAt("2026-11-23T10:00");
    tickets.addPublicReply(
        admin, id, "<p>Is it switched on?</p>", List.of(), TicketStatus.PENDING_ON_CLIENT);
    return id;
  }

  private List<Map<String, Object>> clientMails(long id, String marker) {
    return outbox(id, "client_facing").stream()
        .filter(m -> (m.get("subject") + " " + m.get("text_body")).contains(marker))
        .toList();
  }

  private List<String> skippedLines(long id) {
    return queries.detail(admin, id).timelineEntries().stream()
        .filter(e -> "CLIENT_EMAIL_SKIPPED".equals(e.type()))
        .map(TimelineEntryView::text)
        .toList();
  }

  private static final String RECEIVED = "We have received your request";
  private static final String RESOLVED = "is resolved";
  private static final String CLOSED = "Your request has been closed";
  private static final String REMINDER = "We are waiting for your reply";

  // ---------------------------------------------------------------------------------------------
  // Ticket received

  @Test
  void ticketReceivedOnIsQueuedAsBefore() {
    long id = create(TicketSource.PORTAL);
    assertThat(clientMails(id, RECEIVED)).hasSize(1);
    assertThat(skippedLines(id)).isEmpty();
  }

  @Test
  void ticketReceivedOffIsNotQueuedAndTheTimelineSaysSo() {
    turn(ClientEmail.TICKET_RECEIVED);
    long id = create(TicketSource.PORTAL);
    assertThat(clientMails(id, RECEIVED)).isEmpty();
    assertThat(skippedLines(id))
        .containsExactly("Ticket received not sent - turned off for " + orgName);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT new_value, reason_ref_id, actor_kind FROM ticket_event WHERE ticket_id = ? AND"
                + " type = 'CLIENT_EMAIL_SKIPPED'",
            id);
    assertThat(row.get("new_value")).isEqualTo("TICKET_RECEIVED");
    assertThat(((Number) row.get("reason_ref_id")).longValue()).isEqualTo(org);
    assertThat(row.get("actor_kind")).isEqualTo("SYSTEM");
  }

  @Test
  void theAgentOpenedTicketEmailIsNeverSwitchedOff() {
    switches.save(admin, org, new Settings(false, false, false, false), null);
    clockAt("2026-11-23T09:00");
    long id = create(TicketSource.AGENT);
    assertThat(clientMails(id, "We've logged your request as")).hasSize(1);
    assertThat(skippedLines(id)).isEmpty();
    // Agent public replies are never switchable either.
    tickets.addPublicReply(admin, id, "<p>On our way.</p>", List.of(), null);
    assertThat(clientMails(id, "On our way.")).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------------
  // Resolved notice

  @Test
  void resolvedNoticeOnIsQueuedAsBefore() {
    long id = create(TicketSource.AGENT);
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    assertThat(clientMails(id, RESOLVED)).hasSize(1);
    assertThat(skippedLines(id)).isEmpty();
  }

  @Test
  void resolvedNoticeOffIsNotQueuedAndTheTimelineSaysSo() {
    turn(ClientEmail.RESOLVED_NOTICE);
    long id = create(TicketSource.AGENT);
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
    assertThat(clientMails(id, RESOLVED)).isEmpty();
    assertThat(skippedLines(id))
        .containsExactly("Resolved notice not sent - turned off for " + orgName);
  }

  @Test
  void resolvedNoticeOffAlsoCoversTheAutoResolve() {
    turn(ClientEmail.RESOLVED_NOTICE);
    long id = pendingOnClient();
    clockAt("2026-12-02T10:00"); // 7 business days later: auto-resolve (before any reminder)
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.AUTO_RESOLVED);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
    assertThat(clientMails(id, RESOLVED)).isEmpty();
    assertThat(skippedLines(id))
        .containsExactly("Resolved notice not sent - turned off for " + orgName);
  }

  // ---------------------------------------------------------------------------------------------
  // Closed notice

  @Test
  void closedNoticeOnIsQueuedAsBefore() {
    clockAt("2026-11-23T09:00");
    long id = create(TicketSource.AGENT);
    clockAt("2026-11-23T10:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    clockAt("2026-11-28T10:00");
    assertThat(timers.processResolved(SYSTEM, id)).isEqualTo(Action.AUTO_CLOSED);
    assertThat(clientMails(id, CLOSED)).hasSize(1);
    assertThat(skippedLines(id)).isEmpty();
  }

  @Test
  void closedNoticeOffIsNotQueuedTheTicketStillClosesAndTheTimelineSaysSo() {
    turn(ClientEmail.CLOSED_NOTICE);
    clockAt("2026-11-23T09:00");
    long id = create(TicketSource.AGENT);
    clockAt("2026-11-23T10:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    clockAt("2026-11-28T10:00");
    assertThat(timers.processResolved(SYSTEM, id)).isEqualTo(Action.AUTO_CLOSED);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.CLOSED);
    assertThat(clientMails(id, CLOSED)).isEmpty();
    assertThat(skippedLines(id))
        .containsExactly("Closed notice not sent - turned off for " + orgName);
  }

  // ---------------------------------------------------------------------------------------------
  // Pending reminder

  @Test
  void pendingReminderOnIsQueuedAsBefore() {
    long id = pendingOnClient();
    clockAt("2026-11-26T10:00");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.REMINDED);
    assertThat(clientMails(id, REMINDER)).hasSize(1);
    assertThat(skippedLines(id)).isEmpty();
  }

  @Test
  void pendingReminderOffIsNotQueuedAndTheTimelineSaysSoOncePerSpell() {
    turn(ClientEmail.PENDING_REMINDER);
    long id = pendingOnClient();
    clockAt("2026-11-26T10:00");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.REMINDER_TURNED_OFF);
    clockAt("2026-11-27T10:00");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.NONE);
    assertThat(clientMails(id, REMINDER)).isEmpty();
    assertThat(skippedLines(id))
        .containsExactly("Pending reminder not sent - turned off for " + orgName);
    // The pre-ADR-048 SLA timers switch is the same stored value.
    assertThat(slaConfig.settings(admin, org).pendingReminderEnabled()).isFalse();
  }

  // ---------------------------------------------------------------------------------------------
  // Defaults, triage, stored value

  @Test
  void everySwitchIsOnByDefaultForExistingOrganisationsAndOrganisationsWithoutARow() {
    // A row written before V99 (the seeded SLA settings) reads as all on.
    assertThat(switches.get(admin, org)).isEqualTo(Settings.allOn());
    // An organisation without a settings row reads as all on.
    long bare = newOrganisation("Bare Clinic");
    jdbc.update("DELETE FROM organisation_sla_settings WHERE organisation_id = ?", bare);
    assertThat(switches.get(admin, bare)).isEqualTo(Settings.allOn());
    // V99 added the columns NOT NULL DEFAULT TRUE, which fills every existing row with TRUE.
    List<Map<String, Object>> columns =
        jdbc.queryForList(
            "SELECT column_name, column_default, is_nullable FROM information_schema.columns"
                + " WHERE table_name = 'organisation_sla_settings' AND column_name IN"
                + " ('ticket_received_email_enabled', 'resolved_email_enabled',"
                + " 'closed_email_enabled', 'pending_reminder_enabled')");
    assertThat(columns).hasSize(4);
    assertThat(columns)
        .allSatisfy(
            c -> {
              assertThat(String.valueOf(c.get("column_default"))).isEqualTo("true");
              assertThat(c.get("is_nullable")).isEqualTo("NO");
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM organisation_sla_settings WHERE NOT"
                    + " (ticket_received_email_enabled AND resolved_email_enabled AND"
                    + " closed_email_enabled) AND organisation_id = ?",
                Integer.class,
                org))
        .isZero();
  }

  @Test
  void triageTicketsAreNeverSwitchedOff() {
    for (ClientEmail e : ClientEmail.values()) {
      assertThat(switches.isOn(SYSTEM, null, e)).isTrue();
    }
  }

  @Test
  void theSlaTimersFormWithoutTheReminderSwitchKeepsTheStoredValue() throws Exception {
    turn(ClientEmail.PENDING_REMINDER);
    Browser b = signedIn(adminUser);
    MockHttpServletResponse r =
        b.put(
            "/api/v1/admin/organisations/" + org + "/sla/timers",
            "{\"pendingReminderBusinessDays\":4,\"autoResolveEnabled\":true,"
                + "\"autoResolveBusinessDays\":7,\"autoCloseEnabled\":true,"
                + "\"autoCloseCalendarDays\":5,\"apiAlertClientEmails\":false}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    SettingsRow s = slaConfig.settings(admin, org);
    assertThat(s.pendingReminderEnabled()).isFalse();
    assertThat(s.pendingReminderBusinessDays()).isEqualTo(4);
    assertThat(switches.get(admin, org).pendingReminder()).isFalse();
    // The old body with the field still works and sets it.
    r =
        b.put(
            "/api/v1/admin/organisations/" + org + "/sla/timers",
            "{\"pendingReminderEnabled\":true,\"pendingReminderBusinessDays\":4,"
                + "\"autoResolveEnabled\":true,\"autoResolveBusinessDays\":7,"
                + "\"autoCloseEnabled\":true,\"autoCloseCalendarDays\":5,"
                + "\"apiAlertClientEmails\":false}");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(switches.get(admin, org).pendingReminder()).isTrue();
    // Saving the switches keeps the timers' days.
    turn(ClientEmail.CLOSED_NOTICE);
    assertThat(slaConfig.settings(admin, org).pendingReminderBusinessDays()).isEqualTo(4);
  }

  // ---------------------------------------------------------------------------------------------
  // Admin API

  private String path(long organisationId) {
    return "/api/v1/admin/organisations/" + organisationId + "/client-emails";
  }

  private static final String ALL_OFF =
      "{\"ticketReceived\":false,\"resolvedNotice\":false,\"closedNotice\":false,"
          + "\"pendingReminder\":false}";

  @Test
  void anAdminReadsAndSavesTheSwitchesAndEveryChangeIsAudited() throws Exception {
    Browser b = signedIn(adminUser);
    JsonNode before = json(b.get(path(org)).getContentAsString());
    assertThat(before.get("ticketReceived").asBoolean()).isTrue();
    assertThat(before.get("resolvedNotice").asBoolean()).isTrue();
    assertThat(before.get("closedNotice").asBoolean()).isTrue();
    assertThat(before.get("pendingReminder").asBoolean()).isTrue();

    MockHttpServletResponse r =
        b.put(
            path(org),
            "{\"ticketReceived\":true,\"resolvedNotice\":false,\"closedNotice\":true,"
                + "\"pendingReminder\":false}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode after = json(r.getContentAsString());
    assertThat(after.get("resolvedNotice").asBoolean()).isFalse();
    assertThat(after.get("pendingReminder").asBoolean()).isFalse();
    assertThat(after.get("ticketReceived").asBoolean()).isTrue();
    assertThat(switches.get(admin, org)).isEqualTo(new Settings(true, false, true, false));

    List<Map<String, Object>> events =
        auditEvents("CLIENT_EMAILS_CHANGED", null).stream()
            .filter(a -> a.get("details").toString().contains("\"organisation\": " + org))
            .toList();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("details").toString())
        .contains("resolved_notice:off")
        .contains("pending_reminder:off")
        .doesNotContain("ticket_received")
        .doesNotContain("closed_notice");

    // Saving the same values again changes nothing and logs nothing.
    assertThat(b.put(path(org), r.getContentAsString()).getStatus()).isEqualTo(200);
    assertThat(
            auditEvents("CLIENT_EMAILS_CHANGED", null).stream()
                .filter(a -> a.get("details").toString().contains("\"organisation\": " + org))
                .count())
        .isEqualTo(1);
  }

  @Test
  void aMissingSwitchIsRefusedNotReadAsOff() throws Exception {
    Browser b = signedIn(adminUser);
    MockHttpServletResponse r = b.put(path(org), "{\"ticketReceived\":false}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(switches.get(admin, org)).isEqualTo(Settings.allOn());
  }

  @Test
  void agentsAndCustomersGet403AndAnUnknownOrganisationIs404() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    assertThat(agent.get(path(acme)).getStatus()).isEqualTo(403);
    assertThat(agent.put(path(acme), ALL_OFF).getStatus()).isEqualTo(403);
    Browser client = signedIn(acmeCustomerUser);
    assertThat(client.get(path(acme)).getStatus()).isEqualTo(403);
    assertThat(client.put(path(acme), ALL_OFF).getStatus()).isEqualTo(403);
    assertThat(switches.get(admin, acme)).isEqualTo(Settings.allOn());

    Browser b = signedIn(adminUser);
    assertThat(b.get(path(999_999_999L)).getStatus()).isEqualTo(404);
    assertThat(b.put(path(999_999_999L), ALL_OFF).getStatus()).isEqualTo(404);
  }
}
