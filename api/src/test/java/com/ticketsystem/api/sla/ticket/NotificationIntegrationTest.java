package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.notification.NotificationKind;
import com.ticketsystem.api.ticket.TicketService.ClientReply;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Notifications: the bell API (unread count, list, mark read) scoped to the caller and to the
 * organisations they can still see; event notifications per preference (in-app and/or email); the
 * daily digest at 08:00 in each supporter's timezone, once per day.
 */
class NotificationIntegrationTest extends SlaTestBase {

  private void clientReply(long id) {
    tickets.addClientReply(
        admin,
        id,
        new ClientReply(null, "reporter@example.test", "<p>More detail</p>", true, null, null));
  }

  @Test
  void theBellListsCountsAndMarksOnlyMyNotifications() throws Exception {
    AppUser agent = restrictedAgent(acme);
    long id = acmeTicket().ticketId();
    tickets.assign(admin, id, agent.id());
    clientReply(id);
    notes.addNote(admin, id, "<p>Checked the logs</p>", List.of());
    // Defaults (nothing stored in WP-11a's tables): in-app and email for all three.
    assertThat(notifications(id, NotificationKind.TICKET_ASSIGNED, agent.id())).isEqualTo(1);
    assertThat(notifications(id, NotificationKind.CLIENT_REPLIED, agent.id())).isEqualTo(1);
    assertThat(notifications(id, NotificationKind.INTERNAL_NOTE_ADDED, agent.id())).isEqualTo(1);
    List<Map<String, Object>> mails = outbox(id, "internal");
    assertThat(mails).filteredOn(m -> m.get("to_addresses").equals(agent.email())).hasSize(3);
    assertThat(mails.get(0).get("subject").toString()).contains("(Internal)");

    clock.reset();
    Browser b = signedIn(agent);
    JsonNode count = json(b.get("/api/v1/agent/notifications/unread-count").getContentAsString());
    assertThat(count.get("unread").asLong()).isEqualTo(3);
    JsonNode list = json(b.get("/api/v1/agent/notifications").getContentAsString());
    assertThat(list.size()).isEqualTo(3);
    assertThat(list.get(0).get("reference").asString()).startsWith(activePrefixOf(acme));
    assertThat(list.get(0).get("text").asString()).isEqualTo("Internal note added on your ticket");
    long first = list.get(0).get("id").asLong();
    assertThat(b.post("/api/v1/agent/notifications/" + first + "/read", "{}").getStatus())
        .isEqualTo(204);
    assertThat(
            json(b.get("/api/v1/agent/notifications/unread-count").getContentAsString())
                .get("unread")
                .asLong())
        .isEqualTo(2);

    // Someone else's notification answers 404; a customer gets 403 on the whole tree.
    AppUser other = restrictedAgent(acme);
    Browser o = signedIn(other);
    assertThat(o.post("/api/v1/agent/notifications/" + first + "/read", "{}").getStatus())
        .isEqualTo(404);
    assertThat(json(o.get("/api/v1/agent/notifications").getContentAsString()).size()).isZero();
    Browser c = signedIn(acmeCustomerUser);
    assertThat(c.get("/api/v1/agent/notifications").getStatus()).isEqualTo(403);

    MockHttpServletResponse all = b.post("/api/v1/agent/notifications/read-all", "{}");
    assertThat(json(all.getContentAsString()).get("marked").asInt()).isEqualTo(2);
  }

  @Test
  void aSupporterWhoLosesAccessNoLongerSeesThatOrganisationsNotifications() throws Exception {
    AppUser agent = restrictedAgent(acme);
    long id = acmeTicket().ticketId();
    tickets.assign(admin, id, agent.id());
    clock.reset();
    Browser b = signedIn(agent);
    assertThat(json(b.get("/api/v1/agent/notifications").getContentAsString()).size()).isEqualTo(1);
    jdbc.update("DELETE FROM supporter_organisation WHERE user_id = ?", agent.id());
    jdbc.update(
        "INSERT INTO supporter_organisation (user_id, organisation_id, created_at) VALUES (?, ?,"
            + " now())",
        agent.id(),
        beta);
    assertThat(notificationsVisibleTo(agent)).isZero();
  }

