package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ticket_event}: the ticket's history, shown to supporters only (every row is a line in the
 * agent timeline). Ids and codes are stored, never names; names are resolved when the ticket is
 * read.
 */
@Repository
public class TicketHistoryRepository {

  /** What changed. Stored by name; append new values, never rename. */
  public enum HistoryType {
    CREATED,
    STATUS,
    PRIORITY,
    REQUEST_TYPE,
    ASSIGNEE,
    ORGANISATION,
    LINKED,
    DUPLICATE_OF,
    DUPLICATE_ADDED,
    FOLLOW_UP_OF,
    FOLLOW_UP_ADDED,
    /** Older rows only: the number of participants removed by a move ({@code "2 removed"}). */
    PARTICIPANTS_RECHECKED,
    /** Due times recalculated after a priority change (old and new kept). */
    SLA_DUE_CHANGED,
    /** The Pending-on-client reminder was emailed to the client. */
    CLIENT_REMINDER_SENT,
    /**
     * A participant (CC) was added. {@code reasonRefId} = their user id, or {@code newValue} = the
     * address of an email-only participant; {@code reason} a {@link ParticipantReason}.
     */
    PARTICIPANT_ADDED,
    /** A participant was removed (same columns as {@link #PARTICIPANT_ADDED}). */
    PARTICIPANT_REMOVED,
    /**
     * The reporter changed ({@code oldValue}/{@code newValue} = user ids or null; {@code reason} a
     * {@link ReporterReason}).
     */
    REPORTER,
    /**
     * A link was removed ({@code newValue} = the other ticket id, {@code reason} the {@code
     * LinkKind}).
     */
    LINK_REMOVED,
    /**
     * An automatic client email was not sent because the organisation turned it off ({@code
     * newValue} = the {@code ClientEmail} name, {@code reasonRefId} = the organisation id, actor
     * SYSTEM). Agent-only, like every history row.
     */
    CLIENT_EMAIL_SKIPPED,
    /**
     * One action of an SLA automation rule ({@code note} = the rule's name at the time, {@code
     * oldValue} = the trigger, {@code reason} an {@link AutomationOutcome}, {@code newValue} a
     * count or priority, {@code reasonRefId} the agent concerned). A priority raise or an
     * assignment by a rule is the normal PRIORITY / ASSIGNEE row with {@code reason}
     * SLA_AUTOMATION.
     */
    SLA_AUTOMATION
  }

  /** What one SLA automation action did. Stored by name; append, never rename. */
  public enum AutomationOutcome {
    /** The chosen agents were notified ({@code newValue} = how many could be told). */
    NOTIFIED_AGENTS,
    /** Every agent of the ticket's organisation was notified ({@code newValue} = how many). */
    NOTIFIED_ORGANISATION,
    /** Nobody could be notified (no chosen agent can work the organisation, or none active). */
    NOBODY_NOTIFIED,
    /** Not assigned: the ticket already has an assignee ({@code reasonRefId} = chosen agent). */
    ASSIGN_SKIPPED_ASSIGNED,
    /** Not assigned: the agent is deactivated or cannot work the organisation. */
    ASSIGN_SKIPPED_NOT_ALLOWED,
    /** Priority not raised: the ticket is P1 already. */
    PRIORITY_AT_TOP,
    /** An internal note with the rule's text was added. */
    NOTE_ADDED,
    /** An assignment or priority action skipped because the ticket is Closed (read-only). */
    SKIPPED_CLOSED
  }

  /**
   * Why an {@link HistoryType#ASSIGNEE} change happened when it was not a plain assignment by the
   * actor. Stored by name; append new values, never rename.
   */
  public enum AssignmentReason {
    /** Auto-assignment to the request type's default assignee ({@code reasonRefId} = type id). */
    DEFAULT_REQUEST_TYPE,
    /** Auto-assignment to the organisation's default assignee ({@code reasonRefId} = org id). */
    DEFAULT_ORGANISATION,
    /** The deactivation wizard moved the leaving supporter's open tickets. */
    DEACTIVATED,
    /**
     * Cleared because the assignee cannot work the ticket's new organisation ({@code reasonRefId} =
     * that organisation).
     */
    ORGANISATION_MOVED
  }

  /**
   * Who made a change. Derived from the scope unless the writer says otherwise (a client's email
   * reply is processed with the poller's SYSTEM scope but is the client's doing). Stored by name;
   * append new values, never rename. Older rows have none.
   */
  public enum ActorKind {
    AGENT,
    CLIENT,
    SYSTEM,
    API_KEY;

