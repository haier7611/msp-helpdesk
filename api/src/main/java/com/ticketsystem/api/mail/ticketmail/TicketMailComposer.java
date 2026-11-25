package com.ticketsystem.api.mail.ticketmail;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.MailKind;
import com.ticketsystem.api.mail.MailOutbox;
import com.ticketsystem.api.mail.MailPart;
import com.ticketsystem.api.mail.OutboundEmail;
import com.ticketsystem.api.mail.SupportName;
import com.ticketsystem.api.mail.inbound.HtmlText;
import com.ticketsystem.api.mail.ticketmail.clientdata.TicketMailRepository;
import com.ticketsystem.api.mail.ticketmail.clientdata.TicketMailRepository.Person;
import com.ticketsystem.api.organisation.clientemail.ClientEmail;
import com.ticketsystem.api.organisation.clientemail.ClientEmailSwitches;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import com.ticketsystem.api.ticket.attachment.BlobStore;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.AttachmentRow;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.AuthorKind;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.CcRecipient;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.PublicCommentRow;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository.ParticipantRow;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds and queues every ticket email (the client email matrix, signatures, references and
 * threading). Each email is queued in the caller's transaction through {@link MailOutbox} (which
 * refuses to run without one), so it exists only if the ticket change commits. The methods are
 * deliberately not {@code @Transactional}: an exception here must not mark the ticket transaction
 * rollback-only (the listener logs it and the ticket change still commits).
 *
 * <ul>
 *   <li>Client mail goes to the reporter (To) and confirmed participants (Cc), never to our own
 *       addresses, and only to accounts that still belong to the ticket's organisation (reporter
 *       and participants are re-checked after a move). Reply-To is the support address.
 *   <li>Subject {@code [REF] subject}, the reference again in the footer, threading headers to the
 *       client's earlier mail, multipart HTML + text (with Message-ID and Auto-Submitted).
 *   <li>A public reply carries only the new reply, never the history and never an internal note:
 *       this class reads public comments only and has no access to notes or SLA fields.
 *   <li>Automatic mail (confirmation, resolved, closed) is not sent to bulk or automated reporters
 *       (loop safety) or to triage tickets (unverified senders: no backscatter).
 * </ul>
 */
@Service
public class TicketMailComposer {

  private static final Logger log = LoggerFactory.getLogger(TicketMailComposer.class);

