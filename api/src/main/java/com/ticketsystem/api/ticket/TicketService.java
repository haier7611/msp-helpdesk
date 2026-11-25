package com.ticketsystem.api.ticket;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.AttachmentRow;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.AuthorKind;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.NewPublicComment;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository.RequestTypeRow;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ActorKind;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.AssignmentReason;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.Extra;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ParticipantReason;
import com.ticketsystem.api.ticket.clientdata.TicketLinkRepository;
import com.ticketsystem.api.ticket.clientdata.TicketLinkRepository.LinkKind;
import com.ticketsystem.api.ticket.clientdata.TicketLinkRepository.LinkRow;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.NewTicketRow;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import com.ticketsystem.api.ticket.event.TicketEvent;
import com.ticketsystem.api.ticket.richtext.RichTextSanitiser;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ticket workflow: create from any channel, status changes by the rules in {@link
 * TicketTransitions}, priority and request type, assignment, moving between organisations (new
 * reference, old one an alias), public and client replies, mark as duplicate, related links and
 * follow-ups. Internal notes are in {@code ticket.agent} (they never leave agent-only code).
 *
 * <p>Every method takes the caller's {@link AccessScope}; a ticket the scope cannot see answers 404
 * exactly like a missing one. Every change locks the ticket row first, records history and
 * publishes a {@link TicketEvent} that listeners receive after commit.
 */
@Service
public class TicketService {

  static final int MAX_SUBJECT = 250;
  static final int MAX_BODY_CHARS = 200_000;
  private static final int MAX_DUPLICATE_CHAIN = 50;

  /** A new ticket from any channel. Null fields are optional. */
  public record NewTicket(
      Long organisationId,
      Long reporterUserId,
      String reporterEmail,
      long requestTypeId,
      Priority priority,
      Impact impact,
      Urgency urgency,
      TicketSource source,
      String subject,
      String descriptionHtml,
      List<UUID> attachmentIds,
      String externalId,
      Long assigneeUserId,
      String messageId,
      String authResults) {}

  /** A created ticket and its primary reference. */
  public record Created(long ticketId, TicketReference reference) {}

  /**
   * A client reply (email intake). {@code attachmentIds}: pending files stored from the email by
   * the same scope, bound to the reply.
   */
  public record ClientReply(
      Long authorUserId,
      String authorEmail,
      String bodyHtml,
      boolean receivedByEmail,
      String messageId,
      String authResults,
      List<UUID> attachmentIds) {

    public ClientReply(
        Long authorUserId,
        String authorEmail,
        String bodyHtml,
        boolean receivedByEmail,
        String messageId,
        String authResults) {
      this(authorUserId, authorEmail, bodyHtml, receivedByEmail, messageId, authResults, List.of());
    }

    public ClientReply {
      attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }
  }

  /**
   * Where a client reply landed: the ticket itself, or a new follow-up when the ticket was Closed.
   */
  public record ReplyResult(long ticketId, long commentId, boolean followUpCreated) {}

  private final TicketRepository tickets;
  private final PublicCommentRepository publicComments;
  private final TicketHistoryRepository history;
  private final TicketLinkRepository links;
  private final TicketParticipantRepository participants;
  private final RequestTypeRepository requestTypes;
  private final TicketPeopleRepository people;
  private final ReferenceService references;
  private final AttachmentService attachments;
  private final ApplicationEventPublisher events;
  private final AuditService audit;
  private final ReplyCcService replyCc;
  private final Clock clock;

