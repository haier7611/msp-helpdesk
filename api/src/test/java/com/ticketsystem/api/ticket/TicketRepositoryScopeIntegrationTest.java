package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.reference.clientdata.TicketReferenceRepository;
import com.ticketsystem.api.security.SecurityGoLive;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.attachment.TestImages;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.NewAttachment;
import com.ticketsystem.api.ticket.clientdata.InternalNoteRepository;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.AuthorKind;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.NewPublicComment;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository;
import com.ticketsystem.api.ticket.clientdata.SystemEntryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import com.ticketsystem.api.ticket.clientdata.TicketLinkRepository;
import com.ticketsystem.api.ticket.clientdata.TicketLinkRepository.LinkKind;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.NewTicketRow;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.QueueFilter;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Rule 7 at the repository level for every WP-04 client-data repository: with a customer of Acme,
 * an agent limited to Acme and an admin, Beta's rows read as missing and cannot be written, while
 * the admin sees and changes them. Internal notes, system entries and history are supporter-only
 * even inside the customer's own organisation.
 */
@SecurityGoLive({1})
class TicketRepositoryScopeIntegrationTest extends TicketTestBase {

  @Autowired TicketRepository ticketRepo;
  @Autowired PublicCommentRepository commentRepo;
  @Autowired InternalNoteRepository noteRepo;
  @Autowired SystemEntryRepository systemRepo;
  @Autowired TicketLinkRepository linkRepo;
  @Autowired TicketParticipantRepository participantRepo;
  @Autowired TicketHistoryRepository historyRepo;
  @Autowired TicketPeopleRepository peopleRepo;
  @Autowired AttachmentRepository attachmentRepo;
  @Autowired RequestTypeRepository typeRepo;
  @Autowired TicketReferenceRepository referenceRepo;

  private long betaId;
  private long betaId2;
  private long acmeId;
  private TicketRow betaRow;
  private UUID betaFile;
  private Instant now;

  @BeforeEach
  void rows() {
    now = Instant.now();
    betaId = betaTicket().ticketId();
    betaId2 = betaTicket().ticketId();
    acmeId = acmeTicket().ticketId();
    betaRow = ticketRepo.find(admin, betaId).orElseThrow();
    betaFile = upload(admin, "beta.txt", "synthetic".getBytes());
    tickets.addPublicReply(admin, betaId, "<p>r</p>", List.of(betaFile), null);
    notes.addNote(admin, betaId, "<p>internal</p>", List.of());
    notes.addSystemEntry(admin, betaId, "AUTO_REPLY", "Out of office");
  }

  private List<AccessScope> limited() {
    return List.of(acmeCustomer, acmeAgent);
  }