  /** Message ids we copy into threading headers must look like one (sender-controlled input). */
  private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s\"\\\\]{1,250}>");

  private static final Pattern ATTACHMENT_SRC =
      Pattern.compile("attachment:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

  private static final String FONT = "font-family:Arial,Helvetica,sans-serif;";

  /**
   * A clear gap between a reply's body and the signature (QA T2). A one-cell table with a fixed
   * height is the spacer that Outlook (Word engine) and Gmail both keep; a bare {@code <br>} or a
   * CSS margin is dropped or collapsed by some clients.
   */
  static final String SIGNATURE_SPACER =
      "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
          + " class=\"ts-signature-gap\"><tr><td height=\"24\""
          + " style=\"height:24px;line-height:24px;font-size:24px;\">&nbsp;</td></tr></table>";

  private final TicketRepository tickets;
  private final PublicCommentRepository comments;
  private final TicketParticipantRepository participants;
  private final ReferenceService references;
  private final AttachmentService attachments;
  private final BlobStore blobs;
  private final TicketMailRepository mailData;
  private final SupportAddresses addresses;
  private final SupporterSignatures signatures;
  private final MailOutbox outbox;
  private final SupportName supportName;
  private final ClientEmailSwitches switches;
  private final Clock clock;

  public TicketMailComposer(
      TicketRepository tickets,
      PublicCommentRepository comments,
      TicketParticipantRepository participants,
      ReferenceService references,
      AttachmentService attachments,
      BlobStore blobs,
      TicketMailRepository mailData,
      SupportAddresses addresses,
      SupporterSignatures signatures,
      MailOutbox outbox,
      SupportName supportName,
      ClientEmailSwitches switches,
      Clock clock) {
    this.tickets = tickets;
    this.comments = comments;
    this.participants = participants;
    this.references = references;
    this.attachments = attachments;
    this.blobs = blobs;
    this.mailData = mailData;
    this.addresses = addresses;
    this.signatures = signatures;
    this.outbox = outbox;
    this.supportName = supportName;
    this.switches = switches;
    this.clock = clock;
  }

  /** Marks a ticket as opened by a bulk or automated sender: no automatic client mail. */
  public void suppressAutomaticMail(AccessScope scope, long ticketId, String reason) {
    TicketRow t = tickets.find(scope, ticketId).orElseThrow(AccessScope::notFound);
    mailData.suppress(scope, t.organisationId(), ticketId, reason, clock.instant());
  }

  /**
   * Is automatic client mail switched off for this ticket (opened by a bulk or automated sender,
   * loop safety)? The reminder and Closed notice check this too.
   */
  public boolean isAutomaticMailSuppressed(AccessScope scope, long ticketId) {
    return mailData.isSuppressed(scope, ticketId);
  }

  /**
   * "Ticket created": reference and a copy of what the client submitted. Not sent when the
   * organisation turned "Ticket received" off; the timeline then says so.
   */
  public boolean confirmation(AccessScope scope, long ticketId) {
    return confirmation(scope, ticketId, true);
  }

  private boolean confirmation(AccessScope scope, long ticketId, boolean switchable) {
    Optional<Context> c = automaticContext(scope, ticketId);
    if (c.isEmpty() || (switchable && turnedOff(c.get(), ClientEmail.TICKET_RECEIVED))) {
      return false;
    }
    String ref = c.get().reference();
    List<PublicCommentRow> all = comments.listForTicket(scope, ticketId);
    String submitted = all.isEmpty() ? "" : all.get(0).bodyHtml();
    String html =
        p("Thank you. We have received your request and logged it as " + e(ref) + ".")
            + p("We will reply by email. This is what you sent:")
            + "<blockquote style=\"margin:0 0 0 8px;padding-left:8px;border-left:3px solid"
            + " #A6A6A6;\">"
            + inlineImagesRemoved(submitted)
            + "</blockquote>";
    String text =
        "Thank you. We have received your request and logged it as "
            + ref
            + ".\nWe will reply by email. This is what you sent:\n\n"
            + quote(HtmlText.toPlainText(submitted));
    enqueue(c.get(), null, html, text, List.of());
    return true;
  }

  /**
   * "Resolved": reply to reopen. Not sent when the organisation turned "Resolved notice" off; the
   * timeline then says so.
   */
  public boolean resolved(AccessScope scope, long ticketId) {
    Optional<Context> c = automaticContext(scope, ticketId);
    if (c.isEmpty() || turnedOff(c.get(), ClientEmail.RESOLVED_NOTICE)) {
      return false;
    }
    String ref = c.get().reference();
    String msg =
        "We believe "
            + ref
            + " is resolved. If anything is still not right, just reply to this email and the"
            + " ticket will reopen.";
    enqueue(c.get(), null, p(e(msg)), msg, List.of());
    return true;
  }

  /** "Closed" after the auto-close. */
  public boolean closed(AccessScope scope, long ticketId) {
    Optional<Context> c = automaticContext(scope, ticketId);
    if (c.isEmpty()) {
      return false;
    }
    String ref = c.get().reference();
    String msg =
        ref
            + " is now closed. If you reply to this email, we will open a new ticket linked to"
            + " this one.";
    enqueue(c.get(), null, p(e(msg)), msg, List.of());
    return true;
  }

  /**
   * A supporter's public reply: only the new reply, the supporter's name as display name, the full
   * signature on every reply, inline images and files of the reply as MIME parts (customers cannot
   * download from the app).
   */
  public boolean publicReply(AccessScope scope, long ticketId, long commentId) {
    // A reply whose CC the agent chose goes to exactly that CC (still re-checked below).
    List<CcRecipient> chosenCc = comments.chosenCc(scope, commentId).orElse(null);
    Optional<Context> ctx = tickets.find(scope, ticketId).flatMap(t -> context(scope, t, chosenCc));
    if (ctx.isEmpty()) {
      return false;
    }
    PublicCommentRow comment =
        comments.listForTicket(scope, ticketId).stream()
            .filter(r -> r.id() == commentId && r.authorKind() == AuthorKind.SUPPORTER)
            .findFirst()
            .orElse(null);
    if (comment == null || comment.authorUserId() == null) {
      return false;
    }
    return supporterMail(ctx.get(), comment.authorUserId(), comment, null);
  }

  /**
   * A ticket an agent opened for a client (source AGENT) is emailed like that agent's public reply
   * instead of the "Thank you, we received your request" confirmation: the agent's name as display
   * name, one opening line with the reference, the description as the agent's own message (the
   * ticket's first public comment, authored by the agent), the full signature and the usual footer.
   * Same recipients as a reply (reporter, confirmed participants); same outbox and routing. Returns
   * false when nothing could be queued (no recipient, unknown agent).
   */
  public boolean agentOpened(AccessScope scope, long ticketId, long agentUserId) {
    Person agent = mailData.person(scope, agentUserId).orElse(null);
    if (agent == null || "CUSTOMER".equals(agent.role())) {
      // Defensive: only a supporter's name is ever put on a client email as its sender. Still the
      // agent-opened email (never switchable), so the switch is not asked.
      return confirmation(scope, ticketId, false);
    }
    Optional<Context> ctx = automaticContext(scope, ticketId);
    if (ctx.isEmpty()) {
      return false;
    }
    List<PublicCommentRow> all = comments.listForTicket(scope, ticketId);
    PublicCommentRow description =
        all.isEmpty()
                || all.get(0).authorKind() != AuthorKind.SUPPORTER
                || !Long.valueOf(agentUserId).equals(all.get(0).authorUserId())
            ? null
            : all.get(0);
    String opening = "We've logged your request as " + ctx.get().reference() + ".";
    return supporterMail(ctx.get(), agentUserId, description, opening);
  }

  /**
   * The email of a supporter's message: {@code comment} (may be null: the opening line only) with
   * its files, after an optional {@code opening} line, then the full signature.
   */
  private boolean supporterMail(
      Context ctx, long supporterId, PublicCommentRow comment, String opening) {
    AccessScope scope = ctx.scope();
    Person supporter = mailData.person(scope, supporterId).orElse(null);
    if (supporter == null) {
      return false;
    }
    SupporterSignatures.Signature sig = signatures.render(scope, supporter.id());

    List<MailPart> parts = new ArrayList<>(sig.parts());
    long budget =
        OutboundEmail.MAX_PART_BYTES
            - sig.parts().stream().mapToLong(part -> part.content().length).sum();
    List<String> skipped = new ArrayList<>();
    String body = comment == null ? "" : comment.bodyHtml();
    if (comment != null) {
      for (AttachmentRow a : attachments.listForTicket(scope, ctx.ticketId(), false)) {
        if (a.commentId() == null || a.commentId() != comment.id()) {
          continue;
        }
        if (a.sizeBytes() > budget) {
          skipped.add(a.filename());
          continue;
        }
        byte[] bytes = read(scope, a);
        if (bytes == null) {
          skipped.add(a.filename());
          continue;
        }
        budget -= bytes.length;
        boolean inline = a.image() && a.inline();
        String cid = "att-" + a.publicId();
        parts.add(
            new MailPart(
                inline ? cid : null,
                a.filename(),
                a.contentType().toLowerCase(Locale.ROOT),
                inline,
                bytes));
      }
    }
    body = ATTACHMENT_SRC.matcher(body).replaceAll("cid:att-$1");
    String plainBody = comment == null ? "" : HtmlText.toPlainText(comment.bodyHtml());
    if (opening != null) {
      body = p(e(opening)) + body;
      plainBody = plainBody.isBlank() ? opening : opening + "\n\n" + plainBody;
    }
    String html = body + SIGNATURE_SPACER + sig.html();
    StringBuilder text = new StringBuilder(plainBody).append("\n\n-- \n");
    text.append(sig.text());
    if (!skipped.isEmpty()) {
      String note = "Some files were too large to send by email: " + String.join(", ", skipped);
      html += p(e(note));
      text.append("\n\n").append(note);
    }
    String fromName = supporter.name() + " (" + supportName.get() + ")";
    enqueue(ctx, fromName, html, text.toString(), parts);
    return true;
  }

  /**
   * "Client replied" alert to the assignee: agent-only, Reply-To {@code support+internal@}, subject
   * {@code [REF] (Internal) ...}; replying adds an internal note.
   */
  public boolean clientRepliedAlert(AccessScope scope, long ticketId, long commentId) {
    TicketRow t = tickets.find(scope, ticketId).orElse(null);
    if (t == null || t.assigneeUserId() == null) {
      return false;
    }
    Person assignee = mailData.person(scope, t.assigneeUserId()).orElse(null);
    if (assignee == null || !"ACTIVE".equals(assignee.status())) {
      return false;
    }
    TicketReference ref = references.displayReferences(scope, List.of(ticketId)).get(ticketId);
    PublicCommentRow reply =
        comments.listForTicket(scope, ticketId).stream()
            .filter(r -> r.id() == commentId)
            .findFirst()
            .orElse(null);
    if (ref == null || reply == null) {
      return false;
    }
    String who = reply.authorEmail() == null ? "The client" : reply.authorEmail();
    String html =
        p(e(who) + " replied on " + e(ref.toString()) + ":")
            + "<blockquote style=\"margin:0 0 0 8px;padding-left:8px;border-left:3px solid"
            + " #A6A6A6;\">"
            + inlineImagesRemoved(reply.bodyHtml())
            + "</blockquote>"
            + p("Reply to this email to add an internal note. It is never sent to the client.");
    String text =
        who
            + " replied on "
            + ref
            + ":\n\n"
            + quote(HtmlText.toPlainText(reply.bodyHtml()))
            + "\n\nReply to this email to add an internal note. It is never sent to the client.";
    OutboundEmail.Builder b =
        OutboundEmail.builder(MailKind.INTERNAL)
            .to(assignee.email())
            .reference(ref.toString())
            .subject("Client replied: " + t.subject())
            .body(wrap(html), text)
            .ticketId(ticketId);
    addresses.internal(scope).ifPresent(b::replyTo);
    outbox.enqueue(b.build());
    return true;
  }

  // ---------------------------------------------------------------------------------------------

  private record Context(
      AccessScope scope,
      long ticketId,
      String reference,
      String subject,
      String to,
      List<String> cc) {}

  /** Context for automatic mail: none for triage, suppressed or recipient-less tickets. */
  /**
   * Is this automatic email turned off for the ticket's organisation? Asked only once the email
   * would really be queued (organisation, recipients, no suppression), so the timeline line
   * "<Email> not sent - turned off for <organisation>" is written only for an email that was
   * skipped because of the switch. Same transaction as the queueing.
   */
  private boolean turnedOff(Context c, ClientEmail email) {
    TicketRow t = tickets.find(c.scope(), c.ticketId()).orElse(null);
    if (t == null || t.organisationId() == null) {
      return false; // triage tickets keep sending (they get no automatic mail anyway)
    }
    if (switches.isOn(c.scope(), t.organisationId(), email)) {
      return false;
    }
    switches.recordSkipped(c.scope(), t.organisationId(), t.id(), email);
    return true;
  }

  private Optional<Context> automaticContext(AccessScope scope, long ticketId) {
    TicketRow t = tickets.find(scope, ticketId).orElse(null);
    if (t == null || t.organisationId() == null || mailData.isSuppressed(scope, ticketId)) {
      return Optional.empty();
    }
    return context(scope, t);
  }

  private Optional<Context> context(AccessScope scope, TicketRow t) {
    return context(scope, t, null);
  }

  /**
   * {@code chosenCc} null = the confirmed participants; otherwise the CC an agent chose for this
   * reply, with the same checks: an account must still belong to the organisation, and our own
   * addresses and the To address are dropped.
   */
  private Optional<Context> context(AccessScope scope, TicketRow t, List<CcRecipient> chosenCc) {
    TicketReference ref = references.displayReferences(scope, List.of(t.id())).get(t.id());
    if (ref == null) {
      return Optional.empty();
    }
    String to = null;
    if (t.reporterUserId() != null) {
      Person reporter = mailData.person(scope, t.reporterUserId()).orElse(null);
      if (reporter != null && belongs(reporter, t.organisationId())) {
        to = reporter.email();
      }
    } else if (t.reporterEmail() != null) {
      to = t.reporterEmail();
    }
    Set<String> cc = new LinkedHashSet<>();
    List<ParticipantRow> ccRows =
        chosenCc == null
            ? participants.listForTicket(scope, t.id())
            : chosenRows(scope, t, chosenCc);
    for (ParticipantRow p : ccRows) {
      if (!p.confirmed()) {
        continue;
      }
      if (p.userId() != null) {
        Person person = mailData.person(scope, p.userId()).orElse(null);
        if (person == null || !belongs(person, t.organisationId())) {
          continue;
        }
      }
      cc.add(p.email().toLowerCase(Locale.ROOT));
    }
    if (to != null && addresses.isOurs(scope, to)) {
      to = null;
    }
    cc.removeIf(a -> addresses.isOurs(scope, a));
    if (to == null && cc.isEmpty()) {
      return Optional.empty();
    }
    if (to == null) {
      to = cc.iterator().next();
    }
    String toLower = to.toLowerCase(Locale.ROOT);
    cc.remove(toLower);
    return Optional.of(
        new Context(scope, t.id(), ref.toString(), t.subject(), to, List.copyOf(cc)));
  }

  /** The chosen CC as participant-like rows (an account's id looked up from its address). */
  private List<ParticipantRow> chosenRows(
      AccessScope scope, TicketRow t, List<CcRecipient> chosen) {
    List<ParticipantRow> rows = new ArrayList<>();
    for (CcRecipient r : chosen) {
      // An address-only recipient that has since become an account is checked as that account -
      // except a customer of another organisation, who stays an outside address on this ticket
      // (emailed like any confirmed outside CC, never linked to their
      // account).
      Long userId =
          r.userId() != null ? r.userId() : accountOfAddress(scope, r.email(), t.organisationId());
      rows.add(new ParticipantRow(userId, r.email(), true));
    }
    return rows;
  }

  /**
   * The account behind an address-only recipient, or null when there is none or it is a customer of
   * another organisation (an outside address on this ticket).
   */
  private Long accountOfAddress(AccessScope scope, String email, Long organisationId) {
    Long id = mailData.accountIdByEmail(scope, email).orElse(null);
    if (id == null) {
      return null;
    }
    Person person = mailData.person(scope, id).orElse(null);
    if (person != null
        && "CUSTOMER".equals(person.role())
        && person.organisationId() != null
        && !person.organisationId().equals(organisationId)) {
      return null;
    }
    return id;
  }

  /** An account recipient must be a live customer of the ticket's organisation. */
  private static boolean belongs(Person person, Long organisationId) {
    if (!"CUSTOMER".equals(person.role())) {
      return false;
    }
    if ("DEACTIVATED".equals(person.status())) {
      return false;
    }
    return organisationId != null && organisationId.equals(person.organisationId());
  }

  private void enqueue(Context c, String fromName, String html, String text, List<MailPart> parts) {
    String footerText =
        "Reference: "
            + c.reference()
            + ". Reply to this email to add to your ticket, and please keep ["
            + c.reference()
            + "] in the subject.";
    String fullHtml =
        wrap(
            html
                + "<hr style=\"border:0;border-top:1px solid #DDDDDD;margin:16px 0;\">"
                + "<p style=\"margin:0;"
                + FONT
                + "font-size:12px;color:#555555;\">"
                + e(footerText)
                + "</p>");
    String fullText = text.strip() + "\n\n-- \n" + footerText + "\n";
    OutboundEmail.Builder b =
        OutboundEmail.builder(MailKind.CLIENT_FACING)
            .to(c.to())
            .cc(c.cc())
            .fromName(fromName)
            .reference(c.reference())
            .subject(c.subject())
            .body(fullHtml, fullText)
            .ticketId(c.ticketId());
    addresses.support(c.scope()).ifPresent(b::replyTo);
    threading(c.scope(), c.ticketId()).forEach(b::header);
    parts.forEach(b::part);
    outbox.enqueue(b.build());
  }

  /**
   * {@code In-Reply-To} the client's latest mail (or our latest mail) and {@code References} to
   * both threads, so the client's mail app keeps one conversation. Ids from inbound mail are
   * sender-controlled and copied only when they look like a Message-ID.
   */
  private java.util.Map<String, String> threading(AccessScope scope, long ticketId) {
    List<String> inbound =
        mailData.clientMessageIds(scope, ticketId, 5).stream()
            .filter(TicketMailComposer::looksLikeMessageId)
            .toList();
    List<String> ours = outbox.clientThreadMessageIds(ticketId, 5);
    java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
    String inReplyTo = !inbound.isEmpty() ? inbound.get(0) : ours.isEmpty() ? null : ours.get(0);
    if (inReplyTo == null) {
      return headers;
    }
    headers.put("In-Reply-To", inReplyTo);
    Set<String> refs = new LinkedHashSet<>();
    for (int i = inbound.size() - 1; i >= 0; i--) {
      refs.add(inbound.get(i));
    }
    for (int i = ours.size() - 1; i >= 0; i--) {
      refs.add(ours.get(i));
    }
    StringBuilder value = new StringBuilder();
    for (String id : refs) {
      if (value.length() + id.length() + 1 > 1900) {
        break;
      }
      value.append(value.length() == 0 ? "" : " ").append(id);
    }
    headers.put("References", value.toString());
    return headers;
  }

  /** Bytes of an attachment already authorised by the scoped listing of its ticket. */
  private byte[] read(AccessScope scope, AttachmentRow a) {
    try (InputStream in = blobs.open(a.storageKey())) {
      return in.readNBytes(MailPart.MAX_BYTES + 1);
    } catch (IOException | RuntimeException ex) {
      log.warn("attachment {} could not be read for email", a.publicId());
      return null;
    }
  }

  private static String inlineImagesRemoved(String html) {
    return html == null ? "" : html.replaceAll("<img[^>]*>", "[image]");
  }

  private static String quote(String text) {
    return text.lines().map(l -> "> " + l).reduce((a, b) -> a + "\n" + b).orElse("");
  }

  private static String p(String escapedHtml) {
    return "<p style=\"margin:0 0 12px 0;" + FONT + "font-size:14px;\">" + escapedHtml + "</p>";
  }

  private static String wrap(String inner) {
    return "<div style=\""
        + FONT
        + "font-size:14px;line-height:20px;color:#000000;\">"
        + inner
        + "</div>";
  }

  static String e(String value) {
    return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
  }

  static boolean looksLikeMessageId(String id) {
    return id != null && MESSAGE_ID.matcher(id).matches();
  }
}
