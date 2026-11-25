package com.ticketsystem.api.mail.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.sun.net.httpserver.HttpServer;
import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.mail.MailOutbox;
import com.ticketsystem.api.mail.TestGreenMail;
import com.ticketsystem.api.mail.TestPorts;
import com.ticketsystem.api.mail.intake.EmailRulesService.BlockCheck;
import com.ticketsystem.api.mail.intake.EmailRulesService.BlockRuleRequest;
import com.ticketsystem.api.mail.intake.EmailRulesService.SenderRuleRequest;
import com.ticketsystem.api.mail.intake.MailboxService.MailboxRequest;
import com.ticketsystem.api.mail.intake.MailboxService.MailboxView;
import com.ticketsystem.api.mail.intake.imap.ImapMailboxClient;
import com.ticketsystem.api.mail.intake.poller.ImapPoller;
import com.ticketsystem.api.mail.intake.poller.ImapPoller.PollReport;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.security.SecurityGoLive;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.ReplyCcService;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketTestBase;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WP-07 end to end: a real IMAP server (GreenMail, in-process on 127.0.0.1), the real poller,
 * processor, ticket services and outbox on real Postgres (Testcontainers). Every mail is synthetic
 * ({@code .example} / {@code .test} domains). Outbound mail is checked in the outbox (the rows
 * WP-02a sends unchanged); the MIME layout of parts is covered by {@code SmtpMailTransportTest}.
 */
@SecurityGoLive({5})
class EmailInOutIntegrationTest extends TicketTestBase {

  static final String SUPPORT = "support@acmemsp.example";
  static final String AUTHSERV = "mx.acmemsp.example";

  /** Synthetic, test-only IMAP password (not a secret). */
  static final String IMAP_PASSWORD = "synthetic-imap-password-1";

  /** The port GreenMail actually bound (set in {@link #startImap()}). */
  static int imapPort;

  static GreenMail greenMail;
  static GreenMailUser supportUser;

  @Autowired ImapPoller poller;
  @Autowired MailboxService mailboxService;
  @Autowired com.ticketsystem.api.auth.throttle.RateLimiter rateLimiter;
  @Autowired EmailRulesService rulesService;
  @Autowired MailOutbox outbox;
  @Autowired TransactionTemplate tx;

  long mailboxId;

  @BeforeAll
  static void startImap() {
    TestGreenMail.Started started = TestGreenMail.start(ServerSetup.PROTOCOL_IMAP);
    greenMail = started.greenMail();
    imapPort = started.port(ServerSetup.PROTOCOL_IMAP);
    supportUser = greenMail.setUser(SUPPORT, SUPPORT, IMAP_PASSWORD);
  }

  @AfterAll
  static void stopImap() {
    greenMail.stop();
  }

  @BeforeEach
  void mailbox() throws Exception {
    greenMail.purgeEmailFromAllMailboxes();
    List<Long> existing =
        jdbc.queryForList("SELECT id FROM mailbox WHERE address = ?", Long.class, SUPPORT);
    MailboxRequest request = passwordMailbox(IMAP_PASSWORD);
    if (existing.isEmpty()) {
      mailboxId = mailboxService.create(admin, request, "203.0.113.1").id();
    } else {
      mailboxId = existing.get(0);
      mailboxService.update(admin, mailboxId, request, "203.0.113.1");
    }
    jdbc.update("UPDATE mailbox SET enabled = FALSE WHERE id <> ?", mailboxId);
    jdbc.update("UPDATE email_settings SET supporter_reply_mode = 'INTERNAL_ONLY'");
    jdbc.update("DELETE FROM mail_block_rule");
    // Start every test from a known mark (ADR-033: mail already in the folder is never imported).
    poll();
  }

