package com.ticketsystem.api.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.clientdata.UserAccessRepository;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository;
import com.ticketsystem.api.reference.ReferenceFormat;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.reference.clientdata.PrefixRepository;
import com.ticketsystem.api.reference.clientdata.TicketCounterRepository;
import com.ticketsystem.api.reference.clientdata.TicketReferenceRepository;
import com.ticketsystem.api.reference.clientdata.TicketReferenceRepository.Kind;
import com.ticketsystem.api.reference.clientdata.TicketReferenceRepository.Reason;
import com.ticketsystem.api.security.SecurityGoLive;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Rule 7 at the repository level: every method of every client-data repository added by WP-03,
 * called with a customer scope, an agent limited to Acme Dental and an admin scope, against Acme
 * Dental and Beta Clinic. Reads of the other organisation come back empty (never an error that
 * confirms it exists); writes need the admin scope.
 */
@SecurityGoLive({1})
class RepositoryScopeIntegrationTest extends OrganisationTestBase {

  @Autowired OrganisationRepository organisations;
  @Autowired PrefixRepository prefixes;
  @Autowired TicketCounterRepository counters;
  @Autowired TicketReferenceRepository references;
  @Autowired UserAccessRepository userAccess;

  private long acme;
  private long beta;
  private String betaDomain;
  private AccessScope acmeCustomer;
  private AccessScope acmeAgent;
  private AccessScope admin;
  private TicketReference betaRef;
  private long betaTicket;

  @BeforeEach
  void data() {
    acme = newOrganisation("Acme Dental");
    beta = newOrganisation("Beta Clinic");
    betaDomain = "beta-" + uniquePrefix().toLowerCase() + ".example";
    jdbc.update(
        "INSERT INTO organisation_domain (domain, organisation_id, created_at) VALUES (?, ?,"
            + " now())",
        betaDomain,
        beta);
    acmeCustomer = AccessScope.customer(activeUser(Role.CUSTOMER, acme).id(), acme);
    AppUser agent = restrictedAgent(acme);
    acmeAgent = restrictedScope(agent, acme);
    admin = AccessScope.admin(activeUser(Role.ADMIN).id());
    betaTicket = newTicketId();
    betaRef = ReferenceFormat.parse(activePrefixOf(beta) + "-1").orElseThrow();
    references.insert(admin, betaRef, betaTicket, beta, Kind.PRIMARY, Reason.ISSUED, Instant.now());
  }

  private List<AccessScope> limited() {
    return List.of(acmeCustomer, acmeAgent);
  }

