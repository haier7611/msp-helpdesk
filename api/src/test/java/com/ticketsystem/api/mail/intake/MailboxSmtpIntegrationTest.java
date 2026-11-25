package com.ticketsystem.api.mail.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.mail.MailKind;
import com.ticketsystem.api.mail.MailOutbox;
import com.ticketsystem.api.mail.OutboundEmail;
import com.ticketsystem.api.mail.OutboxWorker;
import com.ticketsystem.api.mail.SupportName;
import com.ticketsystem.api.mail.SystemEmail;
import com.ticketsystem.api.mail.SystemMailService;
import com.ticketsystem.api.mail.TestGreenMail;
import com.ticketsystem.api.mail.TestPorts;
import com.ticketsystem.api.mail.intake.MailboxService.MailboxRequest;
import com.ticketsystem.api.mail.intake.MailboxService.MailboxView;
import com.ticketsystem.api.mail.intake.job.MailboxSender;
import com.ticketsystem.api.ticket.ReplyCcService.ReplyCc;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketTestBase;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-034 end to end: a server-wide SMTP (one GreenMail) and a support mailbox with its own IMAP
 * and SMTP (a second GreenMail that requires sign-in), the real outbox worker, ticket services and
 * Postgres (Testcontainers). Ticket mail and, since ADR-046, account mail go through the mailbox
 * when it has outgoing settings, else through the server SMTP; reserved test domains are never sent
 * through the mailbox. Every name, address and password is synthetic; the real-looking recipients
 * are under the IANA-reserved example.com.
 */
@ExtendWith(OutputCaptureExtension.class)
class MailboxSmtpIntegrationTest extends TicketTestBase {

  static final String SUPPORT = "support@helpdesk.example.com";
  static final String SERVER_FROM = "noreply@server.example.com";
  static final String IMAP_PASSWORD = "synthetic-imap-password-34";
  static final String SMTP_USER = "outgoing@helpdesk.example.com";
  static final String SMTP_PASSWORD = "synthetic-SMTP-app-password-34";

  /**
   * Ports actually bound, chosen by {@link #startMail()} on fresh ports with retries. They are not
   * picked at class load and bound later: on a busy CI host the project's port range can be inside
   * Linux's ephemeral range (32768-60999), so in the meantime any outgoing connection (for example
   * a JDBC connection to the test Postgres) can take one as its local port and the later bind
   * fails.
   */
  static int serverSmtpPort;

  static int imapPort;
  static int mailboxSmtpPort;

  static GreenMail serverSmtp;
  static GreenMail mailboxMail;

  @DynamicPropertySource
  static void serverSmtp(DynamicPropertyRegistry registry) {
    registry.add("app.mail.from-address", () -> SERVER_FROM);
    registry.add("app.mail.smtp.host", () -> "127.0.0.1");
    registry.add(
        "app.mail.smtp.port",
        () -> {
          startMail();
          return serverSmtpPort;
        });
    registry.add("app.mail.smtp.security", () -> "none");
    registry.add("app.mail.smtp.connect-timeout", () -> "2s");
    registry.add("app.mail.smtp.read-timeout", () -> "5s");
  }

  @Autowired MailboxService mailboxService;
  @Autowired OutboxWorker worker;
  @Autowired MailOutbox outbox;
  @Autowired SystemMailService systemMail;
  @Autowired SupportName supportName;
  @Autowired TransactionTemplate tx;

  long mailboxId;
  String customerEmail;

  /**
   * Starts both GreenMails once, each on fresh ports with TestGreenMail's retries (as
   * MailOutboxIntegrationTest does): first from the Spring context's property supplier, which needs
   * the server SMTP port, and again (a no-op) before the tests.
   */
  @BeforeAll
  static synchronized void startMail() {
    if (serverSmtp == null) {
      TestGreenMail.Started server = TestGreenMail.start(ServerSetup.PROTOCOL_SMTP);
      serverSmtp = server.greenMail();
      serverSmtpPort = server.port(ServerSetup.PROTOCOL_SMTP);
    }
    if (mailboxMail == null) {
      TestGreenMail.Started mailbox =
          TestGreenMail.start(ServerSetup.PROTOCOL_IMAP, ServerSetup.PROTOCOL_SMTP);
      mailboxMail = mailbox.greenMail();
      imapPort = mailbox.port(ServerSetup.PROTOCOL_IMAP);
      mailboxSmtpPort = mailbox.port(ServerSetup.PROTOCOL_SMTP);
      mailboxMail.setUser(SUPPORT, SUPPORT, IMAP_PASSWORD);
      mailboxMail.setUser(SMTP_USER, SMTP_USER, SMTP_PASSWORD);
    }
  }

