package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.notification.NotificationKind;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.ticket.TicketService.ClientReply;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.QueueQuery;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * WP-09 wiring of the SLA engine to tickets: stored due times and results recalculated on ticket
 * events, matching the WP-09a hand-calculated fixture table (case numbers Fnn) when replayed
 * through the real services with a controllable clock; 80% and breach notifications exactly once;
 * P1 alerts; fallbacks; SLA never in a customer response.
 */
class SlaWiringIntegrationTest extends SlaTestBase {

  private AppUser customerOf(long org) {
    return activeUser(Role.CUSTOMER, org);
  }

  private void clientReplies(long ticketId) {
    tickets.addClientReply(
        admin,
        ticketId,
        new ClientReply(null, "reporter@example.test", "<p>Here you go</p>", true, null, null));
  }

  @Test
  void spec12P1AndP3CreatedSaturdayNight() {
    long org = londonOrganisation("Acme Dental");
    AppUser customer = customerOf(org);
    clockAt("2026-11-21T23:00");
    long p1 = ticketIn(org, customer, Priority.P1);
    long p3 = ticketIn(org, customer, null);

    // F01: due for first response 23:30 the same night, resolution 4 clock hours.
    Map<String, Object> a = sla(p1);
    assertThat(a.get("sla_clock")).isEqualTo("TWENTY_FOUR_SEVEN");
    assertThat(local(a.get("first_response_due_at"))).isEqualTo("2026-11-21T23:30");
    assertThat(local(a.get("resolution_due_at"))).isEqualTo("2026-11-22T03:00");
    // F02: 4 business hours from Monday 08:30; 3 business days = Wednesday 17:30.
    Map<String, Object> b = sla(p3);
    assertThat(b.get("sla_clock")).isEqualTo("BUSINESS_HOURS");
    assertThat(local(b.get("first_response_due_at"))).isEqualTo("2026-11-23T12:30");
    assertThat(local(b.get("resolution_due_at"))).isEqualTo("2026-11-25T17:30");
    assertThat(b.get("first_response_state")).isEqualTo("RUNNING");
  }

