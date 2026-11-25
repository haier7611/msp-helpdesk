package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.AssignmentEntryView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
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
 * WP-16 (ADR-032): every assignee change is one agent-only timeline line with the right ids, time
 * and wording; it sorts among the comments by time; customers never see it and it never appears in
 * an outbound email; organisation scoping is unchanged.
 */
class AssignmentTimelineIntegrationTest extends TicketTestBase {

  private static final Instant START = Instant.parse("2026-11-19T07:00:00Z");

  @Autowired TicketHistoryRepository historyRepository;

  private AppUser alex;
  private AppUser jo;

  @BeforeEach
  void names() {
    clock.set(START);
    alex = restrictedAgent(acme);
    jo = restrictedAgent(acme, beta);
    rename(alex, "Alex Example");
    rename(jo, "Jo Example");
    rename(adminUser, "Pat Admin");
    rename(acmeAgentUser, "Kim Agent");
  }

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  private void rename(AppUser u, String name) {
    jdbc.update("UPDATE app_user SET name = ? WHERE id = ?", name, u.id());
  }

  private List<AssignmentEntryView> entries(AccessScope scope, long ticketId) {
    return queries.detail(scope, ticketId).assignmentEntries();
  }

  private int assigneeRows(long ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ticket_event WHERE ticket_id = ? AND type = 'ASSIGNEE'",
        Integer.class,
        ticketId);
  }

  @Test
  void assignReassignAndUnassignEachAddExactlyOneLineWithIdsAndTime() {
    long id = acmeTicket().ticketId();
    assertThat(entries(admin, id)).isEmpty();

    clock.advance(Duration.ofMinutes(5));
    Instant t1 = clock.instant();
    tickets.assign(admin, id, alex.id());
    List<AssignmentEntryView> e = entries(admin, id);
    assertThat(e).hasSize(1);
    assertThat(e.get(0).fromUserId()).isNull();
    assertThat(e.get(0).toUserId()).isEqualTo(alex.id());
    assertThat(e.get(0).actorUserId()).isEqualTo(adminUser.id());
    assertThat(e.get(0).reason()).isNull();
    assertThat(e.get(0).createdAt()).isEqualTo(t1);
    assertThat(e.get(0).text()).isEqualTo("Assigned to Alex Example by Pat Admin");

    clock.advance(Duration.ofMinutes(5));
    Instant t2 = clock.instant();
    tickets.assign(acmeAgent, id, jo.id());
    e = entries(admin, id);
    assertThat(e).hasSize(2);
    assertThat(e.get(1).fromUserId()).isEqualTo(alex.id());
    assertThat(e.get(1).toUserId()).isEqualTo(jo.id());
    assertThat(e.get(1).actorUserId()).isEqualTo(acmeAgentUser.id());
    assertThat(e.get(1).createdAt()).isEqualTo(t2);
    assertThat(e.get(1).text())
        .isEqualTo("Reassigned from Alex Example to Jo Example by Kim Agent");

    // Assigning the same person again changes nothing and adds no line.
    tickets.assign(admin, id, jo.id());
    assertThat(entries(admin, id)).hasSize(2);

    clock.advance(Duration.ofMinutes(5));
    tickets.assign(admin, id, null);
    e = entries(admin, id);
    assertThat(e).hasSize(3);
    assertThat(e.get(2).fromUserId()).isEqualTo(jo.id());
    assertThat(e.get(2).toUserId()).isNull();
    assertThat(e.get(2).text()).isEqualTo("Unassigned from Jo Example by Pat Admin");
    assertThat(assigneeRows(id)).isEqualTo(3);
  }

  @Test
  void aTicketCreatedAlreadyAssignedHasOneAssignedLine() {
    long id =
        tickets
            .create(
                acmeAgent,
                new TicketService.NewTicket(
                    acme,
                    acmeCustomerUser.id(),
                    null,
                    generalType,
                    null,
                    Impact.JUST_ME,
                    Urgency.PARTLY,
                    TicketSource.AGENT,
                    "Printer offline",
                    "<p>Second floor.</p>",
                    List.of(),
                    null,
                    alex.id(),
                    null,
                    null))
            .ticketId();
    List<AssignmentEntryView> e = entries(admin, id);
    assertThat(e).hasSize(1);
    assertThat(e.get(0).text()).isEqualTo("Assigned to Alex Example by Kim Agent");
    assertThat(e.get(0).createdAt()).isEqualTo(START);
  }

  @Test
  void movingToAnOrganisationTheAssigneeCannotWorkUnassignsWithTheReason() {
    long id = acmeTicket().ticketId();
    tickets.assign(admin, id, alex.id());
    tickets.changeOrganisation(admin, id, beta);
    List<AssignmentEntryView> e = entries(admin, id);
    assertThat(e).hasSize(2);
    assertThat(e.get(1).reason()).isEqualTo("ORGANISATION_MOVED");
    String betaName =
        jdbc.queryForObject("SELECT name FROM organisation WHERE id = ?", String.class, beta);
    assertThat(e.get(1).text())
        .isEqualTo("Unassigned from Alex Example (ticket moved to " + betaName + ") by Pat Admin");
  }

  @Test
  void linesSortAmongCommentsByTime() {
    long id = acmeTicket().ticketId();
    clock.advance(Duration.ofMinutes(1));
    tickets.assign(admin, id, alex.id());
    clock.advance(Duration.ofMinutes(1));
    notes.addNote(admin, id, "<p>Checking the mailbox.</p>", List.of());
    clock.advance(Duration.ofMinutes(1));
    tickets.assign(admin, id, jo.id());

    TicketDetail d = queries.detail(admin, id);
    Instant description = d.publicComments().get(0).createdAt();
    Instant note = d.internalNotes().get(0).createdAt();
    assertThat(d.assignmentEntries().get(0).createdAt()).isAfter(description).isBefore(note);
    assertThat(d.assignmentEntries().get(1).createdAt()).isAfter(note);
  }

  @Test
  void aUserWhoseNameCannotBeResolvedReadsAsAFormerAgent() {
    long id = acmeTicket().ticketId();
    tickets.assign(admin, id, alex.id());
    // A history row naming a user id that no longer resolves (defensive: ids are kept, names are
    // looked up when read).
    historyRepository.record(
        admin,
        acme,
        id,
        TicketHistoryRepository.HistoryType.ASSIGNEE,
        adminUser.id(),
        Long.toString(alex.id()),
        "999999999",
        clock.instant());
    assertThat(entries(admin, id).get(1).text())
        .isEqualTo("Reassigned from Alex Example to a former agent by Pat Admin");
  }

  @Test
  void customersNeverSeeTheLinesAndTheyNeverAppearInAnEmail() throws Exception {
    long id = acmeTicket().ticketId();
    tickets.assign(admin, id, alex.id());
    tickets.assign(admin, id, jo.id());
    tickets.addPublicReply(admin, id, "<p>We are on it.</p>", List.of(), null);
    tickets.changeStatus(admin, id, TicketStatus.RESOLVED);
    List<String> texts = entries(admin, id).stream().map(AssignmentEntryView::text).toList();
    assertThat(texts).hasSize(2);

    // Agent endpoint: an agent of the organisation gets the lines; a customer is refused.
    JsonNode agentView =
        json(signedIn(acmeAgentUser).get("/api/v1/agent/tickets/" + id).getContentAsString());
    assertThat(agentView.get("assignmentEntries")).hasSize(2);
    assertThat(agentView.get("assignmentEntries").get(1).get("text").asString())
        .isEqualTo("Reassigned from Alex Example to Jo Example by Pat Admin");
    Browser customer = signedIn(acmeCustomerUser);
    var r = customer.get("/api/v1/agent/tickets/" + id);
    assertThat(r.getStatus()).isIn(403, 404);
    assertThat(r.getContentAsString()).doesNotContain("Assigned to", "Reassigned from");

    // Service and storage layers: nothing for customer or API-key scopes.
    assertThatThrownBy(() -> queries.detail(acmeCustomer, id))
        .isInstanceOf(com.ticketsystem.api.auth.ApiException.class);
    assertThat(historyRepository.listForTicket(acmeCustomer, id)).isEmpty();
    assertThat(historyRepository.listForTicket(AccessScope.apiKey(1, acme), id)).isEmpty();

    // Every outbound email about the ticket (client reply and Resolved notice, the assignee's
    // notification) is free of the timeline wording.
    List<Map<String, Object>> outbox =
        jdbc.queryForList(
            "SELECT subject, html_body, text_body FROM outbox_message WHERE ticket_id = ?", id);
    assertThat(outbox).isNotEmpty();
    for (Map<String, Object> m : outbox) {
      String all = m.get("subject") + " " + m.get("html_body") + " " + m.get("text_body");
      for (String text : texts) {
        assertThat(all).doesNotContain(text);
      }
      assertThat(all).doesNotContain("Reassigned from", "Assigned to Alex");
    }
  }

  @Test
  void organisationScopingIsUnchanged() {
    long betaId = betaTicket().ticketId();
    tickets.assign(admin, betaId, jo.id());
    assertThatThrownBy(() -> queries.detail(acmeAgent, betaId))
        .isInstanceOf(com.ticketsystem.api.auth.ApiException.class);
    assertThat(historyRepository.listForTicket(acmeAgent, betaId)).isEmpty();
    assertThat(entries(restrictedScope(jo, acme, beta), betaId)).hasSize(1);
  }
}