  @AfterAll
  static synchronized void stopMail() {
    if (serverSmtp != null) {
      serverSmtp.stop();
      serverSmtp = null;
    }
    if (mailboxMail != null) {
      mailboxMail.stop();
      mailboxMail = null;
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    serverSmtp.purgeEmailFromAllMailboxes();
    mailboxMail.purgeEmailFromAllMailboxes();
    clearSmtpEverywhere();
    jdbc.update("DELETE FROM outbox_message");
    mailboxId = save(request(null, null, null, null));
    jdbc.update("UPDATE mailbox SET enabled = FALSE WHERE id <> ?", mailboxId);
    customerEmail = "sam-" + UUID.randomUUID() + "@client.example.com";
    jdbc.update("UPDATE app_user SET email = ? WHERE id = ?", customerEmail, acmeCustomerUser.id());
  }

  /** Other test classes share the database: never leave a sending mailbox behind. */
  @AfterEach
  void noSendingMailbox() {
    clearSmtpEverywhere();
    jdbc.update("UPDATE mailbox SET enabled = FALSE WHERE id = ?", mailboxId);
  }

  void clearSmtpEverywhere() {
    jdbc.update(
        "UPDATE mailbox SET smtp_host = NULL, smtp_port = NULL, smtp_security = NULL,"
            + " smtp_last_error = NULL, smtp_same_sign_in = TRUE, smtp_username = NULL,"
            + " smtp_secret_ciphertext = NULL, smtp_secret_key_id = NULL");
  }

  /**
   * The support mailbox; {@code smtpHost} null = receive only. {@code smtpUser} null = the same
   * sign-in as incoming.
   */
  static MailboxRequest request(
      String smtpHost, String security, String smtpUser, String smtpPassword) {
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
        IMAP_PASSWORD,
        null,
        null,
        null,
        null,
        List.of(),
        smtpHost,
        smtpHost == null ? null : mailboxSmtpPort,
        security,
        smtpUser == null,
        smtpUser,
        smtpPassword);
  }

  long save(MailboxRequest r) {
    List<Long> existing =
        jdbc.queryForList("SELECT id FROM mailbox WHERE address = ?", Long.class, SUPPORT);
    if (existing.isEmpty()) {
      return mailboxService.create(admin, r, "203.0.113.1").id();
    }
    mailboxService.update(admin, existing.get(0), r, "203.0.113.1");
    return existing.get(0);
  }

  void sameSignIn() {
    save(request("127.0.0.1", "NONE", null, null));
  }

  void separateSignIn(String password) {
    save(request("127.0.0.1", "NONE", SMTP_USER, password));
  }

  /** A public reply as the only queued mail (no ticket confirmation or agent alert around it). */
  void reply(Created c, String html) {
    jdbc.update("DELETE FROM outbox_message");
    tickets.addPublicReply(acmeAgent, c.ticketId(), html, List.of(), null);
    jdbc.update("DELETE FROM outbox_message WHERE kind <> 'client_facing'");
  }

  static MimeMessage only(GreenMail server) {
    MimeMessage[] received = server.getReceivedMessages();
    assertThat(received).hasSize(1);
    return received[0];
  }

  static String from(MimeMessage m) throws Exception {
    return ((InternetAddress) m.getFrom()[0]).getAddress();
  }

  String lastStatus() {
    return jdbc.queryForObject(
        "SELECT status FROM outbox_message ORDER BY id DESC LIMIT 1", String.class);
  }

  List<String> systemEntries(long ticketId) {
    return jdbc.queryForList(
        "SELECT body_text FROM system_entry WHERE ticket_id = ? AND kind = ?",
        String.class,
        ticketId,
        MailboxSender.NOT_EMAILED_KIND);
  }

  List<Map<String, Object>> audit(String type) {
    return jdbc.queryForList(
        "SELECT details::text AS details FROM audit_event WHERE type = ? AND actor_user_id = ?"
            + " ORDER BY id",
        type,
        adminUser.id());
  }

  // ---------------------------------------------------------------------------------------------
  // Routing

