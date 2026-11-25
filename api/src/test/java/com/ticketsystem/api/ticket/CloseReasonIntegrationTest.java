package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TimelineEntryView;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * ADR-041: closing a ticket by hand needs a reason (Other needs a note), Duplicate goes through
 * "mark as duplicate", automatic closes record their own reason, the reason is on the timeline, the
 * agent API and the CSV export (never a client), and only an admin can reopen a closed ticket
 * (step-up, reason, audit event, timeline line).
 */
class CloseReasonIntegrationTest extends TicketTestBase {

  private long resolvedTicket() {
    long id = acmeTicket().ticketId();
    forceStatus(id, TicketStatus.RESOLVED);
    return id;
  }

  private static String status(String status, String reason, String note) {
    StringBuilder b = new StringBuilder("{\"status\":").append(Browser.q(status));
    if (reason != null) {
      b.append(",\"closeReason\":").append(Browser.q(reason));
    }
    if (note != null) {
      b.append(",\"closeNote\":").append(Browser.q(note));
    }
    return b.append('}').toString();
  }

  private static String statusPath(long id) {
    return "/api/v1/agent/tickets/" + id + "/status";
  }

  private static String reopenPath(long id) {
    return "/api/v1/admin/tickets/" + id + "/reopen";
  }

  private Map<String, Object> closeColumns(long id) {
    return jdbc.queryForMap("SELECT close_reason, close_note FROM ticket WHERE id = ?", id);
  }

  private List<String> timeline(AccessScope scope, long id) {
    return queries.detail(scope, id).timelineEntries().stream()
        .map(TimelineEntryView::text)
        .toList();
  }

  private static void assertError(MockHttpServletResponse r, int status, String code)
      throws Exception {
    assertThat(r.getStatus()).isEqualTo(status);
    JsonNode body = json(r.getContentAsString());
    assertThat(body.get("error").asString()).isEqualTo(code);
    assertThat(body.get("message").asString()).isNotBlank();
  }

  private void stepUp(Browser b, AppUser who) throws Exception {
    assertThat(
            b.post("/api/v1/auth/step-up", "{\"code\":" + Browser.q(totp(who)) + "}").getStatus())
        .isEqualTo(200);
  }

  // ---------------------------------------------------------------------------------------------
  // Manual close

  @Test
  void aManualCloseNeedsAManualReason() throws Exception {
    long id = resolvedTicket();
    Browser agent = signedIn(acmeAgentUser);

    assertError(
        agent.post(statusPath(id), status("CLOSED", null, null)), 400, "close_reason_required");
    assertError(
        agent.post(statusPath(id), status("CLOSED", " ", null)), 400, "close_reason_required");
    assertError(
        agent.post(statusPath(id), status("CLOSED", "NOT_A_REASON", null)),
        400,
        "close_reason_required");
    // A system reason cannot be chosen by hand.
    assertError(
        agent.post(statusPath(id), status("CLOSED", "AUTO_CLOSED_NO_REPLY", null)),
        400,
        "close_reason_required");
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
    // The service refuses it too (the QA seeder and any other caller).
    assertThatThrownBy(() -> tickets.changeStatus(acmeAgent, id, TicketStatus.CLOSED))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo("close_reason_required");
    // Other statuses need no reason, and a reason sent with them is ignored.
    assertThat(agent.post(statusPath(id), status("OPEN", "SPAM", null)).getStatus()).isEqualTo(200);
    assertThat(closeColumns(id).get("close_reason")).isNull();
  }

  @Test
  void otherNeedsANoteOfThreeTo500Characters() throws Exception {
    long id = resolvedTicket();
    Browser agent = signedIn(acmeAgentUser);

    assertError(
        agent.post(statusPath(id), status("CLOSED", "OTHER", null)), 400, "close_note_required");
    assertError(
        agent.post(statusPath(id), status("CLOSED", "OTHER", " ab ")), 400, "close_note_required");
    assertError(
        agent.post(statusPath(id), status("CLOSED", "OTHER", "x".repeat(501))),
        400,
        "close_note_required");
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);

