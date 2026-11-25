package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.mail.ticketmail.clientdata.TicketMailRepository;
import com.ticketsystem.api.notification.NotificationKind;
import com.ticketsystem.api.notification.clientdata.NotificationRepository;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.reference.clientdata.TicketReferenceRepository;
import com.ticketsystem.api.security.SecurityGoLive;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository;
import com.ticketsystem.api.ticket.clientdata.InternalNoteRepository;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.AuthorKind;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.CcRecipient;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.NewPublicComment;
import com.ticketsystem.api.ticket.clientdata.SystemEntryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ParticipantReason;
import com.ticketsystem.api.ticket.clientdata.TicketLinkRepository;
import com.ticketsystem.api.ticket.clientdata.TicketLinkRepository.LinkKind;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import com.ticketsystem.api.ticket.portal.clientdata.PortalSubmissionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Writes bind the organisation in their own SQL: a caller whose scope sees Acme passes Acme as the
 * organisation (which the early Java check accepts) together with the id of a Beta row. Every such
 * write must answer 404, exactly like a missing id, and leave the database unchanged.
 */
@SecurityGoLive({1})
class ScopeBoundWriteIntegrationTest extends TicketTestBase {

  @Autowired TicketRepository ticketRepo;
  @Autowired PublicCommentRepository commentRepo;
  @Autowired InternalNoteRepository noteRepo;
  @Autowired SystemEntryRepository systemRepo;
  @Autowired TicketHistoryRepository historyRepo;
  @Autowired TicketParticipantRepository participantRepo;
  @Autowired TicketLinkRepository linkRepo;
  @Autowired AttachmentRepository attachmentRepo;
  @Autowired TicketReferenceRepository referenceRepo;
  @Autowired TicketMailRepository mailRepo;
  @Autowired NotificationRepository notificationRepo;
  @Autowired PortalSubmissionRepository submissionRepo;

  private static final String OUTSIDER = "outsider@beta-clinic.example";

  private long betaId;
  private long betaId2;
  private long acmeId;
  private long betaCommentId;
  private long betaNoteId;
  private long acmeCommentId;
  private TicketRow betaAsAcme;
  private Instant now;

  @BeforeEach
  void rows() {
    now = Instant.now();
    betaId = betaTicket().ticketId();
    betaId2 = betaTicket().ticketId();
    acmeId = acmeTicket().ticketId();
    tickets.addPublicReply(admin, betaId, "<p>beta reply</p>", List.of(), null);
    tickets.addPublicReply(admin, acmeId, "<p>acme reply</p>", List.of(), null);
    notes.addNote(admin, betaId, "<p>beta note</p>", List.of());
    jdbc.update("UPDATE public_comment SET received_by_email = TRUE WHERE ticket_id = ?", betaId);
    jdbc.update("UPDATE internal_note SET received_by_email = TRUE WHERE ticket_id = ?", betaId);
    betaCommentId = lastId("public_comment", betaId);
    betaNoteId = lastId("internal_note", betaId);
    acmeCommentId = lastId("public_comment", acmeId);
    participantRepo.add(admin, beta, betaId, null, OUTSIDER, false, now);
    TicketRow b = ticketRepo.find(admin, betaId).orElseThrow();
    betaAsAcme =
        new TicketRow(
            b.id(),
            acme,
            b.reporterUserId(),
            b.reporterEmail(),
            b.assigneeUserId(),
            b.requestTypeId(),
            b.priority(),
            b.impact(),
            b.urgency(),
            b.status(),
            b.source(),
            b.subject(),
            b.externalId(),
            b.duplicateOfId(),
            b.followUpOfId(),
            b.createdByUserId(),
            b.createdAt(),
            b.updatedAt(),
            b.statusChangedAt());
  }

  private long lastId(String table, long ticketId) {
    return jdbc.queryForObject(
        "SELECT max(id) FROM " + table + " WHERE ticket_id = ?", Long.class, ticketId);
  }