  private static void denied(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.status()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN));
  }

  @Test
  void ticketRepositoryHidesAndProtectsTheOtherOrganisation() {
    for (AccessScope s : limited()) {
      assertThat(ticketRepo.find(s, betaId)).as(s.toString()).isEmpty();
      assertThat(ticketRepo.lock(s, betaId)).isEmpty();
      assertThat(ticketRepo.findAll(s, List.of(betaId, acmeId)))
          .extracting(TicketRow::id)
          .containsExactly(acmeId);
      assertThat(
              ticketRepo.list(
                  s, new QueueFilter(null, null, false, null, false, true, false, null, 500)))
          .extracting(TicketRow::id)
          .doesNotContain(betaId, betaId2);
      assertThat(
              ticketRepo.list(
                  s, new QueueFilter(null, beta, false, null, false, true, false, null, 50)))
          .isEmpty();
      denied(() -> ticketRepo.updateStatus(s, betaRow, TicketStatus.OPEN, now));
      denied(() -> ticketRepo.updatePriority(s, betaRow, Priority.P1, now));
      denied(() -> ticketRepo.updateRequestType(s, betaRow, alertType, now));
      denied(() -> ticketRepo.updateAssignee(s, betaRow, null, now));
      denied(() -> ticketRepo.updateOrganisation(s, betaRow, acme, now));
      denied(() -> ticketRepo.markDuplicate(s, betaRow, betaId2, now));
      denied(() -> ticketRepo.repointDuplicates(s, beta, betaId, betaId2, now));
      denied(() -> ticketRepo.touch(s, betaRow, now));
      denied(
          () ->
              ticketRepo.insert(
                  s,
                  new NewTicketRow(
                      beta,
                      null,
                      null,
                      null,
                      generalType,
                      Priority.P3,
                      null,
                      null,
                      TicketSource.AGENT,
                      "x",
                      null,
                      null,
                      null),
                  now));
    }
    denied(
        () ->
            ticketRepo.insert(
                acmeCustomer,
                new NewTicketRow(
                    null,
                    null,
                    null,
                    null,
                    generalType,
                    Priority.P3,
                    null,
                    null,
                    TicketSource.EMAIL,
                    "triage",
                    null,
                    null,
                    null),
                now));
    assertThat(ticketRepo.find(admin, betaId)).isPresent();
    ticketRepo.updatePriority(admin, betaRow, Priority.P2, now);
    assertThat(ticketRepo.find(admin, betaId).orElseThrow().priority()).isEqualTo(Priority.P2);
  }

  @Test
  void triageTicketsAreForSupportersOnly() {
    long tri = triageTicket().ticketId();
    assertThat(ticketRepo.find(acmeCustomer, tri)).isEmpty();
    assertThat(ticketRepo.find(AccessScope.apiKey(1, acme), tri)).isEmpty();
    assertThat(ticketRepo.find(acmeAgent, tri)).isPresent();
    assertThat(ticketRepo.find(admin, tri)).isPresent();
  }

  @Test
  void commentsNotesEntriesAndHistoryAreScoped() {
    for (AccessScope s : limited()) {
      assertThat(commentRepo.listForTicket(s, betaId)).isEmpty();
      assertThat(noteRepo.listForTicket(s, betaId)).isEmpty();
      assertThat(systemRepo.listForTicket(s, betaId)).isEmpty();
      assertThat(historyRepo.listForTicket(s, betaId)).isEmpty();
      denied(
          () ->
              commentRepo.insert(
                  s,
                  beta,
                  new NewPublicComment(
                      betaId, AuthorKind.CLIENT, null, null, "<p>x</p>", false, null, null),
                  now));
      denied(
          () ->
              noteRepo.insert(
                  s,
                  beta,
                  betaId,
                  InternalNoteRepository.AuthorKind.SUPPORTER,
                  null,
                  "x",
                  false,
                  null,
                  null,
                  now));
      denied(() -> systemRepo.insert(s, beta, betaId, "K", "x", now));
      denied(() -> historyRepo.record(s, beta, betaId, HistoryType.STATUS, null, "a", "b", now));
    }
    denied(() -> commentRepo.existsMessageId(acmeCustomer, "<x@example.test>"));
    assertThat(commentRepo.listForTicket(admin, betaId)).hasSize(2);
    assertThat(noteRepo.listForTicket(admin, betaId)).hasSize(1);
    assertThat(systemRepo.listForTicket(admin, betaId)).hasSize(1);
    assertThat(historyRepo.listForTicket(admin, betaId)).isNotEmpty();
  }

  @Test
  void notesEntriesAndHistoryAreHiddenFromCustomersOfTheSameOrganisation() {
    notes.addNote(admin, acmeId, "<p>acme internal</p>", List.of());
    notes.addSystemEntry(admin, acmeId, "AUTO_REPLY", "x");
    assertThat(noteRepo.listForTicket(acmeCustomer, acmeId)).isEmpty();
    assertThat(systemRepo.listForTicket(acmeCustomer, acmeId)).isEmpty();
    assertThat(historyRepo.listForTicket(acmeCustomer, acmeId)).isEmpty();
    denied(
        () ->
            noteRepo.insert(
                acmeCustomer,
                acme,
                acmeId,
                InternalNoteRepository.AuthorKind.SUPPORTER,
                null,
                "x",
                false,
                null,
                null,
                now));
    denied(() -> systemRepo.insert(acmeCustomer, acme, acmeId, "K", "x", now));
    assertThat(noteRepo.listForTicket(acmeAgent, acmeId)).hasSize(1);
    assertThat(peopleRepo.names(acmeCustomer, Set.of(acmeCustomerUser.id()))).isEmpty();
  }

  @Test
  void linksAndParticipantsAreScoped() {
    linkRepo.link(admin, beta, betaId, betaId2, LinkKind.RELATED, null, now);
    participantRepo.add(admin, beta, betaId, null, "someone@beta-clinic.example", true, now);
    for (AccessScope s : limited()) {
      assertThat(linkRepo.listForTicket(s, betaId)).isEmpty();
      assertThat(participantRepo.listForTicket(s, betaId)).isEmpty();
      denied(() -> linkRepo.link(s, beta, betaId, betaId2, LinkKind.RELATED, null, now));
      denied(() -> linkRepo.removeOnMove(s, betaId, beta, acme));
      denied(() -> participantRepo.add(s, beta, betaId, null, "x@example.test", true, now));
      denied(() -> participantRepo.copy(s, beta, betaId, betaId2, now, null));
      denied(() -> participantRepo.recheckAfterMove(s, betaId, beta, now));
    }
    assertThat(linkRepo.listForTicket(admin, betaId)).hasSize(1);
    assertThat(participantRepo.listForTicket(admin, betaId)).hasSize(1);
  }

  @Test
  void peopleChecksAreScoped() {
    for (AccessScope s : limited()) {
      denied(() -> peopleRepo.customerOf(s, betaCustomerUser.id(), beta));
    }
    denied(() -> peopleRepo.isActiveSupporterFor(acmeCustomer, acmeAgentUser.id(), acme));
    denied(() -> peopleRepo.isActiveSupporterFor(acmeAgent, acmeAgentUser.id(), beta));
    assertThat(peopleRepo.isActiveSupporterFor(admin, acmeAgentUser.id(), beta)).isFalse();
    assertThat(peopleRepo.isActiveSupporterFor(admin, acmeAgentUser.id(), acme)).isTrue();
    assertThat(peopleRepo.customerOf(admin, betaCustomerUser.id(), beta)).isPresent();
    assertThat(peopleRepo.customerOf(admin, betaCustomerUser.id(), acme)).isEmpty();
    assertThat(peopleRepo.names(acmeAgent, Set.of(betaCustomerUser.id(), acmeCustomerUser.id())))
        .containsOnlyKeys(acmeCustomerUser.id());
  }

  @Test
  void attachmentsAreScopedThroughTheirComment() {
    for (AccessScope s : limited()) {
      assertThat(attachmentRepo.findForDownload(s, betaFile)).isEmpty();
      assertThat(attachmentRepo.listForTicket(s, betaId, true)).isEmpty();
      assertThat(attachmentRepo.lockOwnPending(s, List.of(betaFile), now)).isEmpty();
      denied(() -> attachmentRepo.expiredPending(s, now, 10));
      denied(() -> attachmentRepo.deleteExpiredPending(s, 1, now));
      denied(() -> attachmentRepo.bindToPublicComment(s, beta, 1, betaId, 1, false));
      denied(() -> attachmentRepo.bindToInternalNote(s, beta, 1, betaId, 1, false));
    }
    denied(() -> attachmentRepo.bindToInternalNote(acmeCustomer, acme, 1, acmeId, 1, false));
    denied(
        () ->
            attachmentRepo.insertPending(
                AccessScope.apiKey(1, acme),
                new NewAttachment(
                    UUID.randomUUID(), UUID.randomUUID(), null, "a", 1, "text/plain", false),
                now,
                now));
    assertThat(attachmentRepo.findForDownload(admin, betaFile)).isPresent();
    assertThat(attachments.listForTicket(admin, betaId, true)).hasSize(1);
    assertThat(TestImages.png(1, 1)).isNotEmpty();
  }

  @Test
  void requestTypesHideAlertFromCustomers() {
    assertThat(typeRepo.list(acmeCustomer))
        .extracting(r -> r.name())
        .hasSize(8)
        .endsWith("Other or general question")
        .doesNotContain("Alert");
    assertThat(typeRepo.find(acmeCustomer, alertType)).isEmpty();
    assertThat(typeRepo.list(acmeAgent))
        .extracting(r -> r.name())
        .containsSubsequence("Other or general question", "Alert");
    assertThat(typeRepo.findByName(admin, "alert")).isPresent();
  }

  @Test
  void referenceRepositoryAdditionsAreScoped() {
    for (AccessScope s : limited()) {
      denied(() -> referenceRepo.repointToDuplicateTarget(s, betaId, betaId2, beta));
      assertThat(referenceRepo.displayReferences(s, List.of(betaId, acmeId)))
          .containsOnlyKeys(acmeId);
    }
    assertThat(referenceRepo.displayReferences(admin, List.of(betaId, acmeId)))
        .containsOnlyKeys(betaId, acmeId);
  }
}
