package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.ticket.ReplyCcService.ReplyCc;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.ParticipantView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TimelineEntryView;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * ADR-035: the CC an agent chooses in the reply composer. Every rule on the server (same
 * organisation at once, outside addresses only with confirmation, max 10, valid plain addresses,
 * other accounts refused without naming their organisation, restricted agents scoped), the reply's
 * To and CC in the queued email (kept vs this reply only), and the WP-18 timeline lines.
 */
class ReplyCcIntegrationTest extends TicketTestBase {

  @Autowired TicketParticipantRepository participantRepository;

  private String domain;
  private AccessScope poller;
  private long ticketId;

  @BeforeEach
  void data() {
    domain = "acme-" + UUID.randomUUID().toString().substring(0, 8) + ".example";
    jdbc.update(
        "INSERT INTO organisation_domain (domain, organisation_id, created_at) VALUES (?, ?,"
            + " now())",
        domain,
        acme);
    jdbc.update("UPDATE app_user SET name = 'Kim Agent' WHERE id = ?", acmeAgentUser.id());
    poller = AccessScope.system("test: email poller");
    ticketId = acmeTicket().ticketId();
  }

  private AppUser acmeContact(String name) {
    AppUser u = activeUser(Role.CUSTOMER, acme);
    jdbc.update("UPDATE app_user SET name = ? WHERE id = ?", name, u.id());
    return u;
  }

  private long reply(AccessScope scope, ReplyCc cc) {
    return tickets.addPublicReply(scope, ticketId, "<p>Update</p>", List.of(), null, cc);
  }

  private static ReplyCc keep(String... recipients) {
    return new ReplyCc(List.of(recipients), true, List.of());
  }

  private static ReplyCc once(String... recipients) {
    return new ReplyCc(List.of(recipients), false, List.of());
  }

  private Map<String, Object> lastClientMail() {
    return jdbc.queryForMap(
        "SELECT to_addresses, cc_addresses FROM outbox_message WHERE ticket_id = ? AND kind ="
            + " 'client_facing' ORDER BY id DESC LIMIT 1",
        ticketId);
  }

  private List<String> lastCc() {
    String cc = (String) lastClientMail().get("cc_addresses");
    return cc == null ? List.of() : Arrays.asList(cc.split(","));
  }

  private List<ParticipantView> participantsNow() {
    return queries.detail(admin, ticketId).participants();
  }

  private List<String> participantLines() {
    return queries.detail(admin, ticketId).timelineEntries().stream()
        .filter(e -> e.type().startsWith("PARTICIPANT_"))
        .map(TimelineEntryView::text)
        .toList();
  }

  private String errorCode(Runnable call) {
    try {
      call.run();
    } catch (ApiException e) {
      return e.code();
    }
    throw new AssertionError("expected a refusal");
  }