  private static void forbidden(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  private static void notFound(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void organisationRepositoryReadsHideTheOtherOrganisation() {
    for (AccessScope s : limited()) {
      assertThat(organisations.list(s)).extracting(r -> r.id()).contains(acme).doesNotContain(beta);
      assertThat(organisations.find(s, acme)).isPresent();
      assertThat(organisations.find(s, beta)).as(s.toString()).isEmpty();
      assertThat(organisations.domains(s, beta)).isEmpty();
      assertThat(organisations.organisationIdForDomain(s, betaDomain)).isEmpty();
      notFound(() -> organisations.countCustomers(s, beta));
    }
    assertThat(organisations.list(admin)).extracting(r -> r.id()).contains(acme, beta);
    assertThat(organisations.find(admin, beta)).isPresent();
    assertThat(organisations.domains(admin, beta)).containsExactly(betaDomain);
    assertThat(organisations.organisationIdForDomain(admin, betaDomain)).contains(beta);
    assertThat(organisations.countCustomers(admin, beta)).isZero();
  }

  @Test
  void organisationRepositoryWritesAreAdminOnly() {
    Instant now = Instant.now();
    for (AccessScope s : limited()) {
      forbidden(() -> organisations.findForUpdate(s, acme));
      forbidden(() -> organisations.insert(s, "Gamma Care", "UTC", null, now));
      forbidden(() -> organisations.update(s, acme, "Renamed", "UTC", null, now));
      forbidden(() -> organisations.touch(s, acme, now));
      forbidden(() -> organisations.delete(s, acme));
      forbidden(() -> organisations.domainHolder(s, betaDomain));
      forbidden(() -> organisations.insertDomain(s, acme, "gamma.example", now));
      forbidden(() -> organisations.deleteDomain(s, beta, betaDomain));
    }
    assertThat(organisations.domainHolder(admin, betaDomain)).contains(beta);
    assertThat(organisations.findForUpdate(admin, beta)).isPresent();
  }

  @Test
  void prefixRepositoryIsScoped() {
    String betaPrefix = activePrefixOf(beta);
    for (AccessScope s : limited()) {
      assertThat(prefixes.activePrefix(s, acme)).contains(activePrefixOf(acme));
      assertThat(prefixes.activePrefix(s, beta)).isEmpty();
      assertThat(prefixes.listForOrganisation(s, beta)).isEmpty();
      assertThat(prefixes.organisationIdForPrefix(s, betaPrefix)).isEmpty();
      forbidden(() -> prefixes.holder(s, betaPrefix));
      forbidden(() -> prefixes.insertActive(s, acme, "ZZ1", Instant.now()));
      forbidden(() -> prefixes.retireActive(s, acme));
      forbidden(() -> prefixes.reactivate(s, acme, "ZZ1"));
    }
    assertThat(prefixes.activePrefix(admin, beta)).contains(betaPrefix);
    assertThat(prefixes.organisationIdForPrefix(admin, betaPrefix.toLowerCase())).contains(beta);
    assertThat(prefixes.organisationIdForPrefix(admin, "TRI")).isEmpty();
    assertThat(prefixes.holder(admin, "tri")).contains(0L);
  }

  @Test
  void counterRepositoryIsScoped() {
    for (AccessScope s : limited()) {
      assertThat(counters.takeNext(s, beta, Instant.now())).isEmpty();
      assertThat(counters.peekNext(s, beta)).isEmpty();
      forbidden(() -> counters.create(s, beta, Instant.now()));
      forbidden(() -> counters.lock(s, beta));
    }
    forbidden(() -> counters.takeNextTriage(acmeCustomer, Instant.now()));
    assertThat(counters.peekNext(admin, beta)).contains(1L);
    // The limited calls above took nothing from Beta's counter.
    assertThat(counters.peekNext(acmeAgent, acme)).contains(1L);
  }

  @Test
  void referenceRepositoryIsScoped() {
    for (AccessScope s : limited()) {
      assertThat(references.find(s, betaRef)).isEmpty();
      assertThat(references.listForTicket(s, betaTicket)).isEmpty();
      notFound(() -> references.anyForOrganisation(s, beta));
      notFound(
          () ->
              references.insert(
                  s,
                  ReferenceFormat.parse(activePrefixOf(beta) + "-2").orElseThrow(),
                  newTicketId(),
                  beta,
                  Kind.PRIMARY,
                  Reason.ISSUED,
                  Instant.now()));
      notFound(() -> references.demoteAndMove(s, betaTicket, beta));
      // Moving Beta's ticket into Acme: no visible row is touched.
      assertThat(references.demoteAndMove(s, betaTicket, acme)).isZero();
    }
    assertThat(references.find(admin, betaRef)).isPresent();
    assertThat(references.listForTicket(admin, betaTicket)).hasSize(1);
    assertThat(references.listForTicket(admin, betaTicket).get(0).organisationId()).isEqualTo(beta);
    assertThat(references.anyForOrganisation(admin, beta)).isTrue();
  }

  @Test
  void userAccessRepositoryIsAdminOnly() {
    AppUser customer = activeUser(Role.CUSTOMER, acme);
    AppUser agent = activeUser(Role.AGENT);
    for (AccessScope s : limited()) {
      forbidden(() -> userAccess.find(s, customer.id()));
      forbidden(() -> userAccess.lockRole(s, customer.id()));
      forbidden(() -> userAccess.setCustomerOrganisation(s, customer.id(), beta, Instant.now()));
      forbidden(() -> userAccess.setAgentAccess(s, agent.id(), false, Set.of(beta), Instant.now()));
    }
    assertThat(userAccess.find(admin, customer.id()).orElseThrow().organisationId())
        .isEqualTo(acme);
    assertThat(userAccess.setCustomerOrganisation(admin, agent.id(), beta, Instant.now())).isZero();
  }
}
