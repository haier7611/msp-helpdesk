package com.ticketsystem.api.ticket.agent.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.throttle.AgentMessageLimiter;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.ticket.SlaPanel;
import com.ticketsystem.api.sla.ticket.SlaPanelService;
import com.ticketsystem.api.ticket.Impact;
import com.ticketsystem.api.ticket.ReplyCcService;
import com.ticketsystem.api.ticket.RequestTypeService;
import com.ticketsystem.api.ticket.RequestTypeService.RequestTypeOption;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.Urgency;
import com.ticketsystem.api.ticket.agent.AgentTicketDetailService;
import com.ticketsystem.api.ticket.agent.AgentTicketQueryService;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.QueuePage;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.QueueQuery;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.QueueSort;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.ResolvedReference;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketSummary;
import com.ticketsystem.api.ticket.agent.InternalNoteService;
import com.ticketsystem.api.ticket.agent.QueueExportService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent ticket API (agents and admins). Every handler passes the request's AccessScope to a
 * service, so a ticket of an organisation the caller is not assigned to answers 404 exactly like a
 * missing one (security test 1).
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentTicketController {

  record CreateTicketRequest(
      Long organisationId,
      Long reporterUserId,
      Long requestTypeId,
      Priority priority,
      Impact impact,
      Urgency urgency,
      String subject,
      String descriptionHtml,
      List<UUID> attachmentIds,
      Long assigneeUserId) {}

  record CreatedResponse(long id, String reference) {}

  /** {@code closeReason} (and {@code closeNote} for OTHER) are required for CLOSED. */
  record StatusRequest(TicketStatus status, String closeReason, String closeNote) {}

  record StatusResponse(TicketStatus status) {}

  record PriorityRequest(Priority priority) {}

  record RequestTypeRequest(Long requestTypeId) {}

  record AssigneeRequest(Long assigneeUserId) {}

  record OrganisationRequest(Long organisationId) {}

  record ReferenceResponse(String reference) {}

  record CommentRequest(
      String bodyHtml, List<UUID> attachmentIds, TicketStatus thenStatus, CcRequest cc) {}

  /** The CC chosen in the composer; absent = the confirmed participants as before. */
  record CcRequest(
      List<String> recipients, Boolean keepOnFutureReplies, List<String> confirmExternal) {}

  record NoteRequest(String bodyHtml, List<UUID> attachmentIds) {}

  record IdResponse(long id) {}

  record CcCheckRequest(String email) {}

  record CcCheckResponse(String result) {}

  record TicketTargetRequest(Long ticketId, String reference) {}

  static final int MAX_SEARCH = 200;

  private final TicketService tickets;
  private final AgentTicketQueryService queries;
  private final InternalNoteService notes;
  private final RequestTypeService requestTypes;
  private final QueueExportService exports;
  private final Clock clock;
  private final RateLimiter rateLimiter;
  private final AgentMessageLimiter messageLimiter;
  private final SlaPanelService slaPanels;
  private final AgentTicketDetailService details;

  public AgentTicketController(
      TicketService tickets,
      AgentTicketQueryService queries,
      InternalNoteService notes,
      RequestTypeService requestTypes,
      QueueExportService exports,
      Clock clock,
      RateLimiter rateLimiter,
      AgentMessageLimiter messageLimiter,
      SlaPanelService slaPanels,
      AgentTicketDetailService details) {
    this.rateLimiter = rateLimiter;
    this.messageLimiter = messageLimiter;
    this.slaPanels = slaPanels;
    this.details = details;
    this.tickets = tickets;
    this.queries = queries;
    this.notes = notes;
    this.requestTypes = requestTypes;
    this.exports = exports;
    this.clock = clock;
  }

  @GetMapping("/request-types")
  List<RequestTypeOption> requestTypes(AccessScope scope) {
    return requestTypes.forAgents(scope);
  }

  @GetMapping("/tickets")
  ResponseEntity<List<TicketSummary>> list(
      AccessScope scope,
      @RequestParam(required = false) TicketStatus status,
      @RequestParam(required = false) Long organisationId,
      @RequestParam(defaultValue = "false") boolean triage,
      @RequestParam(required = false) String assignee,
      @RequestParam(defaultValue = "false") boolean includeClosed,
      @RequestParam(defaultValue = "false") boolean breaching,
      @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String priority,
      @RequestParam(defaultValue = "false") boolean excludeTriage,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page) {
    QueueQuery query =
        query(
            scope,
            status,
            organisationId,
            triage,
            assignee,
            includeClosed,
            breaching,
            before,
            limit,
            q,
            priority,
            excludeTriage,
            sort,
            page);
    QueuePage result = queries.list(scope, query);
    return ResponseEntity.ok()
        .header("X-Total-Count", Long.toString(result.total()))
        .body(result.items());
  }

  /** CSV of the filtered queue (F7), audit-logged; same filters as the list, no paging. */
  @GetMapping("/tickets/export")
  ResponseEntity<byte[]> export(
      AccessScope scope,
      HttpServletRequest request,
      @RequestParam(required = false) TicketStatus status,
      @RequestParam(required = false) Long organisationId,
      @RequestParam(defaultValue = "false") boolean triage,
      @RequestParam(required = false) String assignee,
      @RequestParam(defaultValue = "false") boolean includeClosed,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String priority,
      @RequestParam(defaultValue = "false") boolean excludeTriage,
      @RequestParam(required = false) String sort) {
    QueueQuery query =
        query(
            scope,
            status,
            organisationId,
            triage,
            assignee,
            includeClosed,
            false,
            null,
            QueueExportService.MAX_ROWS,
            q,
            priority,
            excludeTriage,
            sort,
            0);
    QueueExportService.Export export = exports.export(scope, query, request.getRemoteAddr());
    String filename = "tickets-" + LocalDate.now(clock.withZone(ZoneOffset.UTC)) + ".csv";
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString())
            .header("X-Content-Type-Options", "nosniff");
    if (export.truncated()) {
      response = response.header("X-Export-Truncated", "true");
    }
    return response.body(export.csv().getBytes(StandardCharsets.UTF_8));
  }

  private static QueueQuery query(
      AccessScope scope,
      TicketStatus status,
      Long organisationId,
      boolean triage,
      String assignee,
      boolean includeClosed,
      boolean breaching,
      Long before,
      int limit,
      String q,
      String priority,
      boolean excludeTriage,
      String sort,
      int page) {
    Long assigneeId = null;
    boolean unassigned = false;
    if ("none".equals(assignee)) {
      unassigned = true;
    } else if ("me".equals(assignee)) {
      assigneeId = scope.principalId().orElse(null);
    } else if (assignee != null && !assignee.isBlank()) {
      try {
        assigneeId = Long.parseLong(assignee);
      } catch (NumberFormatException e) {
        throw ApiException.invalidRequest("assignee is me, none or a user id.");
      }
    }
    if (q != null && q.length() > MAX_SEARCH) {
      throw ApiException.invalidRequest("Search text can be up to " + MAX_SEARCH + " characters.");
    }
    if (page < 0) {
      throw ApiException.invalidRequest("page starts at 0.");
    }
    return new QueueQuery(
        status,
        organisationId,
        triage,
        assigneeId,
        unassigned,
        includeClosed,
        breaching,
        before,
        limit,
        q,
        parsePriority(priority),
        excludeTriage,
        parseSort(sort),
        page);
  }

  private static Priority parsePriority(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Priority.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidRequest("priority is P1, P2, P3 or P4.");
    }
  }

  private static QueueSort parseSort(String raw) {
    if (raw == null || raw.isBlank()) {
      return QueueSort.NEWEST;
    }
    try {
      return QueueSort.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidRequest(
          "sort is newest, oldest, updated, priority or first_response.");
    }
  }

  @PostMapping("/tickets")
  @ResponseStatus(HttpStatus.CREATED)
  CreatedResponse create(AccessScope scope, @RequestBody CreateTicketRequest r) {
    if (r.organisationId() == null || r.reporterUserId() == null || r.requestTypeId() == null) {
      throw ApiException.invalidRequest("Choose an organisation, a contact and a request type.");
    }
    Created c =
        tickets.create(
            scope,
            new NewTicket(
                r.organisationId(),
                r.reporterUserId(),
                null,
                r.requestTypeId(),
                r.priority(),
                r.impact(),
                r.urgency(),
                TicketSource.AGENT,
                r.subject(),
                r.descriptionHtml(),
                r.attachmentIds(),
                null,
                r.assigneeUserId(),
                null,
                null));
    return new CreatedResponse(c.ticketId(), c.reference().toString());
  }

  @GetMapping("/tickets/{id}")
  TicketDetail detail(AccessScope scope, @PathVariable long id) {
    return details.detail(scope, id);
  }

  /**
   * The live SLA panel alone, for the ticket page's periodic re-sync. 404 for a triage ticket or
   * one the caller cannot see (agent-only, organisation-scoped).
   */
  @GetMapping("/tickets/{id}/sla")
  SlaPanel slaPanel(AccessScope scope, @PathVariable long id) {
    return slaPanels.panel(scope, id).orElseThrow(AccessScope::notFound);
  }

  @GetMapping("/tickets/by-reference/{reference}")
  ResolvedReference byReference(AccessScope scope, @PathVariable String reference) {
    return queries.resolve(scope, reference);
  }

  @PostMapping("/tickets/{id}/status")
  StatusResponse status(AccessScope scope, @PathVariable long id, @RequestBody StatusRequest r) {
    return new StatusResponse(
        tickets.changeStatus(scope, id, r.status(), r.closeReason(), r.closeNote()));
  }

  @PostMapping("/tickets/{id}/priority")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void priority(AccessScope scope, @PathVariable long id, @RequestBody PriorityRequest r) {
    tickets.changePriority(scope, id, r.priority());
  }

  @PostMapping("/tickets/{id}/request-type")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void requestType(AccessScope scope, @PathVariable long id, @RequestBody RequestTypeRequest r) {
    if (r.requestTypeId() == null) {
      throw ApiException.invalidRequest("Choose a request type.");
    }
    tickets.changeRequestType(scope, id, r.requestTypeId());
  }

  @PostMapping("/tickets/{id}/assignee")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void assignee(AccessScope scope, @PathVariable long id, @RequestBody AssigneeRequest r) {
    tickets.assign(scope, id, r.assigneeUserId());
  }

  @PostMapping("/tickets/{id}/organisation")
  ReferenceResponse organisation(
      AccessScope scope, @PathVariable long id, @RequestBody OrganisationRequest r) {
    if (r.organisationId() == null) {
      throw ApiException.invalidRequest("Choose an organisation.");
    }
    return new ReferenceResponse(
        tickets.changeOrganisation(scope, id, r.organisationId()).toString());
  }

  @PostMapping("/tickets/{id}/public-replies")
  @ResponseStatus(HttpStatus.CREATED)
  IdResponse publicReply(AccessScope scope, @PathVariable long id, @RequestBody CommentRequest r) {
    limitMessages(scope);
    ReplyCcService.ReplyCc cc =
        r.cc() == null
            ? null
            : new ReplyCcService.ReplyCc(
                r.cc().recipients(), r.cc().keepOnFutureReplies(), r.cc().confirmExternal());
    return new IdResponse(
        tickets.addPublicReply(scope, id, r.bodyHtml(), r.attachmentIds(), r.thenStatus(), cc));
  }

  /**
   * The CC editor checks a typed address before the reply is sent. POST so the address stays out of
   * access logs; the answer is a reason code only.
   */
  @PostMapping("/tickets/{id}/cc-check")
  CcCheckResponse ccCheck(AccessScope scope, @PathVariable long id, @RequestBody CcCheckRequest r) {
    limitPerSupporter(RateLimiter.Scope.CC_CHECK_AGENT, scope);
    ReplyCcService.CcCheck result = tickets.checkCc(scope, id, r == null ? null : r.email());
    return new CcCheckResponse(result.name().toLowerCase(Locale.ROOT));
  }

  @PostMapping("/tickets/{id}/internal-notes")
  @ResponseStatus(HttpStatus.CREATED)
  IdResponse internalNote(AccessScope scope, @PathVariable long id, @RequestBody NoteRequest r) {
    limitMessages(scope);
    return new IdResponse(notes.addNote(scope, id, r.bodyHtml(), r.attachmentIds()));
  }

  @PostMapping("/tickets/{id}/duplicate-of")
  IdResponse duplicateOf(
      AccessScope scope, @PathVariable long id, @RequestBody TicketTargetRequest r) {
    return new IdResponse(tickets.markDuplicate(scope, id, target(scope, r)));
  }

  @PostMapping("/tickets/{id}/links")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void link(AccessScope scope, @PathVariable long id, @RequestBody TicketTargetRequest r) {
    tickets.linkRelated(scope, id, target(scope, r));
  }

  @PostMapping("/tickets/{id}/follow-ups")
  @ResponseStatus(HttpStatus.CREATED)
  CreatedResponse followUp(AccessScope scope, @PathVariable long id) {
    Created c = tickets.createFollowUp(scope, id);
    return new CreatedResponse(c.ticketId(), c.reference().toString());
  }

  /** The other ticket by id, or by any of its references (aliases included). */
  private long target(AccessScope scope, TicketTargetRequest r) {
    if (r == null || (r.ticketId() == null && (r.reference() == null || r.reference().isBlank()))) {
      throw ApiException.invalidRequest("Choose the other ticket.");
    }
    return r.ticketId() != null ? r.ticketId() : queries.resolve(scope, r.reference()).ticketId();
  }

  /**
   * per-supporter cap on the agent UI's CC checks; over the cap answers 429 too_many_requests with
   * Retry-After (ApiExceptionHandler).
   */
  private void limitPerSupporter(RateLimiter.Scope limit, AccessScope scope) {
    rateLimiter.consume(limit, scope.principalId().map(String::valueOf).orElse("none"));
  }

  /**
   * Public replies and internal notes share one per-supporter cap (short and long window,
   * app.rate-limits.agent-messages); over it 429 too_many_requests with Retry-After.
   */
  private void limitMessages(AccessScope scope) {
    messageLimiter.consume(scope.principalId().orElseThrow(AccessScope::notFound));
  }
}
