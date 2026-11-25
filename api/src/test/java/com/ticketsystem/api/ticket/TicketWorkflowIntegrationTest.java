package com.ticketsystem.api.ticket;

import static com.ticketsystem.api.sla.TicketStatus.CLOSED;
import static com.ticketsystem.api.sla.TicketStatus.NEW;
import static com.ticketsystem.api.sla.TicketStatus.OPEN;
import static com.ticketsystem.api.sla.TicketStatus.PENDING_ON_CLIENT;
import static com.ticketsystem.api.sla.TicketStatus.PENDING_ON_VENDOR;
import static com.ticketsystem.api.sla.TicketStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketService.ClientReply;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketService.ReplyResult;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import com.ticketsystem.api.ticket.event.TicketEvent;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Statuses, comments, assignment and events through the real services and database. */
class TicketWorkflowIntegrationTest extends TicketTestBase {

  @Autowired TransactionTemplate tx;

  private static void conflict(Runnable r) {
    assertThatThrownBy(r::run)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void aNewTicketIsNewWithAReferenceAndItsDescriptionAsTheOpeningAgentsMessage() {
    Created c = acmeTicket();
    assertThat(statusOf(c.ticketId())).isEqualTo(NEW);
    assertThat(c.reference().prefix()).isEqualTo(activePrefixOf(acme));
    TicketDetail d = queries.detail(admin, c.ticketId());
    assertThat(d.reference()).isEqualTo(c.reference().toString());
    assertThat(d.publicComments()).hasSize(1);
    // ADR-040: a ticket an agent opens starts with the agent's own message, not the client's.
    assertThat(d.publicComments().get(0).authorKind()).isEqualTo("SUPPORTER");
    assertThat(d.publicComments().get(0).author().userId()).isEqualTo(adminUser.id());
    assertThat(d.priority()).isEqualTo(Priority.P3);
    assertThat(d.internalNotes()).isEmpty();
    assertThat(recorder.forTicket(c.ticketId()))
        .containsExactly(
            new TicketEvent.TicketCreated(
                c.ticketId(), acme, TicketSource.AGENT, adminUser.id(), null));
  }

  @Test
  void everyLegalAgentTransitionIsAppliedAndRecorded() {
    for (TicketStatus from : TicketStatus.values()) {
      for (TicketStatus to : TicketStatus.values()) {
        long id = acmeTicket().ticketId();
        forceStatus(id, from);
        recorder.clear();
        if (TicketTransitions.agentMaySet(from, to)) {
          // ADR-041: a close needs a reason.
          String reason = to == CLOSED ? "RESOLVED_NO_FOLLOW_UP" : null;
          assertThat(tickets.changeStatus(acmeAgent, id, to, reason, null)).isEqualTo(to);
          assertThat(statusOf(id)).isEqualTo(to);
          if (from != to) {
            assertThat(recorder.forTicket(id))
                .containsExactly(
                    new TicketEvent.StatusChanged(
                        id, from, to, Trigger.AGENT_SET, acmeAgentUser.id()));
          } else {
            assertThat(recorder.forTicket(id)).isEmpty();
          }
        } else {
          assertThatThrownBy(
                  () -> tickets.changeStatus(acmeAgent, id, to, "RESOLVED_NO_FOLLOW_UP", null))
              .as(from + " -> " + to)
              .isInstanceOf(ApiException.class);
          assertThat(statusOf(id)).isEqualTo(from);
          assertThat(recorder.forTicket(id)).isEmpty();
        }
      }
    }
  }

  @Test
  void aClientReplyMovesEitherPendingStatusOrResolvedToOpen() {
    for (TicketStatus from : EnumSet.of(PENDING_ON_CLIENT, PENDING_ON_VENDOR, RESOLVED)) {
      long id = acmeTicket().ticketId();
      forceStatus(id, from);
      ReplyResult r =
          tickets.addClientReply(
              AccessScope.system("test: email poller"),
              id,
              new ClientReply(
                  acmeCustomerUser.id(),
                  acmeCustomerUser.email(),
                  "<p>Still broken</p>",
                  true,
                  "<reply-" + id + "@client.example>",
                  "dmarc=pass"));
      assertThat(r.ticketId()).isEqualTo(id);
      assertThat(r.followUpCreated()).isFalse();
      assertThat(statusOf(id)).as("from " + from).isEqualTo(OPEN);
    }
    long fresh = acmeTicket().ticketId();
    tickets.addClientReply(
        admin, fresh, new ClientReply(null, "a@example.test", "<p>more</p>", true, null, null));
    assertThat(statusOf(fresh)).isEqualTo(NEW);
  }

  @Test
  void aClientReplyToAClosedTicketOpensALinkedFollowUp() {
    Created old = acmeTicket();
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, email, confirmed, created_at) VALUES (?,"
            + " 'colleague@acme-dental.example', TRUE, now())",
        old.ticketId());
    forceStatus(old.ticketId(), CLOSED);
    ReplyResult r =
        tickets.addClientReply(
            admin,
            old.ticketId(),
            new ClientReply(
                acmeCustomerUser.id(),
                acmeCustomerUser.email(),
                "<p>It is back</p>",
                true,
                null,
                null));
    assertThat(r.followUpCreated()).isTrue();
    assertThat(r.ticketId()).isNotEqualTo(old.ticketId());
    assertThat(statusOf(old.ticketId())).isEqualTo(CLOSED);
    TicketDetail f = queries.detail(admin, r.ticketId());
    assertThat(f.subject()).isEqualTo("Follow-up to " + old.reference());
    assertThat(f.organisationId()).isEqualTo(acme);
    assertThat(f.reporter().userId()).isEqualTo(acmeCustomerUser.id());
    assertThat(f.followUpOf().ticketId()).isEqualTo(old.ticketId());
    assertThat(f.participants())
        .extracting(p -> p.email())
        .contains("colleague@acme-dental.example");
    assertThat(f.links()).extracting(l -> l.ticketId()).contains(old.ticketId());
    assertThat(f.publicComments())
        .extracting(c -> c.bodyHtml())
        .containsExactly("<p>It is back</p>");
    assertThat(f.source()).isEqualTo(TicketSource.EMAIL);
    assertThat(queries.detail(admin, old.ticketId()).links())
        .extracting(l -> l.kind())
        .containsExactly("FOLLOW_UP");
  }

