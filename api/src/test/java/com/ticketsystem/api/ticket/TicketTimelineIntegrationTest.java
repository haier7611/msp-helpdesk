package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketService.ClientReply;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketService.ReplyResult;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TimelineEntryView;
import com.ticketsystem.api.ticket.agent.TriageService;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * WP-18 (ADR-032 change "every change in the timeline"): every ticket change is one agent-only
 * timeline line with the right actor and wording, in time order among the comments, never twice;
 * automatic and client-reply changes say so; customers never see the lines, they never reach an
 * email or an export; a restricted agent never reads another organisation's name, contact or
 * reference through them.
 */
class TicketTimelineIntegrationTest extends TicketTestBase {

  private static final Instant START = Instant.parse("2026-11-19T07:00:00Z");
  private static final String IP = "203.0.113.7";

  @Autowired TicketHistoryRepository historyRepository;
  @Autowired TicketParticipantRepository participantRepository;
  @Autowired TriageService triage;

  private String acmeName;
  private String betaName;
  private AccessScope poller;
  private AccessScope job;

  @BeforeEach
  void names() {
    clock.set(START);
    rename(adminUser, "Pat Admin");
    rename(acmeAgentUser, "Kim Agent");
    acmeName = orgName(acme);
    betaName = orgName(beta);
    poller = AccessScope.system("test: email poller");
    job = AccessScope.system("test: pending timers job");
  }

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  private void rename(AppUser u, String name) {
    jdbc.update("UPDATE app_user SET name = ? WHERE id = ?", name, u.id());
  }

  private String orgName(long org) {
    return jdbc.queryForObject("SELECT name FROM organisation WHERE id = ?", String.class, org);
  }

  private List<TimelineEntryView> timeline(AccessScope scope, long ticketId) {
    return queries.detail(scope, ticketId).timelineEntries();
  }

  private List<String> texts(AccessScope scope, long ticketId) {
    return timeline(scope, ticketId).stream().map(TimelineEntryView::text).toList();
  }

  private List<TimelineEntryView> ofType(AccessScope scope, long ticketId, String type) {
    return timeline(scope, ticketId).stream().filter(e -> e.type().equals(type)).toList();
  }

  private TimelineEntryView only(AccessScope scope, long ticketId, String type) {
    List<TimelineEntryView> e = ofType(scope, ticketId, type);
    assertThat(e).as(type).hasSize(1);
    return e.get(0);
  }

  private Map<String, Object> lastRow(long ticketId, String type) {
    return jdbc.queryForMap(
        "SELECT old_value, new_value, reason, actor_kind, actor_user_id FROM ticket_event WHERE"
            + " ticket_id = ? AND type = ? ORDER BY id DESC LIMIT 1",
        ticketId,
        type);
  }

  private String reference(long ticketId) {
    return queries.detail(admin, ticketId).reference();
  }

  private void tick() {
    clock.advance(Duration.ofMinutes(1));
  }

  private ReplyResult clientReply(long ticketId) {
    return tickets.addClientReply(
        poller,
        ticketId,
        new ClientReply(
            acmeCustomerUser.id(),
            acmeCustomerUser.email(),
            "<p>Still broken</p>",
            true,
            "<reply-" + ticketId + "-" + clock.instant().toEpochMilli() + "@client.example>",
            "dmarc=pass"));
  }

  // ---------------------------------------------------------------------------------------------
  // One line per change type, right actor and from/to