  /** Every row a cross-organisation write could touch, for the three tickets, as text. */
  private String snapshot() {
    StringBuilder out = new StringBuilder();
    for (String sql :
        List.of(
            "SELECT json_agg(x ORDER BY x.id)::text FROM ticket x WHERE x.id IN (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.id)::text FROM public_comment x WHERE x.ticket_id IN"
                + " (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.comment_id, x.email)::text FROM public_comment_cc x"
                + " JOIN public_comment c ON c.id = x.comment_id WHERE c.ticket_id IN (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.id)::text FROM internal_note x WHERE x.ticket_id IN"
                + " (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.id)::text FROM system_entry x WHERE x.ticket_id IN"
                + " (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.id)::text FROM ticket_event x WHERE x.ticket_id IN"
                + " (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.id)::text FROM ticket_participant x WHERE x.ticket_id"
                + " IN (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.id)::text FROM ticket_link x WHERE x.low_ticket_id IN"
                + " (?, ?, ?) OR x.high_ticket_id IN (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.prefix, x.number)::text FROM ticket_reference x WHERE"
                + " x.ticket_id IN (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.ticket_id)::text FROM ticket_mail_suppression x WHERE"
                + " x.ticket_id IN (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.id)::text FROM notification x WHERE x.ticket_id IN"
                + " (?, ?, ?)",
            "SELECT json_agg(x ORDER BY x.id)::text FROM attachment x WHERE x.ticket_id IN"
                + " (?, ?, ?) OR x.status = 'PENDING'",
            "SELECT json_agg(x ORDER BY x.id)::text FROM portal_submission x")) {
      int marks = sql.length() - sql.replace("?", "").length();
      Object[] args = new Object[marks];
      for (int i = 0; i < marks; i++) {
        args[i] = List.of(betaId, betaId2, acmeId).get(i % 3);
      }
      out.append(jdbc.queryForObject(sql, String.class, args)).append('\n');
    }
    return out.toString();
  }

