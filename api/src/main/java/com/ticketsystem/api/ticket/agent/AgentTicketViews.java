package com.ticketsystem.api.ticket.agent;

import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.ticket.QueueTimerService.FirstResponseTimer;
import com.ticketsystem.api.sla.ticket.SlaPanel;
import com.ticketsystem.api.ticket.Impact;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.Urgency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The agent read model (supporters only). It is the only model that carries internal notes,
 * internal attachments, system entries and history, each in its own typed list - there is no shared
 * "comment" type with a visibility flag. Only {@code ..ticket.agent..} code may use these types
 * (ArchUnit); portal and public read models are separate classes.
 */
public final class AgentTicketViews {

  private AgentTicketViews() {}

  public record Person(Long userId, String name, String email) {}

  /** A supporter in the assignee picker. */
  public record SupporterOption(
      long id,
      String name,
      String email,
      boolean outOfOffice,
      java.time.LocalDate outOfOfficeUntil) {}

  public record RequestTypeView(long id, String name, Priority defaultPriority) {}

  public record TicketRefView(long ticketId, String reference) {}

  public record LinkView(long ticketId, String reference, String kind) {}

  /**
   * {@code name}: a contact's current name (shown in the composer's CC line), null for an address.
   */
  public record ParticipantView(Long userId, String email, boolean confirmed, String name) {}

  /** An attachment as the agent sees it; {@code internal} marks a note's file. */
  public record AttachmentView(
      UUID id,
      String filename,
      long size,
      String contentType,
      boolean image,
      boolean inline,
      boolean internal) {}

  /** A supporter's public reply or a client reply. */
  /**
   * A public comment. {@code attachmentsProcessing}: an email's files are still being processed;
   * the UI shows "Processing attachments..." and inline images read "[image]".
   */
  public record PublicCommentView(
      long id,
      String authorKind,
      Person author,
      String bodyHtml,
      boolean receivedByEmail,
      Instant createdAt,
      List<AttachmentView> attachments,
      boolean attachmentsProcessing) {}

  /** An internal note (yellow, "Internal" label in the UI). */
  public record InternalNoteView(
      long id,
      String authorKind,
      Person author,
      String bodyHtml,
      boolean receivedByEmail,
      Instant createdAt,
      List<AttachmentView> attachments,
      boolean attachmentsProcessing) {}

  public record SystemEntryView(long id, String kind, String text, Instant createdAt) {}

  public record HistoryView(
      String type, Long actorUserId, String oldValue, String newValue, Instant createdAt) {}

  /**
   * An assignee change as a one-line timeline entry (agent-only, never a comment). {@code text} is
   * the ready wording ("Reassigned from Sam to Alex by Jo"); {@code reason} is the stored {@code
   * AssignmentReason} name or null for a plain change.
   */
  public record AssignmentEntryView(
      long id,
      Long fromUserId,
      Long toUserId,
      Long actorUserId,
      String reason,
      String text,
      Instant createdAt) {}

  /**
   * One ticket change as a one-line timeline entry (agent-only, never a comment): every {@code
   * ticket_event} row - status (manual and automatic), priority, request type, assignment,
   * organisation, duplicates, links, follow-ups, participants, reporter. {@code type} is the stored
   * history type, {@code actorKind} AGENT, CLIENT, SYSTEM or API_KEY, {@code text} the ready
   * wording with names resolved for the viewer ("Priority changed from P3 Medium to P2 High by Jo
   * Example").
   */
  public record TimelineEntryView(
      long id, String type, String actorKind, Long actorUserId, String text, Instant createdAt) {}

  /**
   * SLA values of a ticket (internal only - agent and admin read models, never a customer
   * response). Due times are null while paused or stopped; null {@code sla} = no SLA yet (triage,
   * or not calculated yet).
   */
  public record SlaView(
      String clock,
      SlaTargetView firstResponse,
      SlaTargetView resolution,
      Instant firstRespondedAt,
      Instant resolvedAt,
      Long pausedMinutes,
      String warning,
      Instant calculatedAt) {}

  /**
   * One target: minutes allowed and used, due time, state (RUNNING, PAUSED, STOPPED, MET,
   * BREACHED).
   */
  public record SlaTargetView(Long targetMinutes, Long usedMinutes, Instant dueAt, String state) {}