  @Test
  void creationStatusPriorityAndRequestTypeEachAddOneLineWithTheActor() {
    long id = acmeTicket().ticketId();
    TimelineEntryView created = only(admin, id, "CREATED");
    assertThat(created.text()).isEqualTo("Ticket created by Pat Admin");
    assertThat(created.actorKind()).isEqualTo("AGENT");
    assertThat(created.actorUserId()).isEqualTo(adminUser.id());

    tick();
    tickets.changeStatus(acmeAgent, id, TicketStatus.PENDING_ON_VENDOR);
    TimelineEntryView status = only(admin, id, "STATUS");
    assertThat(status.text())
        .isEqualTo("Status changed from New to Pending on vendor by Kim Agent");
    assertThat(status.actorKind()).isEqualTo("AGENT");
    assertThat(status.actorUserId()).isEqualTo(acmeAgentUser.id());
    assertThat(status.createdAt()).isEqualTo(clock.instant());
    assertThat(lastRow(id, "STATUS"))
        .containsEntry("old_value", "NEW")
        .containsEntry("new_value", "PENDING_ON_VENDOR")
        .containsEntry("reason", "AGENT_SET")
        .containsEntry("actor_kind", "AGENT");

    tick();
    tickets.changePriority(acmeAgent, id, Priority.P2);
    TimelineEntryView priority = only(admin, id, "PRIORITY");
    assertThat(priority.text())
        .isEqualTo("Priority changed from P3 Medium to P2 High by Kim Agent");
    assertThat(lastRow(id, "PRIORITY"))
        .containsEntry("old_value", "P3")
        .containsEntry("new_value", "P2");
    // The SLA engine recalculates after commit and keeps the old due times (spec 4.7).
    assertThat(texts(admin, id))
        .filteredOn(t -> t.startsWith("SLA due times"))
        .allMatch(t -> t.equals("SLA due times recalculated for the new priority"));

    tick();
    long otherType =
        jdbc.queryForObject(
            "SELECT id FROM request_type WHERE name = 'Other or general question'", Long.class);
    String generalName =
        jdbc.queryForObject(
            "SELECT name FROM request_type WHERE id = ?", String.class, generalType);
    tickets.changeRequestType(acmeAgent, id, otherType);
    assertThat(only(admin, id, "REQUEST_TYPE").text())
        .isEqualTo(
            "Request type changed from "
                + generalName
                + " to Other or general question by Kim Agent");
    assertThat(lastRow(id, "REQUEST_TYPE"))
        .containsEntry("old_value", Long.toString(generalType))
        .containsEntry("new_value", Long.toString(otherType));
  }

  @Test
  void pickingUpAddsTheAssignmentAndOneStatusLineNeverTwice() {
    long id = acmeTicket().ticketId();
    tick();
    tickets.assign(acmeAgent, id, acmeAgentUser.id());
    assertThat(only(admin, id, "ASSIGNEE").text()).isEqualTo("Assigned to Kim Agent by Kim Agent");
    assertThat(only(admin, id, "STATUS").text())
        .isEqualTo("Status changed from New to Open by Kim Agent (picked up)");
    // WP-16 lines unchanged and the same in both lists.
    assertThat(queries.detail(admin, id).assignmentEntries())
        .extracting(a -> a.text())
        .containsExactly("Assigned to Kim Agent by Kim Agent");

    tick();
    tickets.addPublicReply(acmeAgent, id, "<p>Please try again.</p>", List.of(), null);
    tick();
    tickets.changeStatus(acmeAgent, id, TicketStatus.OPEN); // already Open: nothing
    assertThat(ofType(admin, id, "STATUS")).hasSize(1);

    // Every stored row is exactly one line (ids unique, one per row).
    List<TimelineEntryView> all = timeline(admin, id);
    assertThat(all).extracting(TimelineEntryView::id).doesNotHaveDuplicates();
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM ticket_event WHERE ticket_id = ?", Integer.class, id);
    assertThat(all).hasSize(rows);
  }