  /** 404 (never 403, never a silent success) and nothing changed in the database. */
  private void notFoundAndUnchanged(ThrowingCallable write) {
    String before = snapshot();
    assertThatThrownBy(write)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void commentNoteAndEntryWritesCheckTheTicketsOwnOrganisation() {
    for (AccessScope s : List.of(acmeAgent, acmeCustomer)) {
      notFoundAndUnchanged(
          () ->
              commentRepo.insert(
                  s,
                  acme,
                  new NewPublicComment(
                      betaId, AuthorKind.CLIENT, null, null, "<p>x</p>", false, null, null),
                  now));
      notFoundAndUnchanged(
          () -> commentRepo.replaceEmailBody(s, acme, betaCommentId, "<p>replaced</p>"));
      notFoundAndUnchanged(
          () ->
              commentRepo.saveChosenCc(
                  s, acme, betaCommentId, List.of(new CcRecipient("cc@example.test", null, true))));
    }
    notFoundAndUnchanged(
        () ->
            noteRepo.insert(
                acmeAgent,
                acme,
                betaId,
                InternalNoteRepository.AuthorKind.SUPPORTER,
                null,
                "x",
                false,
                null,
                null,
                now));
    notFoundAndUnchanged(
        () -> noteRepo.replaceEmailBody(acmeAgent, acme, betaNoteId, "<p>replaced</p>"));
    notFoundAndUnchanged(() -> systemRepo.insert(acmeAgent, acme, betaId, "K", "x", now));
    notFoundAndUnchanged(
        () -> historyRepo.record(acmeAgent, acme, betaId, HistoryType.STATUS, null, "a", "b", now));
  }

  @Test
  void participantWritesCheckTheTicketsOwnOrganisation() {
    notFoundAndUnchanged(
        () -> participantRepo.add(acmeAgent, acme, betaId, null, "x@example.test", true, now));
    notFoundAndUnchanged(() -> participantRepo.confirm(acmeAgent, acme, betaId, OUTSIDER, now));
    notFoundAndUnchanged(
        () -> participantRepo.confirmFromEmail(acmeAgent, acme, betaId, null, OUTSIDER, now));
    notFoundAndUnchanged(
        () ->
            participantRepo.remove(
                acmeAgent, acme, betaId, OUTSIDER, now, ParticipantReason.AGENT_REMOVED));
    notFoundAndUnchanged(
        () ->
            participantRepo.recordReplyOnly(
                acmeAgent,
                acme,
                betaId,
                true,
                null,
                "x@example.test",
                ParticipantReason.REPLY_ONLY,
                now));
    notFoundAndUnchanged(() -> participantRepo.copy(acmeAgent, acme, acmeId, betaId, now, null));
    notFoundAndUnchanged(() -> participantRepo.recheckAfterMove(acmeAgent, betaId, acme, now));
  }

  @Test
  void ticketWritesCheckTheTicketsOwnOrganisation() {
    notFoundAndUnchanged(() -> ticketRepo.touch(acmeAgent, betaAsAcme, now));
    notFoundAndUnchanged(
        () -> ticketRepo.updateStatus(acmeAgent, betaAsAcme, TicketStatus.OPEN, now));
    notFoundAndUnchanged(() -> ticketRepo.updatePriority(acmeAgent, betaAsAcme, Priority.P1, now));
    notFoundAndUnchanged(
        () -> ticketRepo.updateCloseReason(acmeAgent, betaAsAcme, "RESOLVED", null));
    notFoundAndUnchanged(() -> ticketRepo.markDuplicate(acmeAgent, betaAsAcme, acmeId, now));
    notFoundAndUnchanged(() -> ticketRepo.repointDuplicates(acmeAgent, acme, acmeId, betaId, now));
  }

  @Test
  void linkReferenceAndAttachmentWritesCheckTheTicketsOwnOrganisation() {
    notFoundAndUnchanged(
        () -> linkRepo.link(acmeAgent, acme, acmeId, betaId, LinkKind.RELATED, null, now));
    notFoundAndUnchanged(
        () -> linkRepo.link(acmeAgent, acme, betaId, betaId2, LinkKind.RELATED, null, now));
    notFoundAndUnchanged(
        () ->
            referenceRepo.insert(
                acmeAgent,
                new TicketReference("ZQX", 900_001L),
                betaId,
                acme,
                TicketReferenceRepository.Kind.ALIAS,
                TicketReferenceRepository.Reason.MOVED,
                now));
    notFoundAndUnchanged(
        () -> referenceRepo.repointToDuplicateTarget(acmeAgent, acmeId, betaId, acme));

    UUID file = upload(acmeAgent, "acme.txt", "synthetic".getBytes());
    long fileId =
        jdbc.queryForObject("SELECT id FROM attachment WHERE public_id = ?", Long.class, file);
    notFoundAndUnchanged(
        () ->
            attachmentRepo.bindToPublicComment(
                acmeAgent, acme, fileId, betaId, betaCommentId, false));
    notFoundAndUnchanged(
        () ->
            attachmentRepo.bindToInternalNote(acmeAgent, acme, fileId, betaId, betaNoteId, false));
    // The agent's own ticket, but a comment of another organisation's ticket.
    notFoundAndUnchanged(
        () ->
            attachmentRepo.bindToPublicComment(
                acmeAgent, acme, fileId, acmeId, betaCommentId, false));
    // Positive control: the right ticket and comment bind.
    attachmentRepo.bindToPublicComment(acmeAgent, acme, fileId, acmeId, acmeCommentId, false);
    assertThat(
            jdbc.queryForObject("SELECT status FROM attachment WHERE id = ?", String.class, fileId))
        .isEqualTo("BOUND");
  }

  @Test
  void mailNotificationAndPortalWritesCheckTheTicketsOwnOrganisation() {
    notFoundAndUnchanged(() -> mailRepo.suppress(acmeAgent, acme, betaId, "bulk", now));
    notFoundAndUnchanged(
        () ->
            notificationRepo.insert(
                acmeAgent,
                acmeAgentUser.id(),
                acme,
                betaId,
                NotificationKind.TICKET_ASSIGNED,
                null,
                "cross-org-test",
                now));
    UUID key = UUID.randomUUID();
    assertThat(submissionRepo.claim(acmeCustomer, acme, key, now)).isTrue();
    notFoundAndUnchanged(() -> submissionRepo.bind(acmeCustomer, acme, key, betaId));
    // Positive control: the customer's own ticket binds.
    submissionRepo.bind(acmeCustomer, acme, key, acmeId);
    assertThat(submissionRepo.ticketOf(acmeCustomer, acme, key)).contains(acmeId);
  }
}