  @Test
  void pendingOnClientPausesAndAClientReplyReopensF06() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null);
    clockAt("2026-11-23T10:00");
    tickets.addPublicReply(
        admin, id, "<p>Which printer?</p>", List.of(), TicketStatus.PENDING_ON_CLIENT);
    // Paused: the resolution due time is empty (ADR-017 change), first response met.
    Map<String, Object> paused = sla(id);
    assertThat(paused.get("resolution_state")).isEqualTo("PAUSED");
    assertThat(paused.get("resolution_due_at")).isNull();
    assertThat(paused.get("first_response_state")).isEqualTo("MET");

    clockAt("2026-11-24T10:00");
    clientReplies(id);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.OPEN);
    clockAt("2026-11-24T11:00");
    recalc(id);

    Map<String, Object> s = sla(id);
    assertThat(local(s.get("first_response_due_at"))).isEqualTo("2026-11-23T13:00");
    assertThat(s.get("first_response_state")).isEqualTo("MET");
    assertThat(local(s.get("first_responded_at"))).isEqualTo("2026-11-23T10:00");
    assertThat(local(s.get("resolution_due_at"))).isEqualTo("2026-11-27T09:00");
    assertThat(s.get("resolution_state")).isEqualTo("RUNNING");
    assertThat(s.get("resolution_used_minutes")).isEqualTo(120L);
    assertThat(s.get("sla_paused_minutes")).isEqualTo(540L);
  }

  @Test
  void pendingOnVendorRunsByDefaultF07AndPausesWithThePolicyFlagF08() {
    long runs = londonOrganisation("Acme Dental");
    long pauses = londonOrganisation("Beta Clinic");
    slaConfig.savePolicy(admin, pauses, null, true, SlaConfigService.defaultTargets(), null);
    long[] ids = new long[2];
    long[] orgs = {runs, pauses};
    for (int i = 0; i < 2; i++) {
      clockAt("2026-11-23T09:00");
      ids[i] = ticketIn(orgs[i], customerOf(orgs[i]), null);
      clockAt("2026-11-23T10:00");
      tickets.addPublicReply(
          admin, ids[i], "<p>Asked the supplier</p>", List.of(), TicketStatus.PENDING_ON_VENDOR);
      clockAt("2026-11-24T10:00");
      tickets.changeStatus(admin, ids[i], TicketStatus.OPEN);
      clockAt("2026-11-24T11:00");
      recalc(ids[i]);
    }
    Map<String, Object> f07 = sla(ids[0]);
    assertThat(local(f07.get("resolution_due_at"))).isEqualTo("2026-11-26T09:00");
    assertThat(f07.get("resolution_used_minutes")).isEqualTo(660L);
    assertThat(f07.get("sla_paused_minutes")).isEqualTo(0L);
    Map<String, Object> f08 = sla(ids[1]);
    assertThat(local(f08.get("resolution_due_at"))).isEqualTo("2026-11-27T09:00");
    assertThat(f08.get("resolution_used_minutes")).isEqualTo(120L);
    assertThat(f08.get("sla_paused_minutes")).isEqualTo(540L);
  }

  @Test
  void reopenContinuesTheClockAndTheFinalResolvedCountsF10() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), Priority.P2);
    tickets.assign(admin, id, adminUser.id()); // picks it up: Open at 09:00
    clockAt("2026-11-23T09:30");
    tickets.addPublicReply(admin, id, "<p>On it</p>", List.of(), null);
    clockAt("2026-11-23T12:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    assertThat(local(sla(id).get("resolved_at"))).isEqualTo("2026-11-23T12:00");
    clockAt("2026-11-24T09:00");
    clientReplies(id); // Resolved -> Open: resolved_at cleared, clock carries on
    assertThat(sla(id).get("resolved_at")).isNull();
    clockAt("2026-11-24T13:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);

    Map<String, Object> s = sla(id);
    assertThat(s.get("resolution_state")).isEqualTo("MET");
    assertThat(s.get("resolution_used_minutes")).isEqualTo(420L);
    assertThat(local(s.get("resolved_at"))).isEqualTo("2026-11-24T13:00");
    assertThat(s.get("first_response_state")).isEqualTo("MET");
  }

  @Test
  void anAdminReopenOfAClosedTicketCarriesTheClockOnLikeAClientReopenAdr041() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), Priority.P2);
    tickets.assign(admin, id, adminUser.id()); // picks it up: Open at 09:00
    clockAt("2026-11-23T09:30");
    tickets.addPublicReply(admin, id, "<p>On it</p>", List.of(), null);
    clockAt("2026-11-23T12:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    clockAt("2026-11-23T12:10");
    tickets.changeStatus(admin, id, TicketStatus.CLOSED, "RESOLVED_NO_FOLLOW_UP", null);
    assertThat(local(sla(id).get("resolved_at"))).isEqualTo("2026-11-23T12:00");
    clockAt("2026-11-24T09:00");
    tickets.adminReopen(admin, id, "Client came back", null); // Closed -> Open, no reset
    assertThat(sla(id).get("resolved_at")).isNull();
    clockAt("2026-11-24T13:00");
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);

    // Same as F10: 180 minutes before the close, 240 after the reopen; the closed night is not
    // counted and nothing restarted.
    Map<String, Object> s = sla(id);
    assertThat(s.get("resolution_state")).isEqualTo("MET");
    assertThat(s.get("resolution_used_minutes")).isEqualTo(420L);
    assertThat(local(s.get("resolved_at"))).isEqualTo("2026-11-24T13:00");
  }

  @Test
  void firstResponseIsMetOnlyByASupporterPublicReply() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null);
    clockAt("2026-11-23T09:10");
    notes.addNote(admin, id, "<p>Looks like the PSU</p>", List.of());
    clientReplies(id);
    tickets.changeStatus(admin, id, TicketStatus.OPEN);
    recalc(id);
    assertThat(sla(id).get("first_responded_at")).isNull();
    assertThat(sla(id).get("first_response_state")).isEqualTo("RUNNING");
    clockAt("2026-11-23T09:15");
    tickets.addPublicReply(admin, id, "<p>We are looking at it</p>", List.of(), null);
    assertThat(local(sla(id).get("first_responded_at"))).isEqualTo("2026-11-23T09:15");
    assertThat(sla(id).get("first_response_state")).isEqualTo("MET");
  }

  @Test
  void resolvedWithoutAReplyIsNotApplicableF27AndALateReplyIsBreachedF28() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long f27 = ticketIn(org, customerOf(org), Priority.P2);
    long f28 = ticketIn(org, customerOf(org), Priority.P2);
    clockAt("2026-11-23T09:20");
    tickets.changeStatus(admin, f27, TicketStatus.RESOLVED);
    clockAt("2026-11-23T10:30");
    tickets.addPublicReply(admin, f28, "<p>Sorry for the wait</p>", List.of(), null);
    assertThat(sla(f27).get("first_response_state")).isEqualTo("STOPPED");
    assertThat(sla(f27).get("resolution_state")).isEqualTo("MET");
    assertThat(sla(f27).get("resolution_used_minutes")).isEqualTo(20L);
    assertThat(sla(f28).get("first_response_state")).isEqualTo("BREACHED");
    assertThat(sla(f28).get("first_response_used_minutes")).isEqualTo(90L);
  }

  @Test
  void priorityChangeRecalculatesFromCreationAndKeepsTheOldDueTimesInHistory() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null);
    assertThat(local(sla(id).get("first_response_due_at"))).isEqualTo("2026-11-23T13:00");
    clockAt("2026-11-23T09:30");
    tickets.changePriority(admin, id, Priority.P2);
    assertThat(local(sla(id).get("first_response_due_at"))).isEqualTo("2026-11-23T10:00");
    assertThat(local(sla(id).get("resolution_due_at"))).isEqualTo("2026-11-23T17:00");
    Map<String, Object> h =
        jdbc.queryForMap(
            "SELECT old_value, new_value FROM ticket_event WHERE ticket_id = ? AND type ="
                + " 'SLA_DUE_CHANGED'",
            id);
    assertThat((String) h.get("old_value")).contains(at("2026-11-23T13:00").toString());
    assertThat((String) h.get("new_value")).contains(at("2026-11-23T10:00").toString());
  }

  @Test
  void eightyPercentAndBreachNotifyTheAssigneeExactlyOnceEach() {
    long org = londonOrganisation("Acme Dental");
    AppUser agent = restrictedAgent(org);
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null); // P3: first response 240 business minutes
    tickets.assign(admin, id, agent.id());

    clockAt("2026-11-23T12:11"); // 191 minutes: 79.6%
    minuteJob.runOnce();
    assertThat(notifications(id, NotificationKind.SLA_AT_RISK)).isZero();

    clockAt("2026-11-23T12:12"); // 192 minutes: 80%
    minuteJob.runOnce();
    minuteJob.runOnce();
    clockAt("2026-11-23T12:30");
    minuteJob.runOnce();
    assertThat(notifications(id, NotificationKind.SLA_AT_RISK, agent.id())).isEqualTo(1);
    assertThat(notifications(id, NotificationKind.SLA_AT_RISK)).isEqualTo(1);
    assertThat(sla(id).get("sla_warning")).isEqualTo("AT_RISK");

    clockAt("2026-11-23T13:00"); // exactly the target: still met (WP-09a interpretation 4)
    minuteJob.runOnce();
    assertThat(notifications(id, NotificationKind.SLA_BREACHED)).isZero();
    clockAt("2026-11-23T13:01");
    minuteJob.runOnce();
    minuteJob.runOnce();
    clockAt("2026-11-23T14:00");
    minuteJob.runOnce();
    assertThat(notifications(id, NotificationKind.SLA_BREACHED, agent.id())).isEqualTo(1);
    assertThat(notifications(id, NotificationKind.SLA_BREACHED)).isEqualTo(1);
    assertThat(sla(id).get("sla_warning")).isEqualTo("BREACHED");
    // The email went out once too, as an internal mail with the internal Reply-To.
    List<Map<String, Object>> mails = outbox(id, "internal");
    assertThat(mails)
        .filteredOn(m -> m.get("subject").toString().contains("SLA breached"))
        .hasSize(1);
    // The breaching queue filter lists it.
    assertThat(
            queries
                .list(admin, new QueueQuery(null, org, false, null, false, false, true, null, 50))
                .items())
        .extracting(t -> t.id())
        .containsExactly(id);
  }

  @Test
  void anUnassignedTicketWarnsEverySupporterOfTheOrganisationOnly() {
    long org = londonOrganisation("Acme Dental");
    AppUser ours = restrictedAgent(org);
    AppUser theirs = restrictedAgent(beta);
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null);
    clockAt("2026-11-23T12:12");
    minuteJob.runOnce();
    assertThat(notifications(id, NotificationKind.SLA_AT_RISK, ours.id())).isEqualTo(1);
    assertThat(notifications(id, NotificationKind.SLA_AT_RISK, adminUser.id())).isEqualTo(1);
    assertThat(notifications(id, NotificationKind.SLA_AT_RISK, theirs.id())).isZero();
  }

  @Test
  void p1AlertsEverySupporterOfTheOrganisationAtCreationAndAt80PercentOnce() {
    long org = londonOrganisation("Acme Dental");
    AppUser ours = restrictedAgent(org);
    AppUser away = restrictedAgent(org);
    AppUser theirs = restrictedAgent(beta);
    choose(ours.id(), "SLA_WARNING", false, false);
    choose(ours.id(), "SLA_BREACHED", false, false);
    clockAt("2026-11-21T10:00"); // Saturday: P1 is 24/7
    long id = ticketIn(org, customerOf(org), Priority.P1);
    for (AppUser u : List.of(ours, away, adminUser)) {
      assertThat(notifications(id, NotificationKind.P1_CREATED, u.id())).isEqualTo(1);
    }
    assertThat(notifications(id, NotificationKind.P1_CREATED, theirs.id())).isZero();
    // P1 ignores preferences: email even when the supporter turned emails off.
    assertThat(outbox(id, "internal"))
        .filteredOn(m -> m.get("to_addresses").equals(ours.email()))
        .hasSize(1);

    clockAt("2026-11-21T10:24"); // 24 of 30 minutes: 80% of first response
    minuteJob.runOnce();
    minuteJob.runOnce();
    for (AppUser u : List.of(ours, away, adminUser)) {
      assertThat(notifications(id, NotificationKind.P1_AT_RISK, u.id())).isEqualTo(1);
      assertThat(notifications(id, NotificationKind.P1_CREATED, u.id())).isEqualTo(1);
    }
    assertThat(notifications(id, NotificationKind.P1_AT_RISK, theirs.id())).isZero();
  }

  @Test
  void twoRunnersAtTheSameTimeNotifyABreachOnce() throws Exception {
    long org = londonOrganisation("Acme Dental");
    AppUser agent = restrictedAgent(org);
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), Priority.P2);
    tickets.assign(admin, id, agent.id());
    clockAt("2026-11-23T11:00"); // 120 of 60 minutes: breached

    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<?>> runs =
          List.of(
              pool.submit(() -> await(start, () -> minuteJob.runOnce())),
              pool.submit(() -> await(start, () -> minuteJob.runOnce())),
              pool.submit(() -> await(start, () -> recalc(id))),
              pool.submit(() -> await(start, () -> recalc(id))));
      start.countDown();
      for (Future<?> f : runs) {
        f.get(120, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(notifications(id, NotificationKind.SLA_BREACHED, agent.id())).isEqualTo(1);
  }

  private static void await(CountDownLatch latch, Runnable r) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    r.run();
  }

  @Test
  void anOrganisationWithoutCalendarOrPolicyUsesTheDefaults() {
    // acme (TicketTestBase) is made straight in the database: no hours, no policy rows.
    ZoneId hcm = ZoneId.of("Asia/Ho_Chi_Minh");
    clock.set(ZonedDateTime.of(LocalDateTime.parse("2026-11-23T09:00"), hcm).toInstant());
    long id = ticketIn(acme, acmeCustomerUser, null);
    Map<String, Object> s = sla(id);
    assertThat(s.get("sla_policy_fallback")).isEqualTo(true);
    assertThat(((java.sql.Timestamp) s.get("first_response_due_at")).toInstant())
        .isEqualTo(ZonedDateTime.of(LocalDateTime.parse("2026-11-23T13:00"), hcm).toInstant());
  }

  @Test
  void anUnreadablePolicyFallsBackToTheDefaults() {
    long org = londonOrganisation("Acme Dental");
    // Break the organisation's policy: P3 targets missing.
    jdbc.update(
        "DELETE FROM sla_policy_target WHERE priority = 'P3' AND policy_id IN (SELECT id FROM"
            + " sla_policy WHERE organisation_id = ?)",
        org);
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null);
    assertThat(sla(id).get("sla_policy_fallback")).isEqualTo(true);
    assertThat(local(sla(id).get("first_response_due_at"))).isEqualTo("2026-11-23T13:00");
  }

  @Test
  void aJobCrashMidRunLeavesNoHalfUpdatedTicketAndTheNextRunRecovers() {
    long org = londonOrganisation("Acme Dental");
    clockAt("2026-11-23T09:00");
    long id = ticketIn(org, customerOf(org), null);
    // Simulate a missed reaction: the stored values are stale and the ticket is on the work list.
    jdbc.update(
        "UPDATE ticket SET sla_calculated_at = NULL, first_response_due_at = NULL WHERE id = ?",
        id);
    clockAt("2026-11-23T09:05");
    minuteJob.runOnce();
    assertThat(local(sla(id).get("first_response_due_at"))).isEqualTo("2026-11-23T13:00");
  }

  @Test
  void slaFieldsAreOnTheAgentModelsAndNeverInACustomerResponse() throws Exception {
    long org = londonOrganisation("Acme Dental");
    AppUser customer = customerOf(org);
    clock.reset();
    Browser b = signedIn(customer);
    MockHttpServletResponse r =
        b.post(
            "/api/v1/portal/tickets",
            "{\"requestTypeId\":"
                + generalType
                + ",\"subject\":\"Cannot print\",\"descriptionHtml\":\"<p>Error</p>\","
                + "\"impact\":\"JUST_ME\",\"urgency\":\"PARTLY\",\"attachmentIds\":[]}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    String body = r.getContentAsString().toLowerCase(Locale.ROOT);
    for (String word : List.of("sla", "due", "breach", "target", "paused", "warning")) {
      assertThat(body).doesNotContain(word);
    }
    long id =
        references
            .resolve(admin, json(r.getContentAsString()).get("reference").asString())
            .orElseThrow()
            .ticketId();
    // Every portal route is raise-only; the request types list carries no SLA either.
    String types =
        b.get("/api/v1/portal/request-types").getContentAsString().toLowerCase(Locale.ROOT);
    assertThat(types).doesNotContain("sla").doesNotContain("due");

    TicketDetail d = queries.detail(admin, id);
    assertThat(d.sla()).isNotNull();
    assertThat(d.sla().firstResponse().dueAt()).isNotNull();
    assertThat(d.sla().clock()).isEqualTo("BUSINESS_HOURS");
  }
}
