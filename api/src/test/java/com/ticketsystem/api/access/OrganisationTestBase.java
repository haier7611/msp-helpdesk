package com.ticketsystem.api.access;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.AuthIntegrationTestBase;
import com.ticketsystem.api.auth.Role;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Helpers for the WP-03 integration tests: agents limited to some organisations, placeholder
 * tickets for reference tests (a real row, since WP-04's foreign key) and JSON reading.
 * Organisation names are synthetic ("Acme Dental", "Beta Clinic") with a random suffix so tests
 * sharing one database do not clash.
 */
public abstract class OrganisationTestBase extends AuthIntegrationTestBase {

  protected static final JsonMapper JSON = new JsonMapper();

  /**
   * A fresh placeholder ticket (a bare triage row, no reference) for tests that issue references by
   * hand; returns its id.
   */
  protected long newTicketId() {
    return jdbc.queryForObject(
        "INSERT INTO ticket (request_type_id, priority, status, source, subject, created_at,"
            + " updated_at, status_changed_at) SELECT id, 'P3', 'NEW', 'AGENT', 'Placeholder',"
            + " now(), now(), now() FROM request_type WHERE name = 'Other or general question' RETURNING id",
        Long.class);
  }

  /** An active agent who may work only {@code organisationIds}. */
  protected AppUser restrictedAgent(Long... organisationIds) {
    AppUser agent = activeUser(Role.AGENT);
    jdbc.update("UPDATE app_user SET all_organisations = FALSE WHERE id = ?", agent.id());
    for (Long org : organisationIds) {
      jdbc.update(
          "INSERT INTO supporter_organisation (user_id, organisation_id, created_at)"
              + " VALUES (?, ?, now())",
          agent.id(),
          org);
    }
    return agent;
  }

  protected static AccessScope restrictedScope(AppUser agent, Long... organisationIds) {
    return AccessScope.agent(agent.id(), false, Set.of(organisationIds));
  }

  protected static JsonNode json(String body) {
    return JSON.readTree(body);
  }

  /** The active prefix of an organisation, read directly. */
  protected String activePrefixOf(long organisationId) {
    return jdbc.queryForObject(
        "SELECT prefix FROM organisation_prefix WHERE organisation_id = ? AND status = 'ACTIVE'",
        String.class,
        organisationId);
  }
}
