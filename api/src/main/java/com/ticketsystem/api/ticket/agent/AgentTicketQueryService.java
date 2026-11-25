package com.ticketsystem.api.ticket.agent;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository;
import com.ticketsystem.api.sla.clientdata.TicketSlaRepository.SlaFields;
import com.ticketsystem.api.sla.ticket.QueueTimerService;
import com.ticketsystem.api.sla.ticket.QueueTimerService.FirstResponseTimer;
import com.ticketsystem.api.sla.ticket.QueueTimerService.TimerInput;
import com.ticketsystem.api.ticket.CloseReason;
import com.ticketsystem.api.ticket.TicketTransitions;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.AssignmentEntryView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.AttachmentView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.HistoryView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.InternalNoteView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.LinkView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.ParticipantView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.Person;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.PublicCommentView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.QueuePage;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.QueueQuery;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.QueueSort;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.RequestTypeView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.ResolvedReference;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.SlaTargetView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.SlaView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.SupporterOption;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.SystemEntryView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketRefView;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketSummary;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.AttachmentRow;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.Visibility;
import com.ticketsystem.api.ticket.clientdata.InternalNoteRepository;
import com.ticketsystem.api.ticket.clientdata.InternalNoteRepository.InternalNoteRow;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository;
import com.ticketsystem.api.ticket.clientdata.PublicCommentRepository.PublicCommentRow;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository;
import com.ticketsystem.api.ticket.clientdata.SystemEntryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryRow;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import com.ticketsystem.api.ticket.clientdata.TicketLinkRepository;
import com.ticketsystem.api.ticket.clientdata.TicketLinkRepository.LinkRow;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.CloseInfo;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.QueueFilter;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the agent read model (supporters only; customers and API keys always get 404). */
@Service
public class AgentTicketQueryService {

  public static final int MAX_PAGE = 100;

  private final TicketRepository tickets;
  private final PublicCommentRepository publicComments;
  private final InternalNoteRepository notes;
  private final SystemEntryRepository systemEntries;
  private final TicketHistoryRepository history;
  private final TicketLinkRepository links;
  private final TicketParticipantRepository participants;
  private final RequestTypeRepository requestTypes;
  private final TicketPeopleRepository people;
  private final ReferenceService references;
  private final AttachmentService attachments;
  private final AttachmentRepository attachmentRows;
  private final TicketSlaRepository slaTickets;
  private final OrganisationRepository organisations;
  private final QueueTimerService timers;

  public AgentTicketQueryService(
      TicketRepository tickets,
      PublicCommentRepository publicComments,
      InternalNoteRepository notes,
      SystemEntryRepository systemEntries,
      TicketHistoryRepository history,
      TicketLinkRepository links,
      TicketParticipantRepository participants,
      RequestTypeRepository requestTypes,
      TicketPeopleRepository people,
      ReferenceService references,
      AttachmentService attachments,
      AttachmentRepository attachmentRows,
      OrganisationRepository organisations,
      TicketSlaRepository slaTickets,
      QueueTimerService timers) {
    this.slaTickets = slaTickets;
    this.timers = timers;
    this.tickets = tickets;
    this.publicComments = publicComments;
    this.notes = notes;
    this.systemEntries = systemEntries;
    this.history = history;
    this.links = links;
    this.participants = participants;
    this.requestTypes = requestTypes;
    this.people = people;
    this.references = references;
    this.attachments = attachments;
    this.attachmentRows = attachmentRows;
    this.organisations = organisations;
  }

  /** One page of the queue and the total matching the filter. */
  @Transactional(readOnly = true)
  public QueuePage list(AccessScope scope, QueueQuery query) {
    requireSupporter(scope);
    int limit = Math.max(1, Math.min(query.limit(), MAX_PAGE));
    int page = Math.max(0, query.page());
    QueueFilter f = filter(scope, query, limit, Math.multiplyExact(page, limit), query.beforeId());
    return new QueuePage(summaries(scope, tickets.list(scope, f), true), tickets.count(scope, f));
  }

