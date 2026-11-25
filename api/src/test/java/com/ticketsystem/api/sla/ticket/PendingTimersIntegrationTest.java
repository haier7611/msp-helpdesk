package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.config.SlaConfigTypes.SettingsRow;
import com.ticketsystem.api.sla.ticket.PendingTimersService.Action;
import com.ticketsystem.api.ticket.Impact;
import com.ticketsystem.api.ticket.ReplyCcService.ReplyCc;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;
import com.ticketsystem.api.ticket.Urgency;
import com.ticketsystem.api.ticket.event.TicketEvent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Pending-on-client reminder (3 business days) and auto-resolve (7), the Resolved auto-close (5
 * calendar days), per-organisation settings, and the client email matrix: only the reminder and the
 * Closed notice are WP-09's emails, never to automated senders, never for API alerts unless opted
 * in, never with SLA information. Times are London (Mon 23 Nov 2026 onwards).
 */
class PendingTimersIntegrationTest extends SlaTestBase {

  private long org;
  private AppUser customer;

  @BeforeEach
  void organisation() {
    org = londonOrganisation("Acme Dental");
    customer = activeUser(Role.CUSTOMER, org);
  }

  /** A P3 ticket set Pending on client (with a public reply) on Mon 23 Nov 10:00. */
  private long pendingOnClient(TicketSource source) {
    clockAt("2026-11-23T09:00");
    long id =
        tickets
            .create(
                admin,
                new NewTicket(
                    org,
                    customer.id(),
                    null,
                    generalType,
                    null,
                    Impact.JUST_ME,
                    Urgency.PARTLY,
                    source,
                    "Shared drive missing",
                    "<p>The S: drive has gone.</p>",
                    List.of(),
                    null,
                    null,
                    null,
                    null))
            .ticketId();
    clockAt("2026-11-23T10:00");
    tickets.addPublicReply(
        admin, id, "<p>Which computer is it?</p>", List.of(), TicketStatus.PENDING_ON_CLIENT);
    return id;
  }

  /**
   * WP-09's own client notices (reminder, Closed notice). WP-07's confirmation, public reply and
   * Resolved notice for the same ticket are covered by EmailInOutIntegrationTest.
   */
  private List<Map<String, Object>> clientMails(long id) {
    return outbox(id, "client_facing").stream()
        .filter(
            m -> {
              String subject = String.valueOf(m.get("subject"));
              return subject.contains("We are waiting for your reply")
                  || subject.contains("Your request has been closed");
            })
        .toList();
  }

