package com.ticketsystem.api.ticket.agent;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.AttachmentRow;
import com.ticketsystem.api.ticket.clientdata.InternalNoteRepository;
import com.ticketsystem.api.ticket.clientdata.InternalNoteRepository.AuthorKind;
import com.ticketsystem.api.ticket.clientdata.SystemEntryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import com.ticketsystem.api.ticket.event.TicketEvent;
import com.ticketsystem.api.ticket.richtext.RichTextSanitiser;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/**
 * Internal notes and hidden system entries (supporters only). Notes are allowed on every status,
 * Closed included, and never reopen a ticket. A note never becomes a public reply: there is no
 * conversion method, and its attachments are bound to the note so they inherit its visibility.
 */
@Service
public class InternalNoteService {

  static final int MAX_BODY_CHARS = 200_000;
  static final int MAX_SYSTEM_TEXT = 4000;

  private final TicketRepository tickets;
  private final InternalNoteRepository notes;
  private final SystemEntryRepository systemEntries;
  private final AttachmentService attachments;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public InternalNoteService(
      TicketRepository tickets,
      InternalNoteRepository notes,
      SystemEntryRepository systemEntries,
      AttachmentService attachments,
      ApplicationEventPublisher events,
      Clock clock) {
    this.tickets = tickets;
    this.notes = notes;
    this.systemEntries = systemEntries;
    this.attachments = attachments;
    this.events = events;
    this.clock = clock;
  }

  /** A supporter's note from the agent UI. */
  @Transactional
  public long addNote(AccessScope scope, long ticketId, String bodyHtml, List<UUID> attachmentIds) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    TicketRow t = tickets.lock(scope, ticketId).orElseThrow(AccessScope::notFound);
    if (bodyHtml != null && bodyHtml.length() > MAX_BODY_CHARS) {
      throw ApiException.invalidRequest("The note is too long.");
    }
    List<AttachmentRow> claimed = attachments.claimPending(scope, attachmentIds);
    Set<UUID> images = new HashSet<>();
    claimed.stream().filter(AttachmentRow::image).forEach(a -> images.add(a.publicId()));
    String body = RichTextSanitiser.sanitise(bodyHtml, images);
    if (body.replaceAll("<[^>]*>", "").isBlank() && claimed.isEmpty() && !body.contains("<img")) {
      throw ApiException.invalidRequest("Write a note or attach a file.");
    }
    Instant now = clock.instant();
    Long me = scope.principalId().orElse(null);
    long noteId =
        notes.insert(
            scope,
            t.organisationId(),
            t.id(),
            AuthorKind.SUPPORTER,
            me,
            body,
            false,
            null,
            null,
            now);
    attachments.bindToInternalNote(
        scope, t.organisationId(), t.id(), noteId, claimed, RichTextSanitiser.inlineImageIds(body));
    tickets.touch(scope, t, now);
    events.publishEvent(new TicketEvent.InternalNoteAdded(t.id(), noteId, me));
    return noteId;
  }

  /**
   * A supporter's reply by email to an agent-only notification ({@code support+internal@}): stored
   * as an internal note and never emailed to the client. {@code scope} is that supporter's own
   * scope; the inbound-mail guards (active supporter, DMARC pass, admin setting) have already
   * passed. Allowed on every status, like any note.
   */
  @Transactional
  public long addNoteByEmail(
      AccessScope scope,
      long ticketId,
      String bodyHtml,
      List<UUID> attachmentIds,
      String messageId,
      String authResults) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    TicketRow t = tickets.lock(scope, ticketId).orElseThrow(AccessScope::notFound);
    if (bodyHtml != null && bodyHtml.length() > MAX_BODY_CHARS) {
      throw ApiException.invalidRequest("The note is too long.");
    }
    List<AttachmentRow> claimed = attachments.claimPending(scope, attachmentIds);
    Set<UUID> images = new HashSet<>();
    claimed.stream().filter(AttachmentRow::image).forEach(a -> images.add(a.publicId()));
    String body = RichTextSanitiser.sanitise(bodyHtml, images);
    if (body.isBlank()) {
      body = "<p></p>";
    }
    Instant now = clock.instant();
    Long me = scope.principalId().orElse(null);
    long noteId =
        notes.insert(
            scope,
            t.organisationId(),
            t.id(),
            AuthorKind.SUPPORTER,
            me,
            body,
            true,
            messageId,
            authResults,
            now);
    attachments.bindToInternalNote(
        scope, t.organisationId(), t.id(), noteId, claimed, RichTextSanitiser.inlineImageIds(body));
    tickets.touch(scope, t, now);
    events.publishEvent(new TicketEvent.InternalNoteAdded(t.id(), noteId, me));
    return noteId;
  }

  /**
   * Step 1 of the two-step email note: finishes a supporter's emailed note whose files were
   * processed after it was committed: binds the stored files (the supporter's own pending uploads)
   * and replaces the placeholder body with {@code bodyHtml}, inline images pointing at those files.
   * Sanitised like a new note; no event.
   */
  @Transactional
  public void completeEmailFiles(
      AccessScope scope, long noteId, String bodyHtml, List<UUID> attachmentIds) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    long ticketId = notes.ticketOfEmailNote(scope, noteId).orElseThrow(AccessScope::notFound);
    TicketRow t = tickets.lock(scope, ticketId).orElseThrow(AccessScope::notFound);
    List<AttachmentRow> claimed = attachments.claimPending(scope, attachmentIds);
    Set<UUID> images = new HashSet<>();
    claimed.stream().filter(AttachmentRow::image).forEach(a -> images.add(a.publicId()));
    String body = RichTextSanitiser.sanitise(bodyHtml, images);
    if (body.isBlank()) {
      body = "<p></p>";
    }
    notes.replaceEmailBody(scope, t.organisationId(), noteId, body);
    attachments.bindToInternalNote(
        scope, t.organisationId(), t.id(), noteId, claimed, RichTextSanitiser.inlineImageIds(body));
  }

  /**
   * An SLA automation rule's internal note - the admin's fixed plain text (escaped, line breaks
   * kept), authored by the system. SYSTEM scope only; allowed on every status like any note. No
   * notification of its own (the rule's notify actions do that).
   */
  @Transactional
  public long addAutomationNote(AccessScope scope, long ticketId, String text) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
    TicketRow t = tickets.lock(scope, ticketId).orElseThrow(AccessScope::notFound);
    String escaped = HtmlUtils.htmlEscape(text == null ? "" : text.strip(), "UTF-8");
    String body =
        RichTextSanitiser.sanitise(
            "<p>" + escaped.replace("\r\n", "\n").replace("\n", "<br>") + "</p>", Set.of());
    Instant now = clock.instant();
    long noteId =
        notes.insert(
            scope,
            t.organisationId(),
            t.id(),
            AuthorKind.SYSTEM,
            null,
            body,
            false,
            null,
            null,
            now);
    tickets.touch(scope, t, now);
    return noteId;
  }

  /**
   * A hidden system entry, e.g. a stored auto-reply or "Not assigned to ...: out of office".
   * Supporters and system jobs only; plain text.
   */
  @Transactional
  public long addSystemEntry(AccessScope scope, long ticketId, String kind, String text) {
    scope.requireSupporterOrSystem();
    TicketRow t = tickets.lock(scope, ticketId).orElseThrow(AccessScope::notFound);
    String clean = text == null ? "" : text.strip();
    if (clean.length() > MAX_SYSTEM_TEXT) {
      clean = clean.substring(0, MAX_SYSTEM_TEXT);
    }
    return systemEntries.insert(scope, t.organisationId(), t.id(), kind, clean, clock.instant());
  }
}
