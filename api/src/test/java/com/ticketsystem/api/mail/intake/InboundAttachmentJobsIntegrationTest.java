package com.ticketsystem.api.mail.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.TestGreenMail;
import com.ticketsystem.api.mail.intake.MailboxService.MailboxRequest;
import com.ticketsystem.api.mail.intake.clientdata.InboundAttachmentJobRepository;
import com.ticketsystem.api.mail.intake.job.InboundAttachmentWorker;
import com.ticketsystem.api.mail.intake.poller.ImapPoller;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketTestBase;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.InternalNoteView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.PublicCommentView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.AttachmentRow;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-039 step 1, ticket first and attachments after: the ticket or comment an email becomes is
 * committed before its files are processed; the files, the final body (inline images as {@code
 * src="attachment:<public id>"}) and the "Not attached" entry follow from the attachment worker; a
 * job interrupted by a crash or restart is picked up again from the kept raw email. The worker's
 * dispatch is paused to look at the state in between, exactly as a JVM stopping right after the
 * commit would leave it. GreenMail, the real poller and Postgres; everything synthetic.
 */
class InboundAttachmentJobsIntegrationTest extends TicketTestBase {

  static final String SUPPORT = EmailInOutIntegrationTest.SUPPORT;
  static final String INTERNAL = "support+internal@acmemsp.example";
  static final String PASSWORD = EmailInOutIntegrationTest.IMAP_PASSWORD;

  static GreenMail greenMail;
  static GreenMailUser supportUser;
  static int imapPort;

  @Autowired ImapPoller poller;
  @Autowired MailboxService mailboxService;
  @Autowired InboundAttachmentWorker worker;
  @Autowired AttachmentRepository attachmentRows;
  @Autowired InboundAttachmentJobRepository jobRows;

  @BeforeAll
  static void startMail() {
    TestGreenMail.Started started = TestGreenMail.start(ServerSetup.PROTOCOL_IMAP);
    greenMail = started.greenMail();
    imapPort = started.port(ServerSetup.PROTOCOL_IMAP);
    supportUser = greenMail.setUser(SUPPORT, SUPPORT, PASSWORD);
  }

  @AfterAll
  static void stopMail() {
    greenMail.stop();
  }

  @BeforeEach
  void mailbox() throws Exception {
    greenMail.purgeEmailFromAllMailboxes();
    MailboxRequest r =
        new MailboxRequest(
            "Support",
            SUPPORT,
            true,
            "127.0.0.1",
            imapPort,
            "NONE",
            "INBOX",
            "PASSWORD",
            SUPPORT,
            PASSWORD,
            null,
            null,
            null,
            null,
            List.of(EmailInOutIntegrationTest.AUTHSERV),
            null,
            null,
            null);
    List<Long> existing =
        jdbc.queryForList("SELECT id FROM mailbox WHERE address = ?", Long.class, SUPPORT);
    long id =
        existing.isEmpty() ? mailboxService.create(admin, r, "203.0.113.1").id() : existing.get(0);
    if (!existing.isEmpty()) {
      mailboxService.update(admin, id, r, "203.0.113.1");
    }
    jdbc.update("UPDATE mailbox SET enabled = FALSE WHERE id <> ?", id);
    jdbc.update("UPDATE email_settings SET supporter_reply_mode = 'INTERNAL_ONLY'");
    jdbc.update("DELETE FROM mail_block_rule");
    // Jobs other test classes left behind would be swept into this class's rounds.
    jdbc.update("DELETE FROM inbound_attachment_job");
    poller.pollOnce();
  }

  @AfterEach
  void resume() {
    worker.pauseForTest(false);
  }

  MimeMessage photoMail(String from, String to, String subject) throws Exception {
    return InboundTestMail.multipart(
        from,
        to,
        subject,
        InboundTestMail.related(
            "<p>See this:</p><p><img src=\"cid:shot1\" alt=\"screen\"></p>",
            "shot1",
            InboundTestMail.png()),
        InboundTestMail.file(
            "report.pdf",
            "application/pdf",
            "%PDF-1.7 synthetic".getBytes(StandardCharsets.US_ASCII)));
  }

  Map<String, Object> lastInbound() {
    return jdbc.queryForMap("SELECT * FROM inbound_mail ORDER BY id DESC LIMIT 1");
  }