    MockHttpServletResponse ok =
        agent.post(statusPath(id), status("CLOSED", "other", "  Moved to the new contract  "));
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(json(ok.getContentAsString()).get("status").asString()).isEqualTo("CLOSED");
    assertThat(closeColumns(id))
        .containsEntry("close_reason", "OTHER")
        .containsEntry("close_note", "Moved to the new contract");
    assertThat(timeline(admin, id))
        .contains("Closed by " + acmeAgentUser.name() + ": Other - Moved to the new contract");
  }

  @Test
  void aNoteWithAnotherReasonIsIgnored() throws Exception {
    long id = resolvedTicket();
    Browser agent = signedIn(acmeAgentUser);
    assertThat(
            agent
                .post(statusPath(id), status("CLOSED", "CLIENT_CANCELLED", "not kept"))
                .getStatus())
        .isEqualTo(200);
    assertThat(closeColumns(id))
        .containsEntry("close_reason", "CLIENT_CANCELLED")
        .containsEntry("close_note", null);
    assertThat(timeline(admin, id))
        .contains("Closed by " + acmeAgentUser.name() + ": Client cancelled / no longer needed");
  }

  @Test
  void duplicateThroughTheStatusEndpointPointsToMarkAsDuplicate() throws Exception {
    long id = resolvedTicket();
    Browser agent = signedIn(acmeAgentUser);
    assertError(
        agent.post(statusPath(id), status("CLOSED", "DUPLICATE", null)), 400, "use_mark_duplicate");
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
  }

  @Test
  void aPublicReplyCannotCloseTheTicket() throws Exception {
    long id = resolvedTicket();
    Browser agent = signedIn(acmeAgentUser);
    // ADR-040 (PR #48): an agent-created ticket already starts with the agent's opening message,
    // so compare the count before and after instead of expecting zero.
    long before = supporterComments(id);
    MockHttpServletResponse r =
        agent.post(
            "/api/v1/agent/tickets/" + id + "/public-replies",
            "{\"bodyHtml\":\"<p>Done</p>\",\"attachmentIds\":[],\"thenStatus\":\"CLOSED\"}");
    assertError(r, 400, "close_reason_required");
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
    assertThat(supporterComments(id)).isEqualTo(before);
  }

  @Test
  void spamIsStoredShownToAgentsAndTheAdminCanBlockTheSender() throws Exception {
    long id = resolvedTicket();
    long mailBefore =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_message WHERE ticket_id = ?", Long.class, id);
    Browser agent = signedIn(acmeAgentUser);
    assertThat(agent.post(statusPath(id), status("CLOSED", "SPAM", null)).getStatus())
        .isEqualTo(200);

    JsonNode detail = json(agent.get("/api/v1/agent/tickets/" + id).getContentAsString());
    assertThat(detail.get("closeReason").asString()).isEqualTo("SPAM");
    assertThat(detail.get("closeReasonLabel").asString()).isEqualTo("Spam / junk");
    assertThat(detail.get("allowedStatuses").isEmpty()).isTrue();
    assertThat(timeline(admin, id)).contains("Closed by " + acmeAgentUser.name() + ": Spam / junk");
    // Closing sends the client nothing.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE ticket_id = ?", Long.class, id))
        .isEqualTo(mailBefore);

    // "Block this sender" is the admin block list: agents are refused, the admin adds it.
    String rule =
        "{\"kind\":\"ADDRESS\",\"value\":\"promo@spam-sender.example\",\"confirm\":false}";
    assertThat(agent.post("/api/v1/admin/email/block-rules", rule).getStatus()).isEqualTo(403);
    Browser adminBrowser = signedIn(adminUser);
    assertThat(adminBrowser.post("/api/v1/admin/email/block-rules", rule).getStatus())
        .isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mail_block_rule WHERE value = 'promo@spam-sender.example'",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  void aCloseReasonIsNeverInAClientResponse() throws Exception {
    long id = resolvedTicket();
    tickets.changeStatus(acmeAgent, id, TicketStatus.CLOSED, "OTHER", "Private reason text");
    Browser customer = signedIn(acmeCustomerUser);
    assertThat(customer.get("/api/v1/agent/tickets/" + id).getStatus()).isEqualTo(403);
    assertThat(customer.get("/api/v1/agent/tickets/export").getStatus()).isEqualTo(403);
    MockHttpServletResponse reopen = customer.post(reopenPath(id), "{\"reason\":\"Please\"}");
    assertThat(reopen.getStatus()).isEqualTo(403);
    assertThat(reopen.getContentAsString()).doesNotContain("Private reason text");
    assertThat(statusOf(id)).isEqualTo(TicketStatus.CLOSED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE ticket_id = ? AND (html_body LIKE ?"
                    + " OR text_body LIKE ?)",
                Long.class,
                id,
                "%Private reason%",
                "%Private reason%"))
        .isZero();
  }

  @Test
  void anotherOrganisationsTicketCannotBeClosed() throws Exception {
    long id = betaTicket().ticketId();
    forceStatus(id, TicketStatus.RESOLVED);
    Browser agent = signedIn(acmeAgentUser);
    assertThat(agent.post(statusPath(id), status("CLOSED", "SPAM", null)).getStatus())
        .isEqualTo(404);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.RESOLVED);
    assertThat(closeColumns(id).get("close_reason")).isNull();
  }

  // ---------------------------------------------------------------------------------------------
  // Automatic closes and export

  @Test
  void automaticClosesRecordSystemReasons() {
    long auto = resolvedTicket();
    tickets.applySystemTrigger(AccessScope.system("test auto-close"), auto, Trigger.AUTO_CLOSE);
    assertThat(statusOf(auto)).isEqualTo(TicketStatus.CLOSED);
    assertThat(closeColumns(auto)).containsEntry("close_reason", "AUTO_CLOSED_NO_REPLY");
    assertThat(timeline(admin, auto))
        .contains("Closed automatically: No client reply after it was resolved");

    long source = acmeTicket().ticketId();
    long target = acmeTicket().ticketId();
    tickets.markDuplicate(acmeAgent, source, target);
    assertThat(closeColumns(source)).containsEntry("close_reason", "DUPLICATE");
    assertThat(
            jdbc.queryForObject(
                "SELECT close_reason FROM ticket_event WHERE ticket_id = ? AND type = 'STATUS'",
                String.class,
                source))
        .isEqualTo("DUPLICATE");
    assertThat(timeline(admin, source))
        .anyMatch(
            t ->
                t.startsWith("Marked as a duplicate of ")
                    && t.endsWith("(close reason: Duplicate)"));
  }

  @Test
  void theExportHasACloseReasonColumn() throws Exception {
    long spam = resolvedTicket();
    tickets.changeStatus(acmeAgent, spam, TicketStatus.CLOSED, "SPAM", null);
    long other = resolvedTicket();
    tickets.changeStatus(acmeAgent, other, TicketStatus.CLOSED, "OTHER", "Waiting, on a contract");
    long open = acmeTicket().ticketId();

    Browser agent = signedIn(acmeAgentUser);
    String csv =
        agent
            .get("/api/v1/agent/tickets/export?organisationId=" + acme + "&includeClosed=true")
            .getContentAsString();
    String[] lines = csv.split("\r\n");
    assertThat(lines[0]).endsWith(",SLA met,Close reason");
    String spamRef = references.referencesOf(admin, spam).get(0).toString();
    String otherRef = references.referencesOf(admin, other).get(0).toString();
    String openRef = references.referencesOf(admin, open).get(0).toString();
    for (String line : lines) {
      if (line.startsWith(spamRef + ",")) {
        assertThat(line).endsWith(",Spam / junk");
      } else if (line.startsWith(otherRef + ",")) {
        // Quoted (it has a comma); every cell is neutralised against formulas.
        assertThat(line).endsWith(",\"Other - Waiting, on a contract\"");
      } else if (line.startsWith(openRef + ",")) {
        assertThat(line).endsWith(",");
      }
    }
    assertThat(csv).contains(spamRef).contains(otherRef).contains(openRef);
  }

  // ---------------------------------------------------------------------------------------------
  // Admin reopen

  @Test
  void anAdminReopensWithStepUpAndAReason() throws Exception {
    long id = resolvedTicket();
    tickets.changeStatus(acmeAgent, id, TicketStatus.CLOSED, "NO_RESPONSE", null);
    Browser b = signedIn(adminUser);
    clock.advance(Duration.ofMinutes(11)); // past the step-up window the sign-in opened
    int auditBefore = auditEvents("TICKET_REOPENED", null).size();

    assertError(
        b.post(reopenPath(id), "{\"reason\":\"Client came back\"}"), 403, "step_up_required");
    assertThat(statusOf(id)).isEqualTo(TicketStatus.CLOSED);

    stepUp(b, adminUser);
    assertError(b.post(reopenPath(id), "{}"), 400, "reopen_reason_required");
    assertError(b.post(reopenPath(id), "{\"reason\":\"ab\"}"), 400, "reopen_reason_required");
    assertError(
        b.post(reopenPath(id), "{\"reason\":" + Browser.q("y".repeat(501)) + "}"),
        400,
        "reopen_reason_required");

    MockHttpServletResponse ok = b.post(reopenPath(id), "{\"reason\":\"Client came back\"}");
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(json(ok.getContentAsString()).get("status").asString()).isEqualTo("OPEN");
    assertThat(statusOf(id)).isEqualTo(TicketStatus.OPEN);
    assertThat(closeColumns(id))
        .containsEntry("close_reason", null)
        .containsEntry("close_note", null);
    assertThat(timeline(admin, id))
        .contains("Closed by " + acmeAgentUser.name() + ": No response from client")
        .contains("Reopened by " + adminUser.name() + ": Client came back");

    List<Map<String, Object>> events = auditEvents("TICKET_REOPENED", null);
    assertThat(events).hasSize(auditBefore + 1);
    Map<String, Object> e = events.get(events.size() - 1);
    assertThat(((Number) e.get("actor_user_id")).longValue()).isEqualTo(adminUser.id());
    assertThat(e.get("details").toString())
        .contains("\"ticket\": " + id)
        .contains("\"organisation\"")
        .doesNotContain("Client came back");

    // A reopened ticket accepts changes again, and can be closed again with a new reason.
    tickets.changePriority(acmeAgent, id, Priority.P1);
    tickets.changeStatus(acmeAgent, id, TicketStatus.RESOLVED);
    tickets.changeStatus(acmeAgent, id, TicketStatus.CLOSED, "RESOLVED_NO_FOLLOW_UP", null);
    assertThat(timeline(admin, id))
        .contains("Closed by " + acmeAgentUser.name() + ": No response from client")
        .contains("Closed by " + acmeAgentUser.name() + ": Resolved - no follow-up needed");
  }

  @Test
  void agentsAndEngineersCannotReopen() throws Exception {
    long id = resolvedTicket();
    tickets.changeStatus(acmeAgent, id, TicketStatus.CLOSED, "SPAM", null);
    AppUser engineer = activeUser(Role.AGENT);
    int auditBefore = auditEvents("TICKET_REOPENED", null).size();
    for (AppUser who : List.of(acmeAgentUser, engineer)) {
      Browser b = signedIn(who);
      stepUp(b, who);
      assertThat(b.post(reopenPath(id), "{\"reason\":\"Let me in\"}").getStatus())
          .as(who.email())
          .isEqualTo(403);
      // Nor through the status endpoint: Closed is read-only for them.
      assertThat(b.post(statusPath(id), status("OPEN", null, null)).getStatus()).isEqualTo(409);
    }
    // The service refuses a non-admin scope too (defence in depth).
    assertThatThrownBy(() -> tickets.adminReopen(acmeAgent, id, "Let me in", "127.0.0.1"))
        .isInstanceOf(ApiException.class);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.CLOSED);
    assertThat(auditEvents("TICKET_REOPENED", null)).hasSize(auditBefore);
  }

  @Test
  void onlyAClosedTicketThatIsNotAMergedDuplicateCanBeReopened() {
    long open = acmeTicket().ticketId();
    assertThatThrownBy(() -> tickets.adminReopen(admin, open, "Not closed", null))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo("not_closed");

    long source = acmeTicket().ticketId();
    long target = acmeTicket().ticketId();
    tickets.markDuplicate(acmeAgent, source, target);
    assertThatThrownBy(() -> tickets.adminReopen(admin, source, "Wrong merge", null))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo("duplicate_cannot_reopen");
    assertThat(statusOf(source)).isEqualTo(TicketStatus.CLOSED);
  }

  @Test
  void aClientReplyToAStillClosedTicketStillOpensAFollowUp() {
    long id = resolvedTicket();
    tickets.changeStatus(acmeAgent, id, TicketStatus.CLOSED, "RESOLVED_NO_FOLLOW_UP", null);
    TicketService.ReplyResult r =
        tickets.addClientReply(
            admin,
            id,
            new TicketService.ClientReply(
                acmeCustomerUser.id(),
                acmeCustomerUser.email(),
                "<p>One more thing</p>",
                true,
                null,
                null));
    assertThat(r.followUpCreated()).isTrue();
    assertThat(r.ticketId()).isNotEqualTo(id);
    assertThat(statusOf(id)).isEqualTo(TicketStatus.CLOSED);
  }

  private long supporterComments(long ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM public_comment WHERE ticket_id = ? AND author_kind = 'SUPPORTER'",
        Long.class,
        ticketId);
  }
}
