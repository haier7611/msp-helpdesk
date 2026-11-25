package com.ticketsystem.api.ticket;

import static com.ticketsystem.api.sla.TicketStatus.CLOSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.organisation.OrganisationService;
import com.ticketsystem.api.reference.ReferenceService.Resolved;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import com.ticketsystem.api.ticket.event.TicketEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * References and aliases (spec 6, 11a, 12): concurrent issuing, triage assignment, organisation
 * moves, prefix changes and duplicates - all resolving for ever; duplicates within one organisation
 * only.
 */
class TicketReferenceIntegrationTest extends TicketTestBase {

  @Autowired OrganisationService organisationService;

  private TicketReference resolveTarget(String text) {
    return references.resolve(admin, text).map(Resolved::primary).orElseThrow();
  }

  private long resolveTicket(String text) {
    return references.resolve(admin, text).map(Resolved::ticketId).orElseThrow();
  }

  @Test
  void ticketsCreatedConcurrentlyGetConsecutiveReferences() throws Exception {
    int threads = 6;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Created>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  return acmeTicket();
                }));
      }
      start.countDown();
      List<Long> numbers = new ArrayList<>();
      for (Future<Created> f : futures) {
        numbers.add(f.get(60, TimeUnit.SECONDS).reference().number());
      }
      Collections.sort(numbers);
      assertThat(numbers).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aTriageTicketAssignedToAnOrganisationGetsANewPrimaryAndKeepsItsTriAlias() {
    Created t = triageTicket();
    assertThat(t.reference().prefix()).isEqualTo("TRI");
    assertThatThrownBy(() -> queries.detail(acmeCustomer, t.ticketId()))
        .isInstanceOf(ApiException.class);
    TicketReference fresh = tickets.changeOrganisation(acmeAgent, t.ticketId(), acme);
    assertThat(fresh.prefix()).isEqualTo(activePrefixOf(acme));
    assertThat(resolveTicket(t.reference().toString())).isEqualTo(t.ticketId());
    assertThat(resolveTarget(t.reference().toString().toLowerCase())).isEqualTo(fresh);
    TicketDetail d = queries.detail(acmeAgent, t.ticketId());
    assertThat(d.reference()).isEqualTo(fresh.toString());
    assertThat(d.aliases()).containsExactly(t.reference().toString());
    assertThat(recorder.forTicket(t.ticketId()))
        .contains(
            new TicketEvent.OrganisationChanged(t.ticketId(), null, acme, acmeAgentUser.id()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE type = 'TICKET_ORGANISATION_CHANGED' AND"
                    + " details->>'ticket' = ?",
                Integer.class,
                Long.toString(t.ticketId())))
        .isEqualTo(1);
  }

  @Test
  void movingATicketIssuesANewReferenceKeepsTheOldAsAliasAndRechecksPeople() {
    Created t = acmeTicket();
    AppUser acmeOnly = restrictedAgent(acme);
    tickets.assign(admin, t.ticketId(), acmeOnly.id());
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, user_id, email, confirmed, created_at) VALUES"
            + " (?, ?, ?, TRUE, now())",
        t.ticketId(),
        acmeCustomerUser.id(),
        acmeCustomerUser.email().toLowerCase());
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, email, confirmed, created_at) VALUES (?,"
            + " 'outside@partner.example', TRUE, now())",
        t.ticketId());
    Created related = acmeTicket();
    tickets.linkRelated(admin, t.ticketId(), related.ticketId());

    TicketReference moved = tickets.changeOrganisation(admin, t.ticketId(), beta);

    assertThat(moved.prefix()).isEqualTo(activePrefixOf(beta));
    assertThat(resolveTarget(t.reference().toString())).isEqualTo(moved);
    TicketDetail d = queries.detail(admin, t.ticketId());
    assertThat(d.organisationId()).isEqualTo(beta);
    assertThat(d.assignee()).as("acme-only assignee cleared").isNull();
    assertThat(d.participants())
        .extracting(p -> p.email())
        .containsExactly("outside@partner.example");
    assertThat(d.participants().get(0).confirmed()).isFalse();
    assertThat(d.links()).isEmpty();
    // The acme-only agent can no longer see it, by either reference.
    assertThat(references.resolve(acmeAgent, t.reference().toString())).isEmpty();
    assertThatThrownBy(() -> queries.detail(acmeAgent, t.ticketId()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void aRestrictedAgentCannotMoveATicketToAnOrganisationTheyDoNotWork() {
    Created t = acmeTicket();
    assertThatThrownBy(() -> tickets.changeOrganisation(acmeAgent, t.ticketId(), beta))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    assertThat(queries.detail(admin, t.ticketId()).organisationId()).isEqualTo(acme);
  }

  @Test
  void afterAPrefixChangeOldReferencesStillResolve() {
    Created before = acmeTicket();
    String newPrefix = uniquePrefix();
    organisationService.changePrefix(admin, acme, newPrefix, null);
    Created after = acmeTicket();
    assertThat(after.reference().prefix()).isEqualTo(newPrefix);
    assertThat(after.reference().number()).isEqualTo(before.reference().number() + 1);
    assertThat(resolveTicket(before.reference().toString())).isEqualTo(before.ticketId());
  }

  @Test
  void markingADuplicateClosesTheSourceAndAliasesItsReferencesToTheTarget() {
    Created source = acmeTicket();
    Created target = ticket(admin, acme, activeUser(Role.CUSTOMER, acme));
    recorder.clear();

    long result = tickets.markDuplicate(acmeAgent, source.ticketId(), target.ticketId());

    assertThat(result).isEqualTo(target.ticketId());
    assertThat(statusOf(source.ticketId())).isEqualTo(CLOSED);
    assertThat(resolveTicket(source.reference().toString())).isEqualTo(target.ticketId());
    assertThat(resolveTarget(source.reference().toString())).isEqualTo(target.reference());
    TicketDetail s = queries.detail(acmeAgent, source.ticketId());
    assertThat(s.reference()).isEqualTo(source.reference().toString());
    assertThat(s.duplicateOf().ticketId()).isEqualTo(target.ticketId());
    TicketDetail t = queries.detail(acmeAgent, target.ticketId());
    assertThat(t.aliases()).contains(source.reference().toString());
    assertThat(t.participants())
        .extracting(p -> p.email())
        .contains(acmeCustomerUser.email().toLowerCase());
    assertThat(t.links()).extracting(l -> l.kind()).containsExactly("DUPLICATE");
    // No client-facing event: no reply, no creation, no Resolved.
    List<TicketEvent> events = recorder.all();
    assertThat(events)
        .containsExactly(
            new TicketEvent.StatusChanged(
                source.ticketId(),
                com.ticketsystem.api.sla.TicketStatus.NEW,
                CLOSED,
                Trigger.MARK_DUPLICATE,
                acmeAgentUser.id()),
            new TicketEvent.MarkedDuplicate(
                source.ticketId(), target.ticketId(), acmeAgentUser.id()));
  }

  @Test
  void aDuplicateChainIsFollowedToTheFinalTarget() {
    Created a = acmeTicket();
    Created b = acmeTicket();
    Created c = acmeTicket();
    tickets.markDuplicate(admin, b.ticketId(), c.ticketId());
    // a is marked a duplicate of b, which is already a duplicate of c: a lands on c.
    assertThat(tickets.markDuplicate(admin, a.ticketId(), b.ticketId())).isEqualTo(c.ticketId());
    assertThat(resolveTicket(a.reference().toString())).isEqualTo(c.ticketId());
    assertThat(resolveTicket(b.reference().toString())).isEqualTo(c.ticketId());
    // Marking c a duplicate of a closed source that points back at c is refused.
    assertThatThrownBy(() -> tickets.markDuplicate(admin, c.ticketId(), a.ticketId()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> tickets.markDuplicate(admin, c.ticketId(), c.ticketId()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void duplicatesAndLinksStayWithinOneOrganisation() {
    Created a = acmeTicket();
    Created b = betaTicket();
    Created tri = triageTicket();
    assertThatThrownBy(() -> tickets.markDuplicate(admin, a.ticketId(), b.ticketId()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThatThrownBy(() -> tickets.markDuplicate(admin, tri.ticketId(), a.ticketId()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> tickets.linkRelated(admin, a.ticketId(), b.ticketId()))
        .isInstanceOf(ApiException.class);
    // A restricted agent cannot even see the other organisation's ticket: 404.
    assertThatThrownBy(() -> tickets.markDuplicate(acmeAgent, a.ticketId(), b.ticketId()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    assertThat(statusOf(a.ticketId())).isNotEqualTo(CLOSED);
    assertThat(resolveTicket(a.reference().toString())).isEqualTo(a.ticketId());
  }

  @Test
  void relatedLinksShowFromBothSides() {
    Created a = acmeTicket();
    Created b = acmeTicket();
    tickets.linkRelated(acmeAgent, a.ticketId(), b.ticketId());
    tickets.linkRelated(acmeAgent, b.ticketId(), a.ticketId());
    assertThat(queries.detail(acmeAgent, a.ticketId()).links())
        .extracting(l -> l.ticketId())
        .containsExactly(b.ticketId());
    assertThat(queries.detail(acmeAgent, b.ticketId()).links())
        .extracting(l -> l.reference())
        .containsExactly(a.reference().toString());
    assertThat(statusOf(a.ticketId())).isNotEqualTo(CLOSED);
  }
}