  /** The export rows: the whole filter, newest-first or as sorted, at most {@code max} rows. */
  @Transactional(readOnly = true)
  public List<TicketSummary> exportRows(AccessScope scope, QueueQuery query, int max) {
    requireSupporter(scope);
    return summaries(scope, tickets.list(scope, filter(scope, query, max, 0, null)), false);
  }

  private QueueFilter filter(
      AccessScope scope, QueueQuery q, int limit, int offset, Long beforeId) {
    String search = q.search() == null || q.search().isBlank() ? null : q.search().strip();
    Long searchTicketId = null;
    if (search != null) {
      searchTicketId =
          references.resolve(scope, search).map(ReferenceService.Resolved::ticketId).orElse(null);
    }
    QueueSort sort = q.sort() == null ? QueueSort.NEWEST : q.sort();
    return new QueueFilter(
        q.status(),
        q.organisationId(),
        q.triageOnly(),
        q.assigneeUserId(),
        q.unassignedOnly(),
        q.includeClosed(),
        q.breachingOnly(),
        beforeId,
        limit,
        search,
        searchTicketId,
        q.priority(),
        q.excludeTriage(),
        TicketRepository.QueueSort.valueOf(sort.name()),
        offset);
  }

  /**
   * The queue rows. {@code withTimers}: work out the first-response timers (the screen); the CSV
   * export leaves them out.
   */
  private List<TicketSummary> summaries(
      AccessScope scope, List<TicketRow> rows, boolean withTimers) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<Long, TicketReference> refs =
        references.displayReferences(scope, rows.stream().map(TicketRow::id).toList());
    Set<Long> userIds = new HashSet<>();
    rows.forEach(
        t -> {
          if (t.assigneeUserId() != null) {
            userIds.add(t.assigneeUserId());
          }
          if (t.reporterUserId() != null) {
            userIds.add(t.reporterUserId());
          }
        });
    Map<Long, String> names = people.names(scope, userIds);
    Map<Long, String> orgNames = organisationNames(scope);
    Map<Long, String> typeNames = new HashMap<>();
    requestTypes.list(scope).forEach(r -> typeNames.put(r.id(), r.name()));
    Map<Long, SlaFields> sla =
        slaTickets.fieldsFor(scope, rows.stream().map(TicketRow::id).toList());
    Map<Long, CloseInfo> closes =
        tickets.closeReasons(scope, rows.stream().map(TicketRow::id).toList());
    Map<Long, FirstResponseTimer> timerOf =
        withTimers
            ? timers.firstResponse(
                scope,
                rows.stream()
                    .map(
                        t ->
                            new TimerInput(
                                t.id(),
                                t.organisationId(),
                                t.duplicateOfId() != null,
                                sla.get(t.id())))
                    .toList())
            : Map.of();
    return rows.stream()
        .map(
            t ->
                new TicketSummary(
                    t.id(),
                    display(refs.get(t.id())),
                    t.organisationId(),
                    t.subject(),
                    t.status(),
                    t.priority(),
                    t.source(),
                    t.assigneeUserId(),
                    t.createdAt(),
                    t.updatedAt(),
                    t.organisationId() == null ? null : orgNames.get(t.organisationId()),
                    t.assigneeUserId() == null ? null : names.get(t.assigneeUserId()),
                    t.reporterUserId() == null ? null : names.get(t.reporterUserId()),
                    t.reporterEmail(),
                    typeNames.get(t.requestTypeId()),
                    slaView(sla.get(t.id())),
                    closes.containsKey(t.id()) ? closes.get(t.id()).reason() : null,
                    closes.containsKey(t.id()) ? closes.get(t.id()).note() : null,
                    timerOf.get(t.id())))
        .toList();
  }

  private Map<Long, String> organisationNames(AccessScope scope) {
    Map<Long, String> out = new HashMap<>();
    organisations.list(scope).forEach(o -> out.put(o.id(), o.name()));
    return out;
  }

  @Transactional(readOnly = true)
  public TicketDetail detail(AccessScope scope, long ticketId) {
    requireSupporter(scope);
    TicketRow t = tickets.find(scope, ticketId).orElseThrow(AccessScope::notFound);
    List<PublicCommentRow> comments = publicComments.listForTicket(scope, t.id());
    List<InternalNoteRow> noteRows = notes.listForTicket(scope, t.id());
    List<AttachmentRow> files = attachments.listForTicket(scope, t.id(), true);
    // Email comments whose files are still being processed.
    AttachmentRepository.EmailFilesPending processing =
        attachmentRows.emailFilesPending(scope, t.id(), true);
    List<LinkRow> linkRows = links.listForTicket(scope, t.id());
    List<TicketParticipantRepository.ParticipantRow> participantRows =
        participants.listForTicket(scope, t.id());

    List<Long> related = new ArrayList<>();
    related.add(t.id());
    linkRows.forEach(l -> related.add(l.otherTicketId()));
    if (t.duplicateOfId() != null) {
      related.add(t.duplicateOfId());
    }
    if (t.followUpOfId() != null) {
      related.add(t.followUpOfId());
    }
    Map<Long, TicketReference> refs = references.displayReferences(scope, related);

    Set<Long> userIds = new HashSet<>();
    Stream.of(t.reporterUserId(), t.assigneeUserId())
        .filter(java.util.Objects::nonNull)
        .forEach(userIds::add);
    comments.stream()
        .map(PublicCommentRow::authorUserId)
        .filter(java.util.Objects::nonNull)
        .forEach(userIds::add);
    noteRows.stream()
        .map(InternalNoteRow::authorUserId)
        .filter(java.util.Objects::nonNull)
        .forEach(userIds::add);
    participantRows.stream()
        .map(TicketParticipantRepository.ParticipantRow::userId)
        .filter(java.util.Objects::nonNull)
        .forEach(userIds::add);
    List<HistoryRow> historyRows = history.listForTicket(scope, t.id());
    List<HistoryRow> assignmentRows =
        historyRows.stream().filter(h -> h.type() == HistoryType.ASSIGNEE).toList();
    List<Long> historyTickets = new ArrayList<>();
    for (HistoryRow h : historyRows) {
      Stream.of(h.actorUserId(), historyUser(h, true), historyUser(h, false))
          .filter(java.util.Objects::nonNull)
          .forEach(userIds::add);
      if (TICKET_ROWS.contains(h.type())) {
        Long other = userId(h.newValue());
        if (other != null && !refs.containsKey(other)) {
          historyTickets.add(other);
        }
      }
    }
    Map<Long, String> names = people.names(scope, userIds);
    Map<Long, TicketReference> historyRefs = refs;
    if (!historyTickets.isEmpty()) {
      historyRefs = new HashMap<>(refs);
      historyRefs.putAll(references.displayReferences(scope, historyTickets));
    }
    TicketTimeline.Names timelineNames = timelineNames(scope, names, historyRefs);

    String primary = display(refs.get(t.id()));
    List<String> aliases =
        references.referencesOf(scope, t.id()).stream()
            .map(TicketReference::toString)
            .filter(r -> !r.equals(primary))
            .toList();
    var type = requestTypes.find(scope, t.requestTypeId()).orElseThrow();
    CloseInfo close = tickets.closeReasons(scope, List.of(t.id())).get(t.id());

    return new TicketDetail(
        t.id(),
        primary,
        aliases,
        t.organisationId(),
        t.subject(),
        t.status(),
        TicketTransitions.label(t.status()),
        Arrays.stream(TicketStatus.values())
            .filter(s -> s != t.status() && TicketTransitions.agentMaySet(t.status(), s))
            .toList(),
        t.priority(),
        t.impact(),
        t.urgency(),
        t.source(),
        new RequestTypeView(type.id(), type.name(), type.defaultPriority()),
        person(t.reporterUserId(), t.reporterEmail(), names),
        t.assigneeUserId() == null ? null : person(t.assigneeUserId(), null, names),
        refView(t.duplicateOfId(), refs),
        refView(t.followUpOfId(), refs),
        linkRows.stream()
            .filter(l -> refs.containsKey(l.otherTicketId()))
            .map(
                l ->
                    new LinkView(
                        l.otherTicketId(), display(refs.get(l.otherTicketId())), l.kind().name()))
            .toList(),
        participantRows.stream()
            .map(
                p ->
                    new ParticipantView(
                        p.userId(),
                        p.email(),
                        p.confirmed(),
                        p.userId() == null ? null : names.get(p.userId())))
            .toList(),
        comments.stream()
            .map(
                c ->
                    new PublicCommentView(
                        c.id(),
                        c.authorKind().name(),
                        person(c.authorUserId(), c.authorEmail(), names),
                        c.bodyHtml(),
                        c.receivedByEmail(),
                        c.createdAt(),
                        filesOf(files, Visibility.PUBLIC, c.id()),
                        processing.publicCommentIds().contains(c.id())))
            .toList(),
        noteRows.stream()
            .map(
                n ->
                    new InternalNoteView(
                        n.id(),
                        n.authorKind().name(),
                        person(n.authorUserId(), null, names),
                        n.bodyHtml(),
                        n.receivedByEmail(),
                        n.createdAt(),
                        filesOf(files, Visibility.INTERNAL, n.id()),
                        processing.internalNoteIds().contains(n.id())))
            .toList(),
        systemEntries.listForTicket(scope, t.id()).stream()
            .map(e -> new SystemEntryView(e.id(), e.kind(), e.text(), e.createdAt()))
            .toList(),
        assignmentEntries(assignmentRows, timelineNames),
        TicketTimeline.build(historyRows, timelineNames),
        historyRows.stream()
            .map(
                h ->
                    new HistoryView(
                        h.type().name(),
                        h.actorUserId(),
                        h.oldValue(),
                        h.newValue(),
                        h.createdAt()))
            .toList(),
        t.createdAt(),
        t.updatedAt(),
        t.organisationId() == null ? null : organisationNames(scope).get(t.organisationId()),
        slaView(slaTickets.fieldsFor(scope, List.of(t.id())).get(t.id())),
        close == null ? null : close.reason(),
        close == null ? null : closeLabel(close.reason()),
        close == null ? null : close.note(),
        null); // SLA panel: attached by AgentTicketDetailService after this commits
  }

  private static String closeLabel(String reason) {
    CloseReason r = CloseReason.parseOrNull(reason);
    return r == null ? null : r.label();
  }

  /** History rows whose {@code newValue} is another ticket's id. */
  private static final Set<HistoryType> TICKET_ROWS =
      java.util.EnumSet.of(
          HistoryType.LINKED,
          HistoryType.LINK_REMOVED,
          HistoryType.DUPLICATE_OF,
          HistoryType.DUPLICATE_ADDED,
          HistoryType.FOLLOW_UP_OF,
          HistoryType.FOLLOW_UP_ADDED);

  /**
   * The ticket's assignee changes as timeline lines, oldest first (kept for API compatibility;
   * {@code timelineEntries} carries the same lines among every other change). Names are today's
   * (resolved through the scope); one that cannot be resolved reads "a former agent".
   */
  private static List<AssignmentEntryView> assignmentEntries(
      List<HistoryRow> rows, TicketTimeline.Names names) {
    return rows.stream()
        .map(
            h ->
                new AssignmentEntryView(
                    h.id(),
                    userId(h.oldValue()),
                    userId(h.newValue()),
                    h.actorUserId(),
                    h.reason(),
                    TicketTimeline.assignment(h, names),
                    h.createdAt()))
        .toList();
  }

  /**
   * The lookups behind the timeline wording, all through the viewer's scope: user names
   * (supporters, and customers of organisations the scope sees), organisations the scope sees,
   * request types (archived ones included and marked), and references of tickets the scope sees.
   * Anything else resolves to null and reads as a neutral text.
   */
  private TicketTimeline.Names timelineNames(
      AccessScope scope, Map<Long, String> names, Map<Long, TicketReference> refs) {
    return new TicketTimeline.Names() {
      private Map<Long, String> orgNames;
      private final Map<Long, String> typeNames = new HashMap<>();

      @Override
      public String user(Long userId) {
        return userId == null ? null : names.get(userId);
      }

      @Override
      public String organisation(Long organisationId) {
        if (organisationId == null) {
          return null;
        }
        if (orgNames == null) {
          orgNames = organisationNames(scope);
        }
        return orgNames.get(organisationId);
      }

      @Override
      public String requestType(Long requestTypeId) {
        if (requestTypeId == null) {
          return null;
        }
        return typeNames.computeIfAbsent(
            requestTypeId,
            id ->
                requestTypes
                    .find(scope, id)
                    .map(r -> r.archived() ? r.name() + " (archived)" : r.name())
                    .orElse(null));
      }

      @Override
      public String reference(Long ticketId) {
        return ticketId == null ? null : display(refs.get(ticketId));
      }
    };
  }

  /**
   * A user a history row refers to besides its actor: the old ({@code old}) or new assignee or
   * reporter, or the participant.
   */
  private static Long historyUser(HistoryRow h, boolean old) {
    return switch (h.type()) {
      case ASSIGNEE, REPORTER -> userId(old ? h.oldValue() : h.newValue());
      case PARTICIPANT_ADDED, PARTICIPANT_REMOVED, SLA_AUTOMATION -> old ? null : h.reasonRefId();
      default -> null;
    };
  }

  /** A user id stored as text in a history row, or null. */
  private static Long userId(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Internal SLA values; only this agent read model carries them. */
  static SlaView slaView(SlaFields f) {
    if (f == null) {
      return null;
    }
    return new SlaView(
        f.clock().name(),
        new SlaTargetView(
            f.firstResponseTargetMinutes(),
            f.firstResponseUsedMinutes(),
            f.firstResponseDueAt(),
            f.firstResponseState().name()),
        new SlaTargetView(
            f.resolutionTargetMinutes(),
            f.resolutionUsedMinutes(),
            f.resolutionDueAt(),
            f.resolutionState().name()),
        f.firstRespondedAt(),
        f.resolvedAt(),
        f.pausedMinutes(),
        f.warning().name(),
        f.calculatedAt());
  }

  /** Active supporters for the assignee picker; 404 for an organisation not in scope. */
  @Transactional(readOnly = true)
  public List<SupporterOption> supporters(AccessScope scope, Long organisationId) {
    requireSupporter(scope);
    return people.supporters(scope, organisationId).stream()
        .map(
            r ->
                new SupporterOption(
                    r.id(), r.name(), r.email(), r.outOfOffice(), r.outOfOfficeUntil()))
        .toList();
  }

  /** Typed or quoted text such as {@code nwd-1} to its ticket, aliases included. */
  @Transactional(readOnly = true)
  public ResolvedReference resolve(AccessScope scope, String text) {
    requireSupporter(scope);
    var r = references.resolve(scope, text).orElseThrow(AccessScope::notFound);
    return new ResolvedReference(
        r.ticketId(), r.primary().toString(), r.matched().toString(), r.isAlias());
  }

  private static List<AttachmentView> filesOf(
      List<AttachmentRow> files, Visibility visibility, long commentId) {
    return files.stream()
        .filter(
            a ->
                a.visibility() == visibility && a.commentId() != null && a.commentId() == commentId)
        .map(
            a ->
                new AttachmentView(
                    a.publicId(),
                    a.filename(),
                    a.sizeBytes(),
                    a.contentType(),
                    a.image(),
                    a.inline(),
                    visibility == Visibility.INTERNAL))
        .toList();
  }

  private static Person person(Long userId, String email, Map<Long, String> names) {
    if (userId == null && email == null) {
      return null;
    }
    return new Person(userId, userId == null ? null : names.get(userId), email);
  }

  private static TicketRefView refView(Long id, Map<Long, TicketReference> refs) {
    if (id == null || !refs.containsKey(id)) {
      return null;
    }
    return new TicketRefView(id, display(refs.get(id)));
  }

  private static String display(TicketReference r) {
    return r == null ? null : r.toString();
  }

  private static void requireSupporter(AccessScope scope) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
  }
}
