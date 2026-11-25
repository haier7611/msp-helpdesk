package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.ticket.QueueTimerService.FirstResponseTimer;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.QueueQuery;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.QueueSort;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketSummary;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * ADR-047 Decision 1: the agent queue's "First response" column - business time left on the
 * organisation's calendar, stopped outside hours, paused, met, breached by (live while unanswered,
 * final once answered), the first-response sort, and the timer riding only on the scoped agent
 * queue. Triage rows (no organisation) count on the triage default calendar of ADR-047 Decision 2:
 * Monday-Friday 08:30-17:30 in the MSP timezone (Asia/Ho_Chi_Minh by default).
 */
class QueueTimerIntegrationTest extends SlaTestBase {

  private static final ZoneId MSP = ZoneId.of("Asia/Ho_Chi_Minh");

  /** MSP local time (UTC+7, no daylight saving) as an instant. */
  private static Instant msp(String localDateTime) {
    return ZonedDateTime.of(LocalDateTime.parse(localDateTime), MSP).toInstant();
  }

  /**
   * A ticket with {@code subject}; {@code org} null makes a triage ticket from an unknown sender.
   */
  private long ticket(Long org, AppUser reporter, Priority priority, String subject) {
    return tickets
        .create(
            admin,
            new NewTicket(
                org,
                reporter == null ? null : reporter.id(),
                reporter == null ? "unknown.sender@public-mail.example" : null,
                generalType,
                priority,
                null,
                null,
                org == null ? TicketSource.EMAIL : TicketSource.AGENT,
                subject,
                "<p>Help</p>",
                List.of(),
                null,
                null,
                null,
                null))
        .ticketId();
  }

  private List<TicketSummary> triageQueue() {
    return queries
        .list(
            admin,
            new QueueQuery(
                null,
                null,
                true,
                null,
                false,
                true,
                false,
                null,
                50,
                null,
                null,
                false,
                QueueSort.NEWEST,
                0))
        .items();
  }

  private FirstResponseTimer triageTimer(long ticketId) {
    return triageQueue().stream()
        .filter(s -> s.id() == ticketId)
        .findFirst()
        .orElseThrow()
        .firstResponseTimer();
  }

  private AppUser customerOf(long org) {
    return activeUser(Role.CUSTOMER, org);
  }

  private List<TicketSummary> queue(long org, QueueSort sort) {
    return queries
        .list(
            admin,
            new QueueQuery(
                null, org, false, null, false, true, false, null, 50, null, null, false, sort, 0))
        .items();
  }

  private FirstResponseTimer timer(long org, long ticketId) {
    return queue(org, QueueSort.NEWEST).stream()
        .filter(s -> s.id() == ticketId)
        .findFirst()
        .orElseThrow()
        .firstResponseTimer();
  }