  private long notificationsVisibleTo(AppUser agent) throws Exception {
    Browser b = signedIn(agent);
    return json(b.get("/api/v1/agent/notifications/unread-count").getContentAsString())
        .get("unread")
        .asLong();
  }

  @Test
  void preferencesChooseInAppAndEmailPerKind() {
    AppUser agent = restrictedAgent(acme);
    choose(agent.id(), "TICKET_ASSIGNED", true, false);
    choose(agent.id(), "CLIENT_REPLIED", false, true);
    long id = acmeTicket().ticketId();
    tickets.assign(admin, id, agent.id());
    clientReply(id);
    assertThat(notifications(id, NotificationKind.TICKET_ASSIGNED, agent.id())).isZero();
    assertThat(notifications(id, NotificationKind.CLIENT_REPLIED, agent.id())).isEqualTo(1);
    List<Map<String, Object>> mails =
        outbox(id, "internal").stream()
            .filter(m -> m.get("to_addresses").equals(agent.email()))
            .toList();
    assertThat(mails).hasSize(1);
    assertThat(mails.get(0).get("subject").toString()).contains("Ticket assigned to you");
  }

  @Test
  void noOneIsNotifiedAboutTheirOwnAction() {
    long id = acmeTicket().ticketId();
    tickets.assign(acmeAgent, id, acmeAgentUser.id());
    notes.addNote(acmeAgent, id, "<p>Mine</p>", List.of());
    assertThat(notifications(id, NotificationKind.TICKET_ASSIGNED, acmeAgentUser.id())).isZero();
    assertThat(notifications(id, NotificationKind.INTERNAL_NOTE_ADDED, acmeAgentUser.id()))
        .isZero();
  }

  @Test
  void theDailyDigestGoesOutAtEightInEachSupportersTimezoneOncePerDay() {
    AppUser saigon = restrictedAgent(acme);
    AppUser london = restrictedAgent(acme);
    preferences(saigon.id(), true, "Asia/Ho_Chi_Minh", false, null, null);
    preferences(london.id(), true, "Europe/London", false, null, null);
    long id = acmeTicket().ticketId();
    tickets.assign(admin, id, saigon.id());
    tickets.assign(admin, acmeTicket().ticketId(), london.id());

    clock.set(Instant.parse("2030-03-04T00:59:00Z")); // 07:59 in Ho Chi Minh City
    digestJob.runOnce();
    assertThat(digestsTo(saigon)).isZero();
    clock.set(Instant.parse("2030-03-04T01:00:00Z")); // 08:00 there, 01:00 in London
    digestJob.runOnce();
    digestJob.runOnce();
    assertThat(digestsTo(saigon)).isEqualTo(1);
    assertThat(digestsTo(london)).isZero();
    clock.set(Instant.parse("2030-03-04T08:00:00Z")); // 08:00 in London (GMT)
    digestJob.runOnce();
    assertThat(digestsTo(london)).isEqualTo(1);
    assertThat(digestsTo(saigon)).isEqualTo(1);
    clock.set(Instant.parse("2030-03-05T01:00:00Z")); // the next day in Ho Chi Minh City
    digestJob.runOnce();
    assertThat(digestsTo(saigon)).isEqualTo(2);

    Map<String, Object> mail =
        jdbc.queryForMap(
            "SELECT subject, text_body FROM outbox_message WHERE to_addresses = ? AND kind ="
                + " 'internal' AND subject LIKE '%daily digest%' ORDER BY id LIMIT 1",
            saigon.email());
    assertThat(mail.get("text_body").toString())
        .contains("Outlook keeps asking for a password")
        .contains("yours");
    // Default preferences: no digest.
    assertThat(digestsTo(acmeAgentUser)).isZero();
  }

  private int digestsTo(AppUser u) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox_message WHERE to_addresses = ? AND kind = 'internal' AND"
            + " subject LIKE '%daily digest%'",
        Integer.class, u.email());
  }
}