  @Test
  void aTicketReplyGoesThroughTheServerSmtpWithoutOutgoingSettingsAndTheMailboxSmtpWith()
      throws Exception {
    Created c = acmeTicket();
    reply(c, "<p>Via the server</p>");
    worker.runOnce();
    MimeMessage viaServer = only(serverSmtp);
    assertThat(from(viaServer)).isEqualTo(SERVER_FROM);
    assertThat(mailboxMail.getReceivedMessages()).isEmpty();

    serverSmtp.purgeEmailFromAllMailboxes();
    separateSignIn(SMTP_PASSWORD);
    reply(c, "<p>Via the mailbox</p>");
    worker.runOnce();
    MimeMessage viaMailbox = only(mailboxMail);
    assertThat(from(viaMailbox)).isEqualTo(SUPPORT);
    // From keeps the agent's display name, as before.
    assertThat(((InternetAddress) viaMailbox.getFrom()[0]).getPersonal())
        .startsWith(acmeAgentUser.name());
    assertThat(viaMailbox.getRecipients(Message.RecipientType.TO)[0].toString())
        .isEqualTo(customerEmail);
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
    assertThat(lastStatus()).isEqualTo("sent");
    assertThat(mailboxService.get(admin, mailboxId).smtpLastError()).isNull();
  }

