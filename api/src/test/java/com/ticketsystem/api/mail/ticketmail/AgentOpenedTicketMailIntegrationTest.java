package com.ticketsystem.api.mail.ticketmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.SupportName;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository;
import com.ticketsystem.api.ticket.Impact;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.TicketTestBase;
import com.ticketsystem.api.ticket.Urgency;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-040: a ticket an agent opens for a client (source AGENT, F2) is emailed like that agent's
 * public reply - the agent's name as sender, one opening line with the reference, the description
 * as the agent's own message, the full signature and the reference footer - and the description is
 * stored as the agent's first public message. Client-raised tickets keep the confirmation. Real
 * services, outbox and Postgres (Testcontainers); every name and address is synthetic. The
 * reserved-domain guard on the mailbox path is covered by {@code MailboxSmtpIntegrationTest}.
 */
class AgentOpenedTicketMailIntegrationTest extends TicketTestBase {

  static final String DESCRIPTION = "<p>Printer on the second floor jams on every A3 job.</p>";
  static final String DESCRIPTION_TEXT = "Printer on the second floor jams on every A3 job.";

  /** Reply-To needs a support address even when no mailbox is configured (class run alone). */
  @DynamicPropertySource
  static void supportAddress(DynamicPropertyRegistry registry) {
    registry.add("app.mail.from-address", () -> "support@agent-opened.example.com");
  }

  @Autowired SupportName supportName;
  @Autowired SupportAddresses addresses;
  @Autowired SupporterSignatures signatures;
  @Autowired TicketSlaRepository slaRows;
  @Autowired TicketMailComposer composer;
  @Autowired TransactionTemplate tx;

  @BeforeEach
  void cleanOutbox() {
    jdbc.update("DELETE FROM outbox_message");
  }

  Created agentTicket(String descriptionHtml) {
    return create(acmeAgent, TicketSource.AGENT, descriptionHtml);
  }

  Created create(AccessScope scope, TicketSource source, String descriptionHtml) {
    return tickets.create(
        scope,
        new NewTicket(
            acme,
            acmeCustomerUser.id(),
            null,
            generalType,
            null,
            Impact.JUST_ME,
            Urgency.PARTLY,
            source,
            "Printer jams on A3",
            descriptionHtml,
            List.of(),
            null,
            null,
            null,
            null));
  }

  List<Map<String, Object>> clientMail(long ticketId) {
    return jdbc.queryForList(
        "SELECT to_addresses, cc_addresses, from_name, reply_to, subject, html_body, text_body"
            + " FROM outbox_message WHERE ticket_id = ? AND kind = 'client_facing' ORDER BY id",
        ticketId);
  }

  String expectedFromName() {
    return acmeAgentUser.name() + " (" + supportName.get() + ")";
  }

  @Test
  void anAgentOpenedTicketSendsOneEmailBuiltLikeTheAgentsReply() {
    Created c = agentTicket(DESCRIPTION);
    String ref = c.reference().toString();

    List<Map<String, Object>> mails = clientMail(c.ticketId());
    assertThat(mails).hasSize(1);
    Map<String, Object> m = mails.get(0);
    assertThat(m.get("from_name")).isEqualTo(expectedFromName());
    assertThat(m.get("reply_to")).isEqualTo(addresses.support(admin).orElseThrow());
    assertThat(m.get("to_addresses")).isEqualTo(acmeCustomerUser.email());
    assertThat(m.get("subject")).isEqualTo("[" + ref + "] Printer jams on A3");

    SupporterSignatures.Signature sig = signatures.render(admin, acmeAgentUser.id());
    String html = (String) m.get("html_body");
    String text = (String) m.get("text_body");

    // HTML: opening line, the agent's message, the gap, the full signature, then the footer.
    String opening = "We&#39;ve logged your request as " + ref + ".";
    assertThat(html)
        .contains(opening)
        .contains(DESCRIPTION)
        .contains(TicketMailComposer.SIGNATURE_SPACER + sig.html())
        .contains("Reference: " + ref)
        .doesNotContain("This is what you sent")
        .doesNotContain("Thank you. We have received")
        .doesNotContain("<blockquote");
    assertThat(html.indexOf(opening)).isLessThan(html.indexOf(DESCRIPTION));
    assertThat(html.indexOf(DESCRIPTION)).isLessThan(html.indexOf("ts-signature-gap"));
    assertThat(html.indexOf("ts-signature-gap")).isLessThan(html.indexOf(sig.html()));
    assertThat(html.indexOf(sig.html())).isLessThan(html.indexOf("Reference: " + ref));

    // Plain text: the same order, the signature after "-- ", the footer after another "-- ".
    assertThat(text)
        .matches(
            "(?s)"
                + Pattern.quote("We've logged your request as " + ref + ".\n\n" + DESCRIPTION_TEXT)
                + "\\s*\n\n-- \n.*")
        .contains(sig.text())
        .endsWith(
            "\n\n-- \nReference: "
                + ref
                + ". Reply to this email to add to your ticket, and please keep ["
                + ref
                + "] in the subject.\n")
        .doesNotContain("This is what you sent")
        .doesNotContain("> " + DESCRIPTION_TEXT);
    assertThat(text.indexOf(DESCRIPTION_TEXT)).isLessThan(text.indexOf(sig.text()));
  }