  @Test
  void runningShowsTheBusinessTimeLeftNow() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00"); // Monday
    long id = ticketIn(org, customerOf(org), null); // P3: 4 business hours
    recalc(id);
    clockAt("2026-11-23T10:00");
    FirstResponseTimer t = timer(org, id);
    assertThat(t.state()).isEqualTo("RUNNING");
    assertThat(t.remainingMinutes()).isEqualTo(180);
    assertThat(t.businessDayMinutes()).isEqualTo(540);
    assertThat(t.breachedByMinutes()).isNull();
    assertThat(t.asOf()).isEqualTo(at("2026-11-23T10:00"));
    // Recomputed on every read: five minutes later, five minutes less.
    clockAt("2026-11-23T10:05");
    assertThat(timer(org, id).remainingMinutes()).isEqualTo(175);
  }

  @Test
  void outsideBusinessHoursTheTimeLeftIsFrozen() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), Priority.P4); // 1 business day
    recalc(id);
    clockAt("2026-11-23T18:00");
    FirstResponseTimer t = timer(org, id);
    assertThat(t.state()).isEqualTo("STOPPED_OUTSIDE_HOURS");
    assertThat(t.remainingMinutes()).isEqualTo(30);
    clockAt("2026-11-24T08:00");
    assertThat(timer(org, id).remainingMinutes()).isEqualTo(30);
  }

  @Test
  void p1CountsAroundTheClock() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T22:00");
    long id = ticketIn(org, customerOf(org), Priority.P1);
    recalc(id);
    clockAt("2026-11-23T22:20");
    FirstResponseTimer t = timer(org, id);
    assertThat(t.state()).isEqualTo("RUNNING");
    assertThat(t.businessDayMinutes()).isEqualTo(1440);
    assertThat(t.remainingMinutes()).isNotNull().isPositive();
  }

  @Test
  void breachedCountsOnWhileUnansweredAndStopsWhenAnswered() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), Priority.P2); // 1 h first response
    recalc(id);
    clockAt("2026-11-23T10:30");
    recalc(id);
    FirstResponseTimer t = timer(org, id);
    assertThat(t.state()).isEqualTo("BREACHED");
    assertThat(t.breachedByMinutes()).isEqualTo(30);
    assertThat(t.remainingMinutes()).isNull();
    // No recalculation since: still counted from the due time.
    clockAt("2026-11-23T11:15");
    assertThat(timer(org, id).breachedByMinutes()).isEqualTo(75);

    tickets.addPublicReply(admin, id, "<p>Looking now</p>", List.of(), null);
    recalc(id);
    clockAt("2026-11-23T12:00");
    assertThat(timer(org, id).state()).isEqualTo("BREACHED");
    assertThat(timer(org, id).breachedByMinutes()).isEqualTo(75);
  }

  @Test
  void answeredIsMetAndPendingWithoutAReplyIsPaused() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long answered = ticketIn(org, customerOf(org), null);
    long waiting = ticketIn(org, customerOf(org), null);
    clockAt("2026-11-23T10:00");
    tickets.addPublicReply(admin, answered, "<p>On it</p>", List.of(), null);
    tickets.changeStatus(admin, waiting, TicketStatus.PENDING_ON_CLIENT);
    recalc(answered);
    recalc(waiting);
    clockAt("2026-11-23T15:00");

    FirstResponseTimer met = timer(org, answered);
    assertThat(met.state()).isEqualTo("MET");
    assertThat(met.remainingMinutes()).isNull();

    FirstResponseTimer paused = timer(org, waiting);
    assertThat(paused.state()).isEqualTo("PAUSED");
    assertThat(paused.remainingMinutes()).isEqualTo(180);
    assertThat(paused.dueAt()).isNull();
  }

  @Test
  void firstResponseSortPutsOverdueFirstThenSoonestDue() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long slow = ticketIn(org, customerOf(org), Priority.P4); // due Tue 09:00
    long soon = ticketIn(org, customerOf(org), null); // P3, due 13:00
    long late = ticketIn(org, customerOf(org), Priority.P2); // due 10:00
    long done = ticketIn(org, customerOf(org), Priority.P2);
    clockAt("2026-11-23T09:30");
    tickets.addPublicReply(admin, done, "<p>Done</p>", List.of(), null);
    clockAt("2026-11-23T10:30");
    for (long id : List.of(slow, soon, late, done)) {
      recalc(id);
    }
    assertThat(queue(org, QueueSort.FIRST_RESPONSE).stream().map(TicketSummary::id))
        .containsExactly(late, soon, slow, done);
  }

  @Test
  void theListCarriesTheTimerOnlyForTheAgentsOwnOrganisations() throws Exception {
    long org = londonOrganisation("Acme Dental");
    long other = londonOrganisation("Beta Clinic");
    AppUser ours = restrictedAgent(org);
    AppUser theirs = restrictedAgent(other);
    AppUser customer = customerOf(org);
    clock.reset(); // sign-in uses the real clock
    long id = ticketIn(org, customer, null);
    recalc(id);

    Browser mine = signedIn(ours);
    MockHttpServletResponse r =
        mine.get("/api/v1/agent/tickets?organisationId=" + org + "&sort=first_response");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode row = json(r.getContentAsString()).get(0);
    assertThat(row.get("id").asLong()).isEqualTo(id);
    JsonNode t = row.get("firstResponseTimer");
    assertThat(t.get("state").asString()).isIn("RUNNING", "STOPPED_OUTSIDE_HOURS");
    assertThat(t.get("remainingMinutes").asLong()).isPositive();
    assertThat(t.get("businessDayMinutes").asLong()).isEqualTo(540);

    Browser foreign = signedIn(theirs);
    MockHttpServletResponse f = foreign.get("/api/v1/agent/tickets?organisationId=" + org);
    assertThat(f.getContentAsString()).doesNotContain("firstResponseTimer");
    assertThat(foreign.get("/api/v1/agent/tickets?view=all").getContentAsString())
        .doesNotContain("\"id\":" + id + ",");

    Browser client = signedIn(customer);
    MockHttpServletResponse c = client.get("/api/v1/agent/tickets");
    assertThat(c.getStatus()).isEqualTo(403);
    assertThat(c.getContentAsString().toLowerCase(Locale.ROOT)).doesNotContain("timer");
  }

  @Test
  void aBusinessHoursTriageRowShowsTheTimeLeftOnTheTriageDefaultCalendar() {
    clock.set(msp("2026-11-23T09:00")); // Monday in the MSP timezone
    long id = ticket(null, null, Priority.P3, "Triage running " + UUID.randomUUID()); // P3: 4 h
    recalc(id);
    clock.set(msp("2026-11-23T10:00"));
    FirstResponseTimer t = triageTimer(id);
    assertThat(t.state()).isEqualTo("RUNNING");
    assertThat(t.remainingMinutes()).isEqualTo(180);
    assertThat(t.businessDayMinutes()).isEqualTo(540);
    assertThat(t.dueAt()).isEqualTo(msp("2026-11-23T13:00"));
    // Recomputed on every read, like an organisation's row.
    clock.set(msp("2026-11-23T10:05"));
    assertThat(triageTimer(id).remainingMinutes()).isEqualTo(175);
  }

  @Test
  void aTriageRowIsFrozenOutsideTheDefaultHours() {
    clock.set(msp("2026-11-23T09:00"));
    long id = ticket(null, null, Priority.P4, "Triage frozen " + UUID.randomUUID()); // 1 day
    recalc(id);
    clock.set(msp("2026-11-23T18:00")); // after 17:30 MSP
    FirstResponseTimer t = triageTimer(id);
    assertThat(t.state()).isEqualTo("STOPPED_OUTSIDE_HOURS");
    assertThat(t.remainingMinutes()).isEqualTo(30);
    clock.set(msp("2026-11-24T08:00")); // before 08:30 MSP next day
    FirstResponseTimer next = triageTimer(id);
    assertThat(next.state()).isEqualTo("STOPPED_OUTSIDE_HOURS");
    assertThat(next.remainingMinutes()).isEqualTo(30);
    clock.set(msp("2026-11-24T08:45"));
    FirstResponseTimer open = triageTimer(id);
    assertThat(open.state()).isEqualTo("RUNNING");
    assertThat(open.remainingMinutes()).isEqualTo(15);
  }

  @Test
  void firstResponseSortMixesTriageAndOrganisationRowsByDueTime() {
    long org = londonOrganisation("Acme Dental");
    String tag = "Sort mix " + UUID.randomUUID();
    // Monday 08:00 UTC = 15:00 MSP (triage open), 08:00 London (closed until 08:30).
    clock.set(Instant.parse("2026-11-23T08:00:00Z"));
    long triageLate = ticket(null, null, Priority.P2, tag); // due 16:00 MSP = 09:00 UTC
    clock.set(Instant.parse("2026-11-23T09:00:00Z")); // 16:00 MSP, 09:00 London
    long orgLate = ticket(org, customerOf(org), Priority.P2, tag); // due 10:00 UTC
    long orgSoon = ticket(org, customerOf(org), Priority.P3, tag); // due 13:00 UTC
    long triageSlow = ticket(null, null, Priority.P3, tag); // P3: 90 min today, due Tue 11:00 MSP
    long triageMet = ticket(null, null, Priority.P2, tag);
    clock.set(Instant.parse("2026-11-23T09:10:00Z"));
    tickets.addPublicReply(admin, triageMet, "<p>On it</p>", List.of(), null);
    clock.set(Instant.parse("2026-11-23T10:40:00Z")); // 17:40 MSP, 10:40 London
    for (long id : List.of(triageLate, orgLate, orgSoon, triageSlow, triageMet)) {
      recalc(id);
    }

    List<TicketSummary> rows =
        queries
            .list(
                admin,
                new QueueQuery(
                    null,
                    null,
                    false,
                    null,
                    false,
                    true,
                    false,
                    null,
                    50,
                    tag,
                    null,
                    false,
                    QueueSort.FIRST_RESPONSE,
                    0))
            .items();
    assertThat(rows.stream().map(TicketSummary::id))
        .containsExactly(triageLate, orgLate, orgSoon, triageSlow, triageMet);
    FirstResponseTimer late = rows.get(0).firstResponseTimer();
    assertThat(late.state()).isEqualTo("BREACHED");
    assertThat(late.breachedByMinutes()).isEqualTo(90); // 16:00-17:30 MSP, then closed
    assertThat(rows.get(1).firstResponseTimer().breachedByMinutes()).isEqualTo(40);
    FirstResponseTimer slow = rows.get(3).firstResponseTimer();
    assertThat(slow.dueAt()).isEqualTo(msp("2026-11-24T11:00"));
    assertThat(slow.remainingMinutes()).isEqualTo(150);
    assertThat(slow.state()).isEqualTo("STOPPED_OUTSIDE_HOURS"); // after 17:30 MSP
    assertThat(rows.get(4).firstResponseTimer().state()).isEqualTo("MET");
  }
}