  /** Queue filters; null fields do not filter. */
  public record QueueQuery(
      TicketStatus status,
      Long organisationId,
      boolean triageOnly,
      Long assigneeUserId,
      boolean unassignedOnly,
      boolean includeClosed,
      boolean breachingOnly,
      Long beforeId,
      int limit,
      String search,
      Priority priority,
      boolean excludeTriage,
      QueueSort sort,
      int page) {

    /** The default filter: no search, priority or sort; newest first, first page. */
    public QueueQuery(
        TicketStatus status,
        Long organisationId,
        boolean triageOnly,
        Long assigneeUserId,
        boolean unassignedOnly,
        boolean includeClosed,
        boolean breachingOnly,
        Long beforeId,
        int limit) {
      this(
          status,
          organisationId,
          triageOnly,
          assigneeUserId,
          unassignedOnly,
          includeClosed,
          breachingOnly,
          beforeId,
          limit,
          null,
          null,
          false,
          QueueSort.NEWEST,
          0);
    }
  }

  /** Queue sort orders; request values are the lower-case names. */
  public enum QueueSort {
    NEWEST,
    OLDEST,
    UPDATED,
    PRIORITY,
    /** Overdue unanswered first, then the soonest first-response due time. */
    FIRST_RESPONSE
  }

  /** One page of the queue plus the total matching (for the X-Total-Count header). */
  public record QueuePage(List<TicketSummary> items, long total) {}

  /**
   * A row in the queue. {@code firstResponseTimer}: the "First response" column - the business time
   * left now, the time over or the final state; null when the ticket has no SLA yet and always null
   * in CSV export rows.
   */
  public record TicketSummary(
      long id,
      String reference,
      Long organisationId,
      String subject,
      TicketStatus status,
      Priority priority,
      TicketSource source,
      Long assigneeUserId,
      Instant createdAt,
      Instant updatedAt,
      String organisationName,
      String assigneeName,
      String reporterName,
      String reporterEmail,
      String requestTypeName,
      SlaView sla,
      String closeReason,
      String closeNote,
      FirstResponseTimer firstResponseTimer) {}

  /** The ticket page. */
  public record TicketDetail(
      long id,
      String reference,
      List<String> aliases,
      Long organisationId,
      String subject,
      TicketStatus status,
      String statusLabel,
      List<TicketStatus> allowedStatuses,
      Priority priority,
      Impact impact,
      Urgency urgency,
      TicketSource source,
      RequestTypeView requestType,
      Person reporter,
      Person assignee,
      TicketRefView duplicateOf,
      TicketRefView followUpOf,
      List<LinkView> links,
      List<ParticipantView> participants,
      List<PublicCommentView> publicComments,
      List<InternalNoteView> internalNotes,
      List<SystemEntryView> systemEntries,
      List<AssignmentEntryView> assignmentEntries,
      List<TimelineEntryView> timelineEntries,
      List<HistoryView> history,
      Instant createdAt,
      Instant updatedAt,
      String organisationName,
      SlaView sla,
      String closeReason,
      String closeReasonLabel,
      String closeNote,
      /** Live SLA panel (live, agent-only, triage too); null if it failed. */
      SlaPanel slaPanel) {

    /** This detail with the SLA panel attached (or cleared, for null). */
    public TicketDetail withSlaPanel(SlaPanel panel) {
      return new TicketDetail(
          id,
          reference,
          aliases,
          organisationId,
          subject,
          status,
          statusLabel,
          allowedStatuses,
          priority,
          impact,
          urgency,
          source,
          requestType,
          reporter,
          assignee,
          duplicateOf,
          followUpOf,
          links,
          participants,
          publicComments,
          internalNotes,
          systemEntries,
          assignmentEntries,
          timelineEntries,
          history,
          createdAt,
          updatedAt,
          organisationName,
          sla,
          closeReason,
          closeReasonLabel,
          closeNote,
          panel);
    }
  }

  /** A resolved reference (alias aware). */
  public record ResolvedReference(long ticketId, String reference, String matched, boolean alias) {}
}
