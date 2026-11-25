package com.ticketsystem.api.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.ApiException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** AccessScope decisions (C13): who sees which organisation, 404 not 403, admin-only checks. */
class AccessScopeTest {

  private static final long ACME = 1;
  private static final long BETA = 2;

  @Test
  void customerSeesOnlyTheirOrganisationAndNeverTriage() {
    AccessScope s = AccessScope.customer(10, ACME);
    assertThat(s.canSee(ACME)).isTrue();
    assertThat(s.canSee(BETA)).isFalse();
    assertThat(s.canSeeTriage()).isFalse();
    assertThat(s.organisationIdsOrAll()).contains(Set.of(ACME));
    assertThat(s.isAdmin()).isFalse();
  }

  @Test
  void restrictedAgentSeesOnlyAssignedOrganisationsButSeesTriage() {
    AccessScope s = AccessScope.agent(11, false, Set.of(ACME));
    assertThat(s.canSee(ACME)).isTrue();
    assertThat(s.canSee(BETA)).isFalse();
    assertThat(s.canSeeTriage()).isTrue();
    assertThat(s.isSupporter()).isTrue();
  }

  @Test
  void agentWithNoOrganisationsSeesNone() {
    AccessScope s = AccessScope.agent(11, false, Set.of());
    assertThat(s.canSee(ACME)).isFalse();
    assertThat(s.organisationIdsOrAll()).contains(Set.of());
  }

  @Test
  void allOrganisationsAgentAndAdminSeeEverything() {
    assertThat(AccessScope.agent(12, true, Set.of(ACME)).organisationIdsOrAll()).isEmpty();
    AccessScope admin = AccessScope.admin(13);
    assertThat(admin.canSee(BETA)).isTrue();
    assertThat(admin.organisationIdsOrAll()).isEmpty();
    assertThat(admin.isAdmin()).isTrue();
  }

  @Test
  void apiKeySeesItsOneOrganisationAndNoTriage() {
    AccessScope s = AccessScope.apiKey(99, BETA);
    assertThat(s.canSee(BETA)).isTrue();
    assertThat(s.canSee(ACME)).isFalse();
    assertThat(s.canSeeTriage()).isFalse();
  }

  @Test
  void requireAnswers404NotFound() {
    assertThatThrownBy(() -> AccessScope.customer(10, ACME).require(BETA))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void onlyAdminsPassRequireAdmin() {
    AccessScope.admin(1).requireAdmin();
    for (AccessScope s :
        new AccessScope[] {
          AccessScope.agent(2, true, Set.of()),
          AccessScope.customer(3, ACME),
          AccessScope.apiKey(4, ACME),
          AccessScope.system("test")
        }) {
      assertThatThrownBy(s::requireAdmin)
          .as(s.toString())
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }
  }

  @Test
  void systemScopeNeedsAReasonAndSeesAll() {
    assertThatThrownBy(() -> AccessScope.system(" ")).isInstanceOf(IllegalArgumentException.class);
    AccessScope s = AccessScope.system("nightly retention job");
    assertThat(s.isSystem()).isTrue();
    assertThat(s.canSee(BETA)).isTrue();
    assertThat(s.principalId()).isEqualTo(Optional.empty());
    s.requireSupporterOrSystem();
  }

  @Test
  void customersAndKeysFailRequireSupporterOrSystem() {
    assertThatThrownBy(() -> AccessScope.customer(1, ACME).requireSupporterOrSystem())
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> AccessScope.apiKey(1, ACME).requireSupporterOrSystem())
        .isInstanceOf(ApiException.class);
  }

  @Test
  void sqlConditionFollowsTheScope() {
    assertThat(ScopeSql.organisation(AccessScope.admin(1), "o.id")).isEqualTo("TRUE");
    assertThat(ScopeSql.organisation(AccessScope.agent(1, false, Set.of()), "o.id"))
        .isEqualTo("FALSE");
    assertThat(ScopeSql.organisation(AccessScope.customer(1, ACME), "o.id"))
        .isEqualTo("o.id IN (:scopeOrgIds)");
    assertThat(ScopeSql.organisationOrTriage(AccessScope.customer(1, ACME), "o.id"))
        .isEqualTo("o.id IN (:scopeOrgIds)");
    assertThat(ScopeSql.organisationOrTriage(AccessScope.agent(1, false, Set.of(ACME)), "o.id"))
        .isEqualTo("(o.id IN (:scopeOrgIds) OR o.id IS NULL)");
  }
}
