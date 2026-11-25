package com.ticketsystem.api.mail.intake.poller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.ticketsystem.api.mail.TestPorts;
import com.ticketsystem.api.mail.intake.MailboxService;
import com.ticketsystem.api.mail.intake.MailboxService.MailboxRequest;
import com.ticketsystem.api.ticket.TicketTestBase;
import jakarta.mail.Flags;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-033: a newly connected mailbox only turns new emails into tickets. A real IMAP server
 * (GreenMail on 127.0.0.1), the real poller and Postgres (Testcontainers). Each test uses its own
 * synthetic mailbox address, so rows left by other tests never matter.
 */
class ImapPollerStartPositionIntegrationTest extends TicketTestBase {

  /** Synthetic, test-only IMAP password (not a secret). */
  static final String PASSWORD = "synthetic-imap-password-33";

  static final int IMAP_PORT = TestPorts.free();
  static GreenMail greenMail;

  @Autowired ImapPoller poller;
  @Autowired MailboxService mailboxService;

  String address;
  GreenMailUser user;

  @BeforeAll
  static void startImap() {
    greenMail = new GreenMail(new ServerSetup(IMAP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_IMAP));
    greenMail.start();
  }

  @AfterAll
  static void stopImap() {
    greenMail.stop();
  }

  @BeforeEach
  void freshMailbox() {
    // Only this test's mailbox is polled.
    jdbc.update("UPDATE mailbox SET enabled = FALSE");
    address = "start-" + UUID.randomUUID().toString().replace("-", "") + "@acmemsp.example";
    user = greenMail.setUser(address, address, PASSWORD);
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void mailAlreadyInTheFolderIsNotImportedOnTheFirstPoll() throws Exception {
    deliver("Old one");
    deliver("Old two");
    long id = create(true, "INBOX");
    // Recorded at save: the mark sits on the last mail already there (UIDNEXT - 1).
    assertThat(position(id).get("uid_validity")).isNotNull();
    assertThat((Long) position(id).get("last_uid")).isGreaterThanOrEqualTo(2L);

    poller.pollOnce();

    assertThat(imported(id)).isEmpty();
  }

  @Test
  void mailDeliveredAfterTheMailboxIsCreatedIsImported() throws Exception {
    deliver("Before");
    long id = create(true, "INBOX");
    deliver("After");

    poller.pollOnce();

    assertThat(imported(id)).containsExactly("After");
  }

  @Test
  void twoMailsArrivingDuringAnOutageAreBothImported() throws Exception {
    long id = create(true, "INBOX");
    poller.pollOnce();
    // The server rejects the login for a while: polls fail, mail keeps arriving.
    mailboxService.update(admin, id, request(true, "INBOX", "wrong-password"), null);
    deliver("During outage 1");
    deliver("During outage 2");
    assertThat(poller.pollOnce().failures()).isOne();
    assertThat(imported(id)).isEmpty();

    mailboxService.update(admin, id, request(true, "INBOX", PASSWORD), null);
    poller.pollOnce();

    assertThat(imported(id)).containsExactly("During outage 1", "During outage 2");
  }

  /**
   * Switching polling off and on keeps the position: switching off is a pause, like an outage, so
   * nothing that arrived meanwhile is skipped and nothing already imported comes back. Only the
   * first switch-on records a position (see {@link #aMailboxSavedDisabledStartsWhenPollingIsOn}).
   */
  @Test
  void switchingPollingOffAndOnKeepsThePosition() throws Exception {
    long id = create(true, "INBOX");
    deliver("While on");
    poller.pollOnce();
    assertThat(imported(id)).containsExactly("While on");
    Map<String, Object> before = position(id);

    mailboxService.update(admin, id, request(false, "INBOX", null), null);
    deliver("While off");
    assertThat(poller.pollOnce().mailboxes()).isZero();
    mailboxService.update(admin, id, request(true, "INBOX", null), null);
    assertThat(position(id)).isEqualTo(before);
    poller.pollOnce();

    assertThat(imported(id)).containsExactly("While on", "While off");
  }

  @Test
  void aMailboxSavedDisabledStartsWhenPollingIsOn() throws Exception {
    long id = create(false, "INBOX");
    assertThat(position(id).get("uid_validity")).isNull();
    deliver("Before polling was on");

    mailboxService.update(admin, id, request(true, "INBOX", null), null);
    deliver("After polling was on");
    poller.pollOnce();

    assertThat(imported(id)).containsExactly("After polling was on");
  }

  @Test
  void changingTheFolderStartsAgainAtTheNewFoldersUidNext() throws Exception {
    MailFolder support =
        greenMail.getManagers().getImapHostManager().createMailbox(user, "Support");
    support.appendMessage(message("Old in Support"), new Flags(), new Date());
    support.appendMessage(message("Old in Support 2"), new Flags(), new Date());
    long id = create(true, "INBOX");
    deliver("In inbox");
    poller.pollOnce();
    assertThat(imported(id)).containsExactly("In inbox");

    mailboxService.update(admin, id, request(true, "Support", null), null);
    // Recorded at save, from the new folder (the old folder's mark meant nothing there).
    assertThat(position(id).get("uid_validity")).isNotNull();
    assertThat((Long) position(id).get("last_uid")).isGreaterThanOrEqualTo(2L);
    poller.pollOnce();
    assertThat(imported(id)).containsExactly("In inbox");

    support.appendMessage(message("New in Support"), new Flags(), new Date());
    poller.pollOnce();
    assertThat(imported(id)).containsExactly("In inbox", "New in Support");
  }

  @Test
  void aUidValidityChangeStartsAgainAtUidNextWithAWarningAndAnAuditEvent() throws Exception {
    deliver("Never imported");
    long id = create(true, "INBOX");
    poller.pollOnce();
    long realValidity = (Long) position(id).get("uid_validity");
    long realMark = (Long) position(id).get("last_uid");
    // As if the server had renumbered the folder since the last poll: under the old rule the poller
    // would read the whole folder again from UID 1.
    jdbc.update(
        "UPDATE mailbox SET uid_validity = uid_validity + 1000, last_uid = 0 WHERE id = ?", id);

    ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      poller.pollOnce();
    } finally {
      ((Logger) LoggerFactory.getLogger(ImapPoller.class)).detachAppender(logs);
    }

    assertThat(imported(id)).isEmpty();
    assertThat(position(id)).containsEntry("uid_validity", realValidity);
    assertThat(position(id)).containsEntry("last_uid", realMark);
    assertThat(logs.list)
        .anySatisfy(
            e -> {
              assertThat(e.getLevel()).isEqualTo(Level.WARN);
              assertThat(e.getFormattedMessage()).contains("UIDVALIDITY changed");
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE type = 'MAILBOX_UIDVALIDITY_CHANGED' AND"
                    + " details ->> 'mailbox' = ?",
                Integer.class,
                Long.toString(id)))
        .isOne();

    deliver("After the change");
    poller.pollOnce();
    assertThat(imported(id)).containsExactly("After the change");
  }

