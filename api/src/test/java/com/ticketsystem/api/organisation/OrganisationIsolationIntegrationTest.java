package com.ticketsystem.api.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.OrganisationTestBase;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.security.SecurityGoLive;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Security test 1, organisation part (spec 12): an agent assigned only to Acme Dental gets 404 for
 * Beta Clinic's id on every organisation endpoint and never sees it in a list; a customer gets
 * nothing; an agent with all organisations and an admin see both.
 */
@SecurityGoLive({1})
class OrganisationIsolationIntegrationTest extends OrganisationTestBase {

  private static final String AGENT_BASE = "/api/v1/agent/organisations";
  private static final String ADMIN_BASE = "/api/v1/admin/organisations";

  private long acme;
  private long beta;

  @BeforeEach
  void organisations() {
    acme = newOrganisation("Acme Dental");
    beta = newOrganisation("Beta Clinic");
  }

  private static List<Long> ids(MockHttpServletResponse r) throws Exception {
    List<Long> out = new ArrayList<>();
    for (JsonNode n : json(r.getContentAsString())) {
      out.add(n.get("id").asLong());
    }
    return out;
  }

  @Test
  void agentLimitedToAcmeGets404ForBetaOnEveryOrganisationEndpoint() throws Exception {
    Browser agent = signedIn(restrictedAgent(acme));

    MockHttpServletResponse list = agent.get(AGENT_BASE);
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat(ids(list)).contains(acme).doesNotContain(beta);

    assertThat(agent.get(AGENT_BASE + "/" + acme).getStatus()).isEqualTo(200);
    MockHttpServletResponse other = agent.get(AGENT_BASE + "/" + beta);
    assertThat(other.getStatus()).isEqualTo(404);
    // Same answer as an id that does not exist: existence is not confirmed.
    MockHttpServletResponse missing = agent.get(AGENT_BASE + "/999999999");
    assertThat(other.getContentAsString()).isEqualTo(missing.getContentAsString());
    assertThat(other.getContentAsString()).doesNotContain("Beta");

    // The admin tree is closed to agents entirely.
    assertThat(agent.get(ADMIN_BASE + "/" + beta).getStatus()).isEqualTo(403);
    assertThat(agent.get(ADMIN_BASE).getStatus()).isEqualTo(403);
  }

  @Test
  void agentWithNoOrganisationsSeesNone() throws Exception {
    Browser agent = signedIn(restrictedAgent());
    assertThat(ids(agent.get(AGENT_BASE))).doesNotContain(acme, beta);
    assertThat(agent.get(AGENT_BASE + "/" + acme).getStatus()).isEqualTo(404);
  }

  @Test
  void agentWithAllOrganisationsAndAdminSeeBoth() throws Exception {
    Browser agent = signedIn(activeUser(Role.AGENT));
    assertThat(ids(agent.get(AGENT_BASE))).contains(acme, beta);
    assertThat(agent.get(AGENT_BASE + "/" + beta).getStatus()).isEqualTo(200);

    Browser admin = signedIn(activeUser(Role.ADMIN));
    assertThat(ids(admin.get(AGENT_BASE))).contains(acme, beta);
    assertThat(ids(admin.get(ADMIN_BASE))).contains(acme, beta);
    assertThat(admin.get(ADMIN_BASE + "/" + beta).getStatus()).isEqualTo(200);
  }

  @Test
  void customersCannotReachOrganisationEndpointsAtAll() throws Exception {
    Browser customer = signedIn(activeUser(Role.CUSTOMER, acme));
    assertThat(customer.get(AGENT_BASE).getStatus()).isEqualTo(403);
    assertThat(customer.get(AGENT_BASE + "/" + acme).getStatus()).isEqualTo(403);
    assertThat(customer.get(AGENT_BASE + "/" + beta).getStatus()).isEqualTo(403);
    assertThat(customer.get(ADMIN_BASE + "/" + beta).getStatus()).isEqualTo(403);
  }
}