  @Test
  void theSameSignInAsIncomingSendsTicketMailToo() throws Exception {
    sameSignIn();
    assertThat(mailboxService.get(admin, mailboxId).sending()).isTrue();
    Created c = acmeTicket();
    reply(c, "<p>Same sign-in</p>");
    worker.runOnce();
    assertThat(from(only(mailboxMail))).isEqualTo(SUPPORT);
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Account mail (ADR-046 Decision 1)

  /** A password-changed notice (no token) queued as the only mail. */
  void accountNotice(String to) {
    jdbc.update("DELETE FROM outbox_message");
    tx.executeWithoutResult(
        s ->
            systemMail.send(
                SystemEmail.PASSWORD_CHANGED,
                to,
                Map.of("recipientName", "Lee", "link", "https://support.example.com/forgot"),
                null));
  }

  /** An agent invitation (token-bearing, sensitive) queued as the only mail. */
  void invitation(String to) {
    jdbc.update("DELETE FROM outbox_message");
    tx.executeWithoutResult(
        s ->
            systemMail.send(
                SystemEmail.SUPPORTER_INVITATION,
                to,
                Map.of(
                    "recipientName",
                    "Lee",
                    "link",
                    "https://support.example.com/accept#synthetic-token",
                    "validFor",
                    "48 hours"),
                Instant.now().plus(Duration.ofHours(48))));
  }

  static String someone() {
    return "lee-" + UUID.randomUUID() + "@client.example.com";
  }

  @Test
  void anInvitationGoesThroughTheMailboxSmtpFromTheSupportAddressWithTheSupportName()
      throws Exception {
    separateSignIn(SMTP_PASSWORD);
    String to = someone();
    invitation(to);

    worker.runOnce();

    MimeMessage m = only(mailboxMail);
    assertThat(from(m)).isEqualTo(SUPPORT);
    assertThat(((InternetAddress) m.getFrom()[0]).getPersonal()).isEqualTo(supportName.get());
    assertThat(m.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo(to);
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
    assertThat(lastStatus()).isEqualTo("sent");
    assertThat(mailboxService.get(admin, mailboxId).smtpLastError()).isNull();
  }

  @Test
  void accountMailUsesTheSameSignInAsIncomingToo() throws Exception {
    sameSignIn();
    accountNotice(someone());

    worker.runOnce();

    assertThat(from(only(mailboxMail))).isEqualTo(SUPPORT);
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
  }

  @Test
  void accountMailFallsBackToTheServerSmtpWithoutOutgoingSettingsAndSwitchesWithoutARestart()
      throws Exception {
    // A receive-only mailbox: the server SMTP, unchanged.
    accountNotice(someone());
    worker.runOnce();
    MimeMessage viaServer = only(serverSmtp);
    assertThat(from(viaServer)).isEqualTo(SERVER_FROM);
    assertThat(mailboxMail.getReceivedMessages()).isEmpty();

    // Outgoing settings saved: the very next round sends through the mailbox.
    serverSmtp.purgeEmailFromAllMailboxes();
    separateSignIn(SMTP_PASSWORD);
    accountNotice(someone());
    worker.runOnce();
    assertThat(from(only(mailboxMail))).isEqualTo(SUPPORT);
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();

    // Removed again: back to the server SMTP.
    mailboxMail.purgeEmailFromAllMailboxes();
    save(request(null, null, null, null));
    accountNotice(someone());
    worker.runOnce();
    assertThat(from(only(serverSmtp))).isEqualTo(SERVER_FROM);
    assertThat(mailboxMail.getReceivedMessages()).isEmpty();
  }

  @Test
  void aDisabledMailboxWithOutgoingSettingsIsIgnoredForAccountMail() throws Exception {
    separateSignIn(SMTP_PASSWORD);
    jdbc.update("UPDATE mailbox SET enabled = FALSE WHERE id = ?", mailboxId);
    invitation(someone());

    worker.runOnce();

    assertThat(from(only(serverSmtp))).isEqualTo(SERVER_FROM);
    assertThat(mailboxMail.getReceivedMessages()).isEmpty();
  }

  @Test
  void aRefusedSignInRecordsTheErrorRetriesAccountMailAndNeverLeaksThePassword(
      CapturedOutput output) throws Exception {
    String wrong = "wrong-synthetic-password-46";
    separateSignIn(wrong);
    invitation(someone());

    worker.runOnce();

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, attempts, last_error, next_attempt_at > now() AS later"
                + " FROM outbox_message");
    assertThat(row.get("status")).isEqualTo("queued");
    assertThat(row.get("attempts")).isEqualTo(1);
    assertThat(row.get("later")).isEqualTo(true);
    assertThat(row.get("last_error")).isEqualTo("SMTP authentication failed");
    assertThat(mailboxService.get(admin, mailboxId).smtpLastError())
        .isEqualTo("SMTP authentication failed");
    // No fallback: never through the server SMTP (a different From, and on the demo a catcher).
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
    assertThat(mailboxMail.getReceivedMessages()).isEmpty();

    // Fixed and due again: the same row is sent on its second attempt, the error clears.
    separateSignIn(SMTP_PASSWORD);
    jdbc.update("UPDATE outbox_message SET next_attempt_at = now()");
    worker.runOnce();

    Map<String, Object> sent = jdbc.queryForMap("SELECT status, attempts FROM outbox_message");
    assertThat(sent.get("status")).isEqualTo("sent");
    assertThat(sent.get("attempts")).isEqualTo(2);
    assertThat(from(only(mailboxMail))).isEqualTo(SUPPORT);
    assertThat(mailboxService.get(admin, mailboxId).smtpLastError()).isNull();
    assertThat(output.getAll()).doesNotContain(wrong).doesNotContain(SMTP_PASSWORD);
  }

  @Test
  void accountMailToAReservedDomainIsNeverSentThroughTheMailbox(CapturedOutput output) {
    separateSignIn(SMTP_PASSWORD);
    String reserved = "pat-" + UUID.randomUUID() + "@clinic.test";
    invitation(reserved);

    worker.runOnce();

    assertThat(mailboxMail.getReceivedMessages()).isEmpty();
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
    Map<String, Object> row = jdbc.queryForMap("SELECT status, last_error FROM outbox_message");
    assertThat(row.get("status")).isEqualTo("failed");
    assertThat(row.get("last_error")).isEqualTo("not emailed: test address");
    assertThat(output.getAll()).contains("reserved test domain").doesNotContain(reserved);
    assertThat(mailboxService.get(admin, mailboxId).smtpLastError()).isNull();
  }

  // ---------------------------------------------------------------------------------------------
  // Reserved test domains

  @Test
  void aReservedDomainRecipientIsNeverSentThroughTheMailboxAndTheTicketSaysSo(CapturedOutput output)
      throws Exception {
    String reserved = "pat-" + UUID.randomUUID() + "@clinic.example";
    jdbc.update("UPDATE app_user SET email = ? WHERE id = ?", reserved, acmeCustomerUser.id());
    separateSignIn(SMTP_PASSWORD);
    Created c = acmeTicket();
    reply(c, "<p>To a test address</p>");

    worker.runOnce();

    assertThat(mailboxMail.getReceivedMessages()).isEmpty();
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
    Map<String, Object> row = jdbc.queryForMap("SELECT status, last_error FROM outbox_message");
    assertThat(row.get("status")).isEqualTo("failed");
    assertThat(row.get("last_error")).isEqualTo("not emailed: test address");
    assertThat(systemEntries(c.ticketId())).containsExactly("Not emailed: test address");
    assertThat(output.getAll()).contains("reserved test domain").doesNotContain(reserved);
    // Not the mailbox's fault: its sending error stays clear.
    assertThat(mailboxService.get(admin, mailboxId).smtpLastError()).isNull();
  }

  @Test
  void aMixedRecipientListGoesToTheRealAddressesOnly() throws Exception {
    separateSignIn(SMTP_PASSWORD);
    Created c = acmeTicket();
    jdbc.update("DELETE FROM outbox_message");
    String real = "kim-" + UUID.randomUUID() + "@client.example.com";
    String realCc = "lou-" + UUID.randomUUID() + "@client.example.org";
    tx.executeWithoutResult(
        s ->
            outbox.enqueue(
                OutboundEmail.builder(MailKind.CLIENT_FACING)
                    .to("sam@clinic.test")
                    .cc(List.of(real, "x@sub.domain.invalid", "y@app.localhost", realCc))
                    .ticketId(c.ticketId())
                    .subject("Mixed")
                    .body("<p>Mixed</p>", "Mixed")
                    .build()));

    worker.runOnce();

    assertThat(lastStatus()).isEqualTo("sent");
    MimeMessage m = mailboxMail.getReceivedMessages()[0];
    assertThat(Arrays.stream(m.getRecipients(Message.RecipientType.TO)).map(Object::toString))
        .containsExactly(real);
    assertThat(Arrays.stream(m.getRecipients(Message.RecipientType.CC)).map(Object::toString))
        .containsExactly(realCc);
    // GreenMail keeps one copy per delivered recipient: exactly the two real ones.
    assertThat(mailboxMail.getReceivedMessages()).hasSize(2);
    assertThat(systemEntries(c.ticketId())).containsExactly("Not emailed: test address");
  }

  /** ADR-040: an agent's own new ticket for the client, as the agent who opens it. */
  Created agentOpenedTicket() {
    jdbc.update("DELETE FROM outbox_message");
    return ticket(acmeAgent, acme, acmeCustomerUser);
  }

  @Test
  void anAgentOpenedTicketGoesThroughTheMailboxLikeAReply() throws Exception {
    separateSignIn(SMTP_PASSWORD);
    Created c = agentOpenedTicket();
    jdbc.update("DELETE FROM outbox_message WHERE kind <> 'client_facing'");

    worker.runOnce();

    MimeMessage m = only(mailboxMail);
    assertThat(from(m)).isEqualTo(SUPPORT);
    assertThat(((InternetAddress) m.getFrom()[0]).getPersonal())
        .startsWith(acmeAgentUser.name() + " (");
    assertThat(((InternetAddress) m.getReplyTo()[0]).getAddress()).isEqualTo(SUPPORT);
    assertThat(m.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo(customerEmail);
    assertThat(m.getSubject()).startsWith("[" + c.reference() + "] ");
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
    assertThat(lastStatus()).isEqualTo("sent");
  }

  @Test
  void anAgentOpenedTicketForAReservedDomainContactIsNeverSentThroughTheMailbox() throws Exception {
    String reserved = "pat-" + UUID.randomUUID() + "@clinic.test";
    jdbc.update("UPDATE app_user SET email = ? WHERE id = ?", reserved, acmeCustomerUser.id());
    separateSignIn(SMTP_PASSWORD);
    Created c = agentOpenedTicket();
    jdbc.update("DELETE FROM outbox_message WHERE kind <> 'client_facing'");

    worker.runOnce();

    assertThat(mailboxMail.getReceivedMessages()).isEmpty();
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
    Map<String, Object> row = jdbc.queryForMap("SELECT status, last_error FROM outbox_message");
    assertThat(row.get("status")).isEqualTo("failed");
    assertThat(row.get("last_error")).isEqualTo("not emailed: test address");
    assertThat(systemEntries(c.ticketId())).containsExactly("Not emailed: test address");
  }

  @Test
  void aComposerCcOnAReservedDomainIsDroppedAndTheRealCcGoesThroughTheMailbox() throws Exception {
    // ADR-035 x ADR-034: the CC an agent chose in the composer passes the same guard as the To.
    separateSignIn(SMTP_PASSWORD);
    Created c = acmeTicket();
    jdbc.update("DELETE FROM outbox_message");
    String realCc = "lou-" + UUID.randomUUID() + "@client.example.org";
    String reservedCc = "pat-" + UUID.randomUUID() + "@clinic.test";
    tickets.addPublicReply(
        acmeAgent,
        c.ticketId(),
        "<p>With CC</p>",
        List.of(),
        null,
        new ReplyCc(List.of(realCc, reservedCc), false, List.of(realCc, reservedCc)));
    jdbc.update("DELETE FROM outbox_message WHERE kind <> 'client_facing'");
    assertThat(
            jdbc.queryForObject(
                "SELECT cc_addresses FROM outbox_message WHERE kind = 'client_facing'",
                String.class))
        .contains(realCc, reservedCc);

    worker.runOnce();

    assertThat(lastStatus()).isEqualTo("sent");
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
    MimeMessage m = mailboxMail.getReceivedMessages()[0];
    assertThat(from(m)).isEqualTo(SUPPORT);
    assertThat(Arrays.stream(m.getRecipients(Message.RecipientType.TO)).map(Object::toString))
        .containsExactly(customerEmail);
    assertThat(Arrays.stream(m.getRecipients(Message.RecipientType.CC)).map(Object::toString))
        .containsExactly(realCc);
    // One copy per delivered recipient: the To and the real CC, never the reserved one.
    assertThat(mailboxMail.getReceivedMessages()).hasSize(2);
    assertThat(systemEntries(c.ticketId())).containsExactly("Not emailed: test address");
  }

  // ---------------------------------------------------------------------------------------------
  // Failures: last sending error, no fallback

  @Test
  void aRefusedSignInIsTheLastSendingErrorAndTicketMailWaitsWithoutFallingBack() {
    separateSignIn("wrong-synthetic-password");
    Created c = acmeTicket();
    reply(c, "<p>Waits</p>");

    worker.runOnce();

    assertThat(lastStatus()).isEqualTo("queued");
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error FROM outbox_message ORDER BY id DESC LIMIT 1", String.class))
        .isEqualTo("SMTP authentication failed");
    assertThat(mailboxService.get(admin, mailboxId).smtpLastError())
        .isEqualTo("SMTP authentication failed");
    // Never through the server's SMTP instead (that would send from a different address).
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
    assertThat(mailboxMail.getReceivedMessages()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Test connection

  @Test
  void testConnectionSignsInToSmtpAndTheProbeGoesThroughTheMailbox() throws Exception {
    rateLimiter.reset();
    separateSignIn(SMTP_PASSWORD);
    jdbc.update("UPDATE mailbox SET smtp_last_error = 'old error' WHERE id = ?", mailboxId);

    MailboxService.TestResult result = mailboxService.test(admin, mailboxId);

    assertThat(result.loginOk()).as(result.message()).isTrue();
    assertThat(result.message()).contains("IMAP and SMTP");
    assertThat(result.probeId()).isNotNull();
    assertThat(mailboxService.get(admin, mailboxId).smtpLastError()).isNull();
    // Connect + AUTH + QUIT only: no message until the worker sends the probe.
    assertThat(mailboxMail.getReceivedMessages()).isEmpty();
    worker.runOnce();
    MimeMessage probe = only(mailboxMail);
    assertThat(from(probe)).isEqualTo(SUPPORT);
    assertThat(probe.getRecipients(Message.RecipientType.TO)[0].toString())
        .isEqualTo("support+internal@helpdesk.example.com");
    assertThat(probe.getHeader(MailboxService.PROBE_HEADER)).isNotNull();
    assertThat(serverSmtp.getReceivedMessages()).isEmpty();
  }

  @Test
  void testConnectionReportsARefusedSmtpSignInAndQueuesNothing() {
    rateLimiter.reset();
    separateSignIn("wrong-synthetic-password");

    MailboxService.TestResult result = mailboxService.test(admin, mailboxId);

    assertThat(result.loginOk()).isFalse();
    assertThat(result.error()).isEqualTo("SMTP authentication failed");
    assertThat(result.message()).contains("SMTP sign-in failed").contains("app password");
    assertThat(result.probeId()).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_message", Integer.class)).isZero();
    assertThat(mailboxService.get(admin, mailboxId).smtpLastError())
        .isEqualTo("SMTP authentication failed");
  }

  @Test
  void testConnectionReportsAnUnreachableSmtpServer() {
    rateLimiter.reset();
    MailboxRequest r = request("127.0.0.1", "NONE", SMTP_USER, SMTP_PASSWORD);
    save(
        new MailboxRequest(
            r.name(),
            r.address(),
            r.enabled(),
            r.imapHost(),
            r.imapPort(),
            r.imapSecurity(),
            r.imapFolder(),
            r.authType(),
            r.username(),
            null,
            null,
            null,
            null,
            null,
            List.of(),
            "127.0.0.1",
            TestPorts.free(), // nothing listens there
            "NONE",
            false,
            SMTP_USER,
            SMTP_PASSWORD));

    MailboxService.TestResult result = mailboxService.test(admin, mailboxId);

    assertThat(result.loginOk()).isFalse();
    assertThat(result.message()).contains("could not connect to the SMTP server");
    assertThat(mailboxService.get(admin, mailboxId).smtpLastError()).startsWith("connection");
  }

  // ---------------------------------------------------------------------------------------------
  // Settings: validation, secrets, audit, step-up

  @Test
  void plainTextSmtpWithCredentialsToARemoteServerIsRefused() {
    assertThatThrownBy(
            () ->
                mailboxService.update(
                    admin,
                    mailboxId,
                    request("smtp.remote.example.com", "NONE", SMTP_USER, SMTP_PASSWORD),
                    null))
        .hasMessageContaining("Plain-text SMTP would send the password unencrypted");
    assertThatThrownBy(
            () ->
                mailboxService.update(
                    admin, mailboxId, request("smtp.remote.example.com", "NONE", null, null), null))
        .hasMessageContaining("Plain-text SMTP");
    assertThat(mailboxService.get(admin, mailboxId).smtpHost()).isNull();
  }

  @Test
  void plainTextImapToARemoteServerIsRefusedWhateverTheSignIn() {
    MailboxRequest r = request(null, null, null, null);
    MailboxRequest remote =
        new MailboxRequest(
            r.name(),
            r.address(),
            r.enabled(),
            "imap.remote.example.com",
            r.imapPort(),
            "NONE",
            r.imapFolder(),
            r.authType(),
            r.username(),
            null,
            null,
            null,
            null,
            null,
            r.trustedAuthservIds(),
            null,
            null,
            null,
            true,
            null,
            null);
    assertThatThrownBy(() -> mailboxService.update(admin, mailboxId, remote, null))
        .hasMessageContaining("Plain-text IMAP is only allowed to this server itself");
    assertThat(mailboxService.get(admin, mailboxId).imapHost()).isEqualTo("127.0.0.1");
  }

  /** AUD-43: the mailbox-saved audit event records a changed trusted authserv-id list. */
  @Test
  void aChangedTrustedAuthservListIsAudited() {
    MailboxRequest r = request(null, null, null, null);
    MailboxRequest trusted =
        new MailboxRequest(
            r.name(),
            r.address(),
            r.enabled(),
            r.imapHost(),
            r.imapPort(),
            r.imapSecurity(),
            r.imapFolder(),
            r.authType(),
            r.username(),
            null,
            null,
            null,
            null,
            null,
            List.of("MX.Google.com", "(None)"),
            null,
            null,
            null,
            true,
            null,
            null);
    save(trusted);
    List<Map<String, Object>> saved = audit("MAILBOX_SAVED");
    assertThat(saved.get(saved.size() - 1).get("details").toString())
        .contains("\"trusted_authserv_before\": \"\"")
        .contains("\"trusted_authserv_after\": \"mx.google.com,(none)\"");

    // Saved again unchanged: no trusted-list keys.
    save(trusted);
    saved = audit("MAILBOX_SAVED");
    assertThat(saved.get(saved.size() - 1).get("details").toString())
        .doesNotContain("trusted_authserv");

    // Cleared: old value recorded, new one empty.
    save(r);
    saved = audit("MAILBOX_SAVED");
    assertThat(saved.get(saved.size() - 1).get("details").toString())
        .contains("\"trusted_authserv_before\": \"mx.google.com,(none)\"")
        .contains("\"trusted_authserv_after\": \"\"");
  }

  @Test
  void aSeparateSignInNeedsAUserNameAndAPassword() {
    assertThatThrownBy(
            () ->
                mailboxService.update(
                    admin, mailboxId, request("smtp.gmail.com", "TLS", SMTP_USER, null), null))
        .hasMessageContaining("Enter the SMTP password");
    assertThatThrownBy(
            () ->
                mailboxService.update(
                    admin, mailboxId, request("smtp.gmail.com", "TLS", " ", SMTP_PASSWORD), null))
        .hasMessageContaining("Enter the SMTP user name");
  }

  @Test
  void theSmtpPasswordIsEncryptedNeverReturnedNorLoggedAndAuditedWithoutCredentials(
      CapturedOutput output) throws Exception {
    Browser b = signedIn(adminUser);
    String body = mailboxJson("smtp.gmail.com", 465, "TLS", false, SMTP_USER, SMTP_PASSWORD);
    MockHttpServletResponse saved = b.put("/api/v1/admin/email/mailboxes/" + mailboxId, body);
    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    assertThat(saved.getContentAsString())
        .doesNotContain(SMTP_PASSWORD)
        .contains("\"smtpSecretSet\":true")
        .contains("\"smtpSameSignIn\":false");
    assertThat(b.get("/api/v1/admin/email/mailboxes").getContentAsString())
        .doesNotContain(SMTP_PASSWORD)
        .doesNotContain(IMAP_PASSWORD);
    byte[] stored =
        jdbc.queryForObject(
            "SELECT smtp_secret_ciphertext FROM mailbox WHERE id = ?", byte[].class, mailboxId);
    assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(SMTP_PASSWORD);
    MailboxView view = mailboxService.get(admin, mailboxId);
    assertThat(view.toString()).doesNotContain(SMTP_PASSWORD);

    // Changing only the password, then the server, then removing the outgoing settings.
    assertThat(
            b.put(
                    "/api/v1/admin/email/mailboxes/" + mailboxId,
                    mailboxJson("smtp.gmail.com", 465, "TLS", false, SMTP_USER, "new-SMTP-pass-2"))
                .getStatus())
        .isEqualTo(200);
    assertThat(
            b.put(
                    "/api/v1/admin/email/mailboxes/" + mailboxId,
                    mailboxJson("smtp.gmail.com", 587, "STARTTLS", false, SMTP_USER, null))
                .getStatus())
        .isEqualTo(200);
    assertThat(mailboxService.get(admin, mailboxId).smtpSecretSet()).isTrue();
    assertThat(
            b.put(
                    "/api/v1/admin/email/mailboxes/" + mailboxId,
                    mailboxJson(null, null, null, true, null, null))
                .getStatus())
        .isEqualTo(200);
    MailboxView removed = mailboxService.get(admin, mailboxId);
    assertThat(removed.smtpHost()).isNull();
    assertThat(removed.smtpSecretSet()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT smtp_secret_ciphertext IS NULL FROM mailbox WHERE id = ?",
                Boolean.class,
                mailboxId))
        .isTrue();

    List<Map<String, Object>> set = audit("MAILBOX_SMTP_SET");
    List<Map<String, Object>> changed = audit("MAILBOX_SMTP_CHANGED");
    List<Map<String, Object>> gone = audit("MAILBOX_SMTP_REMOVED");
    assertThat(set).hasSize(1);
    assertThat(set.get(0).get("details").toString())
        .contains("\"smtp_sign_in\": \"separate\"")
        .contains("\"smtp_security\": \"TLS\"");
    assertThat(changed).hasSize(2);
    assertThat(changed.get(0).get("details").toString()).contains("\"fields\": \"password\"");
    assertThat(changed.get(1).get("details").toString())
        .contains("\"fields\": \"server,security\"");
    assertThat(gone).hasSize(1);
    for (Map<String, Object> e : List.of(set.get(0), changed.get(0), changed.get(1), gone.get(0))) {
      assertThat(e.get("details").toString())
          .doesNotContain("gmail")
          .doesNotContain(SMTP_USER)
          .doesNotContain(SMTP_PASSWORD)
          .doesNotContain("new-SMTP-pass-2");
    }
    assertThat(output.getAll())
        .doesNotContain(SMTP_PASSWORD)
        .doesNotContain("new-SMTP-pass-2")
        .doesNotContain(IMAP_PASSWORD);
  }

  @Test
  void changingTheOutgoingSettingsNeedsAFreshStepUp() throws Exception {
    Browser b = signedIn(adminUser);
    clock.advance(Duration.ofMinutes(11));
    MockHttpServletResponse refused =
        b.put(
            "/api/v1/admin/email/mailboxes/" + mailboxId,
            mailboxJson("smtp.gmail.com", 465, "TLS", false, SMTP_USER, SMTP_PASSWORD));
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).contains("step_up_required");
    assertThat(mailboxService.get(admin, mailboxId).smtpHost()).isNull();
    assertThat(audit("MAILBOX_SMTP_SET")).isEmpty();
  }

  String mailboxJson(
      String smtpHost,
      Integer smtpPort,
      String smtpSecurity,
      boolean same,
      String smtpUser,
      String smtpPassword) {
    return "{\"name\":\"Support\",\"address\":"
        + Browser.q(SUPPORT)
        + ",\"enabled\":true,\"imapHost\":\"127.0.0.1\",\"imapPort\":"
        + imapPort
        + ",\"imapSecurity\":\"NONE\",\"imapFolder\":\"INBOX\",\"authType\":\"PASSWORD\","
        + "\"username\":"
        + Browser.q(SUPPORT)
        + ",\"secret\":null,\"trustedAuthservIds\":[],\"smtpHost\":"
        + (smtpHost == null ? "null" : Browser.q(smtpHost))
        + ",\"smtpPort\":"
        + (smtpPort == null ? "null" : smtpPort)
        + ",\"smtpSecurity\":"
        + (smtpSecurity == null ? "null" : Browser.q(smtpSecurity))
        + ",\"smtpSameSignIn\":"
        + same
        + ",\"smtpUsername\":"
        + (smtpUser == null ? "null" : Browser.q(smtpUser))
        + ",\"smtpSecret\":"
        + (smtpPassword == null ? "null" : Browser.q(smtpPassword))
        + "}";
  }
}