  @Test
  void anExistingMailboxPositionIsKept() throws Exception {
    long id = create(true, "INBOX");
    long validity = (Long) position(id).get("uid_validity");
    long start = (Long) position(id).get("last_uid");
    deliver("First");
    deliver("Second");
    deliver("Third");
    // A mailbox from before ADR-033 whose stored position is after "First": it continues from
    // there, it is not moved to UIDNEXT.
    jdbc.update("UPDATE mailbox SET last_uid = ? WHERE id = ?", start + 1, id);

    poller.pollOnce();

    assertThat(position(id)).containsEntry("uid_validity", validity);
    assertThat(imported(id)).containsExactly("Second", "Third");
  }

  @Test
  void aMailboxWithNoPositionYetStartsAtUidNextOnItsFirstPoll() throws Exception {
    long id = create(true, "INBOX");
    // Created before ADR-033 and never polled (or saved while the server was unreachable).
    jdbc.update("UPDATE mailbox SET uid_validity = NULL, last_uid = 0 WHERE id = ?", id);
    deliver("Waiting unread");

    poller.pollOnce();
    assertThat(imported(id)).isEmpty();
    assertThat(position(id).get("uid_validity")).isNotNull();

    deliver("Next");
    poller.pollOnce();
    assertThat(imported(id)).containsExactly("Next");
  }

  @Test
  void aSaveWhileTheServerRejectsTheLoginLeavesThePositionToThePoller() throws Exception {
    deliver("Old");
    long id =
        mailboxService.create(admin, request(true, "INBOX", "wrong-password"), "203.0.113.1").id();
    assertThat(position(id).get("uid_validity")).isNull();

    mailboxService.update(admin, id, request(true, "INBOX", PASSWORD), null);
    poller.pollOnce();

    assertThat(imported(id)).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  long create(boolean enabled, String folder) {
    return mailboxService.create(admin, request(enabled, folder, PASSWORD), "203.0.113.1").id();
  }

  MailboxRequest request(boolean enabled, String folder, String password) {
    return new MailboxRequest(
        "Start",
        address,
        enabled,
        "127.0.0.1",
        IMAP_PORT,
        "NONE",
        folder,
        "PASSWORD",
        address,
        password,
        null,
        null,
        null,
        null,
        List.of("mx.acmemsp.example"));
  }

  MimeMessage message(String subject) throws Exception {
    String raw =
        "From: Sam Example <sam@public-mail.example>\r\n"
            + "To: "
            + address
            + "\r\nSubject: "
            + subject
            + "\r\nDate: Thu, 19 Nov 2026 10:15:00 +0700\r\nMessage-ID: <"
            + UUID.randomUUID()
            + "@mail.example>\r\nMIME-Version: 1.0\r\nContent-Type: text/plain;"
            + " charset=utf-8\r\n\r\nHello\r\n";
    return new MimeMessage(
        Session.getInstance(new Properties()),
        new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
  }

  void deliver(String subject) throws Exception {
    user.deliver(message(subject));
  }

  /** Subjects of the mail this mailbox imported, in the order it was read. */
  List<String> imported(long mailboxId) {
    return jdbc.queryForList(
        "SELECT subject FROM inbound_mail WHERE mailbox_id = ? ORDER BY id",
        String.class,
        mailboxId);
  }

  Map<String, Object> position(long mailboxId) {
    return jdbc.queryForMap("SELECT uid_validity, last_uid FROM mailbox WHERE id = ?", mailboxId);
  }

  static ListAppender<ILoggingEvent> captureLogs() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ImapPoller.class)).addAppender(appender);
    return appender;
  }
}
