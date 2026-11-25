package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.supporter.SupporterAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * WP-11a deactivation wizard on WP-04's real tickets ({@link DeactivationTicketReassignment}): open
 * tickets move to the chosen supporter, or to Unassigned where that supporter cannot work the
 * organisation; Resolved and Closed tickets stay; each move is recorded in the ticket's history and
 * the deactivation's audit event carries the count.
 */
class DeactivationReassignmentIntegrationTest extends TicketTestBase {

  @Autowired SupporterAdminService supporters;

  private Long assignee(long ticketId) {
    return jdbc.queryForObject(
        "SELECT assignee_user_id FROM ticket WHERE id = ?", Long.class, ticketId);
  }

  private int assigneeEvents(long ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ticket_event WHERE ticket_id = ? AND type = 'ASSIGNEE'",
        Integer.class,
        ticketId);
  }

  @Test
  void openTicketsMoveToTheChosenSupporterOrUnassignedAndClosedOnesStay() throws Exception {
    AppUser leaving = activeUser(Role.AGENT);
    long a1 = acmeTicket().ticketId();
    long b1 = betaTicket().ticketId();
    long r1 = acmeTicket().ticketId();
    for (long id : new long[] {a1, b1, r1}) {
      tickets.assign(admin, id, leaving.id());
    }
    jdbc.update("UPDATE ticket SET status = 'RESOLVED' WHERE id = ?", r1);

    Browser b = signedIn(adminUser);
    String base = "/api/v1/admin/supporters/" + leaving.id();
    JsonNode plan = json(b.get(base + "/deactivation").getContentAsString());
    assertThat(plan.get("openTickets").asInt()).isEqualTo(2);
    assertThat(json(b.get(base).getContentAsString()).get("openTickets").asInt()).isEqualTo(2);

    // The target may only work Acme: the Beta ticket goes to Unassigned.
    assertThat(
            b.post(base + "/deactivate", "{\"reassignTo\":" + acmeAgentUser.id() + "}").getStatus())
        .isEqualTo(200);
    assertThat(assignee(a1)).isEqualTo(acmeAgentUser.id());
    assertThat(assignee(b1)).isNull();
    assertThat(assignee(r1)).isEqualTo(leaving.id());
    assertThat(assigneeEvents(a1)).isEqualTo(2);
    assertThat(assigneeEvents(b1)).isEqualTo(2);
    assertThat(assigneeEvents(r1)).isEqualTo(1);
    assertThat(auditEvents("SUPPORTER_DEACTIVATED", leaving.id()).get(0).get("details").toString())
        .contains("\"tickets\": 2")
        .contains("\"reassigned_to\": " + acmeAgentUser.id());
    assertThat(supporters.get(admin, leaving.id()).openTickets()).isZero();
  }

  /** WP-16: each move is one timeline line that says the previous assignee was deactivated. */
  @Test
  void eachMoveIsOneTimelineLineNamingTheDeactivation() throws Exception {
    AppUser leaving = activeUser(Role.AGENT);
    jdbc.update("UPDATE app_user SET name = 'Lee Leaving' WHERE id = ?", leaving.id());
    jdbc.update("UPDATE app_user SET name = 'Kim Agent' WHERE id = ?", acmeAgentUser.id());
    jdbc.update("UPDATE app_user SET name = 'Pat Admin' WHERE id = ?", adminUser.id());
    long a1 = acmeTicket().ticketId();
    long b1 = betaTicket().ticketId();
    tickets.assign(admin, a1, leaving.id());
    tickets.assign(admin, b1, leaving.id());

    Browser b = signedIn(adminUser);
    assertThat(
            b.post(
                    "/api/v1/admin/supporters/" + leaving.id() + "/deactivate",
                    "{\"reassignTo\":" + acmeAgentUser.id() + "}")
                .getStatus())
        .isEqualTo(200);

    var acmeLines = queries.detail(admin, a1).assignmentEntries();
    assertThat(acmeLines).hasSize(2);
    assertThat(acmeLines.get(1).fromUserId()).isEqualTo(leaving.id());
    assertThat(acmeLines.get(1).toUserId()).isEqualTo(acmeAgentUser.id());
    assertThat(acmeLines.get(1).actorUserId()).isEqualTo(adminUser.id());
    assertThat(acmeLines.get(1).reason()).isEqualTo("DEACTIVATED");
    assertThat(acmeLines.get(1).text())
        .isEqualTo(
            "Reassigned from Lee Leaving to Kim Agent (Lee Leaving deactivated) by Pat Admin");
    var betaLines = queries.detail(admin, b1).assignmentEntries();
    assertThat(betaLines).hasSize(2);
    assertThat(betaLines.get(1).text())
        .isEqualTo("Unassigned from Lee Leaving (Lee Leaving deactivated) by Pat Admin");
  }

  private String status(long ticketId) {
    return jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, ticketId);
  }

  /**
   * Audit PR #11 L1: moving a leaving supporter's tickets to yourself is not a pick-up - a New
   * ticket stays New (and Pending stays Pending). WP-04's own assign-to-self still picks up.
   */
  @Test
  void reassigningToYourselfKeepsTheStatusInsteadOfPickingUp() {
    AppUser leaving = activeUser(Role.AGENT);
    long fresh = acmeTicket().ticketId();
    long pending = acmeTicket().ticketId();
    tickets.assign(admin, fresh, leaving.id());
    tickets.assign(admin, pending, leaving.id());
    jdbc.update("UPDATE ticket SET status = 'PENDING_ON_CLIENT' WHERE id = ?", pending);
    assertThat(status(fresh)).isEqualTo("NEW");

    supporters.deactivate(admin, leaving.id(), adminUser.id(), null);

    assertThat(assignee(fresh)).isEqualTo(adminUser.id());
    assertThat(assignee(pending)).isEqualTo(adminUser.id());
    assertThat(status(fresh)).isEqualTo("NEW");
    assertThat(status(pending)).isEqualTo("PENDING_ON_CLIENT");
    assertThat(assigneeEvents(fresh)).isEqualTo(2);

    // The normal assign path (WP-04) is unchanged: assigning a New ticket to yourself picks it up.
    long other = acmeTicket().ticketId();
    tickets.assign(admin, other, adminUser.id());
    assertThat(status(other)).isEqualTo("OPEN");
  }

  @Test
  void unassignedClearsEveryOpenTicket() throws Exception {
    AppUser leaving = activeUser(Role.AGENT);
    long a1 = acmeTicket().ticketId();
    tickets.assign(admin, a1, leaving.id());
    supporters.deactivate(AccessScope.admin(adminUser.id()), leaving.id(), null, null);
    assertThat(assignee(a1)).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM app_user WHERE id = ?", String.class, leaving.id()))
        .isEqualTo("DEACTIVATED");
  }
}