  public TicketService(
      TicketRepository tickets,
      PublicCommentRepository publicComments,
      TicketHistoryRepository history,
      TicketLinkRepository links,
      TicketParticipantRepository participants,
      RequestTypeRepository requestTypes,
      TicketPeopleRepository people,
      ReferenceService references,
      AttachmentService attachments,
      ApplicationEventPublisher events,
      AuditService audit,
      ReplyCcService replyCc,
      Clock clock) {
    this.tickets = tickets;
    this.publicComments = publicComments;
    this.history = history;
    this.links = links;
    this.participants = participants;
    this.requestTypes = requestTypes;
    this.people = people;
    this.references = references;
    this.attachments = attachments;
    this.events = events;
    this.audit = audit;
    this.replyCc = replyCc;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------------------------
  // Create

  /**
   * Creates a ticket (status New) with a reference from the organisation's counter, or {@code TRI-}
   * when {@code organisationId} is null (triage, supporters and system only). The description is
   * stored as the first public comment, authored by the client (or the API key), with the uploads
   * bound to it; on a ticket an agent opens for a client (source AGENT) it is authored by that
   * agent and emailed like a public reply.
   */
  @Transactional
  public Created create(AccessScope scope, NewTicket n) {
    Objects.requireNonNull(n.source(), "source");
    Instant now = clock.instant();
    Long org = n.organisationId();
    if (org == null) {
      scope.requireSupporterOrSystem();
    } else {
      scope.require(org);
    }
    String subject = cleanSubject(n.subject());
    RequestTypeRow type =
        requestTypes
            .find(scope, n.requestTypeId())
            // An archived type is not offered for new tickets; system routing (a sender
            // rule, an API alert) still uses it so no mail or alert is lost.
            .filter(r -> !r.archived() || scope.isSystem())
            .orElseThrow(() -> ApiException.invalidRequest("Choose a request type."));
    Priority priority = type.defaultPriority();
    if (n.priority() != null) {
      if (!scope.isSupporter() && !scope.isSystem()) {
        throw ApiException.invalidRequest("Priority is set by the support team.");
      }
      priority = n.priority();
    }
    Long reporter = n.reporterUserId();
    String reporterEmail = blankToNull(n.reporterEmail());
    if (reporter != null) {
      if (org == null) {
        throw ApiException.invalidRequest("A triage ticket has no contact yet.");
      }
      reporterEmail =
          people
              .customerOf(scope, reporter, org)
              .orElseThrow(
                  () -> ApiException.invalidRequest("Choose a contact from this organisation."))
              .email();
    }
    Long assignee = n.assigneeUserId();
    if (assignee != null) {
      scope.requireSupporterOrSystem();
      requireAssignable(scope, assignee, org);
    }
    List<AttachmentRow> claimed = attachments.claimPending(scope, n.attachmentIds());
    String body = sanitise(n.descriptionHtml(), claimed);

    long id =
        tickets.insert(
            scope,
            new NewTicketRow(
                org,
                reporter,
                reporterEmail,
                assignee,
                type.id(),
                priority,
                n.impact(),
                n.urgency(),
                n.source(),
                subject,
                blankToNull(n.externalId()),
                null,
                actor(scope)),
            now);
    TicketReference reference = references.issue(scope, org, id);
    if (!body.isEmpty() || !claimed.isEmpty()) {
      // A ticket an agent opens for a client starts with the agent's own message,
      // emailed to the client like a public reply; every other channel stores the client's text.
      boolean byAgent = n.source() == TicketSource.AGENT && scope.isSupporter();
      AuthorKind kind =
          byAgent
              ? AuthorKind.SUPPORTER
              : n.source() == TicketSource.API ? AuthorKind.API_KEY : AuthorKind.CLIENT;
      long commentId =
          publicComments.insert(
              scope,
              org,
              new NewPublicComment(
                  id,
                  kind,
                  byAgent ? actor(scope) : reporter,
                  byAgent ? null : reporterEmail,
                  body,
                  n.source() == TicketSource.EMAIL,
                  blankToNull(n.messageId()),
                  blankToNull(n.authResults())),
              now);
      attachments.bindToPublicComment(
          scope, org, id, commentId, claimed, RichTextSanitiser.inlineImageIds(body));
    }
    history.record(
        scope,
        org,
        id,
        HistoryType.CREATED,
        actor(scope),
        null,
        n.source().name(),
        now,
        n.source() == TicketSource.EMAIL && scope.isSystem()
            ? Extra.actor(ActorKind.CLIENT)
            : null);
    if (assignee != null) {
      // Created already assigned (agent "New ticket") is an assignment too, so the
      // timeline shows it. The assignee is notified through TicketCreated, not AssigneeChanged.
      history.record(
          scope, org, id, HistoryType.ASSIGNEE, actor(scope), null, assignee.toString(), now);
    }
    events.publishEvent(new TicketEvent.TicketCreated(id, org, n.source(), actor(scope), null));
    if (assignee != null && assignee.equals(actor(scope))) {
      // Creating a ticket assigned to yourself picks it up.
      TicketRow t = lock(scope, id);
      apply(
          scope,
          t,
          TicketTransitions.next(t.status(), Trigger.PICK_UP, null),
          Trigger.PICK_UP,
          now);
    }
    return new Created(id, reference);
  }

  // ---------------------------------------------------------------------------------------------
  // Status, priority, request type, assignee

  /** An agent sets a status by hand (status menu); Closed needs a reason (the overload below). */
  @Transactional
  public TicketStatus changeStatus(AccessScope scope, long ticketId, TicketStatus requested) {
    return changeStatus(scope, ticketId, requested, null, null);
  }

  /**
   * An agent sets a status by hand (status menu). Closed needs {@code closeReason} (a manual {@link
   * CloseReason}; Duplicate is refused with a pointer to "mark as duplicate") and, for Other, a
   * note of 3-500 characters; both are ignored for any other status. The reason is stored on the
   * ticket (current close) and on the history row (timeline).
   */
  @Transactional
  public TicketStatus changeStatus(
      AccessScope scope,
      long ticketId,
      TicketStatus requested,
      String closeReason,
      String closeNote) {
    scope.requireSupporterOrSystem();
    CloseReason.Close close =
        requested == TicketStatus.CLOSED ? CloseReason.request(closeReason, closeNote) : null;
    TicketRow t = lock(scope, ticketId);
    TicketStatus next = TicketTransitions.next(t.status(), Trigger.AGENT_SET, requested);
    apply(scope, t, next, Trigger.AGENT_SET, clock.instant(), close, null);
    return next;
  }

  /**
   * An admin reopens a Closed ticket (status back to Open) with a required reason of 3-500
   * characters; the controller requires a step-up. The SLA resolution clock carries on from the
   * status history as for a client reopen (no reset). The reason is kept on the timeline only; the
   * audit event holds ids. A ticket merged as a duplicate cannot be reopened (its references lead
   * to the ticket it was merged into): reopen that one instead.
   *
   * @throws ApiException 403 for anyone but an admin, 400 {@code reopen_reason_required}, 409
   *     {@code not_closed} or {@code duplicate_cannot_reopen}
   */
  @Transactional
  public TicketStatus adminReopen(AccessScope scope, long ticketId, String reason, String ip) {
    scope.requireAdmin();
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    String why =
        CloseReason.note(reason, "reopen_reason_required", "Add a reason for reopening the ticket");
    TicketRow t = lock(scope, ticketId);
    if (t.status() == TicketStatus.CLOSED && t.duplicateOfId() != null) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "duplicate_cannot_reopen",
          "This ticket was merged into another ticket as a duplicate. Reopen that ticket instead.");
    }
    TicketStatus next = TicketTransitions.next(t.status(), Trigger.ADMIN_REOPEN, null);
    apply(scope, t, next, Trigger.ADMIN_REOPEN, clock.instant(), null, why);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("ticket", t.id());
    if (t.organisationId() != null) {
      details.put("organisation", t.organisationId());
    }
    audit.record(TicketAuditEvent.TICKET_REOPENED, actor(scope), null, ip, details);
    return next;
  }

  /**
   * A status change from a scheduled job: {@link Trigger#AUTO_RESOLVE} or {@link
   * Trigger#AUTO_CLOSE}.
   */
  @Transactional
  public TicketStatus applySystemTrigger(AccessScope scope, long ticketId, Trigger trigger) {
    if (!scope.isSystem() || (trigger != Trigger.AUTO_RESOLVE && trigger != Trigger.AUTO_CLOSE)) {
      throw AccessScope.notFound();
    }
    TicketRow t = lock(scope, ticketId);
    TicketStatus next = TicketTransitions.next(t.status(), trigger, null);
    apply(scope, t, next, trigger, clock.instant());
    return next;
  }

  @Transactional
  public void changePriority(AccessScope scope, long ticketId, Priority priority) {
    scope.requireSupporterOrSystem();
    if (priority == null) {
      throw ApiException.invalidRequest("Choose a priority.");
    }
    changePriority(scope, lockOpen(scope, ticketId), priority, Extra.NONE, events::publishEvent);
  }

  /**
   * An SLA automation rule raises the priority one level (never above P1). Exactly like a manual
   * change - history row, {@link TicketEvent.PriorityChanged} and so the SLA recalculation - with
   * the rule's name on the history row so the timeline reads "by Automation (rule)". SYSTEM only.
   * Returns the new priority, or null when the ticket is already P1.
   *
   * <p>The {@link TicketEvent.PriorityChanged} event goes to {@code publish} instead of straight to
   * the application: the engine publishes it only once the rule's savepoint has committed, so a
   * rule whose later action fails leaves no event behind for the change it rolled back.
   */
  @Transactional
  public Priority raisePriorityByAutomation(
      AccessScope scope, long ticketId, String ruleName, Consumer<TicketEvent> publish) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
    TicketRow t = lockOpen(scope, ticketId);
    Priority raised = oneLevelUp(t.priority());
    if (raised == null) {
      return null;
    }
    changePriority(scope, t, raised, automation(ruleName), publish);
    return raised;
  }

  /** The next priority up, or null for P1 (the top). */
  static Priority oneLevelUp(Priority p) {
    return switch (p) {
      case P1 -> null;
      case P2 -> Priority.P1;
      case P3 -> Priority.P2;
      case P4 -> Priority.P3;
    };
  }

  private void changePriority(
      AccessScope scope,
      TicketRow t,
      Priority priority,
      Extra extra,
      Consumer<TicketEvent> publish) {
    if (t.priority() == priority) {
      return;
    }
    Instant now = clock.instant();
    tickets.updatePriority(scope, t, priority, now);
    history.record(
        scope,
        t.organisationId(),
        t.id(),
        HistoryType.PRIORITY,
        actor(scope),
        t.priority().name(),
        priority.name(),
        now,
        extra);
    publish.accept(new TicketEvent.PriorityChanged(t.id(), t.priority(), priority, actor(scope)));
  }

  /** The history columns that mark a change made by an SLA automation rule. */
  private static Extra automation(String ruleName) {
    return new Extra(ActorKind.SYSTEM, AUTOMATION_REASON, null, null, null, null, ruleName);
  }

  /**
   * The {@code ticket_event.reason} of a PRIORITY or ASSIGNEE row written by an SLA automation rule
   * ({@code note} = the rule's name at the time).
   */
  public static final String AUTOMATION_REASON = "SLA_AUTOMATION";

  @Transactional
  public void changeRequestType(AccessScope scope, long ticketId, long requestTypeId) {
    scope.requireSupporterOrSystem();
    TicketRow t = lockOpen(scope, ticketId);
    RequestTypeRow type =
        requestTypes
            .find(scope, requestTypeId)
            .filter(r -> !r.archived() || r.id() == t.requestTypeId())
            .orElseThrow(() -> ApiException.invalidRequest("Choose a request type."));
    if (t.requestTypeId() == type.id()) {
      return;
    }
    Instant now = clock.instant();
    tickets.updateRequestType(scope, t, type.id(), now);
    history.record(
        scope,
        t.organisationId(),
        t.id(),
        HistoryType.REQUEST_TYPE,
        actor(scope),
        Long.toString(t.requestTypeId()),
        Long.toString(type.id()),
        now);
  }

  /**
   * Assigns ({@code assigneeUserId}) or unassigns (null) a ticket (any agent with access to the
   * organisation may reassign). The assignee must be an active supporter with access to the
   * ticket's organisation. Assigning a New ticket to yourself picks it up: it moves to Open.
   */
  @Transactional
  public void assign(AccessScope scope, long ticketId, Long assigneeUserId) {
    assign(scope, ticketId, assigneeUserId, true, null, null);
  }

  /**
   * Moves a ticket to another assignee (or Unassigned) and keeps its status - never a pick-up, even
   * when the new assignee is the actor. Used by the deactivation wizard: an admin taking over a
   * leaving supporter's tickets has not started work on them, so a New ticket stays New. Same
   * scope, assignee checks, history and event as {@link #assign}; the history row carries the
   * reason {@link AssignmentReason#DEACTIVATED} for the timeline.
   */
  @Transactional
  public void reassignKeepingStatus(AccessScope scope, long ticketId, Long assigneeUserId) {
    assign(scope, ticketId, assigneeUserId, false, AssignmentReason.DEACTIVATED, null);
  }

  /**
   * Auto-assignment by a default-assignee rule: like {@link #assign}, never a pick-up, with the
   * rule ({@code reason} and the organisation or request type id) kept in the history row for the
   * timeline.
   */
  @Transactional
  public void assignAutomatically(
      AccessScope scope,
      long ticketId,
      long assigneeUserId,
      AssignmentReason reason,
      Long reasonRefId) {
    assign(scope, ticketId, assigneeUserId, false, reason, reasonRefId);
  }

  /** Why an SLA automation rule did not assign the ticket. */
  public enum AutomationAssignResult {
    ASSIGNED,
    /** The ticket already has an assignee: the rule never takes a ticket away from anyone. */
    ALREADY_ASSIGNED,
    /** The agent is deactivated or cannot work the ticket's organisation. */
    NOT_ASSIGNABLE
  }

  /**
   * An SLA automation rule assigns an unassigned ticket to the chosen agent - never a pick-up (the
   * status stays), same history and event as {@link #assign}, with the rule's name on the history
   * row. Nothing changes when the ticket is assigned already or the agent cannot be assigned
   * (deactivated, or not an agent of the ticket's organisation). SYSTEM only. The {@link
   * TicketEvent.AssigneeChanged} event goes to {@code publish}, as for {@link
   * #raisePriorityByAutomation}, so a rolled-back rule sends no "assigned to you" notification.
   */
  @Transactional
  public AutomationAssignResult assignByAutomation(
      AccessScope scope,
      long ticketId,
      long assigneeUserId,
      String ruleName,
      Consumer<TicketEvent> publish) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
    TicketRow t = lockOpen(scope, ticketId);
    if (t.assigneeUserId() != null) {
      return AutomationAssignResult.ALREADY_ASSIGNED;
    }
    if (!people.isActiveSupporterFor(scope, assigneeUserId, t.organisationId())) {
      return AutomationAssignResult.NOT_ASSIGNABLE;
    }
    setAssignee(scope, t, assigneeUserId, clock.instant(), automation(ruleName), publish);
    return AutomationAssignResult.ASSIGNED;
  }

  private void assign(
      AccessScope scope,
      long ticketId,
      Long assigneeUserId,
      boolean pickUp,
      AssignmentReason reason,
      Long reasonRefId) {
    scope.requireSupporterOrSystem();
    TicketRow t = lockOpen(scope, ticketId);
    if (assigneeUserId != null) {
      requireAssignable(scope, assigneeUserId, t.organisationId());
    }
    Instant now = clock.instant();
    setAssignee(scope, t, assigneeUserId, now, reason, reasonRefId);
    Long me = actor(scope);
    if (pickUp && assigneeUserId != null && assigneeUserId.equals(me)) {
      apply(
          scope,
          t,
          TicketTransitions.next(t.status(), Trigger.PICK_UP, null),
          Trigger.PICK_UP,
          now);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Organisation

  /**
   * Assigns a triage ticket to an organisation, or moves a ticket to another one: a new primary
   * reference from the new organisation's counter (every earlier reference stays an alias),
   * participants re-checked, links to tickets left behind removed, the assignee cleared if they
   * cannot work the new organisation. Audit-logged. Closed tickets cannot move.
   */
  @Transactional
  public TicketReference changeOrganisation(
      AccessScope scope, long ticketId, long newOrganisationId) {
    scope.requireSupporterOrSystem();
    scope.require(newOrganisationId);
    TicketRow t = lockOpen(scope, ticketId);
    if (t.organisationId() != null && t.organisationId() == newOrganisationId) {
      return references.referencesOf(scope, t.id()).get(0);
    }
    Instant now = clock.instant();
    TicketReference before = references.displayReferences(scope, List.of(t.id())).get(t.id());
    TicketReference fresh = references.assignOrganisation(scope, t.id(), newOrganisationId);
    tickets.updateOrganisation(scope, t, newOrganisationId, now);
    // The move first, then what it caused (links, participants, assignee), in that order.
    history.record(
        scope,
        newOrganisationId,
        t.id(),
        HistoryType.ORGANISATION,
        actor(scope),
        t.organisationId() == null ? null : t.organisationId().toString(),
        Long.toString(newOrganisationId),
        now,
        Extra.NONE.withReferences(
            before == null ? null : before.toString(), fresh == null ? null : fresh.toString()));
    for (LinkRow removedLink :
        links.removeOnMove(scope, t.id(), t.organisationId(), newOrganisationId)) {
      Extra why = new Extra(null, removedLink.kind().name(), newOrganisationId, null, null);
      history.record(
          scope,
          newOrganisationId,
          t.id(),
          HistoryType.LINK_REMOVED,
          actor(scope),
          null,
          Long.toString(removedLink.otherTicketId()),
          now,
          why);
      history.record(
          scope,
          t.organisationId(),
          removedLink.otherTicketId(),
          HistoryType.LINK_REMOVED,
          actor(scope),
          null,
          Long.toString(t.id()),
          now,
          why);
    }
    participants.recheckAfterMove(scope, t.id(), newOrganisationId, now);
    TicketRow moved = lock(scope, t.id());
    if (moved.assigneeUserId() != null
        && !people.isActiveSupporterFor(scope, moved.assigneeUserId(), newOrganisationId)) {
      setAssignee(scope, moved, null, now, AssignmentReason.ORGANISATION_MOVED, newOrganisationId);
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("ticket", t.id());
    details.put("organisation", newOrganisationId);
    if (t.organisationId() != null) {
      details.put("old_organisation", t.organisationId());
    }
    audit.record(TicketAuditEvent.TICKET_ORGANISATION_CHANGED, actor(scope), null, null, details);
    events.publishEvent(
        new TicketEvent.OrganisationChanged(
            t.id(), t.organisationId(), newOrganisationId, actor(scope)));
    return fresh;
  }

  // ---------------------------------------------------------------------------------------------
  // Comments

  /**
   * A supporter's public reply from the agent UI (emailed to the client by email). A New ticket
   * moves to Open; {@code thenStatus} sets a status in the same action ("Send and set Pending on
   * client"). Refused on Closed tickets. Sent replies cannot be edited (there is no update path).
   */
  @Transactional
  public long addPublicReply(
      AccessScope scope,
      long ticketId,
      String bodyHtml,
      List<UUID> attachmentIds,
      TicketStatus thenStatus) {
    return addPublicReply(scope, ticketId, bodyHtml, attachmentIds, thenStatus, null);
  }

  /**
   * As above with the CC the agent chose in the composer; {@code cc} null keeps today's behaviour
   * (the reply goes to the confirmed participants). Every CC rule is checked by {@link
   * ReplyCcService} before anything is stored.
   */
  @Transactional
  public long addPublicReply(
      AccessScope scope,
      long ticketId,
      String bodyHtml,
      List<UUID> attachmentIds,
      TicketStatus thenStatus,
      ReplyCcService.ReplyCc cc) {
    return publicReply(scope, ticketId, bodyHtml, attachmentIds, thenStatus, false, null, null, cc);
  }

  /**
   * The composer's early CC check: how {@code email} would be treated as a CC on this ticket, as a
   * reason code only. Read-only; the reply checks every rule again.
   */
  @Transactional(readOnly = true)
  public ReplyCcService.CcCheck checkCc(AccessScope scope, long ticketId, String email) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    TicketRow t = tickets.find(scope, ticketId).orElseThrow(AccessScope::notFound);
    return replyCc.check(scope, t, email);
  }

  /**
   * A supporter's public reply sent by email (only when the admin setting is "Notes and public
   * replies"). {@code scope} is that supporter's own scope, so their organisation access applies.
   * Emailed to the client like an agent-UI reply.
   */
  @Transactional
  public long addPublicReplyByEmail(
      AccessScope scope,
      long ticketId,
      String bodyHtml,
      List<UUID> attachmentIds,
      String messageId,
      String authResults) {
    return publicReply(
        scope,
        ticketId,
        bodyHtml,
        attachmentIds,
        null,
        true,
        blankToNull(messageId),
        blankToNull(authResults),
        null);
  }

  private long publicReply(
      AccessScope scope,
      long ticketId,
      String bodyHtml,
      List<UUID> attachmentIds,
      TicketStatus thenStatus,
      boolean byEmail,
      String messageId,
      String authResults,
      ReplyCcService.ReplyCc cc) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    TicketRow t = lockOpen(scope, ticketId);
    if (thenStatus == TicketStatus.CLOSED) {
      // Closing needs a reason, chosen in the close dialog (status menu).
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "close_reason_required",
          "Close the ticket from the status menu, where you choose a reason.");
    }
    ReplyCcService.Plan ccPlan = cc == null ? null : replyCc.plan(scope, t, cc);
    Instant now = clock.instant();
    List<AttachmentRow> claimed = attachments.claimPending(scope, attachmentIds);
    String body = requireContent(sanitise(bodyHtml, claimed), claimed);
    long commentId =
        publicComments.insert(
            scope,
            t.organisationId(),
            new NewPublicComment(
                t.id(),
                AuthorKind.SUPPORTER,
                actor(scope),
                null,
                body,
                byEmail,
                messageId,
                authResults),
            now);
    attachments.bindToPublicComment(
        scope,
        t.organisationId(),
        t.id(),
        commentId,
        claimed,
        RichTextSanitiser.inlineImageIds(body));
    if (ccPlan != null) {
      replyCc.apply(scope, t, commentId, ccPlan, now);
    }
    tickets.touch(scope, t, now);
    events.publishEvent(new TicketEvent.PublicReplyAdded(t.id(), commentId, actor(scope)));
    TicketStatus afterReply =
        TicketTransitions.next(t.status(), Trigger.SUPPORTER_PUBLIC_REPLY, null);
    apply(scope, t, afterReply, Trigger.SUPPORTER_PUBLIC_REPLY, now);
    if (thenStatus != null) {
      TicketStatus chosen = TicketTransitions.next(afterReply, Trigger.AGENT_SET, thenStatus);
      apply(scope, withStatus(t, afterReply), chosen, Trigger.AGENT_SET, now);
    }
    return commentId;
  }

  /**
   * A client reply (by email; the poller calls this with a SYSTEM scope after its sender and DMARC
   * guards). Either Pending status or Resolved moves to Open. On a Closed ticket the reply opens a
   * new linked follow-up ticket instead and is stored there.
   */
  @Transactional
  public ReplyResult addClientReply(AccessScope scope, long ticketId, ClientReply reply) {
    scope.requireSupporterOrSystem();
    TicketRow t = lock(scope, ticketId);
    Instant now = clock.instant();
    boolean followUp = t.status() == TicketStatus.CLOSED;
    TicketRow target =
        followUp ? lock(scope, createFollowUpRow(scope, t, TicketSource.EMAIL, now)) : t;
    List<AttachmentRow> claimed = attachments.claimPending(scope, reply.attachmentIds());
    String body = sanitise(reply.bodyHtml(), claimed);
    if (body.isEmpty()) {
      body = "<p></p>";
    }
    long commentId =
        publicComments.insert(
            scope,
            target.organisationId(),
            new NewPublicComment(
                target.id(),
                AuthorKind.CLIENT,
                reply.authorUserId(),
                blankToNull(reply.authorEmail()),
                body,
                reply.receivedByEmail(),
                blankToNull(reply.messageId()),
                blankToNull(reply.authResults())),
            now);
    attachments.bindToPublicComment(
        scope,
        target.organisationId(),
        target.id(),
        commentId,
        claimed,
        RichTextSanitiser.inlineImageIds(body));
    tickets.touch(scope, target, now);
    events.publishEvent(
        new TicketEvent.ClientReplyAdded(target.id(), commentId, reply.receivedByEmail()));
    apply(
        scope,
        target,
        TicketTransitions.next(target.status(), Trigger.CLIENT_REPLY, null),
        Trigger.CLIENT_REPLY,
        now);
    return new ReplyResult(target.id(), commentId, followUp);
  }

  /**
   * Step 1 of email attachment handling: finishes a comment received by email whose files were
   * processed after it was committed. Binds the stored files ({@code attachmentIds}, the scope's
   * own pending uploads) to the comment and replaces its placeholder body with {@code bodyHtml},
   * where inline images point at those files ({@code attachment:<public id>}). Sanitised exactly
   * like a new comment. No event and no status change: the comment itself was announced when it was
   * saved. Works on a Closed ticket too (the comment is already there).
   */
  @Transactional
  public void completeEmailFiles(
      AccessScope scope, long commentId, String bodyHtml, List<UUID> attachmentIds) {
    scope.requireSupporterOrSystem();
    long ticketId =
        publicComments.ticketOfEmailComment(scope, commentId).orElseThrow(AccessScope::notFound);
    TicketRow t = lock(scope, ticketId);
    List<AttachmentRow> claimed = attachments.claimPending(scope, attachmentIds);
    Set<UUID> images = new HashSet<>();
    claimed.stream().filter(AttachmentRow::image).forEach(a -> images.add(a.publicId()));
    // No length check here: the placeholder body passed it when the comment was saved, and each
    // image's src grows by a few characters only.
    String body = RichTextSanitiser.sanitise(bodyHtml, images);
    if (body.isBlank()) {
      body = "<p></p>";
    }
    publicComments.replaceEmailBody(scope, t.organisationId(), commentId, body);
    attachments.bindToPublicComment(
        scope,
        t.organisationId(),
        t.id(),
        commentId,
        claimed,
        RichTextSanitiser.inlineImageIds(body));
  }

  // ---------------------------------------------------------------------------------------------
  // API alerts

  /** What an alert repeat did to its ticket. */
  public record AlertUpdate(long commentId, TicketStatus from, TicketStatus to) {}

  /**
   * A repeat of an API alert on a ticket that is not Closed: the alert is added as a public comment
   * authored by the key, and the ticket moves by {@link Trigger#ALERT_RESOLVED} ({@code resolve})
   * or {@link Trigger#ALERT_REPEAT} (Resolved reopens to Open). API-key scope only; the key's
   * organisation bounds the lookup, so another organisation's ticket answers 404.
   */
  @Transactional
  public AlertUpdate addAlertComment(
      AccessScope scope, long ticketId, String bodyHtml, boolean resolve) {
    long keyId = requireApiKey(scope);
    TicketRow t = lockOpen(scope, ticketId);
    if (t.source() != TicketSource.API) {
      throw AccessScope.notFound();
    }
    Instant now = clock.instant();
    long commentId = insertAlertComment(scope, t, bodyHtml, now);
    tickets.touch(scope, t, now);
    events.publishEvent(new TicketEvent.AlertCommentAdded(t.id(), commentId, keyId));
    Trigger trigger = resolve ? Trigger.ALERT_RESOLVED : Trigger.ALERT_REPEAT;
    TicketStatus next = TicketTransitions.next(t.status(), trigger, null);
    apply(scope, t, next, trigger, now);
    return new AlertUpdate(commentId, t.status(), next);
  }

  /**
   * A repeat of an API alert whose ticket is Closed: a new linked follow-up ticket (source API,
   * same organisation, request type and participants) with the alert as its first comment. The
   * caller (integrations module) sets the external id on it in the same transaction.
   */
  @Transactional
  public Created createAlertFollowUp(AccessScope scope, long closedTicketId, String bodyHtml) {
    long keyId = requireApiKey(scope);
    TicketRow closed = lock(scope, closedTicketId);
    if (closed.source() != TicketSource.API) {
      throw AccessScope.notFound();
    }
    Instant now = clock.instant();
    long id = createFollowUpRow(scope, closed, TicketSource.API, now);
    TicketRow followUp = lock(scope, id);
    long commentId = insertAlertComment(scope, followUp, bodyHtml, now);
    events.publishEvent(new TicketEvent.AlertCommentAdded(id, commentId, keyId));
    return new Created(id, references.referencesOf(scope, id).get(0));
  }

  private long insertAlertComment(AccessScope scope, TicketRow t, String bodyHtml, Instant now) {
    String body = sanitise(bodyHtml, List.of());
    if (body.isEmpty()) {
      body = "<p></p>";
    }
    return publicComments.insert(
        scope,
        t.organisationId(),
        new NewPublicComment(t.id(), AuthorKind.API_KEY, null, null, body, false, null, null),
        now);
  }

  private static long requireApiKey(AccessScope scope) {
    if (scope.kind() != AccessScope.Kind.API_KEY) {
      throw AccessScope.notFound();
    }
    return scope.principalId().orElseThrow(AccessScope::notFound);
  }

  // ---------------------------------------------------------------------------------------------
  // Duplicates, links, follow-ups

  /**
   * Marks {@code sourceId} as a duplicate of {@code targetId}: following the target's own duplicate
   * chain to its final target, both in the same organisation. The source is Closed without a client
   * email, both are linked, the source's participants (and reporter) are copied to the target, and
   * every reference of the source becomes an alias of the target.
   */
  @Transactional
  public long markDuplicate(AccessScope scope, long sourceId, long targetId) {
    scope.requireSupporterOrSystem();
    if (sourceId == targetId) {
      throw ApiException.invalidRequest("A ticket cannot be a duplicate of itself.");
    }
    long finalTarget = finalTarget(scope, targetId);
    if (finalTarget == sourceId) {
      throw ApiException.invalidRequest("That ticket is already a duplicate of this one.");
    }
    // Lock in id order so two opposite requests cannot deadlock.
    TicketRow first = lock(scope, Math.min(sourceId, finalTarget));
    TicketRow second = lock(scope, Math.max(sourceId, finalTarget));
    TicketRow source = first.id() == sourceId ? first : second;
    TicketRow target = first.id() == sourceId ? second : first;
    if (target.duplicateOfId() != null) {
      throw new ApiException(
          HttpStatus.CONFLICT, "ticket_changed", "That ticket changed. Please try again.");
    }
    Long org = source.organisationId();
    if (org == null || !org.equals(target.organisationId())) {
      throw ApiException.invalidRequest(
          "Duplicates can only be marked within the same organisation.");
    }
    TicketStatus closed = TicketTransitions.next(source.status(), Trigger.MARK_DUPLICATE, null);
    Instant now = clock.instant();
    tickets.markDuplicate(scope, source, target.id(), now);
    tickets.repointDuplicates(scope, org, source.id(), target.id(), now);
    references.aliasToDuplicateTarget(scope, source.id(), target.id(), org);
    Long me = actor(scope);
    // The merge lines first, then the participants it copied to the target.
    history.record(
        scope,
        org,
        source.id(),
        HistoryType.STATUS,
        me,
        source.status().name(),
        closed.name(),
        now,
        Extra.reason(Trigger.MARK_DUPLICATE).withClose(CloseReason.DUPLICATE.name(), null));
    history.record(
        scope,
        org,
        source.id(),
        HistoryType.DUPLICATE_OF,
        me,
        null,
        Long.toString(target.id()),
        now);
    history.record(
        scope,
        org,
        target.id(),
        HistoryType.DUPLICATE_ADDED,
        me,
        null,
        Long.toString(source.id()),
        now);
    participants.copy(
        scope, org, source.id(), target.id(), now, ParticipantReason.DUPLICATE_MERGED);
    if (source.reporterEmail() != null
        && !source.reporterEmail().equalsIgnoreCase(Objects.toString(target.reporterEmail(), ""))) {
      participants.add(
          scope,
          org,
          target.id(),
          source.reporterUserId(),
          source.reporterEmail(),
          true,
          now,
          ParticipantReason.DUPLICATE_MERGED);
    }
    links.link(scope, org, source.id(), target.id(), LinkKind.DUPLICATE, me, now);
    events.publishEvent(
        new TicketEvent.StatusChanged(
            source.id(), source.status(), closed, Trigger.MARK_DUPLICATE, me));
    events.publishEvent(new TicketEvent.MarkedDuplicate(source.id(), target.id(), me));
    return target.id();
  }

  /** Links two tickets of the same organisation as related, without closing either. */
  @Transactional
  public void linkRelated(AccessScope scope, long ticketId, long otherId) {
    scope.requireSupporterOrSystem();
    if (ticketId == otherId) {
      throw ApiException.invalidRequest("A ticket cannot be linked to itself.");
    }
    TicketRow first = lock(scope, Math.min(ticketId, otherId));
    TicketRow second = lock(scope, Math.max(ticketId, otherId));
    Long org = first.organisationId();
    if (org == null || !org.equals(second.organisationId())) {
      throw ApiException.invalidRequest("Only tickets of the same organisation can be linked.");
    }
    Instant now = clock.instant();
    Long me = actor(scope);
    if (links.link(scope, org, ticketId, otherId, LinkKind.RELATED, me, now)) {
      history.record(
          scope, org, ticketId, HistoryType.LINKED, me, null, Long.toString(otherId), now);
      history.record(
          scope, org, otherId, HistoryType.LINKED, me, null, Long.toString(ticketId), now);
      events.publishEvent(new TicketEvent.TicketsLinked(ticketId, otherId, me));
    }
  }

  /**
   * Creates a follow-up of a Closed ticket: "Follow-up to NWD-001", same organisation, reporter,
   * request type and participants, linked to the old one. Email replies to Closed tickets come here
   * through {@link #addClientReply}; agents can also start one by hand.
   */
  @Transactional
  public Created createFollowUp(AccessScope scope, long closedTicketId) {
    scope.requireSupporterOrSystem();
    TicketRow t = lock(scope, closedTicketId);
    long id = createFollowUpRow(scope, t, TicketSource.AGENT, clock.instant());
    return new Created(id, references.referencesOf(scope, id).get(0));
  }

  private long createFollowUpRow(AccessScope scope, TicketRow t, TicketSource source, Instant now) {
    if (t.status() != TicketStatus.CLOSED) {
      throw new ApiException(
          HttpStatus.CONFLICT, "not_closed", "Only a closed ticket can have a follow-up.");
    }
    RequestTypeRow type =
        requestTypes.find(scope, t.requestTypeId()).orElseThrow(AccessScope::notFound);
    TicketReference old = references.displayReferences(scope, List.of(t.id())).get(t.id());
    String subject = "Follow-up to " + (old == null ? "#" + t.id() : old.toString());
    Long org = t.organisationId();
    long id =
        tickets.insert(
            scope,
            new NewTicketRow(
                org,
                t.reporterUserId(),
                t.reporterEmail(),
                null,
                type.id(),
                type.defaultPriority(),
                t.impact(),
                t.urgency(),
                source,
                subject,
                null,
                t.id(),
                actor(scope)),
            now);
    references.issue(scope, org, id);
    Long me = actor(scope);
    if (org != null) {
      participants.copy(scope, org, t.id(), id, now, null);
      links.link(scope, org, t.id(), id, LinkKind.FOLLOW_UP, me, now);
    }
    // A follow-up opened by a client's email reply is the client's doing.
    Extra who =
        source == TicketSource.EMAIL && scope.isSystem() ? Extra.actor(ActorKind.CLIENT) : null;
    history.record(scope, org, id, HistoryType.CREATED, me, null, source.name(), now, who);
    history.record(
        scope, org, id, HistoryType.FOLLOW_UP_OF, me, null, Long.toString(t.id()), now, who);
    history.record(
        scope, org, t.id(), HistoryType.FOLLOW_UP_ADDED, me, null, Long.toString(id), now, who);
    events.publishEvent(new TicketEvent.TicketCreated(id, org, source, me, t.id()));
    return id;
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  private long finalTarget(AccessScope scope, long targetId) {
    long current = targetId;
    Set<Long> seen = new HashSet<>();
    for (int i = 0; i < MAX_DUPLICATE_CHAIN; i++) {
      TicketRow t = tickets.find(scope, current).orElseThrow(AccessScope::notFound);
      if (t.duplicateOfId() == null) {
        return t.id();
      }
      if (!seen.add(t.id())) {
        break;
      }
      current = t.duplicateOfId();
    }
    throw ApiException.invalidRequest("That ticket's duplicate chain is too long.");
  }

  private void apply(
      AccessScope scope, TicketRow t, TicketStatus next, Trigger trigger, Instant now) {
    apply(scope, t, next, trigger, now, null, null);
  }

  /**
   * Applies a status change. A change to Closed records its {@link CloseReason} on the ticket and
   * the history row - the agent's {@code close} for {@link Trigger#AGENT_SET}, {@link
   * CloseReason#AUTO_CLOSED_NO_REPLY} for {@link Trigger#AUTO_CLOSE}; {@code note} is the admin's
   * reopen reason ({@link Trigger#ADMIN_REOPEN}). Leaving Closed clears the ticket's reason.
   */
  private void apply(
      AccessScope scope,
      TicketRow t,
      TicketStatus next,
      Trigger trigger,
      Instant now,
      CloseReason.Close close,
      String note) {
    if (next == t.status()) {
      return;
    }
    CloseReason.Close closing = null;
    if (next == TicketStatus.CLOSED) {
      closing =
          trigger == Trigger.AUTO_CLOSE
              ? new CloseReason.Close(CloseReason.AUTO_CLOSED_NO_REPLY, null)
              : Objects.requireNonNull(close, "a close needs a reason");
    }
    tickets.updateStatus(scope, t, next, now);
    // The trigger tells the timeline why (client reply, auto-resolve, ...); a client's
    // email reply runs with the poller's SYSTEM scope but is the client's doing.
    Extra why = Extra.reason(trigger);
    if (trigger == Trigger.CLIENT_REPLY && !scope.isSupporter()) {
      why = why.withActor(ActorKind.CLIENT);
    }
    if (closing != null) {
      tickets.updateCloseReason(scope, t, closing.reason().name(), closing.note());
      why = why.withClose(closing.reason().name(), closing.note());
    } else if (note != null) {
      why = why.withClose(null, note);
    }
    history.record(
        scope,
        t.organisationId(),
        t.id(),
        HistoryType.STATUS,
        actor(scope),
        t.status().name(),
        next.name(),
        now,
        why);
    events.publishEvent(
        new TicketEvent.StatusChanged(t.id(), t.status(), next, trigger, actor(scope)));
  }

  private void setAssignee(
      AccessScope scope,
      TicketRow t,
      Long assignee,
      Instant now,
      AssignmentReason reason,
      Long reasonRefId) {
    setAssignee(
        scope, t, assignee, now, Extra.reason(reason).withRef(reasonRefId), events::publishEvent);
  }

  private void setAssignee(
      AccessScope scope,
      TicketRow t,
      Long assignee,
      Instant now,
      Extra extra,
      Consumer<TicketEvent> publish) {
    if (Objects.equals(t.assigneeUserId(), assignee)) {
      return;
    }
    tickets.updateAssignee(scope, t, assignee, now);
    history.record(
        scope,
        t.organisationId(),
        t.id(),
        HistoryType.ASSIGNEE,
        actor(scope),
        Objects.toString(t.assigneeUserId(), null),
        Objects.toString(assignee, null),
        now,
        extra);
    publish.accept(
        new TicketEvent.AssigneeChanged(t.id(), t.assigneeUserId(), assignee, actor(scope)));
  }

  private void requireAssignable(AccessScope scope, long userId, Long organisationId) {
    if (!people.isActiveSupporterFor(scope, userId, organisationId)) {
      throw ApiException.invalidRequest(
          "That person cannot be assigned: they are not an active agent of this organisation.");
    }
  }

  private TicketRow lock(AccessScope scope, long ticketId) {
    return tickets.lock(scope, ticketId).orElseThrow(AccessScope::notFound);
  }

  /** Locks a ticket that may still change (anything but Closed: read-only). */
  private TicketRow lockOpen(AccessScope scope, long ticketId) {
    TicketRow t = lock(scope, ticketId);
    if (t.status() == TicketStatus.CLOSED) {
      throw new ApiException(
          HttpStatus.CONFLICT, "ticket_closed", "This ticket is closed and cannot be changed.");
    }
    return t;
  }

  private static TicketRow withStatus(TicketRow t, TicketStatus status) {
    return new TicketRow(
        t.id(),
        t.organisationId(),
        t.reporterUserId(),
        t.reporterEmail(),
        t.assigneeUserId(),
        t.requestTypeId(),
        t.priority(),
        t.impact(),
        t.urgency(),
        status,
        t.source(),
        t.subject(),
        t.externalId(),
        t.duplicateOfId(),
        t.followUpOfId(),
        t.createdByUserId(),
        t.createdAt(),
        t.updatedAt(),
        t.statusChangedAt());
  }

  static String sanitise(String html, List<AttachmentRow> claimed) {
    if (html != null && html.length() > MAX_BODY_CHARS) {
      throw ApiException.invalidRequest("The message is too long.");
    }
    Set<UUID> ids = new HashSet<>();
    for (AttachmentRow a : claimed) {
      if (a.image()) {
        ids.add(a.publicId());
      }
    }
    return RichTextSanitiser.sanitise(html, ids);
  }

  static String requireContent(String body, List<AttachmentRow> claimed) {
    if (body.replaceAll("<[^>]*>", "").isBlank() && claimed.isEmpty() && !body.contains("<img")) {
      throw ApiException.invalidRequest("Write a message or attach a file.");
    }
    return body;
  }

  private static String cleanSubject(String subject) {
    String s = subject == null ? "" : subject.strip().replaceAll("\\p{Cntrl}", " ");
    if (s.isEmpty()) {
      throw ApiException.invalidRequest("Enter a subject.");
    }
    if (s.length() > MAX_SUBJECT) {
      throw ApiException.invalidRequest("The subject can be at most 250 characters.");
    }
    return s;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  /** The acting user, or null for system jobs and API keys. */
  static Long actor(AccessScope scope) {
    return scope.kind() == AccessScope.Kind.CUSTOMER || scope.isSupporter()
        ? scope.principalId().orElse(null)
        : null;
  }
}