  private int comments() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM public_comment WHERE ticket_id = ?", Integer.class, ticketId);
  }

  // ---------------------------------------------------------------------------------------------
  // Same organisation, kept

  @Test
  void aSameOrganisationContactIsAddedAtOnceKeptAndEmailedInCc() {
    AppUser sam = acmeContact("Sam Okoye");
    reply(acmeAgent, keep(sam.email()));

    assertThat(participantsNow())
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.userId()).isEqualTo(sam.id());
              assertThat(p.confirmed()).isTrue();
              assertThat(p.name()).isEqualTo("Sam Okoye");
            });
    assertThat(participantLines()).containsExactly("Sam Okoye added as CC by Kim Agent");
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT actor_kind, actor_user_id, reason FROM ticket_event WHERE ticket_id = ? AND"
                + " type = 'PARTICIPANT_ADDED'",
            ticketId);
    assertThat(row.get("actor_kind")).isEqualTo("AGENT");
    assertThat(((Number) row.get("actor_user_id")).longValue()).isEqualTo(acmeAgentUser.id());
    assertThat(row.get("reason")).isEqualTo("AGENT_ADDED");

    assertThat(lastClientMail().get("to_addresses"))
        .isEqualTo(acmeCustomerUser.email().toLowerCase());
    assertThat(lastCc()).containsExactly(sam.email().toLowerCase());

    // Kept: the next reply without a choice goes to the participants, Sam included.
    reply(acmeAgent, null);
    assertThat(lastCc()).containsExactly(sam.email().toLowerCase());
  }

  @Test
  void anAddressOnAVerifiedDomainIsAddedAtOnce() {
    String reception = "reception@" + domain;
    reply(acmeAgent, keep(reception.toUpperCase()));
    assertThat(participantsNow())
        .extracting(ParticipantView::email, ParticipantView::confirmed)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(reception, true));
    assertThat(participantLines()).containsExactly(reception + " added as CC by Kim Agent");
    assertThat(lastCc()).containsExactly(reception);
  }

  @Test
  void theReporterInCcIsIgnored() {
    reply(acmeAgent, keep(acmeCustomerUser.email()));
    assertThat(participantsNow()).isEmpty();
    assertThat(lastCc()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Outside addresses

  @Test
  void anOutsideAddressNeedsConfirmationAndNothingIsStoredWithoutIt() {
    int before = comments();
    assertThatThrownBy(() -> reply(acmeAgent, keep("pat@outside.example")))
        .isInstanceOf(ApiException.class)
        .hasMessage(
            "pat@outside.example is not from " + orgName(acme) + ". Confirm to add them anyway.")
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo("external_cc_unconfirmed");
    assertThat(comments()).isEqualTo(before);
    assertThat(participantsNow()).isEmpty();
    assertThat(participantLines()).isEmpty();
  }

  @Test
  void theAgentsConfirmationAddsAnOutsideAddressAsConfirmed() {
    reply(
        acmeAgent,
        new ReplyCc(List.of("pat@outside.example"), true, List.of("PAT@outside.example")));
    assertThat(participantsNow())
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.email()).isEqualTo("pat@outside.example");
              assertThat(p.confirmed()).isTrue();
            });
    assertThat(participantLines())
        .containsExactly(
            "pat@outside.example added as CC (outside address, confirmed) by Kim Agent");
    assertThat(lastCc()).containsExactly("pat@outside.example");
  }

  @Test
  void confirmingAnUnconfirmedParticipantFromAnEmailCountsAsTheExternalConfirmation() {
    participantRepository.add(
        poller, acme, ticketId, null, "it@vendor.example", false, clock.instant());
    reply(acmeAgent, null);
    assertThat(lastCc()).isEmpty(); // unconfirmed: no mail

    reply(acmeAgent, new ReplyCc(List.of("it@vendor.example"), true, List.of("it@vendor.example")));
    assertThat(participantsNow()).extracting(ParticipantView::confirmed).containsExactly(true);
    assertThat(participantLines())
        .contains("it@vendor.example confirmed as CC (outside address) by Kim Agent");
    assertThat(lastCc()).containsExactly("it@vendor.example");
  }

  // ---------------------------------------------------------------------------------------------
  // Keep off: this reply only

  @Test
  void keepOffCcsThisReplyOnlyAndLeavesTheParticipantsAlone() {
    AppUser sam = acmeContact("Sam Okoye");
    AppUser lee = acmeContact("Lee Park");
    reply(acmeAgent, keep(sam.email()));

    long commentId =
        reply(
            acmeAgent,
            new ReplyCc(
                List.of(sam.email(), lee.email(), "pat@outside.example"),
                false,
                List.of("pat@outside.example")));
    assertThat(lastCc())
        .containsExactlyInAnyOrder(
            sam.email().toLowerCase(), lee.email().toLowerCase(), "pat@outside.example");
    assertThat(participantsNow()).extracting(ParticipantView::userId).containsExactly(sam.id());
    assertThat(participantLines())
        .containsExactly(
            "Sam Okoye added as CC by Kim Agent",
            "Lee Park CC'd on this reply only by Kim Agent",
            "pat@outside.example CC'd on this reply only (outside address, confirmed) by Kim Agent");
    assertThat(
            jdbc.queryForList(
                "SELECT email FROM public_comment_cc WHERE comment_id = ? AND one_off ORDER BY"
                    + " email",
                String.class,
                commentId))
        .containsExactlyInAnyOrder(lee.email().toLowerCase(), "pat@outside.example");

    // The next reply goes to the participants only.
    reply(acmeAgent, null);
    assertThat(lastCc()).containsExactly(sam.email().toLowerCase());
  }

  @Test
  void removingWithKeepOffLeavesThemOutOfThisReplyOnly() {
    AppUser sam = acmeContact("Sam Okoye");
    reply(acmeAgent, keep(sam.email()));
    reply(acmeAgent, once());
    assertThat(lastCc()).isEmpty();
    assertThat(participantsNow()).hasSize(1);
    assertThat(participantLines())
        .containsExactly(
            "Sam Okoye added as CC by Kim Agent",
            "Sam Okoye left out of CC on this reply only by Kim Agent");
    reply(acmeAgent, null);
    assertThat(lastCc()).containsExactly(sam.email().toLowerCase());
  }

  @Test
  void removingWithKeepOnRemovesTheParticipant() {
    AppUser sam = acmeContact("Sam Okoye");
    reply(acmeAgent, keep(sam.email(), "reception@" + domain));
    reply(acmeAgent, keep("reception@" + domain));
    assertThat(participantsNow())
        .extracting(ParticipantView::email)
        .containsExactly("reception@" + domain);
    assertThat(participantLines()).contains("Sam Okoye removed from CC by Kim Agent");
    assertThat(lastCc()).containsExactly("reception@" + domain);
  }

  // ---------------------------------------------------------------------------------------------
  // Limits and syntax

  @Test
  void moreThanTenOnAReplyIsRefused() {
    List<String> eleven = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      eleven.add("person" + i + "@" + domain);
    }
    assertThat(errorCode(() -> reply(acmeAgent, new ReplyCc(eleven, false, List.of()))))
        .isEqualTo("cc_limit");
  }

  @Test
  void aTicketCannotGrowPastTenParticipants() {
    for (int i = 0; i < 10; i++) {
      participantRepository.add(
          poller, acme, ticketId, null, "p" + i + "@vendor.example", false, clock.instant());
    }
    assertThat(errorCode(() -> reply(acmeAgent, keep("one.more@" + domain)))).isEqualTo("cc_limit");
    // This reply only does not grow the ticket.
    reply(acmeAgent, once("one.more@" + domain));
    assertThat(lastCc()).containsExactly("one.more@" + domain);
  }

  @Test
  void invalidOrHeaderBreakingAddressesAreRefused() {
    for (String bad :
        List.of(
            "not an address",
            "",
            "a@b",
            "pat@outside.example>, evil@attacker.example",
            "\"Pat\" <pat@outside.example>",
            "pat@outside.example\r\nBcc: evil@attacker.example")) {
      assertThat(errorCode(() -> reply(acmeAgent, keep(bad)))).as(bad).isEqualTo("invalid_request");
    }
    assertThat(participantsNow()).isEmpty();
  }

  @Test
  void notOnTriageTickets() {
    long triage = triageTicket().ticketId();
    assertThat(
            errorCode(
                () ->
                    tickets.addPublicReply(
                        admin, triage, "<p>Hi</p>", List.of(), null, keep("a@" + domain))))
        .isEqualTo("invalid_request");
  }

  // ---------------------------------------------------------------------------------------------
  // Other accounts and scoping

  /** The same text with the address replaced, to compare two answers about different addresses. */
  private static String masked(String body, String address) {
    return body.replace(address.toLowerCase(), "<address>").replace(address, "<address>");
  }

  @Test
  void anotherOrganisationsContactIsAnsweredExactlyLikeAnUnknownOutsideAddress() throws Exception {
    // AUD-40 Low: the answers must not tell an agent that an address is registered elsewhere.
    AppUser formerBeta = activeUser(Role.CUSTOMER, beta);
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", formerBeta.id());
    String unknown = "pat@outside.example";
    Browser agent = signedIn(acmeAgentUser);
    String replies = "/api/v1/agent/tickets/" + ticketId + "/public-replies";

    for (String account : List.of(betaCustomerUser.email(), formerBeta.email())) {
      // Early check from the CC editor.
      var checkAccount =
          agent.post(
              "/api/v1/agent/tickets/" + ticketId + "/cc-check", "{\"email\":\"" + account + "\"}");
      var checkUnknown =
          agent.post(
              "/api/v1/agent/tickets/" + ticketId + "/cc-check", "{\"email\":\"" + unknown + "\"}");
      assertThat(checkAccount.getStatus()).as(account).isEqualTo(checkUnknown.getStatus());
      assertThat(checkAccount.getContentAsString())
          .as(account)
          .isEqualTo(checkUnknown.getContentAsString())
          .isEqualTo("{\"result\":\"external\"}");

      // Sending without confirmation: the same refusal, word for word apart from the address.
      String noConfirm = "{\"bodyHtml\":\"<p>Hi</p>\",\"cc\":{\"recipients\":[\"%s\"]}}";
      var refusedAccount = agent.post(replies, noConfirm.formatted(account));
      var refusedUnknown = agent.post(replies, noConfirm.formatted(unknown));
      assertThat(refusedAccount.getStatus()).as(account).isEqualTo(400);
      assertThat(refusedUnknown.getStatus()).isEqualTo(400);
      assertThat(masked(refusedAccount.getContentAsString(), account))
          .as(account)
          .isEqualTo(masked(refusedUnknown.getContentAsString(), unknown))
          .contains("external_cc_unconfirmed")
          .doesNotContain(orgName(beta));
    }

    // Confirmed: both are accepted the same way and look the same on the ticket.
    int before = comments();
    String betaAddress = betaCustomerUser.email().toLowerCase();
    String confirmed =
        "{\"bodyHtml\":\"<p>Hi</p>\",\"cc\":{\"recipients\":[%s],\"keepOnFutureReplies\":true,"
            + "\"confirmExternal\":[\"%s\"]}}";
    assertThat(
            agent
                .post(replies, confirmed.formatted("\"" + betaAddress + "\"", betaAddress))
                .getStatus())
        .isEqualTo(201);
    assertThat(lastCc()).containsExactly(betaAddress); // emailed as an outside CC
    assertThat(
            agent
                .post(
                    replies,
                    confirmed.formatted("\"" + betaAddress + "\",\"" + unknown + "\"", unknown))
                .getStatus())
        .isEqualTo(201);
    assertThat(comments()).isEqualTo(before + 2);
    assertThat(participantsNow())
        .extracting(ParticipantView::email, ParticipantView::userId, ParticipantView::name)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(betaAddress, null, null),
            org.assertj.core.groups.Tuple.tuple(unknown, null, null));
    assertThat(participantLines())
        .containsExactly(
            betaAddress + " added as CC (outside address, confirmed) by Kim Agent",
            unknown + " added as CC (outside address, confirmed) by Kim Agent");

    // Emailed like any outside CC (ADR-050 change 2026-11-24): on the reply and later through the
    // participant list (the path the Resolved notice uses too).
    assertThat(lastCc()).containsExactlyInAnyOrder(betaAddress, unknown);
    reply(acmeAgent, null);
    assertThat(lastCc()).containsExactlyInAnyOrder(betaAddress, unknown);

    // Never linked to their account: the participant and the reply's CC row carry no user id, and
    // the contact gets no access to the ticket (customers have no ticket view at all, ADR-018).
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_participant WHERE ticket_id = ? AND user_id IS NOT"
                    + " NULL",
                Integer.class,
                ticketId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM public_comment_cc c JOIN public_comment p ON p.id ="
                    + " c.comment_id WHERE p.ticket_id = ? AND c.user_id IS NOT NULL",
                Integer.class,
                ticketId))
        .isZero();
    Browser contact = signedIn(betaCustomerUser);
    assertThat(contact.get("/api/v1/agent/tickets/" + ticketId).getStatus()).isEqualTo(403);
  }

  @Test
  void agentAccountsDeactivatedContactsAndSupportAddressesAreRefused() {
    AppUser gone = acmeContact("Former Person");
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", gone.id());
    for (String address : List.of(adminUser.email(), gone.email())) {
      assertThat(
              errorCode(
                  () -> reply(acmeAgent, new ReplyCc(List.of(address), true, List.of(address)))))
          .as(address)
          .isEqualTo("cc_not_allowed");
    }
  }

  @Test
  void aRestrictedAgentCannotReplyWithCcOnAnotherOrganisationsTicket() {
    long betaId = betaTicket().ticketId();
    assertThat(
            errorCode(
                () ->
                    tickets.addPublicReply(
                        acmeAgent, betaId, "<p>Hi</p>", List.of(), null, keep("x@" + domain))))
        .isEqualTo("not_found");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_participant WHERE ticket_id = ?",
                Integer.class,
                betaId))
        .isZero();
  }

  @Test
  void suggestionsOnlyShowContactsOfOrganisationsTheAgentWorks() throws Exception {
    AppUser sam = acmeContact("Sam Okoye");
    Browser agent = signedIn(acmeAgentUser);
    JsonNode own =
        json(
            agent
                .get("/api/v1/agent/contacts?q=" + sam.email() + "&organisationId=" + acme)
                .getContentAsString());
    assertThat(own.get("items").size()).isEqualTo(1);
    var other =
        agent.get(
            "/api/v1/agent/contacts?q=" + betaCustomerUser.email() + "&organisationId=" + beta);
    if (other.getStatus() == 200) {
      assertThat(json(other.getContentAsString()).get("items").size()).isZero();
    } else {
      assertThat(other.getStatus()).isEqualTo(404);
    }
    JsonNode any =
        json(
            agent.get("/api/v1/agent/contacts?q=" + betaCustomerUser.email()).getContentAsString());
    assertThat(any.get("items").size()).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // Over HTTP

  @Test
  void repliesAndNotesShareAPerAgentCapOf30AMinute() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    String replies = "/api/v1/agent/tickets/" + ticketId + "/public-replies";
    String notes = "/api/v1/agent/tickets/" + ticketId + "/internal-notes";
    String body = "{\"bodyHtml\":\"<p>Hi</p>\"}";
    int before = comments();
    for (int i = 0; i < 30; i++) {
      assertThat(agent.post(i % 2 == 0 ? replies : notes, body).getStatus())
          .as("message %d", i + 1)
          .isEqualTo(201);
    }
    int notesBefore =
        jdbc.queryForObject(
            "SELECT count(*) FROM internal_note WHERE ticket_id = ?", Integer.class, ticketId);
    for (String path : List.of(replies, notes)) {
      var refused = agent.post(path, body);
      assertThat(refused.getStatus()).as(path).isEqualTo(429);
      assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isBetween(1L, 61L);
      JsonNode error = json(refused.getContentAsString());
      assertThat(error.get("error").asString()).isEqualTo("too_many_requests");
      assertThat(error.get("message").asString()).startsWith("Too many attempts. Please wait");
    }
    // A refused message stores nothing.
    assertThat(comments()).isEqualTo(before + 15);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM internal_note WHERE ticket_id = ?", Integer.class, ticketId))
        .isEqualTo(notesBefore);

    // Per agent: another supporter is not affected.
    Browser other = signedIn(restrictedAgent(acme));
    assertThat(other.post(replies, body).getStatus()).isEqualTo(201);
    assertThat(other.post(notes, body).getStatus()).isEqualTo(201);

    // The window resets: a minute later the first agent can write again (application clock).
    clock.advance(Duration.ofSeconds(61));
    assertThat(agent.post(replies, body).getStatus()).isEqualTo(201);
    assertThat(agent.post(notes, body).getStatus()).isEqualTo(201);
  }

  @Test
  void ccChecksAreCappedPerAgentAt60AMinute() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    for (int i = 0; i < 60; i++) {
      ccCheck(agent, ticketId, "pat@outside.example", 200);
    }
    String refused = ccCheck(agent, ticketId, "pat@outside.example", 429);
    assertThat(json(refused).get("error").asString()).isEqualTo("too_many_requests");
    ccCheck(signedIn(restrictedAgent(acme)), ticketId, "pat@outside.example", 200);
  }

  @Test
  void theReplyEndpointTakesTheCcChoiceAndAnswersErrorsWithACode() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    String path = "/api/v1/agent/tickets/" + ticketId + "/public-replies";
    var refused =
        agent.post(
            path,
            "{\"bodyHtml\":\"<p>Hi</p>\",\"cc\":{\"recipients\":[\"pat@outside.example\"],"
                + "\"keepOnFutureReplies\":true}}");
    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(json(refused.getContentAsString()).get("error").asString())
        .isEqualTo("external_cc_unconfirmed");

    var ok =
        agent.post(
            path,
            "{\"bodyHtml\":\"<p>Hi</p>\",\"cc\":{\"recipients\":[\"pat@outside.example\"],"
                + "\"keepOnFutureReplies\":false,\"confirmExternal\":[\"pat@outside.example\"]}}");
    assertThat(ok.getStatus()).isEqualTo(201);
    assertThat(lastCc()).containsExactly("pat@outside.example");
    assertThat(participantsNow()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Early check from the CC editor (cc-check)

  private String ccCheck(Browser agent, long ticket, String email, int status) throws Exception {
    var res =
        agent.post(
            "/api/v1/agent/tickets/" + ticket + "/cc-check", "{\"email\":\"" + email + "\"}");
    assertThat(res.getStatus()).as(email).isEqualTo(status);
    return res.getContentAsString();
  }

  @Test
  void theCcCheckAnswersAReasonCodeOnlyAndRefusesStaffAccounts() throws Exception {
    AppUser sam = acmeContact("Sam Okoye");
    AppUser gone = acmeContact("Former Person");
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", gone.id());
    Browser agent = signedIn(acmeAgentUser);

    Map<String, String> expected =
        Map.of(
            sam.email(),
            "allowed",
            "reception@" + domain,
            "allowed",
            "pat@outside.example",
            "external",
            adminUser.email(),
            "staff",
            acmeAgentUser.email(),
            "staff",
            betaCustomerUser.email(),
            "external",
            gone.email(),
            "not_allowed");
    for (Map.Entry<String, String> e : expected.entrySet()) {
      JsonNode answer = json(ccCheck(agent, ticketId, e.getKey(), 200));
      assertThat(answer.get("result").asString()).as(e.getKey()).isEqualTo(e.getValue());
      // Nothing but the code: no name, no organisation, no account id.
      assertThat(answer.propertyNames()).containsExactly("result");
      assertThat(answer.toString())
          .doesNotContain(orgName(beta))
          .doesNotContain(orgName(acme))
          .doesNotContain("Former Person");
    }
    assertThat(
            json(agent
                    .post("/api/v1/agent/tickets/" + ticketId + "/cc-check", "{\"email\":\"x\"}")
                    .getContentAsString())
                .get("error")
                .asString())
        .isEqualTo("invalid_request");
    // Read-only: nothing joined the ticket.
    assertThat(participantsNow()).isEmpty();
  }

  @Test
  void aRestrictedAgentGetsNotFoundForAnotherOrganisationsTicket() throws Exception {
    long betaId = betaTicket().ticketId();
    Browser agent = signedIn(acmeAgentUser);
    String answer = ccCheck(agent, betaId, betaCustomerUser.email(), 404);
    assertThat(answer).doesNotContain(orgName(beta)).doesNotContain(betaCustomerUser.email());
    // The same 404 as a ticket that does not exist.
    ccCheck(agent, Long.MAX_VALUE, betaCustomerUser.email(), 404);
    assertThat(errorCode(() -> tickets.checkCc(acmeCustomer, ticketId, betaCustomerUser.email())))
        .isEqualTo("not_found");
  }

  @Test
  void erasingAPersonRemovesThemFromReplyCcLists() {
    AppUser lee = acmeContact("Lee Park");
    long commentId = reply(acmeAgent, once(lee.email()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM public_comment_cc WHERE comment_id = ?",
                Integer.class,
                commentId))
        .isEqualTo(1);
    jdbc.queryForObject("SELECT ts_erase_user(?, now())", Boolean.class, lee.id());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM public_comment_cc WHERE comment_id = ?",
                Integer.class,
                commentId))
        .isZero();
  }

  private String orgName(long org) {
    return jdbc.queryForObject("SELECT name FROM organisation WHERE id = ?", String.class, org);
  }
}
