package com.ticketsystem.api.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.OrganisationTestBase;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.organisation.OrganisationService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The per-organisation counter and the reference/alias table (spec 6, 8 and 12; ADR-014; rule 10):
 * race-safe issuing, rollback, prefix change, triage and move aliases, and scoped resolution.
 */
class ReferenceCounterIntegrationTest extends OrganisationTestBase {

  @Autowired CounterService counter;
  @Autowired ReferenceService references;
  @Autowired OrganisationService organisations;
  @Autowired TransactionTemplate tx;

  private final AccessScope admin = AccessScope.admin(1);
  private final AccessScope system = AccessScope.system("reference integration test");

  private long acme;
  private long beta;

  @BeforeEach
  void organisations() {
    acme = newOrganisation("Acme Dental");
    beta = newOrganisation("Beta Clinic");
  }

  private TicketReference next(AccessScope scope, long org) {
    return tx.execute(s -> counter.next(scope, org));
  }

  private TicketReference issue(AccessScope scope, Long org, long ticketId) {
    return tx.execute(s -> references.issue(scope, org, ticketId));
  }

  @Test
  void twoConcurrentCallersGetDifferentConsecutiveNumbers() throws Exception {
    CountDownLatch bothInside = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    Callable<Long> call =
        () ->
            tx.execute(
                s -> {
                  bothInside.countDown();
                  try {
                    go.await(10, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return counter.next(admin, acme).number();
                });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Long> a = pool.submit(call);
      Future<Long> b = pool.submit(call);
      assertThat(bothInside.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(1L, 2L);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void manyParallelIssuersNeverDuplicateOrSkip() throws Exception {
    int threads = 8;
    int each = 25;
    CyclicBarrier start = new CyclicBarrier(threads);
    List<Long> numbers = Collections.synchronizedList(new ArrayList<>());
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  for (int i = 0; i < each; i++) {
                    numbers.add(issue(admin, acme, newTicketId()).number());
                  }
                  return null;
                }));
      }
      for (Future<?> f : futures) {
        f.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(numbers)
        .hasSize(threads * each)
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrderElementsOf(
            LongStream.rangeClosed(1, threads * each).boxed().toList());
  }

  @Test
  void aRolledBackTransactionDoesNotConsumeANumber() {
    assertThat(next(admin, acme).number()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      assertThat(counter.next(admin, acme).number()).isEqualTo(2);
                      throw new IllegalStateException("ticket insert failed");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(next(admin, acme).number()).isEqualTo(2);
  }

  @Test
  void theCounterMustRunInsideTheCallersTransaction() {
    assertThatThrownBy(() -> counter.next(admin, acme))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> references.issue(admin, acme, newTicketId()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void eachOrganisationHasItsOwnSequenceAndPrefix() {
    TicketReference a1 = next(admin, acme);
    TicketReference a2 = next(admin, acme);
    TicketReference b1 = next(admin, beta);
    assertThat(a1.toString()).isEqualTo(activePrefixOf(acme) + "-001");
    assertThat(a2.toString()).isEqualTo(activePrefixOf(acme) + "-002");
    assertThat(b1.toString()).isEqualTo(activePrefixOf(beta) + "-001");
  }

  @Test
  void afterAPrefixChangeTheSequenceContinuesAndOldReferencesStillResolve() {
    String oldPrefix = activePrefixOf(acme);
    long ticket = newTicketId();
    TicketReference old = issue(admin, acme, ticket);
    assertThat(old.number()).isEqualTo(1);

    String newPrefix = uniquePrefix();
    organisations.changePrefix(admin, acme, newPrefix.toLowerCase(), "203.0.113.9");
    TicketReference next = issue(admin, acme, newTicketId());
    assertThat(next.prefix()).isEqualTo(newPrefix);
    assertThat(next.number()).isEqualTo(2);

    ReferenceService.Resolved r =
        references.resolve(system, oldPrefix.toLowerCase() + "-1").orElseThrow();
    assertThat(r.ticketId()).isEqualTo(ticket);
    assertThat(r.organisationId()).isEqualTo(acme);
    // The retired prefix stays reserved: another organisation cannot take it.
    assertThatThrownBy(() -> organisations.changePrefix(admin, beta, oldPrefix, "203.0.113.9"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void triageTicketAssignedToAnOrganisationKeepsItsTriReferenceAsAnAlias() {
    long ticket = newTicketId();
    TicketReference tri = issue(system, null, ticket);
    assertThat(tri.prefix()).isEqualTo("TRI");

    TicketReference primary = tx.execute(s -> references.assignOrganisation(admin, ticket, acme));
    assertThat(primary.prefix()).isEqualTo(activePrefixOf(acme));

    ReferenceService.Resolved viaAlias =
        references.resolve(system, tri.toString().toLowerCase()).orElseThrow();
    assertThat(viaAlias.ticketId()).isEqualTo(ticket);
    assertThat(viaAlias.primary()).isEqualTo(primary);
    assertThat(viaAlias.isAlias()).isTrue();
    assertThat(viaAlias.organisationId()).isEqualTo(acme);

    ReferenceService.Resolved viaPrimary =
        references.resolve(system, primary.toString()).orElseThrow();
    assertThat(viaPrimary.isAlias()).isFalse();

    // Moving to another organisation: a new primary from Beta; both earlier references alias it.
    TicketReference moved = tx.execute(s -> references.assignOrganisation(admin, ticket, beta));
    assertThat(moved.prefix()).isEqualTo(activePrefixOf(beta));
    assertThat(references.referencesOf(admin, ticket)).containsExactly(moved, tri, primary);
    assertThat(references.resolve(system, tri.toString()).orElseThrow().primary()).isEqualTo(moved);
    assertThat(references.resolve(system, primary.toString()).orElseThrow().organisationId())
        .isEqualTo(beta);

    // Assigning to the organisation it is already in changes nothing.
    TicketReference again = tx.execute(s -> references.assignOrganisation(admin, ticket, beta));
    assertThat(again).isEqualTo(moved);
  }

  @Test
  void resolutionIsScopedByOrganisation() {
    long acmeTicket = newTicketId();
    long triageTicket = newTicketId();
    TicketReference acmeRef = issue(admin, acme, acmeTicket);
    TicketReference triRef = issue(system, null, triageTicket);

    AccessScope betaCustomer = AccessScope.customer(5, beta);
    AccessScope acmeCustomer = AccessScope.customer(6, acme);
    AccessScope betaAgent = AccessScope.agent(7, false, Set.of(beta));
    AccessScope betaKey = AccessScope.apiKey(8, beta);

    assertThat(references.resolve(acmeCustomer, acmeRef.toString())).isPresent();
    assertThat(references.resolve(admin, acmeRef.toString())).isPresent();
    assertThat(references.resolve(betaCustomer, acmeRef.toString())).isEmpty();
    assertThat(references.resolve(betaAgent, acmeRef.toString())).isEmpty();
    assertThat(references.resolve(betaKey, acmeRef.toString())).isEmpty();
    assertThat(references.referencesOf(betaAgent, acmeTicket)).isEmpty();

    // Triage is for supporters only (spec 11a); customers and keys never see it.
    assertThat(references.resolve(betaAgent, triRef.toString())).isPresent();
    assertThat(references.resolve(acmeCustomer, triRef.toString())).isEmpty();
    assertThat(references.resolve(betaKey, triRef.toString())).isEmpty();

    assertThat(references.resolve(admin, "not a reference")).isEmpty();
    assertThat(references.resolve(admin, activePrefixOf(acme) + "-999999")).isEmpty();
  }

  @Test
  void issuingAndMovingAreScopedToo() {
    AccessScope betaAgent = AccessScope.agent(7, false, Set.of(beta));
    AccessScope acmeCustomer = AccessScope.customer(6, acme);

    assertNotFound(() -> next(betaAgent, acme));
    assertNotFound(() -> next(acmeCustomer, beta));
    assertNotFound(() -> next(admin, 999_999_999L));
    assertThatThrownBy(() -> issue(acmeCustomer, null, newTicketId()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));

    long ticket = newTicketId();
    issue(system, null, ticket);
    // A Beta-only agent may pick up a triage ticket for Beta, but not move it to Acme.
    assertNotFound(() -> tx.execute(s -> references.assignOrganisation(betaAgent, ticket, acme)));
    assertThat(tx.execute(s -> references.assignOrganisation(betaAgent, ticket, beta)).prefix())
        .isEqualTo(activePrefixOf(beta));
    // Once in Beta, an Acme-only agent cannot see it to move it.
    AccessScope acmeAgent = AccessScope.agent(9, false, Set.of(acme));
    assertNotFound(() -> tx.execute(s -> references.assignOrganisation(acmeAgent, ticket, acme)));
    assertThat(next(admin, acme).number()).isEqualTo(1);
  }

  private static void assertNotFound(Runnable r) {
    assertThatThrownBy(r::run)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }
}