  static MailboxRequest passwordMailbox(String password) {
    return new MailboxRequest(
        "Support",
        SUPPORT,
        true,
        "127.0.0.1",
        imapPort,
        "NONE",
        "INBOX",
        "PASSWORD",
        SUPPORT,
        password,
        null,
        null,
        null,
        null,
        List.of(AUTHSERV));
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  /** A synthetic raw email. {@code dmarc}: pass, fail or null (no Authentication-Results). */
  static String eml(
      String from,
      String to,
      String cc,
      String subject,
      Map<String, String> headers,
      String body,
      String dmarc) {
    String domain = from.substring(from.indexOf('@') + 1);
    StringBuilder sb = new StringBuilder();
    if (dmarc != null) {
      sb.append("Authentication-Results: ")
          .append(AUTHSERV)
          .append(";\r\n spf=pass smtp.mailfrom=")
          .append(domain)
          .append(";\r\n dkim=pass header.d=")
          .append(domain)
          .append(";\r\n dmarc=")
          .append(dmarc)
          .append(" header.from=")
          .append(domain)
          .append("\r\n");
    }
    sb.append("From: Sam Example <").append(from).append(">\r\n");
    sb.append("To: ").append(to).append("\r\n");
    if (cc != null) {
      sb.append("Cc: ").append(cc).append("\r\n");
    }
    sb.append("Subject: ").append(subject).append("\r\n");
    sb.append("Date: Tue, 17 Nov 2026 10:15:00 +0700\r\n");
    if (headers == null || !headers.containsKey("Message-ID")) {
      sb.append("Message-ID: <").append(UUID.randomUUID()).append("@mail.example>\r\n");
    }
    if (headers != null) {
      headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
    }
    sb.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n");
    sb.append(body).append("\r\n");
    return sb.toString();
  }

  static String mail(String from, String subject, String body) {
    return eml(from, SUPPORT, null, subject, null, body, "pass");
  }

  void deliver(String raw) throws Exception {
    supportUser.deliver(
        new MimeMessage(
            Session.getInstance(new Properties()),
            new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))));
  }

  PollReport poll() {
    return poller.pollOnce();
  }

  PollReport deliverAndPoll(String raw) throws Exception {
    deliver(raw);
    return poll();
  }

  String refOf(long ticketId) {
    return references.referencesOf(admin, ticketId).get(0).toString();
  }

  /** The tolerant form a mail client might produce: "[nwd-1]" for NWD-001. */
  String sloppyRefOf(long ticketId) {
    TicketReference r = references.referencesOf(admin, ticketId).get(0);
    return "[" + r.prefix().toLowerCase(Locale.ROOT) + "-" + r.number() + "]";
  }

  Map<String, Object> lastInbound() {
    return jdbc.queryForMap("SELECT * FROM inbound_mail ORDER BY id DESC LIMIT 1");
  }

  /**
   * Client-authored public comments. {@code acmeTicket()} is opened by an agent, so its description
   * is the agent's message (ADR-040) and only the client's own replies count here.
   */
  int clientComments(long ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM public_comment WHERE ticket_id = ? AND author_kind = 'CLIENT'",
        Integer.class,
        ticketId);
  }

  List<Map<String, Object>> outboxFor(long ticketId) {
    return jdbc.queryForList(
        "SELECT * FROM outbox_message WHERE ticket_id = ? ORDER BY id", ticketId);
  }

  List<Map<String, Object>> clientMailFor(long ticketId) {
    return jdbc.queryForList(
        "SELECT * FROM outbox_message WHERE ticket_id = ? AND kind = 'client_facing' ORDER BY id",
        ticketId);
  }

  String newDomain() {
    String d = "client-" + UUID.randomUUID().toString().replace("-", "") + ".example";
    jdbc.update(
        "INSERT INTO organisation_domain (domain, organisation_id, created_at) VALUES (?, ?,"
            + " now())",
        d,
        acme);
    return d;
  }

  long ticketOf(Map<String, Object> inboundRow) {
    return ((Number) inboundRow.get("ticket_id")).longValue();
  }

  Long orgOf(long ticketId) {
    return jdbc.queryForObject(
        "SELECT organisation_id FROM ticket WHERE id = ?", Long.class, ticketId);
  }

  // ---------------------------------------------------------------------------------------------
  // Inbound: new tickets

  @Test
  void aKnownContactsEmailBecomesATicketWithAConfirmation() throws Exception {
    deliverAndPoll(mail(acmeCustomerUser.email(), "New starter needs a laptop", "Hello, laptop."));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("CREATED");
    long t = ticketOf(row);
    assertThat(orgOf(t)).isEqualTo(acme);
    assertThat(
            jdbc.queryForMap(
                "SELECT reporter_user_id, source, subject FROM ticket WHERE id = ?", t))
        .containsEntry("reporter_user_id", acmeCustomerUser.id())
        .containsEntry("source", "EMAIL")
        .containsEntry("subject", "New starter needs a laptop");
    assertThat(row.get("raw_eml")).isNotNull();

    List<Map<String, Object>> mails = clientMailFor(t);
    assertThat(mails).hasSize(1);
    Map<String, Object> confirmation = mails.get(0);
    String ref = refOf(t);
    assertThat(confirmation.get("to_addresses")).isEqualTo(acmeCustomerUser.email());
    assertThat(confirmation.get("reply_to")).isEqualTo(SUPPORT);
    assertThat((String) confirmation.get("subject"))
        .isEqualTo("[" + ref + "] New starter needs a laptop");
    assertThat((String) confirmation.get("text_body"))
        .contains("Reference: " + ref)
        .contains("keep [" + ref + "] in the subject")
        .contains("Hello, laptop.");
    assertThat((String) confirmation.get("extra_headers")).contains("In-Reply-To: <");
  }

  @Test
  void anUnknownSenderOnAVerifiedDomainWithDmarcGetsATicketAndAContact() throws Exception {
    String domain = newDomain();
    String sender = "new.person@" + domain;
    deliverAndPoll(mail(sender, "Printer jammed", "Please help."));
    long t = ticketOf(lastInbound());
    assertThat(orgOf(t)).isEqualTo(acme);
    assertThat(
            jdbc.queryForMap(
                "SELECT role, status, organisation_id FROM app_user WHERE email = ?", sender))
        .containsEntry("role", "CUSTOMER")
        .containsEntry("status", "INVITED")
        .containsEntry("organisation_id", acme);
  }

  @Test
  void anUnknownSenderOnAVerifiedDomainWithoutDmarcCreatesNothingButATriageTicket()
      throws Exception {
    String domain = newDomain();
    String sender = "spoof@" + domain;
    deliverAndPoll(eml(sender, SUPPORT, null, "Pay this invoice", null, "Now.", "fail"));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("TRIAGE");
    assertThat(row.get("reason")).isEqualTo("NOT_AUTHENTICATED");
    assertThat(orgOf(ticketOf(row))).isNull();
    assertThat(jdbc.queryForList("SELECT id FROM app_user WHERE email = ?", sender)).isEmpty();
    assertThat(clientMailFor(ticketOf(row))).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT body_text FROM system_entry WHERE ticket_id = ? AND kind = 'EMAIL_TRIAGE'",
                String.class,
                ticketOf(row)))
        .contains("Sender not authenticated");
  }

  @Test
  void aPublicDomainUnknownSenderGoesToTriage() throws Exception {
    deliverAndPoll(mail("someone." + UUID.randomUUID() + "@gmail.com", "Hi", "Hello"));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("TRIAGE");
    assertThat(row.get("reason")).isEqualTo("UNKNOWN_SENDER");
  }

  // ---------------------------------------------------------------------------------------------
  // Inbound: CC on a client's email (ADR-043). CC addresses use the RFC 2606 second-level names
  // (example.com/.org/.net subdomains): synthetic, but not on the reserved test TLDs that intake
  // skips.

  /** A unique synthetic address that is not on a reserved test TLD. */
  static String ccAddress(String local, String base) {
    return local + "@cc-" + UUID.randomUUID().toString().replace("-", "") + "." + base;
  }

  /** A saved contact of {@code org} on a non-reserved synthetic domain. */
  AppUser contactOn(long org, String email) {
    long id =
        users.insertActive(
            email, "Robin Colleague", Role.CUSTOMER, org, goodPasswordHash(), Instant.now());
    return users.findById(id).orElseThrow();
  }

  List<String> participantsOf(long ticketId) {
    return jdbc
        .queryForList(
            "SELECT email, confirmed FROM ticket_participant WHERE ticket_id = ? ORDER BY id",
            ticketId)
        .stream()
        .map(m -> m.get("email") + "=" + m.get("confirmed"))
        .toList();
  }

  List<Map<String, Object>> participantEvents(long ticketId) {
    return jdbc.queryForList(
        "SELECT reason, actor_kind, new_value, reason_ref_id FROM ticket_event WHERE ticket_id = ?"
            + " AND type = 'PARTICIPANT_ADDED' ORDER BY id",
        ticketId);
  }

  List<String> systemEntries(long ticketId, String kind) {
    return jdbc.queryForList(
        "SELECT body_text FROM system_entry WHERE ticket_id = ? AND kind = ? ORDER BY id",
        String.class,
        ticketId,
        kind);
  }

  @Test
  void aVerifiedEmailsCcsBecomeConfirmedParticipantsAndGetTheConfirmation() throws Exception {
    AppUser colleague = contactOn(acme, ccAddress("colleague", "example.com"));
    String colleagueLower = colleague.email().toLowerCase(Locale.ROOT);
    String external = ccAddress("Partner", "example.org");
    String externalLower = external.toLowerCase(Locale.ROOT);
    deliverAndPoll(
        eml(
            acmeCustomerUser.email(),
            SUPPORT,
            "Robin <" + colleague.email().toUpperCase(Locale.ROOT) + ">, " + external,
            "Shared drive missing",
            null,
            "Both of us cannot see it.",
            "pass"));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("CREATED");
    long t = ticketOf(row);
    assertThat(participantsOf(t))
        .containsExactly(colleagueLower + "=true", externalLower + "=true");
    // The confirmation goes to the sender with both CCs.
    String cc = (String) clientMailFor(t).get(0).get("cc_addresses");
    assertThat(cc).contains(colleagueLower).contains(externalLower);
    // One client-actor line each: the contact by account, the outside address by address.
    List<Map<String, Object>> events = participantEvents(t);
    assertThat(events).extracting(e -> e.get("reason")).containsOnly("EMAIL_CC");
    assertThat(events).extracting(e -> e.get("actor_kind")).containsOnly("CLIENT");
    assertThat(((Number) events.get(0).get("reason_ref_id")).longValue()).isEqualTo(colleague.id());
    assertThat(events.get(1).get("new_value")).isEqualTo(externalLower);
    // The next public reply goes to them too.
    tickets.addPublicReply(acmeAgent, t, "<p>Fixed.</p>", List.of(), null);
    List<Map<String, Object>> mail = clientMailFor(t);
    assertThat((String) mail.get(mail.size() - 1).get("cc_addresses")).contains(externalLower);
  }

  @Test
  void anUnverifiedEmailsCcsWaitAndGetNoMail() throws Exception {
    String partner = ccAddress("partner", "example.org");
    deliverAndPoll(
        eml(acmeCustomerUser.email(), SUPPORT, partner, "Pay this invoice", null, "Now.", "fail"));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("TRIAGE");
    assertThat(row.get("reason")).isEqualTo("NOT_AUTHENTICATED");
    long t = ticketOf(row);
    assertThat(participantsOf(t)).containsExactly(partner + "=false");
    assertThat(participantEvents(t))
        .extracting(e -> e.get("reason") + "/" + e.get("actor_kind"))
        .containsExactly("EMAIL_CC_UNCONFIRMED/CLIENT");
    assertThat(outboxFor(t)).isEmpty();
  }

  @Test
  void excludedCcAddressesAreNeverAdded() throws Exception {
    String tag = UUID.randomUUID().toString().replace("-", "");
    // A support mailbox on a non-reserved name, so only the "ours" rule can skip it.
    String helpAddress = "help-" + tag + "@acmemsp.example.net";
    greenMail.setUser(helpAddress, helpAddress, IMAP_PASSWORD);
    long help =
        mailboxService.create(admin, otherMailbox("Help", helpAddress, IMAP_PASSWORD), null).id();
    jdbc.update("UPDATE mailbox SET enabled = FALSE WHERE id = ?", help);
    String helpInternal = "help-" + tag + "+internal@acmemsp.example.net";
    long agentId =
        users.insertActive(
            ccAddress("agent", "example.com"),
            "Kim Agent",
            Role.AGENT,
            null,
            goodPasswordHash(),
            Instant.now());
    String agentEmail = users.findById(agentId).orElseThrow().email();
    AppUser otherOrgContact = contactOn(beta, ccAddress("beta.contact", "example.com"));
    String reserved = "someone-" + tag + "@clinic.example";
    String kept = ccAddress("kept", "example.org");
    String sender = acmeCustomerUser.email();
    deliverAndPoll(
        eml(
            sender,
            SUPPORT,
            String.join(
                ", ",
                helpAddress,
                helpInternal,
                agentEmail,
                otherOrgContact.email(),
                reserved,
                sender.toUpperCase(Locale.ROOT),
                SUPPORT,
                "support+internal@acmemsp.example",
                kept,
                kept.toUpperCase(Locale.ROOT)),
            "Printer offline",
            null,
            "Please help.",
            "pass"));
    long t = ticketOf(lastInbound());
    assertThat(participantsOf(t)).containsExactly(kept + "=true");
    // Nothing about the other organisation's contact is written anywhere on the ticket.
    assertThat(participantEvents(t)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM system_entry WHERE ticket_id = ? AND body_text ILIKE ?",
                Integer.class,
                t,
                "%" + otherOrgContact.email() + "%"))
        .isZero();
  }

  @Test
  void ccOverTheLimitIsListedInAHiddenSystemEntry() throws Exception {
    Created c = acmeTicket();
    for (int i = 0; i < 8; i++) {
      jdbc.update(
          "INSERT INTO ticket_participant (ticket_id, email, confirmed, created_at) VALUES (?, ?,"
              + " TRUE, now())",
          c.ticketId(),
          ccAddress("existing" + i, "example.com"));
    }
    String a = ccAddress("a", "example.org");
    String b = ccAddress("b", "example.org");
    String x = ccAddress("x", "example.org");
    String y = ccAddress("y", "example.org");
    deliverAndPoll(
        eml(
            acmeCustomerUser.email(),
            SUPPORT,
            String.join(", ", a, b, x, y),
            "Re: [" + refOf(c.ticketId()) + "]",
            null,
            "Adding my team.",
            "pass"));
    assertThat(lastInbound().get("outcome")).isEqualTo("CLIENT_REPLY");
    assertThat(participantsOf(c.ticketId())).hasSize(10).contains(a + "=true", b + "=true");
    assertThat(systemEntries(c.ticketId(), "EMAIL_CC_LIMIT"))
        .containsExactly("Not added (limit): " + x + ", " + y);
  }

  @Test
  void aVerifiedReplyConfirmsAWaitingCc() throws Exception {
    Created c = acmeTicket();
    String pending = ccAddress("pending", "example.org");
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, email, confirmed, created_at) VALUES (?, ?,"
            + " FALSE, now())",
        c.ticketId(),
        pending);
    String subject = "Re: [" + refOf(c.ticketId()) + "]";
    deliverAndPoll(
        eml(
            acmeCustomerUser.email(),
            SUPPORT,
            pending.toUpperCase(Locale.ROOT),
            subject,
            null,
            "Pending is my colleague.",
            "pass"));
    assertThat(lastInbound().get("outcome")).isEqualTo("CLIENT_REPLY");
    assertThat(participantsOf(c.ticketId())).containsExactly(pending + "=true");
    assertThat(participantEvents(c.ticketId()))
        .extracting(e -> e.get("reason") + "/" + e.get("actor_kind") + "/" + e.get("new_value"))
        .containsExactly("EMAIL_CC_CONFIRMED/CLIENT/" + pending);
    tickets.addPublicReply(acmeAgent, c.ticketId(), "<p>Thanks.</p>", List.of(), null);
    List<Map<String, Object>> mail = clientMailFor(c.ticketId());
    assertThat((String) mail.get(mail.size() - 1).get("cc_addresses")).isEqualTo(pending);
    // The same CC again: no duplicate, no second line.
    deliverAndPoll(
        eml(acmeCustomerUser.email(), SUPPORT, pending, subject, null, "Again.", "pass"));
    assertThat(participantsOf(c.ticketId())).containsExactly(pending + "=true");
    assertThat(participantEvents(c.ticketId())).hasSize(1);
  }

  @Test
  void aVerifiedTriageEmailsCcsAreConfirmedOnTheTriageTicket() throws Exception {
    AppUser acmeColleague = contactOn(acme, ccAddress("colleague", "example.com"));
    String friend = ccAddress("friend", "example.net");
    deliverAndPoll(
        eml(
            "someone." + UUID.randomUUID() + "@gmail.com",
            SUPPORT,
            friend + ", " + acmeColleague.email(),
            "Hi",
            null,
            "Hello",
            "pass"));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("TRIAGE");
    assertThat(row.get("reason")).isEqualTo("UNKNOWN_SENDER");
    long t = ticketOf(row);
    assertThat(orgOf(t)).isNull();
    // A saved contact is never put on a ticket that has no organisation yet.
    assertThat(participantsOf(t)).containsExactly(friend + "=true");
    assertThat(outboxFor(t)).isEmpty(); // triage tickets get no confirmation
  }

  // ---------------------------------------------------------------------------------------------
  // Inbound: threading and guards

  @Test
  void aReplyWithASloppyReferenceThreadsOntoTheTicket() throws Exception {
    Created c = acmeTicket();
    forceStatus(c.ticketId(), TicketStatus.PENDING_ON_CLIENT);
    deliverAndPoll(
        mail(
            acmeCustomerUser.email(), "RE: FW: " + sloppyRefOf(c.ticketId()) + " Outlook", "Yes."));
    assertThat(lastInbound().get("outcome")).isEqualTo("CLIENT_REPLY");
    assertThat(clientComments(c.ticketId())).isEqualTo(1);
    assertThat(statusOf(c.ticketId())).isEqualTo(TicketStatus.OPEN);
    // The assignee-less ticket gets no internal alert; a client reply is never an internal note.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM internal_note WHERE ticket_id = ?",
                Integer.class,
                c.ticketId()))
        .isZero();
  }

  @Test
  void aReplyWithoutAReferenceThreadsByInReplyTo() throws Exception {
    Created c = acmeTicket();
    tickets.addPublicReply(acmeAgent, c.ticketId(), "<p>Can you restart?</p>", List.of(), null);
    String ourId =
        (String)
            clientMailFor(c.ticketId())
                .get(clientMailFor(c.ticketId()).size() - 1)
                .get("message_id");
    deliverAndPoll(
        eml(
            acmeCustomerUser.email(),
            SUPPORT,
            null,
            "Re: your reply",
            Map.of("In-Reply-To", ourId, "References", ourId),
            "Restarted, still broken.",
            "pass"));
    assertThat(lastInbound().get("outcome")).isEqualTo("CLIENT_REPLY");
    assertThat(ticketOf(lastInbound())).isEqualTo(c.ticketId());
  }

  @Test
  void aParticipantFailingDmarcGoesToTriage() throws Exception {
    Created c = acmeTicket();
    deliverAndPoll(
        eml(
            acmeCustomerUser.email(),
            SUPPORT,
            null,
            "Re: " + sloppyRefOf(c.ticketId()),
            null,
            "Spoofed",
            "fail"));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("reason")).isEqualTo("NOT_AUTHENTICATED");
    assertThat(ticketOf(row)).isNotEqualTo(c.ticketId());
    assertThat(clientComments(c.ticketId())).isEqualTo(0);
  }

  @Test
  void aNonParticipantReplyGoesToTriage() throws Exception {
    Created c = acmeTicket();
    deliverAndPoll(
        mail(
            "stranger-" + UUID.randomUUID() + "@elsewhere.example",
            "Re: " + sloppyRefOf(c.ticketId()),
            "Injected"));
    assertThat(lastInbound().get("reason")).isEqualTo("NOT_A_PARTICIPANT");
    assertThat(clientComments(c.ticketId())).isEqualTo(0);
    // A customer of another organisation quoting the reference: also triage.
    deliverAndPoll(mail(betaCustomerUser.email(), "Re: [" + refOf(c.ticketId()) + "]", "Mine?"));
    assertThat(lastInbound().get("reason")).isEqualTo("REFERENCE_OTHER_ORGANISATION");
  }

  @Test
  void anotherOrganisationsContactCcdOnATicketRepliesAsAnOutsideParticipantOfThatTicketOnly()
      throws Exception {
    // ADR-050 change 2026-11-24, active and deactivated alike (PR #70 audit Low 1).
    AppUser formerBeta = activeUser(Role.CUSTOMER, beta);
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", formerBeta.id());
    for (AppUser contact : List.of(betaCustomerUser, formerBeta)) {
      String address = contact.email().toLowerCase(Locale.ROOT);
      Created c = acmeTicket();
      Created otherAcme = acmeTicket();
      tickets.addPublicReply(
          acmeAgent,
          c.ticketId(),
          "<p>Copying you in.</p>",
          List.of(),
          null,
          new ReplyCcService.ReplyCc(List.of(address), true, List.of(address)));
      List<Map<String, Object>> sent = clientMailFor(c.ticketId());
      assertThat((String) sent.get(sent.size() - 1).get("cc_addresses"))
          .as(address)
          .isEqualTo(address);

      // Their reply threads onto that ticket as an outside participant's, never as their account.
      deliverAndPoll(mail(contact.email(), "Re: [" + refOf(c.ticketId()) + "]", "Noted."));
      Map<String, Object> row = lastInbound();
      assertThat(row.get("outcome")).as(address).isEqualTo("CLIENT_REPLY");
      assertThat(ticketOf(row)).isEqualTo(c.ticketId());
      Map<String, Object> comment =
          jdbc.queryForMap(
              "SELECT author_user_id, lower(author_email) AS author_email FROM public_comment"
                  + " WHERE ticket_id = ? AND author_kind = 'CLIENT' ORDER BY id DESC LIMIT 1",
              c.ticketId());
      assertThat(comment.get("author_user_id")).as(address).isNull();
      assertThat(comment.get("author_email")).isEqualTo(address);

      // Another ticket of the same organisation stays out of reach.
      deliverAndPoll(mail(contact.email(), "Re: [" + refOf(otherAcme.ticketId()) + "]", "And?"));
      assertThat(lastInbound().get("reason"))
          .as(address)
          .isIn("REFERENCE_OTHER_ORGANISATION", "NOT_A_PARTICIPANT");
      assertThat(ticketOf(lastInbound())).isNotEqualTo(otherAcme.ticketId());
      assertThat(clientComments(otherAcme.ticketId())).isZero();
    }
  }

  @Test
  void anUnknownReferenceGoesToTriage() throws Exception {
    deliverAndPoll(mail(acmeCustomerUser.email(), "Re: [ZZQ-99999] old ticket", "Hello"));
    assertThat(lastInbound().get("reason")).isEqualTo("REFERENCE_NOT_FOUND");
  }

  @Test
  void aReplyToAClosedTicketCreatesAFollowUp() throws Exception {
    Created c = acmeTicket();
    forceStatus(c.ticketId(), TicketStatus.CLOSED);
    deliverAndPoll(
        mail(acmeCustomerUser.email(), "Re: [" + refOf(c.ticketId()) + "]", "It is back."));
    long t = ticketOf(lastInbound());
    assertThat(t).isNotEqualTo(c.ticketId());
    assertThat(
            jdbc.queryForObject("SELECT follow_up_of_id FROM ticket WHERE id = ?", Long.class, t))
        .isEqualTo(c.ticketId());
    assertThat(clientMailFor(t)).hasSize(1); // the follow-up's confirmation
  }

  @Test
  void theSameMailDeliveredTwiceCreatesOneCommentAndUidValidityChangesDoNotReprocess()
      throws Exception {
    Created c = acmeTicket();
    String mid = "<twice-" + UUID.randomUUID() + "@mail.example>";
    String raw =
        eml(
            acmeCustomerUser.email(),
            SUPPORT,
            null,
            "Re: [" + refOf(c.ticketId()) + "]",
            Map.of("Message-ID", mid),
            "Once only",
            "pass");
    deliver(raw);
    deliver(raw);
    PollReport report = poll();
    assertThat(report.outcomes()).containsEntry("CLIENT_REPLY", 1).containsEntry("DUPLICATE", 1);
    assertThat(clientComments(c.ticketId())).isEqualTo(1);
    // The repeat delivery is logged (findable by sender) without a second raw copy (AUD-13 L3).
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inbound_mail WHERE message_id = ? AND outcome = 'DUPLICATE'"
                    + " AND raw_eml IS NULL AND from_address = ?",
                Integer.class,
                mid,
                acmeCustomerUser.email()))
        .isOne();
    // A new UIDVALIDITY starts again at UIDNEXT (ADR-033); nothing is processed twice.
    jdbc.update("UPDATE mailbox SET uid_validity = uid_validity + 1000 WHERE id = ?", mailboxId);
    PollReport again = poll();
    assertThat(again.outcomes()).doesNotContainKey("CLIENT_REPLY");
    assertThat(clientComments(c.ticketId())).isEqualTo(1);
  }

  @Test
  void twoPollersAtOnceProcessEachMailOnce() throws Exception {
    for (int i = 0; i < 5; i++) {
      deliver(mail(acmeCustomerUser.email(), "Parallel " + i, "Body " + i));
    }
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<PollReport>> jobs =
          List.of(() -> poller.pollOnce("test-a"), () -> poller.pollOnce("test-b"));
      List<PollReport> reports = new ArrayList<>();
      for (Future<PollReport> f : pool.invokeAll(jobs)) {
        reports.add(f.get());
      }
      poll();
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket WHERE reporter_user_id = ? AND subject LIKE"
                    + " 'Parallel %'",
                Integer.class, acmeCustomerUser.id()))
        .isEqualTo(5);
  }

  @Test
  void aMalformedMailIsKeptAndGoesToTriage() throws Exception {
    deliverAndPoll(
        "From: broken@mail.example\r\nTo: "
            + SUPPORT
            + "\r\nSubject: broken\r\nMessage-ID: <broken-"
            + UUID.randomUUID()
            + "@mail.example>\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed;"
            + " boundary=\"zzz\"\r\n\r\nno boundary here at all\r\n");
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("TRIAGE");
    assertThat(row.get("raw_eml")).isNotNull();
    assertThat(orgOf(ticketOf(row))).isNull();
  }

  // ---------------------------------------------------------------------------------------------
  // Loop safety, fold-in, block list, sender rules

  @Test
  void aNewsletterBecomesATriageTicketAndGetsNoAutomaticReply() throws Exception {
    deliverAndPoll(
        eml(
            "news@offers-" + UUID.randomUUID().toString().replace("-", "") + ".example",
            SUPPORT,
            null,
            "November offers",
            Map.of("List-Unsubscribe", "<https://offers.example/u>", "Precedence", "bulk"),
            "Buy now",
            "pass"));
    long t = ticketOf(lastInbound());
    assertThat(outboxFor(t)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_mail_suppression WHERE ticket_id = ?",
                Integer.class,
                t))
        .isOne();
  }

  @Test
  void anAutomatedMailFromAContactCreatesATicketButNoConfirmation() throws Exception {
    deliverAndPoll(
        eml(
            acmeCustomerUser.email(),
            SUPPORT,
            null,
            "Disk alert",
            Map.of("Auto-Submitted", "auto-generated"),
            "Disk 91% full",
            "pass"));
    long t = ticketOf(lastInbound());
    assertThat(orgOf(t)).isEqualTo(acme);
    assertThat(clientMailFor(t)).isEmpty();
    tickets.changeStatus(acmeAgent, t, TicketStatus.RESOLVED);
    assertThat(clientMailFor(t)).isEmpty(); // no Resolved notice to an automated sender either
  }

  @Test
  void anAutoReplyOnATicketIsAHiddenEntryAndDoesNotReopen() throws Exception {
    Created c = acmeTicket();
    forceStatus(c.ticketId(), TicketStatus.RESOLVED);
    deliverAndPoll(
        eml(
            acmeCustomerUser.email(),
            SUPPORT,
            null,
            "Automatic reply: [" + refOf(c.ticketId()) + "]",
            Map.of("Auto-Submitted", "auto-replied"),
            "I am out of the office.",
            "pass"));
    assertThat(lastInbound().get("outcome")).isEqualTo("SYSTEM_ENTRY");
    assertThat(statusOf(c.ticketId())).isEqualTo(TicketStatus.RESOLVED);
    assertThat(clientComments(c.ticketId())).isEqualTo(0);
  }

  @Test
  void the21stNewMailInAnHourBecomesACommentOnTheLatestTicket() throws Exception {
    for (int i = 1; i <= 21; i++) {
      deliver(mail(acmeCustomerUser.email(), "Flood " + i, "Mail " + i));
    }
    PollReport report = poll();
    assertThat(report.outcomes()).containsEntry("CREATED", 20).containsEntry("FOLDED", 1);
    Map<String, Object> folded = lastInbound();
    assertThat(folded.get("outcome")).isEqualTo("FOLDED");
    assertThat(clientComments(ticketOf(folded))).isEqualTo(2);
  }

  @Test
  void blockedMailCreatesNothingAndCanBeRestored() throws Exception {
    String spammer = "promo@spam-" + UUID.randomUUID().toString().replace("-", "") + ".example";
    String spamDomain = spammer.substring(spammer.indexOf('@') + 1);
    rulesService.addBlockRule(
        admin,
        rulesService.checkBlockRule(admin, new BlockRuleRequest("domain", spamDomain)),
        null);
    int ticketsBefore = jdbc.queryForObject("SELECT count(*) FROM ticket", Integer.class);
    deliverAndPoll(mail(spammer, "Cheap pills", "Buy"));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("BLOCKED");
    assertThat(row.get("ticket_id")).isNull();
    assertThat(row.get("block_rule")).isEqualTo(spamDomain);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket", Integer.class))
        .isEqualTo(ticketsBefore);
    assertThat(rulesService.blockedMail(admin))
        .anySatisfy(b -> assertThat(b.rule()).isEqualTo(spamDomain));

    long restored = rulesService.restore(admin, ((Number) row.get("id")).longValue());
    assertThat(orgOf(restored)).isNull(); // unknown sender: a normal triage ticket
    assertThat(
            jdbc.queryForObject(
                "SELECT restored_at IS NOT NULL FROM inbound_mail WHERE id = ?",
                Boolean.class,
                row.get("id")))
        .isTrue();
    assertThat(auditEvents("BLOCKED_MAIL_RESTORED", null)).isNotEmpty();
  }

  @Test
  void aBlockedSendersReplyOnAnExistingTicketIsBlockedToo() throws Exception {
    Created c = acmeTicket();
    rulesService.addBlockRule(
        admin,
        rulesService.checkBlockRule(
            admin, new BlockRuleRequest("address", acmeCustomerUser.email())),
        null);
    deliverAndPoll(mail(acmeCustomerUser.email(), "Re: [" + refOf(c.ticketId()) + "]", "Hello"));
    assertThat(lastInbound().get("outcome")).isEqualTo("BLOCKED");
    assertThat(clientComments(c.ticketId())).isEqualTo(0);
  }

  @Test
  void theSupportDomainCannotBeBlockedAndAClientDomainNeedsConfirmation() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                rulesService.checkBlockRule(
                    admin, new BlockRuleRequest("domain", "acmemsp.example")))
        .hasMessageContaining("cannot be blocked");
    String domain = newDomain();
    assertThat(
            rulesService
                .checkBlockRule(admin, new BlockRuleRequest("domain", domain))
                .needsConfirmation())
        .isTrue();
  }

  @Test
  void aSenderRuleRoutesTheNextMail() throws Exception {
    String alerts = "alerts@monitor-" + UUID.randomUUID().toString().replace("-", "") + ".example";
    deliverAndPoll(mail(alerts, "CPU high", "Server 1"));
    assertThat(lastInbound().get("outcome")).isEqualTo("TRIAGE");
    rulesService.addSenderRule(
        admin, new SenderRuleRequest("ADDRESS", alerts, acme, alertType), null);
    deliverAndPoll(mail(alerts, "CPU high again", "Server 1"));
    long t = ticketOf(lastInbound());
    assertThat(orgOf(t)).isEqualTo(acme);
    assertThat(
            jdbc.queryForObject("SELECT request_type_id FROM ticket WHERE id = ?", Long.class, t))
        .isEqualTo(alertType);
  }

  // ---------------------------------------------------------------------------------------------
  // Supporter replies by email

  static final String INTERNAL = "support+internal@acmemsp.example";

  void setMode(String mode) {
    jdbc.update("UPDATE email_settings SET supporter_reply_mode = ?", mode);
  }

  int notesOn(long ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM internal_note WHERE ticket_id = ? AND received_by_email",
        Integer.class,
        ticketId);
  }

  @Test
  void internalNotesOnlyMode() throws Exception {
    Created c = acmeTicket();
    int mailsBefore = clientMailFor(c.ticketId()).size();
    deliverAndPoll(
        eml(
            acmeAgentUser.email(),
            INTERNAL,
            null,
            "Re: [" + refOf(c.ticketId()) + "] (Internal)",
            null,
            "Checked the logs.",
            "pass"));
    assertThat(lastInbound().get("outcome")).isEqualTo("NOTE");
    assertThat(notesOn(c.ticketId())).isOne();
    assertThat(clientMailFor(c.ticketId())).hasSize(mailsBefore);
    assertThat(auditEvents("SUPPORTER_EMAIL_REPLY", null))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains("dmarc"));
    deliverAndPoll(
        mail(acmeAgentUser.email(), "Re: [" + refOf(c.ticketId()) + "]", "Public answer by email"));
    assertThat(lastInbound().get("reason")).isEqualTo("SUPPORTER_PUBLIC_REPLIES_OFF");
    assertThat(clientMailFor(c.ticketId())).hasSize(mailsBefore);
  }

  @Test
  void offMode() throws Exception {
    setMode("OFF");
    Created c = acmeTicket();
    deliverAndPoll(
        eml(
            acmeAgentUser.email(),
            INTERNAL,
            null,
            "Re: [" + refOf(c.ticketId()) + "]",
            null,
            "Note",
            "pass"));
    assertThat(lastInbound().get("reason")).isEqualTo("SUPPORTER_REPLIES_OFF");
    assertThat(notesOn(c.ticketId())).isZero();
  }

  @Test
  void notesAndPublicRepliesMode() throws Exception {
    setMode("NOTES_AND_PUBLIC");
    Created c = acmeTicket();
    int mailsBefore = clientMailFor(c.ticketId()).size();
    deliverAndPoll(
        mail(
            acmeAgentUser.email(), "Re: [" + refOf(c.ticketId()) + "]", "Fixed it from my phone."));
    assertThat(lastInbound().get("outcome")).isEqualTo("PUBLIC_REPLY");
    List<Map<String, Object>> mails = clientMailFor(c.ticketId());
    assertThat(mails).hasSize(mailsBefore + 1);
    Map<String, Object> reply = mails.get(mails.size() - 1);
    assertThat((String) reply.get("from_name")).isEqualTo("Sam Example (Example MSP Support)");
    assertThat((String) reply.get("html_body")).contains("Fixed it from my phone.");
    deliverAndPoll(
        eml(
            acmeAgentUser.email(),
            INTERNAL,
            null,
            "Re: [" + refOf(c.ticketId()) + "]",
            null,
            "Private remark",
            "pass"));
    assertThat(lastInbound().get("outcome")).isEqualTo("NOTE");
    assertThat(clientMailFor(c.ticketId())).hasSize(mailsBefore + 1);
  }

  @Test
  void theInternalAddressFromANonSupporterGoesToTriage() throws Exception {
    Created c = acmeTicket();
    deliverAndPoll(
        eml(
            acmeCustomerUser.email(),
            INTERNAL,
            null,
            "Re: [" + refOf(c.ticketId()) + "]",
            null,
            "Let me in",
            "pass"));
    assertThat(lastInbound().get("reason")).isEqualTo("NOT_A_SUPPORTER");
    assertThat(notesOn(c.ticketId())).isZero();
    assertThat(clientComments(c.ticketId())).isEqualTo(0);
  }

  @Test
  void aSupporterWithoutAccessCannotAddANote() throws Exception {
    Created c = betaTicket(); // acmeAgent is limited to Acme
    deliverAndPoll(
        eml(
            acmeAgentUser.email(),
            INTERNAL,
            null,
            "Re: [" + refOf(c.ticketId()) + "]",
            null,
            "x",
            "pass"));
    assertThat(lastInbound().get("reason")).isEqualTo("NOT_A_SUPPORTER");
  }

  // ---------------------------------------------------------------------------------------------
  // Outbound

  @Test
  void anInternalNoteIsNeverEmailed() {
    Created c = acmeTicket();
    int before = outboxFor(c.ticketId()).size();
    notes.addNote(acmeAgent, c.ticketId(), "<p>Secret: admin password reset</p>", List.of());
    assertThat(outboxFor(c.ticketId())).hasSize(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE html_body LIKE '%admin password"
                    + " reset%' OR text_body LIKE '%admin password reset%'",
                Integer.class))
        .isZero();
  }

  @Test
  void aPublicReplyCarriesNameSignatureLogoReferenceAndOnlyTheNewReply() {
    Created c = acmeTicket();
    String ref = refOf(c.ticketId());
    tickets.addPublicReply(acmeAgent, c.ticketId(), "<p>First answer</p>", List.of(), null);
    tickets.addPublicReply(acmeAgent, c.ticketId(), "<p>Second answer</p>", List.of(), null);
    List<Map<String, Object>> mails = clientMailFor(c.ticketId());
    Map<String, Object> first = mails.get(mails.size() - 2);
    Map<String, Object> second = mails.get(mails.size() - 1);

    assertThat(first.get("from_name")).isEqualTo("Sam Example (Example MSP Support)");
    assertThat(first.get("reply_to")).isEqualTo(SUPPORT);
    assertThat(first.get("to_addresses")).isEqualTo(acmeCustomerUser.email());
    assertThat((String) first.get("subject")).startsWith("[" + ref + "] ");
    assertThat((String) first.get("html_body"))
        .contains("First answer")
        .contains("cid:signature-logo")
        .contains("Reference: " + ref);
    // A clear gap between the body and the signature (WP-17): a fixed-height spacer cell in the
    // HTML that Outlook and Gmail keep, and a blank line before "-- " in the plain-text part.
    String html = (String) first.get("html_body");
    int bodyEnd = html.indexOf("First answer</p>") + "First answer</p>".length();
    assertThat(html.substring(bodyEnd)).startsWith("<table role=\"presentation\"");
    assertThat(html).contains("class=\"ts-signature-gap\"").contains("height:24px");
    assertThat(html.indexOf("ts-signature-gap")).isLessThan(html.indexOf("cid:signature-logo"));
    assertThat((String) first.get("text_body")).matches("(?s).*First answer\\s*\\n\\n-- \\n.*");
    assertThat((String) first.get("text_body")).contains("Reference: " + ref);
    List<Map<String, Object>> parts =
        jdbc.queryForList(
            "SELECT content_id, inline, content_type FROM outbox_part WHERE outbox_message_id ="
                + " ?",
            first.get("id"));
    assertThat(parts)
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.get("content_id")).isEqualTo("signature-logo");
              assertThat(p.get("inline")).isEqualTo(true);
              assertThat(p.get("content_type")).isEqualTo("image/png");
            });

    // Second reply (ADR-015 change 2026-11-20): the full signature and logo again, never the
    // history.
    assertThat((String) second.get("html_body"))
        .contains("Second answer")
        .doesNotContain("First answer")
        .contains("cid:signature-logo");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_part WHERE outbox_message_id = ? AND content_id ="
                    + " 'signature-logo'",
                Integer.class,
                second.get("id")))
        .isEqualTo(1);
    assertThat((String) second.get("extra_headers")).contains("References: ");
  }

  @Test
  void aClientReplyAlertsTheAssigneeThroughTheInternalAddress() throws Exception {
    Created c = acmeTicket();
    tickets.assign(acmeAgent, c.ticketId(), acmeAgentUser.id());
    deliverAndPoll(
        mail(acmeCustomerUser.email(), "Re: [" + refOf(c.ticketId()) + "]", "More info"));
    Map<String, Object> alert =
        jdbc.queryForMap(
            "SELECT * FROM outbox_message WHERE ticket_id = ? AND kind = 'internal' AND"
                + " to_addresses = ? AND subject LIKE '%client replied%' ORDER BY id DESC LIMIT 1",
            c.ticketId(), acmeAgentUser.email());
    // One alert only: WP-09's notification (agent-only, "(Internal)" with the reference).
    assertThat((String) alert.get("subject"))
        .contains("(Internal)")
        .contains(refOf(c.ticketId()))
        .containsIgnoringCase("client replied");
  }

  @Test
  void resolvedSendsANoticeAndTriageTicketsGetNoConfirmation() {
    Created c = acmeTicket();
    int before = clientMailFor(c.ticketId()).size();
    tickets.changeStatus(acmeAgent, c.ticketId(), TicketStatus.RESOLVED);
    List<Map<String, Object>> mails = clientMailFor(c.ticketId());
    assertThat(mails).hasSize(before + 1);
    assertThat((String) mails.get(mails.size() - 1).get("text_body")).contains("is resolved");
    Created triage = triageTicket();
    assertThat(clientMailFor(triage.ticketId())).isEmpty();
  }

  @Test
  void aReporterMovedOutOfTheOrganisationGetsNoMore() {
    Created c = acmeTicket();
    // The reporter's account now belongs to another organisation (AUD-10 Low).
    jdbc.update(
        "UPDATE app_user SET organisation_id = ? WHERE id = ?", beta, acmeCustomerUser.id());
    int before = clientMailFor(c.ticketId()).size();
    tickets.addPublicReply(acmeAgent, c.ticketId(), "<p>Hello?</p>", List.of(), null);
    assertThat(clientMailFor(c.ticketId())).hasSize(before);
  }

  // ---------------------------------------------------------------------------------------------
  // Mailbox failures and OAuth2

  @Test
  void aLoginFailureIsRecordedRetriedWithBackOffAndLosesNoMail() throws Exception {
    mailboxService.update(admin, mailboxId, passwordMailbox("wrong-password"), null);
    deliver(mail(acmeCustomerUser.email(), "Kept for later", "Body"));
    PollReport failed = poll();
    assertThat(failed.failures()).isOne();
    Map<String, Object> m =
        jdbc.queryForMap(
            "SELECT last_error, consecutive_failures, next_poll_at FROM mailbox WHERE id = ?",
            mailboxId);
    assertThat(m.get("last_error")).isEqualTo("login failed");
    assertThat(m.get("next_poll_at")).isNotNull();
    // Back-off: the mailbox is not due again yet.
    assertThat(poll().mailboxes()).isZero();
    mailboxService.update(admin, mailboxId, passwordMailbox(IMAP_PASSWORD), null);
    poll();
    assertThat(lastInbound().get("subject")).isEqualTo("Kept for later");
  }

  @Test
  void oauth2ClientCredentialsLogInWithAToken() throws Exception {
    int port = TestPorts.free();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.createContext(
        "/token",
        exchange -> {
          String form =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          boolean ok =
              form.contains("grant_type=client_credentials")
                  && form.contains("client_secret=synthetic-client-secret");
          byte[] body =
              (ok
                      ? "{\"access_token\":\"" + IMAP_PASSWORD + "\",\"expires_in\":3600}"
                      : "{\"error\":\"invalid_client\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(ok ? 200 : 401, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      MailboxRequest oauth =
          new MailboxRequest(
              "Support",
              SUPPORT,
              true,
              "127.0.0.1",
              imapPort,
              "NONE",
              "INBOX",
              "OAUTH2",
              SUPPORT,
              "synthetic-client-secret",
              null,
              "http://127.0.0.1:" + port + "/token",
              "synthetic-client-id",
              null,
              List.of(AUTHSERV));
      MailboxView v = mailboxService.update(admin, mailboxId, oauth, null);
      assertThat(v.authType()).isEqualTo("OAUTH2");
      assertThat(v.secretSet()).isTrue();
      deliverAndPoll(mail(acmeCustomerUser.email(), "Via OAuth", "Body"));
      assertThat(lastInbound().get("subject")).isEqualTo("Via OAuth");

      MailboxRequest badSecret =
          new MailboxRequest(
              "Support",
              SUPPORT,
              true,
              "127.0.0.1",
              imapPort,
              "NONE",
              "INBOX",
              "OAUTH2",
              SUPPORT,
              "wrong-secret",
              null,
              "http://127.0.0.1:" + port + "/token",
              "synthetic-client-id",
              null,
              List.of(AUTHSERV));
      mailboxService.update(admin, mailboxId, badSecret, null);
      assertThat(poll().failures()).isOne();
      assertThat(
              jdbc.queryForObject(
                  "SELECT last_error FROM mailbox WHERE id = ?", String.class, mailboxId))
          .isEqualTo("token request failed");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void theSecretIsEncryptedAndNeverReturned() {
    byte[] stored =
        jdbc.queryForObject(
            "SELECT secret_ciphertext FROM mailbox WHERE id = ?", byte[].class, mailboxId);
    assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(IMAP_PASSWORD);
    assertThat(mailboxService.get(admin, mailboxId).toString()).doesNotContain(IMAP_PASSWORD);
  }

  @Test
  void testConnectionLogsInAndAProbeArrivingAtTheInternalAddressIsRecognised() throws Exception {
    MailboxService.TestResult result = mailboxService.test(admin, mailboxId);
    assertThat(result.loginOk()).isTrue();
    String token =
        jdbc.queryForObject(
            "SELECT extra_headers FROM outbox_message WHERE kind = 'internal' AND subject ="
                + " '(Internal) Mailbox test' ORDER BY id DESC LIMIT 1",
            String.class);
    String value = token.substring(token.indexOf(": ") + 2).strip();
    deliverAndPoll(
        eml(
            SUPPORT,
            INTERNAL,
            null,
            "Mailbox test",
            Map.of(MailboxService.PROBE_HEADER, value),
            "Test",
            "pass"));
    assertThat(lastInbound().get("outcome")).isEqualTo("PROBE");
    assertThat(mailboxService.probeStatus(admin, mailboxId, result.probeId()).arrived()).isTrue();

    // A forged probe header with an unknown token is an ordinary mail, never dropped.
    deliverAndPoll(
        eml(
            "x-" + UUID.randomUUID() + "@elsewhere.example",
            SUPPORT,
            null,
            "Hidden?",
            Map.of(MailboxService.PROBE_HEADER, "0123456789abcdef"),
            "Test",
            "pass"));
    assertThat(lastInbound().get("outcome")).isEqualTo("TRIAGE");

    mailboxService.update(admin, mailboxId, passwordMailbox("wrong-password"), null);
    rateLimiter.reset();
    assertThat(mailboxService.test(admin, mailboxId).error()).isEqualTo("login failed");
  }

  @Test
  void testConnectionIsRateLimitedPerMailboxAndPerAdminWithNoLoginOrProbeWhenRefused() {
    rateLimiter.reset();
    long before = probeMails();
    assertThat(mailboxService.test(admin, mailboxId).loginOk()).isTrue();
    assertThat(probeMails()).isEqualTo(before + 1);

    // Second test inside 60 s: refused with a 429, nothing queued.
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mailboxService.test(admin, mailboxId))
        .isInstanceOf(com.ticketsystem.api.auth.throttle.ThrottledException.class);
    assertThat(probeMails()).isEqualTo(before + 1);

    // Per admin: 10 an hour across mailboxes (distinct mailbox keys, same admin).
    rateLimiter.reset();
    for (int i = 0; i < 10; i++) {
      rateLimiter.consume(
          com.ticketsystem.api.auth.throttle.RateLimiter.Scope.MAILBOX_TEST_ADMIN,
          String.valueOf(admin.principalId().orElse(null)));
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mailboxService.test(admin, mailboxId))
        .isInstanceOf(com.ticketsystem.api.auth.throttle.ThrottledException.class);
    assertThat(probeMails()).isEqualTo(before + 1);
  }

  private long probeMails() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox_message WHERE kind = 'internal' AND subject ="
            + " '(Internal) Mailbox test'",
        Long.class);
  }

  @Test
  void agentsAndCustomersCannotUseTheIntakeTables() {
    AppUser agent = activeUser(Role.AGENT);
    AccessScope agentScope = AccessScope.agent(agent.id(), true, java.util.Set.of());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mailboxService.list(agentScope))
        .hasMessageContaining("access");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> rulesService.blockRules(acmeCustomer))
        .hasMessageContaining("access");
  }

  // ---------------------------------------------------------------------------------------------
  // Audit fixes (AUD-13, "Audit - PR #13")

  static String bigMail(String from, String subject, int bytes) {
    StringBuilder body = new StringBuilder(bytes + 128);
    String line = "x".repeat(76) + "\r\n";
    while (body.length() < bytes) {
      body.append(line);
    }
    return mail(from, subject, body.toString());
  }

  @Test
  void largeMailsAreReadOneAtATimeWithinARoundBudgetAndAnOversizedOneBecomesTriage()
      throws Exception {
    int mb = 1024 * 1024;
    for (int i = 1; i <= 3; i++) {
      deliver(bigMail(acmeCustomerUser.email(), "Big " + i, mb));
    }
    deliver(bigMail(acmeCustomerUser.email(), "Oversized", 3 * mb));
    deliver(mail(acmeCustomerUser.email(), "Small after", "Hi"));
    // Small limits stand in for the defaults (200 mails, 100 MB per round, 30 MB cap).
    ImapPoller.RoundLimits limits = new ImapPoller.RoundLimits(200, 2L * mb + mb / 2, 2L * mb);

    PollReport first = poller.pollOnce("test-big", limits);
    assertThat(first.outcomes()).containsOnlyKeys("CREATED").containsEntry("CREATED", 2);
    assertThat(first.stoppedEarly()).isTrue();
    assertThat(first.bytesRead()).isGreaterThan(2L * mb);
    // Read one at a time: never more than one message body held, although 2 MB were read.
    assertThat(first.peakHeldBytes()).isLessThan(mb + mb / 10);

    PollReport second = poller.pollOnce("test-big", limits);
    assertThat(second.outcomes()).containsEntry("CREATED", 2).containsEntry("TRIAGE", 1);
    assertThat(second.peakHeldBytes()).isLessThan(mb + mb / 10);
    Map<String, Object> big =
        jdbc.queryForMap(
            "SELECT reason, raw_size, octet_length(raw_eml) AS kept, uid, ticket_id FROM"
                + " inbound_mail WHERE mailbox_id = ? AND outcome = 'TRIAGE' ORDER BY id DESC"
                + " LIMIT 1",
            mailboxId);
    assertThat(big.get("reason")).isEqualTo("UNPARSEABLE");
    assertThat(((Number) big.get("raw_size")).longValue()).isGreaterThan(3L * mb);
    // Only a truncated copy is stored (AUD-13 L10), and the mark moved past it (no retry loop).
    assertThat(((Number) big.get("kept")).longValue())
        .isPositive()
        .isLessThanOrEqualTo(ImapMailboxClient.TRUNCATED_COPY_BYTES);
    assertThat(orgOf(ticketOf(big))).isNull();
    long lastUid =
        jdbc.queryForObject("SELECT last_uid FROM mailbox WHERE id = ?", Long.class, mailboxId);
    assertThat(lastUid).isGreaterThan(((Number) big.get("uid")).longValue());
    assertThat(poller.pollOnce("test-big", limits).outcomes()).isEmpty();
  }

  @Test
  void theImapClientHandsOutOneMessageAtATime() throws Exception {
    deliver(mail(acmeCustomerUser.email(), "One", "a"));
    deliver(mail(acmeCustomerUser.email(), "Two", "b"));
    ImapMailboxClient.Connection c =
        new ImapMailboxClient.Connection("127.0.0.1", imapPort, "NONE", "INBOX", SUPPORT, false);
    try (ImapMailboxClient client = ImapMailboxClient.open(c, IMAP_PASSWORD)) {
      List<ImapMailboxClient.Pending> pending = client.listAfter(0, 10);
      assertThat(pending).hasSize(2);
      ImapMailboxClient.Fetched one =
          client.download(pending.get(0).uid(), 1_000_000).orElseThrow();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> client.download(pending.get(1).uid(), 1_000_000))
          .isInstanceOf(IllegalStateException.class);
      client.release(one);
      ImapMailboxClient.Fetched two =
          client.download(pending.get(1).uid(), 1_000_000).orElseThrow();
      assertThat(two.tooLarge()).isFalse();
      assertThat(new String(two.raw(), StandardCharsets.UTF_8)).contains("Subject: Two");
    }
  }

  @Test
  void twentyForgedMailsDoNotCaptureTheClientsNextGenuineMail() throws Exception {
    String client = acmeCustomerUser.email();
    for (int i = 1; i <= 20; i++) {
      deliver(eml(client, SUPPORT, null, "Forged " + i, null, "Pay this", "fail"));
    }
    deliver(mail(client, "Genuine request", "Real one"));
    PollReport report = poll();
    assertThat(report.outcomes())
        .containsEntry("TRIAGE", 20)
        .containsEntry("CREATED", 1)
        .doesNotContainKey("FOLDED");
    Map<String, Object> genuine = lastInbound();
    assertThat(genuine.get("outcome")).isEqualTo("CREATED");
    long t = ticketOf(genuine);
    assertThat(orgOf(t)).isEqualTo(acme);
    assertThat(clientMailFor(t)).hasSize(1); // its confirmation, with a [REF] to reply to
    // The 21st forged mail folds among the forged triage tickets only.
    deliverAndPoll(eml(client, SUPPORT, null, "Forged 21", null, "Pay", "fail"));
    Map<String, Object> folded = lastInbound();
    assertThat(folded.get("outcome")).isEqualTo("FOLDED");
    assertThat(ticketOf(folded)).isNotEqualTo(t);
    assertThat(orgOf(ticketOf(folded))).isNull();
  }

  @Test
  void aRestoredMailFromASavedContactGetsNoConfirmation() throws Exception {
    rulesService.addBlockRule(
        admin,
        rulesService.checkBlockRule(
            admin, new BlockRuleRequest("address", acmeCustomerUser.email())),
        null);
    deliverAndPoll(mail(acmeCustomerUser.email(), "Blocked then restored", "Body"));
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("BLOCKED");
    long t = rulesService.restore(admin, ((Number) row.get("id")).longValue());
    assertThat(orgOf(t)).isEqualTo(acme);
    assertThat(clientMailFor(t)).isEmpty();
  }

  @Test
  void aMailCommittedMeanwhileByAnotherPollerIsRolledBackAndLoggedAsDuplicate() throws Exception {
    String subject = "Raced " + UUID.randomUUID();
    deliver(mail(acmeCustomerUser.email(), subject, "Body"));
    long[] uids =
        greenMail.getManagers().getImapHostManager().getInbox(supportUser).getMessageUids();
    long uid = uids[uids.length - 1];
    Long validity =
        jdbc.queryForObject("SELECT uid_validity FROM mailbox WHERE id = ?", Long.class, mailboxId);
    // Another poller committed this UID between our duplicate check and our insert.
    jdbc.update(
        "INSERT INTO inbound_mail (mailbox_id, uid_validity, uid, message_id, raw_sha256,"
            + " raw_size, raw_purge_after, received_at, outcome) VALUES (?, ?, ?, ?, ?, 0, now(),"
            + " now(), 'CREATED')",
        mailboxId,
        validity,
        uid,
        "<other-" + UUID.randomUUID() + "@mail.example>",
        "0".repeat(64));
    PollReport report = poll();
    assertThat(report.outcomes()).containsEntry("DUPLICATE", 1).doesNotContainKey("CREATED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket WHERE subject = ?", Integer.class, subject))
        .isZero();
    assertThat(
            jdbc.queryForObject("SELECT last_uid FROM mailbox WHERE id = ?", Long.class, mailboxId))
        .isGreaterThanOrEqualTo(uid);
  }

  @Test
  void blockingAParentOfAClientsVerifiedDomainNeedsConfirmation() {
    String parent = "parent-" + UUID.randomUUID().toString().replace("-", "") + ".example";
    jdbc.update(
        "INSERT INTO organisation_domain (domain, organisation_id, created_at) VALUES (?, ?,"
            + " now())",
        "eu." + parent,
        acme);
    BlockCheck check = rulesService.checkBlockRule(admin, new BlockRuleRequest("domain", parent));
    assertThat(check.needsConfirmation()).isTrue();
    assertThat(check.warning()).contains("eu." + parent);
  }

  @Test
  void foldingIntoAClosedTicketOpensOneFollowUpThatTakesTheNextFoldedMails() throws Exception {
    String client = acmeCustomerUser.email();
    for (int i = 1; i <= 20; i++) {
      deliver(mail(client, "Burst " + i, "Mail " + i));
    }
    poll();
    long latest = ticketOf(lastInbound());
    forceStatus(latest, TicketStatus.CLOSED);
    deliver(mail(client, "Burst 21", "x"));
    deliver(mail(client, "Burst 22", "y"));
    assertThat(poll().outcomes()).containsEntry("FOLDED", 2);
    List<Map<String, Object>> folded =
        jdbc.queryForList(
            "SELECT ticket_id, new_ticket FROM inbound_mail WHERE outcome = 'FOLDED' AND"
                + " from_address = ? ORDER BY id",
            client);
    long followUp = ticketOf(folded.get(0));
    assertThat(followUp).isNotEqualTo(latest);
    assertThat(
            jdbc.queryForObject(
                "SELECT follow_up_of_id FROM ticket WHERE id = ?", Long.class, followUp))
        .isEqualTo(latest);
    assertThat(folded.get(0).get("new_ticket")).isEqualTo(true);
    assertThat(ticketOf(folded.get(1))).isEqualTo(followUp);
    assertThat(folded.get(1).get("new_ticket")).isEqualTo(false);
  }

  @Test
  void anUndecryptableMailboxDoesNotStopTheOthers() throws Exception {
    String tag = UUID.randomUUID().toString().replace("-", "");
    String brokenAddress = "broken-" + tag + "@acmemsp.example";
    String helpAddress = "help-" + tag + "@acmemsp.example";
    GreenMailUser helpUser = greenMail.setUser(helpAddress, helpAddress, IMAP_PASSWORD);
    long broken =
        mailboxService
            .create(admin, otherMailbox("Broken", brokenAddress, "synthetic-pw-2"), null)
            .id();
    jdbc.update("UPDATE mailbox SET secret_key_id = 'retired-key' WHERE id = ?", broken);
    long help =
        mailboxService.create(admin, otherMailbox("Help", helpAddress, IMAP_PASSWORD), null).id();
    try {
      helpUser.deliver(
          new MimeMessage(
              Session.getInstance(new Properties()),
              new ByteArrayInputStream(
                  eml(acmeCustomerUser.email(), helpAddress, null, "Via help", null, "Hi", "pass")
                      .getBytes(StandardCharsets.UTF_8))));
      PollReport report = poll();
      assertThat(report.failures()).isOne();
      assertThat(
              jdbc.queryForObject(
                  "SELECT last_error FROM mailbox WHERE id = ?", String.class, broken))
          .isEqualTo("stored secret unreadable");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM inbound_mail WHERE mailbox_id = ? AND subject = 'Via"
                      + " help'",
                  Integer.class,
                  help))
          .isOne();
    } finally {
      jdbc.update("UPDATE mailbox SET enabled = FALSE WHERE id IN (?, ?)", broken, help);
    }
  }

  static MailboxRequest otherMailbox(String name, String address, String password) {
    return new MailboxRequest(
        name,
        address,
        true,
        "127.0.0.1",
        imapPort,
        "NONE",
        "INBOX",
        "PASSWORD",
        address,
        password,
        null,
        null,
        null,
        null,
        List.of(AUTHSERV));
  }
}