  @Test
  void agentsCanStartAFollowUpOnlyFromAClosedTicket() {
    Created open = acmeTicket();
    conflict(() -> tickets.createFollowUp(acmeAgent, open.ticketId()));
    forceStatus(open.ticketId(), CLOSED);
    Created f = tickets.createFollowUp(acmeAgent, open.ticketId());
    assertThat(statusOf(f.ticketId())).isEqualTo(NEW);
    assertThat(recorder.forTicket(f.ticketId()))
        .contains(
            new TicketEvent.TicketCreated(
                f.ticketId(), acme, TicketSource.AGENT, acmeAgentUser.id(), open.ticketId()));
  }

  @Test
  void closedTicketsAcceptInternalNotesButNothingElse() {
    long id = acmeTicket().ticketId();
    forceStatus(id, CLOSED);
    long note = notes.addNote(acmeAgent, id, "<p>Closed as agreed by phone</p>", List.of());
    assertThat(note).isPositive();
    assertThat(statusOf(id)).isEqualTo(CLOSED);
    conflict(() -> tickets.addPublicReply(acmeAgent, id, "<p>x</p>", List.of(), null));
    conflict(() -> tickets.changePriority(acmeAgent, id, Priority.P1));
    conflict(() -> tickets.assign(acmeAgent, id, acmeAgentUser.id()));
    conflict(() -> tickets.changeRequestType(acmeAgent, id, alertType));
    conflict(() -> tickets.changeOrganisation(admin, id, beta));
    conflict(() -> tickets.changeStatus(acmeAgent, id, OPEN));
  }

  @Test
  void aPublicReplyPicksUpANewTicketAndCanSetAStatus() {
    long id = acmeTicket().ticketId();
    recorder.clear();
    long comment =
        tickets.addPublicReply(
            acmeAgent, id, "<p>Can you restart?</p>", List.of(), PENDING_ON_CLIENT);
    assertThat(statusOf(id)).isEqualTo(PENDING_ON_CLIENT);
    assertThat(recorder.forTicket(id))
        .containsExactly(
            new TicketEvent.PublicReplyAdded(id, comment, acmeAgentUser.id()),
            new TicketEvent.StatusChanged(
                id, NEW, OPEN, Trigger.SUPPORTER_PUBLIC_REPLY, acmeAgentUser.id()),
            new TicketEvent.StatusChanged(
                id, OPEN, PENDING_ON_CLIENT, Trigger.AGENT_SET, acmeAgentUser.id()));
    assertThatThrownBy(() -> tickets.addPublicReply(acmeAgent, id, "  <p> </p> ", List.of(), null))
        .isInstanceOf(ApiException.class);
    // An illegal follow-on status rolls the reply back too.
    int before = publicCount(id);
    assertThatThrownBy(() -> tickets.addPublicReply(acmeAgent, id, "<p>x</p>", List.of(), NEW))
        .isInstanceOf(ApiException.class);
    assertThat(publicCount(id)).isEqualTo(before);
  }