    static ActorKind of(AccessScope scope) {
      return switch (scope.kind()) {
        case SUPPORTER -> AGENT;
        case CUSTOMER -> CLIENT;
        case API_KEY -> API_KEY;
        case SYSTEM -> SYSTEM;
      };
    }
  }

  /** Why a participant was added or removed. Stored by name; append, never rename. */
  public enum ParticipantReason {
    /** To/Cc of a verified (DMARC pass) client email, confirmed. */
    EMAIL_CC,
    /**
     * To/Cc of an unverified client email, waiting for an agent to confirm (no mail until then).
     */
    EMAIL_CC_UNCONFIRMED,
    /** The reporter of a ticket marked as a duplicate of this one. */
    DUPLICATE_MERGED,
    /** Not a customer of the new organisation, removed when the ticket moved. */
    ORGANISATION_MOVED,
    /** An agent added them as CC in the reply composer (same organisation). */
    AGENT_ADDED,
    /** As above for an address outside the organisation, the agent confirmed it. */
    AGENT_ADDED_EXTERNAL,
    /** An agent confirmed an unconfirmed participant (from an email) in the composer. */
    AGENT_CONFIRMED_EXTERNAL,
    /** An agent removed them from CC in the reply composer. */
    AGENT_REMOVED,
    /**
     * CC'd on one reply only (PARTICIPANT_ADDED) or left out of one reply only
     * (PARTICIPANT_REMOVED); the ticket's participants do not change.
     */
    REPLY_ONLY,
    /** CC'd on one reply only, an outside address the agent confirmed. */
    REPLY_ONLY_EXTERNAL,
    /** An admin moved the contact to another organisation (Admin - Customers). */
    CONTACT_MOVED,
    /**
     * An unconfirmed participant was CC'd again on a verified (DMARC pass) client email and is now
     * confirmed.
     */
    EMAIL_CC_CONFIRMED
  }

  /** Why the reporter changed. Stored by name; append, never rename. */
  public enum ReporterReason {
    /** The email triage dialog saved the sender as a contact of the organisation. */
    TRIAGE_CONTACT
  }

  /**
   * The optional columns of a history row. {@code actorKind} null = derived from the scope; {@code
   * reason} is a stored code whose meaning depends on the row type (see V90).
   */
  public record Extra(
      ActorKind actorKind,
      String reason,
      Long reasonRefId,
      String oldReference,
      String newReference,
      String closeReason,
      String note) {

    public static final Extra NONE = new Extra(null, null, null, null, null);

    /** Without the close-reason and actor columns. */
    public Extra(
        ActorKind actorKind,
        String reason,
        Long reasonRefId,
        String oldReference,
        String newReference) {
      this(actorKind, reason, reasonRefId, oldReference, newReference, null, null);
    }

    public static Extra reason(Enum<?> reason) {
      return new Extra(null, reason == null ? null : reason.name(), null, null, null);
    }

    public static Extra actor(ActorKind kind) {
      return new Extra(kind, null, null, null, null);
    }

    public Extra withActor(ActorKind kind) {
      return new Extra(kind, reason, reasonRefId, oldReference, newReference, closeReason, note);
    }

    public Extra withRef(Long refId) {
      return new Extra(actorKind, reason, refId, oldReference, newReference, closeReason, note);
    }

    public Extra withReferences(String oldRef, String newRef) {
      return new Extra(actorKind, reason, reasonRefId, oldRef, newRef, closeReason, note);
    }

    /**
     * The close reason (CloseReason name) of a close, and the free-text note (the Other note, or an
     * admin's reopen reason).
     */
    public Extra withClose(String closeReasonName, String noteText) {
      return new Extra(
          actorKind, reason, reasonRefId, oldReference, newReference, closeReasonName, noteText);
    }
  }