  long ticketOf(Map<String, Object> row) {
    return ((Number) row.get("ticket_id")).longValue();
  }

  String descriptionOf(long ticketId) {
    return jdbc.queryForObject(
        "SELECT body_html FROM public_comment WHERE ticket_id = ? ORDER BY id LIMIT 1",
        String.class,
        ticketId);
  }

  int openJobs(long ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM inbound_attachment_job WHERE ticket_id = ? AND status IN"
            + " ('PENDING', 'RUNNING')",
        Integer.class,
        ticketId);
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void theTicketIsCommittedFirstAndItsFilesFollow() throws Exception {
    worker.pauseForTest(true);
    supportUser.deliver(photoMail(acmeCustomerUser.email(), SUPPORT, "Screen shows an error"));
    poller.pollOnce();

    // Committed and visible: the ticket, its description with a placeholder, no files yet.
    Map<String, Object> row = lastInbound();
    assertThat(row.get("outcome")).isEqualTo("CREATED");
    long t = ticketOf(row);
    assertThat(descriptionOf(t))
        .contains("See this:")
        .contains(InboundAttachments.IMAGE_PLACEHOLDER)
        .doesNotContain("<img");
    assertThat(attachments.listForTicket(admin, t, true)).isEmpty();
    assertThat(openJobs(t)).isEqualTo(1);
    TicketDetail before = queries.detail(admin, t);
    assertThat(before.publicComments())
        .singleElement()
        .extracting(PublicCommentView::attachmentsProcessing)
        .isEqualTo(true);
    // The confirmation email went out with the ticket (it never carries files).
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE ticket_id = ?", Integer.class, t))
        .isPositive();

    // The worker runs: files bound, placeholder replaced by the stored inline image.
    worker.pauseForTest(false);
    worker.dispatchDue(null);
    List<AttachmentRow> files = attachments.listForTicket(admin, t, true);
    assertThat(files).hasSize(2);
    AttachmentRow inline = files.stream().filter(AttachmentRow::inline).findFirst().orElseThrow();
    assertThat(descriptionOf(t))
        .contains("src=\"attachment:" + inline.publicId() + "\"")
        .doesNotContain(InboundAttachments.IMAGE_PLACEHOLDER)
        .doesNotContainPattern("attachment:\\d+\"");
    assertThat(openJobs(t)).isZero();
    assertThat(queries.detail(admin, t).publicComments())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.attachmentsProcessing()).isFalse();
              assertThat(c.attachments()).hasSize(2);
            });
    // Public files of a client's email: the customer sees them too.
    assertThat(attachments.listForTicket(acmeCustomer, t, false)).hasSize(2);
  }

  @Test
  void theProcessingStateIsScopedByOrganisation() throws Exception {
    worker.pauseForTest(true);
    supportUser.deliver(photoMail(acmeCustomerUser.email(), SUPPORT, "Scoped processing"));
    poller.pollOnce();
    long t = ticketOf(lastInbound());
    assertThat(attachmentRows.emailFilesPending(admin, t, true).publicCommentIds()).hasSize(1);
    assertThat(attachmentRows.emailFilesPending(acmeCustomer, t, false).publicCommentIds())
        .hasSize(1);
    // Another organisation's customer sees nothing of it, not even that it exists.
    assertThat(attachmentRows.emailFilesPending(betaCustomer, t, true).publicCommentIds())
        .isEmpty();
    assertThat(attachmentRows.emailFilesPending(betaCustomer, t, true).internalNoteIds()).isEmpty();
  }

  @Test
  void aSupportersEmailedNoteGetsItsFilesAfterwardsAsInternalFiles() throws Exception {
    Created c = acmeTicket();
    worker.pauseForTest(true);
    supportUser.deliver(
        InboundTestMail.multipart(
            acmeAgentUser.email(),
            INTERNAL,
            "Re: ["
                + references.referencesOf(admin, c.ticketId()).get(0).toString()
                + "] (Internal)",
            InboundTestMail.related(
                "<p>Server screen</p><p><img src=\"cid:s1\"></p>", "s1", InboundTestMail.png())));
    poller.pollOnce();
    assertThat(lastInbound().get("outcome")).isEqualTo("NOTE");
    InternalNoteView note = queries.detail(admin, c.ticketId()).internalNotes().getLast();
    assertThat(note.attachmentsProcessing()).isTrue();
    assertThat(note.bodyHtml()).contains(InboundAttachments.IMAGE_PLACEHOLDER);
    // The customer never learns about an internal note's files, processing or not.
    assertThat(attachmentRows.emailFilesPending(acmeCustomer, c.ticketId(), true).internalNoteIds())
        .isEmpty();

    worker.pauseForTest(false);
    worker.dispatchDue(null);
    InternalNoteView done = queries.detail(admin, c.ticketId()).internalNotes().getLast();
    assertThat(done.attachmentsProcessing()).isFalse();
    assertThat(done.attachments()).singleElement().satisfies(a -> assertThat(a.inline()).isTrue());
    assertThat(done.bodyHtml()).contains("attachment:" + done.attachments().get(0).id());
    AttachmentRow file = attachments.listForTicket(admin, c.ticketId(), true).getFirst();
    assertThat(file.uploaderUserId()).isEqualTo(acmeAgentUser.id());
    assertThat(attachments.listForTicket(acmeCustomer, c.ticketId(), true)).isEmpty();
  }

  @Test
  void oneBadFileNeverBlocksTheOthers() throws Exception {
    byte[] truncatedJpeg = java.util.Arrays.copyOf(InboundTestMail.jpeg(), 120);
    supportUser.deliver(
        InboundTestMail.multipart(
            acmeCustomerUser.email(),
            SUPPORT,
            "Mixed files",
            InboundTestMail.html("<p>Files</p>"),
            InboundTestMail.file("broken.jpg", "image/jpeg", truncatedJpeg),
            InboundTestMail.file(
                "drawing.svg",
                "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8)),
            InboundTestMail.file(
                "notes.txt", "text/plain", "synthetic".getBytes(StandardCharsets.UTF_8)),
            InboundTestMail.file("shot.png", "image/png", InboundTestMail.png())));
    poller.pollOnce();
    long t = ticketOf(lastInbound());
    assertThat(attachments.listForTicket(admin, t, true))
        .extracting(AttachmentRow::filename)
        .containsExactlyInAnyOrder("notes.txt", "shot.png");
    String note =
        jdbc.queryForObject(
            "SELECT body_text FROM system_entry WHERE ticket_id = ? AND kind ="
                + " 'EMAIL_ATTACHMENTS'",
            String.class,
            t);
    assertThat(note)
        .startsWith("Not attached")
        .contains("broken.jpg - image could not be read")
        .contains("drawing.svg - SVG files are not accepted");
    assertThat(openJobs(t)).isZero();
  }

  @Test
  void aJobInterruptedByACrashIsPickedUpAgainFromTheKeptEmail() throws Exception {
    worker.pauseForTest(true);
    supportUser.deliver(photoMail(acmeCustomerUser.email(), SUPPORT, "Crash in the middle"));
    poller.pollOnce();
    long t = ticketOf(lastInbound());
    // The JVM died while the job ran: RUNNING, one attempt used, a lease that is still live.
    jdbc.update(
        "UPDATE inbound_attachment_job SET status = 'RUNNING', attempts = 1, lease_until ="
            + " TIMESTAMPTZ '2999-01-01 00:00Z' WHERE ticket_id = ?",
        t);
    worker.pauseForTest(false);
    long jobId =
        jdbc.queryForObject(
            "SELECT id FROM inbound_attachment_job WHERE ticket_id = ?", Long.class, t);
    // While the lease is live nobody else takes it.
    assertThat(worker.runOnce(jobId)).isEqualTo(InboundAttachmentWorker.Result.SKIPPED);
    assertThat(attachments.listForTicket(admin, t, true)).isEmpty();

    // After the lease ran out, the next poll round's sweep finishes it.
    jdbc.update(
        "UPDATE inbound_attachment_job SET lease_until = TIMESTAMPTZ '2000-01-01 00:00Z' WHERE"
            + " ticket_id = ?",
        t);
    poller.pollOnce();
    assertThat(attachments.listForTicket(admin, t, true)).hasSize(2);
    assertThat(descriptionOf(t)).doesNotContain(InboundAttachments.IMAGE_PLACEHOLDER);
    assertThat(openJobs(t)).isZero();
    // Exactly once: a second run finds nothing to do.
    assertThat(worker.runOnce(jobId)).isEqualTo(InboundAttachmentWorker.Result.SKIPPED);
    assertThat(attachments.listForTicket(admin, t, true)).hasSize(2);
  }

  @Test
  void aJobThatKeepsKillingTheWorkerIsGivenUpAfterMaxAttempts() throws Exception {
    worker.pauseForTest(true);
    supportUser.deliver(photoMail(acmeCustomerUser.email(), SUPPORT, "Crash every time"));
    poller.pollOnce();
    long t = ticketOf(lastInbound());
    long jobId =
        jdbc.queryForObject(
            "SELECT id FROM inbound_attachment_job WHERE ticket_id = ?", Long.class, t);
    AccessScope system = AccessScope.system("test");
    // Each round: a worker claims the job, then the JVM dies (failed() is never reached) and the
    // lease runs out.
    for (int i = 1; i <= InboundAttachmentWorker.MAX_ATTEMPTS; i++) {
      assertThat(jobRows.claim(system, jobId, Instant.parse("2999-01-01T00:00:00Z"), Duration.ZERO))
          .hasValueSatisfying(c -> assertThat(c.attempts()).isPositive());
      jdbc.update(
          "UPDATE inbound_attachment_job SET lease_until = TIMESTAMPTZ '2000-01-01 00:00Z' WHERE"
              + " id = ?",
          jobId);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM inbound_attachment_job WHERE id = ?", Integer.class, jobId))
        .isEqualTo(InboundAttachmentWorker.MAX_ATTEMPTS);
    worker.pauseForTest(false);

    // The next sweep claims it once more and gives it up without processing it.
    poller.pollOnce();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM inbound_attachment_job WHERE id = ?", String.class, jobId))
        .isEqualTo("FAILED");
    assertThat(attachments.listForTicket(admin, t, true)).isEmpty();
    assertThat(openJobs(t)).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT body_text FROM system_entry WHERE ticket_id = ? AND kind ="
                    + " 'EMAIL_ATTACHMENTS'",
                String.class,
                t))
        .singleElement()
        .isEqualTo(InboundAttachmentWorker.GAVE_UP);
    assertThat(queries.detail(admin, t).publicComments().getFirst().attachmentsProcessing())
        .isFalse();

    // Never claimed again, by a direct run or a later sweep.
    assertThat(worker.runOnce(jobId)).isEqualTo(InboundAttachmentWorker.Result.SKIPPED);
    assertThat(jobRows.claim(system, jobId, Instant.parse("2999-01-01T00:00:00Z"), Duration.ZERO))
        .isEmpty();
    poller.pollOnce();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM system_entry WHERE ticket_id = ? AND kind ="
                    + " 'EMAIL_ATTACHMENTS'",
                Integer.class,
                t))
        .isEqualTo(1);
  }

  @Test
  void aJobWhoseEmailIsGoneIsGivenUpWithANote() throws Exception {
    worker.pauseForTest(true);
    supportUser.deliver(photoMail(acmeCustomerUser.email(), SUPPORT, "Raw purged"));
    poller.pollOnce();
    Map<String, Object> row = lastInbound();
    long t = ticketOf(row);
    jdbc.update("UPDATE inbound_mail SET raw_eml = NULL WHERE id = ?", row.get("id"));
    worker.pauseForTest(false);
    long jobId =
        jdbc.queryForObject(
            "SELECT id FROM inbound_attachment_job WHERE ticket_id = ?", Long.class, t);
    assertThat(worker.runOnce(jobId)).isEqualTo(InboundAttachmentWorker.Result.FAILED);
    assertThat(openJobs(t)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT body_text FROM system_entry WHERE ticket_id = ? AND kind ="
                    + " 'EMAIL_ATTACHMENTS'",
                String.class,
                t))
        .startsWith("Attachments could not be processed");
    assertThat(queries.detail(admin, t).publicComments().getFirst().attachmentsProcessing())
        .isFalse();
  }

  @Test
  void aMailWithoutFilesMakesNoJob() throws Exception {
    supportUser.deliver(
        InboundTestMail.multipart(
            acmeCustomerUser.email(),
            SUPPORT,
            "Text only",
            InboundTestMail.html("<p>No files here</p>")));
    poller.pollOnce();
    long t = ticketOf(lastInbound());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inbound_attachment_job WHERE ticket_id = ?",
                Integer.class,
                t))
        .isZero();
    assertThat(descriptionOf(t)).contains("No files here");
  }
}