  @Test
  void assigningYourselfPicksUpAndOthersMustWorkTheOrganisation() {
    long id = acmeTicket().ticketId();
    AppUser betaOnly = restrictedAgent(beta);
    assertThatThrownBy(() -> tickets.assign(acmeAgent, id, betaOnly.id()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThatThrownBy(() -> tickets.assign(acmeAgent, id, acmeCustomerUser.id()))
        .isInstanceOf(ApiException.class);
    AppUser other = activeUser(Role.AGENT);
    tickets.assign(acmeAgent, id, other.id());
    assertThat(statusOf(id)).isEqualTo(NEW);
    tickets.assign(acmeAgent, id, acmeAgentUser.id());
    assertThat(statusOf(id)).isEqualTo(OPEN);
    tickets.assign(acmeAgent, id, null);
    assertThat(queries.detail(admin, id).assignee()).isNull();
  }

  @Test
  void priorityAndRequestTypeAreAgentOnlyAndRecorded() {
    long id = acmeTicket().ticketId();
    tickets.changePriority(acmeAgent, id, Priority.P1);
    tickets.changeRequestType(acmeAgent, id, alertType);
    TicketDetail d = queries.detail(acmeAgent, id);
    assertThat(d.priority()).isEqualTo(Priority.P1);
    assertThat(d.requestType().name()).isEqualTo("Alert");
    assertThat(d.history()).extracting(h -> h.type()).contains("PRIORITY", "REQUEST_TYPE");
    assertThatThrownBy(() -> tickets.changePriority(acmeCustomer, id, Priority.P4))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void customersCannotChoosePriorityOrTheAlertType() {
    assertThatThrownBy(
            () ->
                tickets.create(
                    acmeCustomer,
                    new NewTicket(
                        acme,
                        acmeCustomerUser.id(),
                        null,
                        generalType,
                        Priority.P1,
                        null,
                        null,
                        TicketSource.PORTAL,
                        "x",
                        "<p>x</p>",
                        List.of(),
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                tickets.create(
                    acmeCustomer,
                    new NewTicket(
                        acme,
                        acmeCustomerUser.id(),
                        null,
                        alertType,
                        null,
                        null,
                        null,
                        TicketSource.PORTAL,
                        "x",
                        "<p>x</p>",
                        List.of(),
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void eventsAreDeliveredOnlyAfterCommit() {
    long id = acmeTicket().ticketId();
    recorder.clear();
    tx.executeWithoutResult(
        status -> {
          tickets.changePriority(acmeAgent, id, Priority.P2);
          assertThat(recorder.forTicket(id)).isEmpty();
          status.setRollbackOnly();
        });
    assertThat(recorder.forTicket(id)).isEmpty();
    tickets.changePriority(acmeAgent, id, Priority.P2);
    assertThat(recorder.forTicket(id))
        .containsExactly(
            new TicketEvent.PriorityChanged(id, Priority.P3, Priority.P2, acmeAgentUser.id()));
  }

  @Test
  void jobTriggersAreSystemOnly() {
    long id = acmeTicket().ticketId();
    forceStatus(id, PENDING_ON_CLIENT);
    assertThatThrownBy(() -> tickets.applySystemTrigger(admin, id, Trigger.AUTO_RESOLVE))
        .isInstanceOf(ApiException.class);
    AccessScope job = AccessScope.system("test: auto-resolve job");
    assertThat(tickets.applySystemTrigger(job, id, Trigger.AUTO_RESOLVE)).isEqualTo(RESOLVED);
    assertThat(tickets.applySystemTrigger(job, id, Trigger.AUTO_CLOSE)).isEqualTo(CLOSED);
    conflict(() -> tickets.applySystemTrigger(job, id, Trigger.AUTO_CLOSE));
  }

  @Test
  void subjectsAndContactsAreValidated() {
    assertThatThrownBy(
            () ->
                tickets.create(
                    admin,
                    new NewTicket(
                        acme,
                        betaCustomerUser.id(),
                        null,
                        generalType,
                        null,
                        null,
                        null,
                        TicketSource.AGENT,
                        "x",
                        "",
                        List.of(),
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                tickets.create(
                    admin,
                    new NewTicket(
                        acme,
                        acmeCustomerUser.id(),
                        null,
                        generalType,
                        null,
                        null,
                        null,
                        TicketSource.AGENT,
                        "  ",
                        "",
                        List.of(),
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                tickets.create(
                    admin,
                    new NewTicket(
                        acme,
                        acmeCustomerUser.id(),
                        null,
                        generalType,
                        null,
                        null,
                        null,
                        TicketSource.AGENT,
                        "x".repeat(251),
                        "",
                        List.of(),
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(ApiException.class);
  }

  private int publicCount(long ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM public_comment WHERE ticket_id = ?", Integer.class, ticketId);
  }
}