  @Test
  void anUnknownRequestTypeAndAnArchivedOneReadClearly() {
    long id = acmeTicket().ticketId();
    long otherType =
        jdbc.queryForObject(
            "SELECT id FROM request_type WHERE name = 'Other or general question'", Long.class);
    tickets.changeRequestType(admin, id, otherType);
    historyRepository.record(
        admin,
        acme,
        id,
        TicketHistoryRepository.HistoryType.REQUEST_TYPE,
        adminUser.id(),
        Long.toString(otherType),
        "999999999",
        clock.instant());
    jdbc.update("UPDATE request_type SET archived = TRUE WHERE id = ?", otherType);
    try {
      List<String> lines =
          ofType(admin, id, "REQUEST_TYPE").stream().map(TimelineEntryView::text).toList();
      assertThat(lines.get(0)).endsWith(" to Other or general question (archived) by Pat Admin");
      assertThat(lines.get(1))
          .isEqualTo(
              "Request type changed from Other or general question (archived) to a removed"
                  + " request type by Pat Admin");
    } finally {
      jdbc.update("UPDATE request_type SET archived = FALSE WHERE id = ?", otherType);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Client replies and automatic jobs

  @Test
  void clientRepliesSayTheClientMovedTheTicket() {
    long pending = acmeTicket().ticketId();
    forceStatus(pending, TicketStatus.PENDING_ON_CLIENT);
    tick();
    clientReply(pending);
    TimelineEntryView moved = only(admin, pending, "STATUS");
    assertThat(moved.text()).isEqualTo("Moved to Open by client reply");
    assertThat(moved.actorKind()).isEqualTo("CLIENT");
    assertThat(moved.actorUserId()).isNull();
    assertThat(lastRow(pending, "STATUS"))
        .containsEntry("old_value", "PENDING_ON_CLIENT")
        .containsEntry("new_value", "OPEN")
        .containsEntry("reason", "CLIENT_REPLY");

    long resolved = acmeTicket().ticketId();
    forceStatus(resolved, TicketStatus.RESOLVED);
    clientReply(resolved);
    assertThat(only(admin, resolved, "STATUS").text()).isEqualTo("Reopened by client reply");
  }

  @Test
  void aClientReplyToAClosedTicketShowsTheFollowUpOnBothTickets() {
    long closed = acmeTicket().ticketId();
    String oldRef = reference(closed);
    forceStatus(closed, TicketStatus.CLOSED);
    tick();
    ReplyResult r = clientReply(closed);
    assertThat(r.followUpCreated()).isTrue();
    String newRef = reference(r.ticketId());

    TimelineEntryView onOld = only(admin, closed, "FOLLOW_UP_ADDED");
    assertThat(onOld.text())
        .isEqualTo("Follow-up " + newRef + " opened by a client reply to this closed ticket");
    assertThat(onOld.actorKind()).isEqualTo("CLIENT");
    TimelineEntryView onNew = only(admin, r.ticketId(), "FOLLOW_UP_OF");
    assertThat(onNew.text())
        .isEqualTo("Follow-up of " + oldRef + ", opened by a client reply to the closed ticket");
    // No separate "Ticket created" line on the follow-up (the follow-up line says it).
    assertThat(ofType(admin, r.ticketId(), "CREATED")).isEmpty();

    // An agent's follow-up names the agent.
    long closed2 = acmeTicket().ticketId();
    forceStatus(closed2, TicketStatus.CLOSED);
    long manual = tickets.createFollowUp(acmeAgent, closed2).ticketId();
    assertThat(only(admin, manual, "FOLLOW_UP_OF").text())
        .isEqualTo("Follow-up of " + reference(closed2) + " created by Kim Agent");
    assertThat(only(admin, closed2, "FOLLOW_UP_ADDED").text())
        .isEqualTo("Follow-up " + reference(manual) + " created by Kim Agent");
  }

  @Test
  void automaticJobsSayAutomatically() {
    long id = acmeTicket().ticketId();
    forceStatus(id, TicketStatus.PENDING_ON_CLIENT);
    tick();
    tickets.applySystemTrigger(job, id, Trigger.AUTO_RESOLVE);
    tick();
    tickets.applySystemTrigger(job, id, Trigger.AUTO_CLOSE);
    List<TimelineEntryView> status = ofType(admin, id, "STATUS");
    assertThat(status)
        .extracting(TimelineEntryView::text)
        .containsExactly(
            "Resolved automatically (no client reply)",
            "Closed automatically: No client reply after it was resolved"); // ADR-041
    assertThat(status).extracting(TimelineEntryView::actorKind).containsOnly("SYSTEM");
    assertThat(status).extracting(TimelineEntryView::actorUserId).containsOnlyNulls();
  }

  @Test
  void monitoringAlertsAreTheApiKeysDoing() {
    AccessScope key = AccessScope.apiKey(1, acme);
    long id =
        tickets
            .create(
                key,
                new NewTicket(
                    acme,
                    null,
                    null,
                    alertType,
                    null,
                    null,
                    null,
                    TicketSource.API,
                    "Disk full on FS01",
                    "<p>95%</p>",
                    List.of(),
                    "alert-wp18-" + System.nanoTime(),
                    null,
                    null,
                    null))
            .ticketId();
    tick();
    tickets.addAlertComment(key, id, "<p>resolved</p>", true);
    TimelineEntryView created = only(admin, id, "CREATED");
    assertThat(created.text()).isEqualTo("Ticket created by a monitoring alert (API)");
    assertThat(created.actorKind()).isEqualTo("API_KEY");
    TimelineEntryView resolved = only(admin, id, "STATUS");
    assertThat(resolved.text()).isEqualTo("Resolved by a monitoring alert");
    assertThat(resolved.actorKind()).isEqualTo("API_KEY");
  }

  // ---------------------------------------------------------------------------------------------
  // Organisation, reporter, duplicates, links, participants

  @Test
  void triageSetsTheOrganisationAndTheReporterWithTheReferenceChange() {
    long id = triageTicket().ticketId();
    String local = "wp18.sender" + System.nanoTime();
    jdbc.update("UPDATE ticket SET reporter_email = ? WHERE id = ?", local + "@sender.example", id);
    String triageRef = reference(id);
    tick();
    triage.assign(admin, id, acme, true, false, IP);
    String newRef = reference(id);
    assertThat(only(admin, id, "ORGANISATION").text())
        .isEqualTo(
            "Organisation set to "
                + acmeName
                + " (from email triage) by Pat Admin (reference "
                + triageRef
                + " is now "
                + newRef
                + ")");
    TimelineEntryView reporter = only(admin, id, "REPORTER");
    assertThat(reporter.text())
        .isEqualTo("Sender saved as contact " + local + " and set as reporter by Pat Admin");
    assertThat(lastRow(id, "REPORTER")).containsEntry("reason", "TRIAGE_CONTACT");
  }

  @Test
  void aMoveShowsBothOrganisationsAndReferencesButHidesWhatARestrictedAgentCannotSee()
      throws Exception {
    long id = acmeTicket().ticketId();
    String oldRef = reference(id);
    AppUser colleague = activeUser(Role.CUSTOMER, acme);
    rename(colleague, "Robin Colleague");
    participantRepository.add(poller, acme, id, colleague.id(), colleague.email(), true, START);
    tick();
    tickets.changeOrganisation(admin, id, beta);
    String newRef = reference(id);

    assertThat(only(admin, id, "ORGANISATION").text())
        .isEqualTo(
            "Moved from "
                + acmeName
                + " to "
                + betaName
                + " by Pat Admin (reference "
                + oldRef
                + " is now "
                + newRef
                + ")");
    assertThat(only(admin, id, "PARTICIPANT_REMOVED").text())
        .isEqualTo(
            "Robin Colleague removed as a participant (not a contact of the new organisation) by"
                + " Pat Admin");

    // An agent of Beta only: the old organisation, its reference and its contact stay hidden.
    AppUser betaOnly = restrictedAgent(beta);
    AccessScope betaAgent = restrictedScope(betaOnly, beta);
    List<String> seen = texts(betaAgent, id);
    assertThat(seen)
        .contains(
            "Moved from another organisation to "
                + betaName
                + " by Pat Admin (new reference "
                + newRef
                + ")",
            "A contact of another organisation removed as a participant (not a contact of the new"
                + " organisation) by Pat Admin");
    for (String line : seen) {
      assertThat(line).doesNotContain(acmeName, oldRef, "Robin Colleague");
    }
    // Over HTTP too.
    String body = signedIn(betaOnly).get("/api/v1/agent/tickets/" + id).getContentAsString();
    assertThat(json(body).get("timelineEntries")).isNotEmpty();
    assertThat(body).doesNotContain(acmeName, "Robin Colleague");
  }

  @Test
  void markingADuplicateShowsTheMergeOnBothTicketsWithTheCopiedParticipants() {
    long source = acmeTicket().ticketId();
    long target = acmeTicket().ticketId();
    String sourceRef = reference(source);
    String targetRef = reference(target);
    participantRepository.add(
        poller, acme, source, null, "colleague@acme-dental.example", true, START);
    tick();
    tickets.markDuplicate(admin, source, target);

    assertThat(only(admin, source, "DUPLICATE_OF").text())
        .isEqualTo(
            "Marked as a duplicate of "
                + targetRef
                + " and closed by Pat Admin (close reason: Duplicate)");
    // The closing status change is covered by that line, not shown twice.
    assertThat(ofType(admin, source, "STATUS")).isEmpty();
    assertThat(lastRow(source, "STATUS")).containsEntry("reason", "MARK_DUPLICATE");
    assertThat(only(admin, target, "DUPLICATE_ADDED").text())
        .isEqualTo(sourceRef + " marked as a duplicate of this ticket (merged) by Pat Admin");
    assertThat(only(admin, target, "PARTICIPANT_ADDED").text())
        .isEqualTo(
            "colleague@acme-dental.example added as a participant (from a merged duplicate) by"
                + " Pat Admin");
  }

  @Test
  void relatedLinksAddedAndRemovedShowOnBothTickets() {
    long a = acmeTicket().ticketId();
    long b = acmeTicket().ticketId();
    String aRef = reference(a);
    String bRef = reference(b);
    tick();
    tickets.linkRelated(acmeAgent, a, b);
    assertThat(only(admin, a, "LINKED").text())
        .isEqualTo("Linked to related ticket " + bRef + " by Kim Agent");
    assertThat(only(admin, b, "LINKED").text())
        .isEqualTo("Linked to related ticket " + aRef + " by Kim Agent");

    tick();
    tickets.changeOrganisation(admin, a, beta);
    assertThat(only(admin, a, "LINK_REMOVED").text())
        .isEqualTo(
            "Related link to "
                + bRef
                + " removed (a ticket moved to another organisation) by Pat Admin");
    // Acme's agent cannot see ticket a any more: its reference is never shown to them.
    TimelineEntryView onB = only(acmeAgent, b, "LINK_REMOVED");
    assertThat(onB.text())
        .isEqualTo(
            "Related link to another ticket removed (a ticket moved to another organisation) by"
                + " Pat Admin");
    assertThat(only(acmeAgent, b, "LINKED").text())
        .isEqualTo("Linked to another ticket (related) by Kim Agent");
    assertThat(texts(acmeAgent, b)).noneMatch(t -> t.contains(reference(a)));
  }

  @Test
  void participantsFromAnEmailAreTheClientsDoing() {
    long id = acmeTicket().ticketId();
    tick();
    participantRepository.add(poller, acme, id, null, "Cc.Person@Acme-Dental.example", true, START);
    participantRepository.add(poller, acme, id, null, "outsider@elsewhere.example", false, START);
    // Already there: no second line.
    participantRepository.add(poller, acme, id, null, "cc.person@acme-dental.example", true, START);
    List<TimelineEntryView> added = ofType(admin, id, "PARTICIPANT_ADDED");
    assertThat(added)
        .extracting(TimelineEntryView::text)
        .containsExactly(
            "cc.person@acme-dental.example added as CC (from the client's email)",
            "outsider@elsewhere.example added as CC (from the client's email) (waiting for"
                + " confirmation)");
    assertThat(added).extracting(TimelineEntryView::actorKind).containsOnly("CLIENT");
  }

  @Test
  void erasingAPersonRemovesTheirAddressFromParticipantLines() {
    long id = acmeTicket().ticketId();
    AppUser person = activeUser(Role.CUSTOMER, acme);
    participantRepository.add(poller, acme, id, null, person.email(), true, START);
    assertThat(only(admin, id, "PARTICIPANT_ADDED").text())
        .startsWith(person.email().toLowerCase());
    jdbc.queryForObject("SELECT ts_erase_user(?, now())", Boolean.class, person.id());
    assertThat(only(admin, id, "PARTICIPANT_ADDED").text())
        .isEqualTo("Someone (address removed) added as CC (from the client's email)");
    Integer left =
        jdbc.queryForObject(
            "SELECT count(*) FROM ticket_event WHERE lower(new_value) = lower(?)",
            Integer.class,
            person.email());
    assertThat(left).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // Ordering, invisibility

  @Test
  void linesSortAmongCommentsByTime() {
    long id = acmeTicket().ticketId();
    tick();
    tickets.changePriority(admin, id, Priority.P2);
    tick();
    notes.addNote(admin, id, "<p>Checking.</p>", List.of());
    tick();
    tickets.changeStatus(admin, id, TicketStatus.PENDING_ON_VENDOR);

    var d = queries.detail(admin, id);
    Instant note = d.internalNotes().get(0).createdAt();
    Instant description = d.publicComments().get(0).createdAt();
    TimelineEntryView priority = only(admin, id, "PRIORITY");
    TimelineEntryView status = only(admin, id, "STATUS");
    assertThat(priority.createdAt()).isAfter(description).isBefore(note);
    assertThat(status.createdAt()).isAfter(note);
    // Oldest first.
    assertThat(d.timelineEntries())
        .extracting(TimelineEntryView::createdAt)
        .isSortedAccordingTo(Instant::compareTo);
  }

  @Test
  void customersNeverSeeTheLinesAndTheyNeverAppearInAnEmailOrAnExport() throws Exception {
    long id = acmeTicket().ticketId();
    tickets.changePriority(admin, id, Priority.P2);
    tickets.addPublicReply(admin, id, "<p>We are on it.</p>", List.of(), null);
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    List<String> lines = texts(admin, id);
    assertThat(lines).contains("Priority changed from P3 Medium to P2 High by Pat Admin");

    JsonNode agentView =
        json(signedIn(acmeAgentUser).get("/api/v1/agent/tickets/" + id).getContentAsString());
    assertThat(agentView.get("timelineEntries")).isNotEmpty();

    Browser customer = signedIn(acmeCustomerUser);
    var r = customer.get("/api/v1/agent/tickets/" + id);
    assertThat(r.getStatus()).isIn(403, 404);
    assertThat(r.getContentAsString()).doesNotContain("Priority changed", "Status changed");
    assertThatThrownBy(() -> queries.detail(acmeCustomer, id))
        .isInstanceOf(com.ticketsystem.api.auth.ApiException.class);
    assertThat(historyRepository.listForTicket(acmeCustomer, id)).isEmpty();
    assertThat(historyRepository.listForTicket(AccessScope.apiKey(1, acme), id)).isEmpty();

    List<Map<String, Object>> outbox =
        jdbc.queryForList(
            "SELECT subject, html_body, text_body FROM outbox_message WHERE ticket_id = ?", id);
    assertThat(outbox).isNotEmpty();
    for (Map<String, Object> m : outbox) {
      String all = m.get("subject") + " " + m.get("html_body") + " " + m.get("text_body");
      for (String line : lines) {
        assertThat(all).doesNotContain(line);
      }
      assertThat(all).doesNotContain("Priority changed", "Status changed from");
    }

    String csv =
        signedIn(acmeAgentUser)
            .get("/api/v1/agent/tickets/export?includeClosed=true")
            .getContentAsString();
    assertThat(csv).doesNotContain("Priority changed", "Status changed from", "Ticket created by");
  }

  @Test
  void organisationScopingIsUnchanged() {
    long betaId = betaTicket().ticketId();
    tickets.changePriority(admin, betaId, Priority.P1);
    assertThatThrownBy(() -> queries.detail(acmeAgent, betaId))
        .isInstanceOf(com.ticketsystem.api.auth.ApiException.class);
    assertThat(historyRepository.listForTicket(acmeAgent, betaId)).isEmpty();
  }
}