  @Test
  void theDescriptionIsTheAgentsFirstPublicMessageAndIsNotDuplicated() {
    Created c = agentTicket(DESCRIPTION);
    TicketDetail d = queries.detail(admin, c.ticketId());
    assertThat(d.publicComments()).hasSize(1);
    assertThat(d.publicComments().get(0).authorKind()).isEqualTo("SUPPORTER");
    assertThat(d.publicComments().get(0).author().userId()).isEqualTo(acmeAgentUser.id());
    assertThat(d.publicComments().get(0).bodyHtml()).isEqualTo(DESCRIPTION);
    assertThat(
            jdbc.queryForObject(
                "SELECT author_email FROM public_comment WHERE ticket_id = ?",
                String.class,
                c.ticketId()))
        .isNull();
  }

  @Test
  void theOpeningMessageDoesNotMeetFirstResponseButTheNextReplyDoes() {
    Created c = agentTicket(DESCRIPTION);
    assertThat(slaRows.firstSupporterReply(admin, c.ticketId())).isEmpty();
    tickets.addPublicReply(acmeAgent, c.ticketId(), "<p>On my way.</p>", List.of(), null);
    assertThat(slaRows.firstSupporterReply(admin, c.ticketId())).isPresent();
  }

  @Test
  void withoutADescriptionTheEmailIsTheOpeningLineAndTheSignature() {
    Created c = agentTicket("");
    String ref = c.reference().toString();
    List<Map<String, Object>> mails = clientMail(c.ticketId());
    assertThat(mails).hasSize(1);
    assertThat(mails.get(0).get("from_name")).isEqualTo(expectedFromName());
    assertThat((String) mails.get(0).get("text_body"))
        .startsWith("We've logged your request as " + ref + ".\n\n-- \n")
        .contains(signatures.render(admin, acmeAgentUser.id()).text());
    assertThat(queries.detail(admin, c.ticketId()).publicComments()).isEmpty();
  }

  @Test
  void aClientRaisedTicketKeepsTheConfirmationUnchanged() {
    Created c = create(acmeCustomer, TicketSource.PORTAL, DESCRIPTION);
    String ref = c.reference().toString();
    List<Map<String, Object>> mails = clientMail(c.ticketId());
    assertThat(mails).hasSize(1);
    Map<String, Object> m = mails.get(0);
    assertThat(m.get("from_name")).isEqualTo(supportName.get());
    assertThat((String) m.get("html_body"))
        .contains("Thank you. We have received your request and logged it as " + ref + ".")
        .contains("We will reply by email. This is what you sent:")
        .contains("<blockquote")
        .doesNotContain("logged your request as");
    assertThat((String) m.get("text_body"))
        .startsWith(
            "Thank you. We have received your request and logged it as "
                + ref
                + ".\nWe will reply by email. This is what you sent:\n\n> "
                + DESCRIPTION_TEXT);
    assertThat(queries.detail(admin, c.ticketId()).publicComments().get(0).authorKind())
        .isEqualTo("CLIENT");
  }

  @Test
  void aFollowUpAnAgentStartsByHandKeepsTheConfirmation() {
    Created old = agentTicket(DESCRIPTION);
    forceStatus(old.ticketId(), TicketStatus.CLOSED);
    Created f = tickets.createFollowUp(acmeAgent, old.ticketId());
    List<Map<String, Object>> mails = clientMail(f.ticketId());
    assertThat(mails).hasSize(1);
    assertThat(mails.get(0).get("from_name")).isEqualTo(supportName.get());
    assertThat((String) mails.get(0).get("text_body")).contains("This is what you sent");
  }

  @Test
  void onlyASupportersNameIsEverPutOnTheEmailAsItsSender() {
    Created c = create(acmeCustomer, TicketSource.PORTAL, DESCRIPTION);
    jdbc.update("DELETE FROM outbox_message");
    tx.executeWithoutResult(
        s -> composer.agentOpened(AccessScope.system("test"), c.ticketId(), acmeCustomerUser.id()));
    List<Map<String, Object>> mails = clientMail(c.ticketId());
    assertThat(mails).hasSize(1);
    assertThat(mails.get(0).get("from_name")).isEqualTo(supportName.get());
    assertThat((String) mails.get(0).get("text_body")).contains("This is what you sent");
  }
}