  @Test
  void remindsAfterThreeBusinessDaysOnceThenAutoResolvesAfterSevenThenClosesAfterFiveDays() {
    long id = pendingOnClient(TicketSource.AGENT);
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, email, confirmed, created_at) VALUES"
            + " (?, 'colleague@acme-dental.example', TRUE, now()), (?, 'no-reply@acme-dental.example',"
            + " TRUE, now()), (?, 'outsider@other.example', FALSE, now())",
        id,
        id,
        id);

    // 3 business days = 1,620 business minutes from Mon 10:00 = Thu 26 Nov 10:00.
    clockAt("2026-11-26T09:59");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.NONE);
    clockAt("2026-11-26T10:00");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.REMINDED);
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.NONE);
    clockAt("2026-11-27T10:00");
    minuteJob.runOnce();
    List<Map<String, Object>> mails = clientMails(id);
    assertThat(mails).hasSize(1);
    Map<String, Object> reminder = mails.get(0);
    assertThat(reminder.get("subject").toString())
        .startsWith("[" + activePrefixOf(org))
        .contains("We are waiting for your reply");
    assertThat(reminder.get("to_addresses")).isEqualTo(customer.email().toLowerCase(Locale.ROOT));
    // The confirmed colleague is copied; the automated sender and the unconfirmed CC are not.
    assertThat(reminder.get("cc_addresses")).isEqualTo("colleague@acme-dental.example");
    assertThat(statusOf(id)).isEqualTo(TicketStatus.PENDING_ON_CLIENT);

    // 7 business days = 3,780 minutes from Mon 10:00 = Wed 2 Dec 10:00.
    clockAt("2026-12-02T09:59");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.NONE);
    clockAt("2026-12-02T10:00");
    recorder.clear();
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.AUTO_RESOLVED);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
    // WP-07 sends the matrix "Resolved" email from this event; WP-09 adds no email of its own.
    assertThat(recorder.forTicket(id))
        .anySatisfy(
            e ->
                assertThat(e)
                    .isEqualTo(
                        new TicketEvent.StatusChanged(
                            id,
                            TicketStatus.PENDING_ON_CLIENT,
                            TicketStatus.RESOLVED,
                            Trigger.AUTO_RESOLVE,
                            null)));
    assertThat(clientMails(id)).hasSize(1);

    // 5 calendar days after Resolved (Wed 2 Dec 10:00) = Mon 7 Dec 10:00.
    clockAt("2026-12-07T09:59");
    assertThat(timers.processResolved(SYSTEM, id)).isEqualTo(Action.NONE);
    clockAt("2026-12-07T10:00");
    assertThat(timers.processResolved(SYSTEM, id)).isEqualTo(Action.AUTO_CLOSED);
    assertThat(timers.processResolved(SYSTEM, id)).isEqualTo(Action.NONE);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.CLOSED);
    mails = clientMails(id);
    assertThat(mails).hasSize(2);
    assertThat(mails.get(1).get("subject").toString()).contains("Your request has been closed");

    for (Map<String, Object> m : mails) {
      String all =
          (m.get("subject") + " " + m.get("html_body") + " " + m.get("text_body"))
              .toLowerCase(Locale.ROOT);
      for (String word : List.of("sla", " due", "breach", "target", "business hours")) {
        assertThat(all).as("client email must not mention %s", word).doesNotContain(word);
      }
      assertThat(m.get("html_body").toString()).contains("Shared drive missing");
    }
  }

  @Test
  void anotherOrganisationsContactConfirmedAsAnOutsideCcGetsTheReminderAndClosedNotice() {
    // ADR-050 change 2026-11-24: on this ticket they are an outside participant like any other.
    long otherOrg = londonOrganisation("Beta Clinic");
    AppUser betaContact = activeUser(Role.CUSTOMER, otherOrg);
    String betaAddress = betaContact.email().toLowerCase(Locale.ROOT);
    long id = pendingOnClient(TicketSource.AGENT);
    tickets.addPublicReply(
        admin,
        id,
        "<p>Copying the vendor in.</p>",
        List.of(),
        TicketStatus.PENDING_ON_CLIENT,
        new ReplyCc(List.of(betaAddress), true, List.of(betaAddress)));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_participant WHERE ticket_id = ? AND lower(email) = ?"
                    + " AND confirmed AND user_id IS NULL",
                Integer.class,
                id,
                betaAddress))
        .isOne();

    clockAt("2026-11-26T10:00");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.REMINDED);
    clockAt("2026-12-02T10:00");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.AUTO_RESOLVED);
    clockAt("2026-12-07T10:00");
    assertThat(timers.processResolved(SYSTEM, id)).isEqualTo(Action.AUTO_CLOSED);
    minuteJob.runOnce();

    List<Map<String, Object>> mails = clientMails(id);
    assertThat(mails).hasSize(2);
    for (Map<String, Object> m : mails) {
      assertThat(m.get("to_addresses")).isEqualTo(customer.email().toLowerCase(Locale.ROOT));
      assertThat(m.get("cc_addresses")).isEqualTo(betaAddress);
    }
  }

  @Test
  void aNewPendingSpellCanRemindAgain() {
    long id = pendingOnClient(TicketSource.AGENT);
    clockAt("2026-11-26T10:00");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.REMINDED);
    clockAt("2026-11-26T11:00");
    tickets.changeStatus(admin, id, TicketStatus.OPEN);
    tickets.changeStatus(admin, id, TicketStatus.PENDING_ON_CLIENT);
    clockAt("2026-12-01T11:00"); // 3 business days later: Thu 26 11:00 -> Tue 1 Dec 11:00
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.REMINDED);
    assertThat(clientMails(id)).hasSize(2);
  }

  @Test
  void pendingOnVendorIsNeverRemindedOrAutoResolved() {
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customer, null);
    tickets.changeStatus(admin, id, TicketStatus.PENDING_ON_VENDOR);
    clockAt("2026-12-14T09:00");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.NONE);
    minuteJob.runOnce();
    assertThat(statusOf(id)).isEqualTo(TicketStatus.PENDING_ON_VENDOR);
    assertThat(clientMails(id)).isEmpty();
  }

  @Test
  void aSwitchedOffOrganisationGetsNoReminderAutoResolveOrAutoClose() {
    slaConfig.saveTimers(
        admin, org, new SettingsRow(false, 3, false, 7, false, 5, false, null), null);
    long id = pendingOnClient(TicketSource.AGENT);
    clockAt("2026-12-14T09:00");
    // ADR-048: the due reminder is not sent (one "not sent - turned off" line, once per spell).
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.REMINDER_TURNED_OFF);
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.NONE);
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    clockAt("2027-01-14T09:00");
    assertThat(timers.processResolved(SYSTEM, id)).isEqualTo(Action.NONE);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
    assertThat(clientMails(id)).isEmpty();
  }

  @Test
  void perOrganisationDaysAreUsed() {
    slaConfig.saveTimers(admin, org, new SettingsRow(true, 1, true, 2, true, 1, false, null), null);
    long id = pendingOnClient(TicketSource.AGENT);
    clockAt("2026-11-24T10:00"); // 1 business day = 540 minutes after Mon 10:00
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.REMINDED);
    clockAt("2026-11-25T10:00");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.AUTO_RESOLVED);
    clockAt("2026-11-26T10:00");
    assertThat(timers.processResolved(SYSTEM, id)).isEqualTo(Action.AUTO_CLOSED);
  }

  @Test
  void apiAlertTicketsEmailTheClientOnlyWhenTheOrganisationOptedIn() {
    long quiet = pendingOnClient(TicketSource.API);
    clockAt("2026-11-26T10:00");
    assertThat(timers.processPending(SYSTEM, quiet)).isEqualTo(Action.REMINDED_WITHOUT_EMAIL);
    assertThat(clientMails(quiet)).isEmpty();

    slaConfig.saveTimers(admin, org, new SettingsRow(true, 3, true, 7, true, 5, true, null), null);
    long loud = pendingOnClient(TicketSource.API);
    clockAt("2026-11-26T10:00");
    assertThat(timers.processPending(SYSTEM, loud)).isEqualTo(Action.REMINDED);
    assertThat(clientMails(loud)).hasSize(1);
  }

  @Test
  void anAutomatedReporterNeverGetsAnAutomaticEmail() {
    long id = pendingOnClient(TicketSource.AGENT);
    jdbc.update(
        "UPDATE ticket SET reporter_user_id = NULL, reporter_email = 'noreply@monitor.example'"
            + " WHERE id = ?",
        id);
    clockAt("2026-11-26T10:00");
    assertThat(timers.processPending(SYSTEM, id)).isEqualTo(Action.REMINDED_WITHOUT_EMAIL);
    assertThat(clientMails(id)).isEmpty();
  }
}