  /**
   * One row. {@code reason} is the stored code ({@link #assignmentReason()}, {@link #reasonAs});
   * {@code actorKind} is null for older rows.
   */
  public record HistoryRow(
      long id,
      HistoryType type,
      Long actorUserId,
      String oldValue,
      String newValue,
      Instant createdAt,
      String reason,
      Long reasonRefId,
      ActorKind actorKind,
      String oldReference,
      String newReference,
      String closeReason,
      String note) {

    /** Without the close-reason and actor columns. */
    public HistoryRow(
        long id,
        HistoryType type,
        Long actorUserId,
        String oldValue,
        String newValue,
        Instant createdAt,
        String reason,
        Long reasonRefId,
        ActorKind actorKind,
        String oldReference,
        String newReference) {
      this(
          id,
          type,
          actorUserId,
          oldValue,
          newValue,
          createdAt,
          reason,
          reasonRefId,
          actorKind,
          oldReference,
          newReference,
          null,
          null);
    }

    /** The assignment reason of an ASSIGNEE row, or null. */
    public AssignmentReason assignmentReason() {
      return type == HistoryType.ASSIGNEE ? reasonAs(AssignmentReason.class) : null;
    }

    /** {@code reason} as a value of {@code enumType}, or null when absent or unknown. */
    public <E extends Enum<E>> E reasonAs(Class<E> enumType) {
      if (reason == null) {
        return null;
      }
      try {
        return Enum.valueOf(enumType, reason);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
  }

  private final JdbcClient jdbc;

  public TicketHistoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void record(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      HistoryType type,
      Long actorUserId,
      String oldValue,
      String newValue,
      Instant now) {
    record(scope, organisationId, ticketId, type, actorUserId, oldValue, newValue, now, Extra.NONE);
  }

  /** As above, with the assignment reason ({@code null} for a plain change). */
  public void record(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      HistoryType type,
      Long actorUserId,
      String oldValue,
      String newValue,
      Instant now,
      AssignmentReason reason,
      Long reasonRefId) {
    record(
        scope,
        organisationId,
        ticketId,
        type,
        actorUserId,
        oldValue,
        newValue,
        now,
        Extra.reason(reason).withRef(reasonRefId));
  }

  /** As above, with the optional columns ({@code null} = none). */
  public void record(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      HistoryType type,
      Long actorUserId,
      String oldValue,
      String newValue,
      Instant now,
      Extra extra) {
    TicketScopes.requireWritable(scope, organisationId);
    Extra e = extra == null ? Extra.NONE : extra;
    ActorKind kind = e.actorKind() == null ? ActorKind.of(scope) : e.actorKind();
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "INSERT INTO ticket_event (ticket_id, type, actor_user_id, old_value,"
                            + " new_value, created_at, reason, reason_ref_id, actor_kind,"
                            + " old_reference, new_reference, close_reason, note) SELECT :t,"
                            + " :type, :actor, :old, :new, :now, :reason, :reasonRef, :kind,"
                            + " :oldRef, :newRef, :closeReason, :note WHERE "
                            + ScopeSql.ticketVisible(scope, ":t"))
                    .param("t", ticketId)
                    .param("type", type.name())
                    .param("actor", actorUserId)
                    .param("old", truncate(oldValue))
                    .param("new", truncate(newValue))
                    .param("now", Timestamp.from(now))
                    .param("reason", e.reason())
                    .param("reasonRef", e.reasonRefId())
                    .param("kind", kind.name())
                    .param("oldRef", e.oldReference())
                    .param("newRef", e.newReference())
                    .param("closeReason", e.closeReason())
                    .param("note", e.note()),
                scope)
            .update();
    if (n != 1) {
      throw AccessScope.notFound();
    }
  }

  /** Oldest first; supporters and system only (empty otherwise). */
  public List<HistoryRow> listForTicket(AccessScope scope, long ticketId) {
    if (!scope.isSupporter() && !scope.isSystem()) {
      return List.of();
    }
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT e.id, e.type, e.actor_user_id, e.old_value, e.new_value, e.created_at,"
                        + " e.reason, e.reason_ref_id, e.actor_kind, e.old_reference,"
                        + " e.new_reference, e.close_reason, e.note FROM ticket_event e JOIN ticket t ON t.id ="
                        + " e.ticket_id WHERE e.ticket_id = :t AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id")
                        + " ORDER BY e.id")
                .param("t", ticketId),
            scope)
        .query(
            (rs, n) ->
                new HistoryRow(
                    rs.getLong("id"),
                    HistoryType.valueOf(rs.getString("type")),
                    rs.getObject("actor_user_id", Long.class),
                    rs.getString("old_value"),
                    rs.getString("new_value"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("reason"),
                    rs.getObject("reason_ref_id", Long.class),
                    rs.getString("actor_kind") == null
                        ? null
                        : ActorKind.valueOf(rs.getString("actor_kind")),
                    rs.getString("old_reference"),
                    rs.getString("new_reference"),
                    rs.getString("close_reason"),
                    rs.getString("note")))
        .list();
  }

  private static String truncate(String v) {
    return v == null || v.length() <= 200 ? v : v.substring(0, 200);
  }
}
