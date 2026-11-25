package com.ticketsystem.api.ticket.agent;

import com.ticketsystem.api.organisation.clientemail.ClientEmail;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.automation.SlaAutomationTrigger;
import com.ticketsystem.api.ticket.CloseReason;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.TicketTransitions;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TimelineEntryView;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ActorKind;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.AssignmentReason;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.AutomationOutcome;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryRow;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ParticipantReason;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ReporterReason;
import com.ticketsystem.api.ticket.clientdata.TicketLinkRepository.LinkKind;
import java.util.ArrayList;
import java.util.List;

/**
 * The one-line wording of every ticket history row in the agent timeline. Built at read time from
 * the {@code ticket_event} rows; ids are resolved through {@link Names}, which the caller backs
 * with the viewer's own scope, so a restricted agent never sees the name of an organisation,
 * contact, ticket reference or request type it could not see anyway - it reads a neutral "another
 * organisation" / "another ticket" instead. Agent read model only - never a portal response, an
 * email or an export.
 */
public final class TicketTimeline {

  static final String ANOTHER_ORGANISATION = "another organisation";
  static final String ANOTHER_TICKET = "another ticket";
  static final String OTHER_CONTACT = "a contact of another organisation";
  static final String FORMER_USER = "a former user";
  static final String REMOVED_TYPE = "a removed request type";

  /** Lookups backed by the viewer's scope; each returns null when the viewer may not see it. */
  interface Names {
    String user(Long userId);

    String organisation(Long organisationId);

    String requestType(Long requestTypeId);

    String reference(Long ticketId);
  }

  private TicketTimeline() {}

  /** Every row as a timeline line, oldest first; rows that another line covers are left out. */
  static List<TimelineEntryView> build(List<HistoryRow> rows, Names names) {
    boolean followUp = rows.stream().anyMatch(r -> r.type() == HistoryType.FOLLOW_UP_OF);
    List<TimelineEntryView> out = new ArrayList<>();
    for (HistoryRow h : rows) {
      if (followUp && h.type() == HistoryType.CREATED) {
        continue; // the "Follow-up of ..." line says how it was created
      }
      String text = text(h, names);
      if (text == null) {
        continue;
      }
      out.add(
          new TimelineEntryView(
              h.id(), h.type().name(), actorKind(h).name(), h.actorUserId(), text, h.createdAt()));
    }
    return out;
  }

  /**
   * Who acted: the stored kind, or for rows older than the actor column an agent when a user is
   * recorded.
   */
  static ActorKind actorKind(HistoryRow h) {
    if (h.actorKind() != null) {
      return h.actorKind();
    }
    if (h.type() == HistoryType.CREATED && "PORTAL".equals(h.newValue())) {
      return ActorKind.CLIENT;
    }
    return h.actorUserId() == null ? ActorKind.SYSTEM : ActorKind.AGENT;
  }

  /** The line's wording, or null when the row is covered by another line. */
  static String text(HistoryRow h, Names n) {
    return switch (h.type()) {
      case CREATED -> created(h, n);
      case STATUS -> status(h, n);
      case PRIORITY ->
          "Priority changed from "
              + priority(h.oldValue())
              + " to "
              + priority(h.newValue())
              + by(h, n);
      case SLA_DUE_CHANGED ->
          !"ORGANISATION_MOVED".equals(h.reason())
              ? "SLA due times recalculated for the new priority"
              : h.reasonRefId() == null
                  ? "SLA due times recalculated for the triage defaults"
                  : "SLA due times recalculated for the new organisation";
      case CLIENT_REMINDER_SENT ->
          "emailed".equals(h.newValue())
              ? "Pending-on-client reminder emailed to the client"
              : "Pending-on-client reminder due (no client email sent)";
      case REQUEST_TYPE ->
          "Request type changed from "
              + requestType(h.oldValue(), n)
              + " to "
              + requestType(h.newValue(), n)
              + by(h, n);
      case ASSIGNEE -> assignment(h, n);
      case ORGANISATION -> organisation(h, n);
      case PARTICIPANTS_RECHECKED ->
          "Participants re-checked after the move ("
              + (h.newValue() == null ? "some removed" : h.newValue())
              + ")"
              + by(h, n);
      case PARTICIPANT_ADDED -> participantAdded(h, n);
      case PARTICIPANT_REMOVED -> participantRemoved(h, n);
      case REPORTER -> reporter(h, n);
      case LINKED ->
          (n.reference(id(h.newValue())) == null
                  ? "Linked to another ticket (related)"
                  : "Linked to related ticket " + ticket(h.newValue(), n))
              + by(h, n);
      case LINK_REMOVED -> linkRemoved(h, n);
      case DUPLICATE_OF ->
          "Marked as a duplicate of "
              + ticket(h.newValue(), n)
              + " and closed"
              + by(h, n)
              + " (close reason: "
              + CloseReason.DUPLICATE.label()
              + ")";
      case DUPLICATE_ADDED ->
          orElse(n.reference(id(h.newValue())), "Another ticket")
              + " marked as a duplicate of this ticket (merged)"
              + by(h, n);
      case FOLLOW_UP_OF -> "Follow-up of " + ticket(h.newValue(), n) + followUpCause(h, n, false);
      case FOLLOW_UP_ADDED -> "Follow-up " + ticket(h.newValue(), n) + followUpCause(h, n, true);
      case SLA_AUTOMATION -> automation(h, n);
      case CLIENT_EMAIL_SKIPPED -> clientEmailSkipped(h, n);
    };
  }

  /** True for a PRIORITY or ASSIGNEE row written by an SLA automation rule. */
  static boolean byAutomation(HistoryRow h) {
    return TicketService.AUTOMATION_REASON.equals(h.reason()) && h.type() != HistoryType.STATUS;
  }

  /** "Automation (rule name)", the actor of every line an SLA automation rule wrote. */
  static String automationActor(HistoryRow h) {
    return "Automation (" + orElse(h.note(), "a deleted rule") + ")";
  }

  /** One SLA automation action, e.g. "Automation (Escalate): 2 agents notified (...)". */
  private static String automation(HistoryRow h, Names n) {
    AutomationOutcome outcome = h.reasonAs(AutomationOutcome.class);
    String agent = user(h.reasonRefId(), n, AssignmentTimeline.FORMER_AGENT);
    String what =
        outcome == null
            ? "action done"
            : switch (outcome) {
              case NOTIFIED_AGENTS -> count(h.newValue(), "agent") + " notified";
              case NOTIFIED_ORGANISATION ->
                  "the organisation's agents notified (" + count(h.newValue(), "agent") + ")";
              case NOBODY_NOTIFIED ->
                  "nobody notified (no active agent who can work this ticket,"
                      + " or their notifications are off)";
              case ASSIGN_SKIPPED_ASSIGNED ->
                  "not assigned to " + agent + " - the ticket already has an assignee";
              case ASSIGN_SKIPPED_NOT_ALLOWED ->
                  "not assigned to "
                      + agent
                      + " - the agent is deactivated or cannot work this organisation";
              case PRIORITY_AT_TOP -> "priority not raised - it is already P1 Critical";
              case NOTE_ADDED -> "internal note added";
              case SKIPPED_CLOSED -> "assignment or priority change skipped - the ticket is closed";
            };
    SlaAutomationTrigger trigger = parse(SlaAutomationTrigger.class, h.oldValue());
    return automationActor(h) + ": " + what + (trigger == null ? "" : " (" + trigger.label() + ")");
  }

  private static String count(String value, String noun) {
    Long c = id(value);
    long k = c == null ? 0 : c;
    return k + " " + noun + (k == 1 ? "" : "s");
  }

  /**
   * "Resolved notice not sent - turned off for <organisation>". The organisation goes through the
   * viewer's access like every other name (it can differ from the ticket's current organisation
   * after a move).
   */
  private static String clientEmailSkipped(HistoryRow h, Names n) {
    ClientEmail email = parse(ClientEmail.class, h.newValue());
    String what = email == null ? "Client email" : email.label();
    return what
        + " not sent - turned off for "
        + orElse(n.organisation(h.reasonRefId()), ANOTHER_ORGANISATION);
  }

  private static String created(HistoryRow h, Names n) {
    TicketSource source = parse(TicketSource.class, h.newValue());
    if (source == null) {
      return "Ticket created" + by(h, n);
    }
    return switch (source) {
      case PORTAL ->
          "Ticket created in the portal by "
              + (h.actorUserId() == null ? "the client" : user(h.actorUserId(), n, FORMER_USER));
      case EMAIL -> "Ticket created from an email";
      case API -> "Ticket created by a monitoring alert (API)";
      case AGENT -> "Ticket created" + by(h, n);
    };
  }

  private static String status(HistoryRow h, Names n) {
    TicketStatus from = parse(TicketStatus.class, h.oldValue());
    TicketStatus to = parse(TicketStatus.class, h.newValue());
    Trigger trigger = h.reasonAs(Trigger.class);
    String change = "Status changed from " + statusLabel(from) + " to " + statusLabel(to);
    if (trigger == null) {
      return change + by(h, n);
    }
    return switch (trigger) {
      case CLIENT_REPLY -> {
        if (from == TicketStatus.RESOLVED) {
          yield "Reopened by client reply";
        }
        if (to == TicketStatus.OPEN
            && (from == TicketStatus.PENDING_ON_CLIENT || from == TicketStatus.PENDING_ON_VENDOR)) {
          yield "Moved to Open by client reply";
        }
        yield change + " by client reply";
      }
      case AUTO_RESOLVE -> "Resolved automatically (no client reply)";
      case AUTO_CLOSE -> "Closed automatically" + closeReason(h);
      case ALERT_REPEAT -> "Reopened by a repeat monitoring alert";
      case ALERT_RESOLVED -> "Resolved by a monitoring alert";
      // The "Marked as a duplicate of ... and closed" line covers it.
      case MARK_DUPLICATE -> null;
      case PICK_UP -> change + by(h, n) + " (picked up)";
      case SUPPORTER_PUBLIC_REPLY -> change + by(h, n) + " (public reply)";
      case AGENT_SET ->
          to == TicketStatus.CLOSED && h.closeReason() != null
              ? "Closed" + by(h, n) + closeReason(h)
              : change + by(h, n);
      case ADMIN_REOPEN -> "Reopened" + by(h, n) + (h.note() == null ? "" : ": " + h.note());
    };
  }

  /**
   * ": <reason label>" (with the note for Other) of a close row, or nothing for a close recorded
   * before close reasons existed.
   */
  private static String closeReason(HistoryRow h) {
    CloseReason reason = CloseReason.parseOrNull(h.closeReason());
    return reason == null ? "" : ": " + CloseReason.describe(reason, h.note());
  }

  /** The assignment wording (also used for {@code assignmentEntries}). */
  static String assignment(HistoryRow h, Names n) {
    AssignmentReason reason = h.assignmentReason();
    String reasonName = null;
    if (reason == AssignmentReason.DEFAULT_ORGANISATION
        || reason == AssignmentReason.ORGANISATION_MOVED) {
      reasonName = orElse(n.organisation(h.reasonRefId()), ANOTHER_ORGANISATION);
    } else if (reason == AssignmentReason.DEFAULT_REQUEST_TYPE) {
      reasonName = orElse(n.requestType(h.reasonRefId()), "the request type");
    }
    Long actor = actorKind(h) == ActorKind.AGENT ? h.actorUserId() : null;
    return AssignmentTimeline.text(
        agentName(id(h.oldValue()), n),
        agentName(id(h.newValue()), n),
        byAutomation(h) ? automationActor(h) : agentName(actor, n),
        reason,
        reasonName);
  }

  private static String organisation(HistoryRow h, Names n) {
    Long fromId = id(h.oldValue());
    String toName = n.organisation(id(h.newValue()));
    String to = orElse(toName, ANOTHER_ORGANISATION);
    // A reference shows only next to an organisation the viewer may see (its prefix names it).
    String newRef = toName == null ? null : h.newReference();
    if (fromId == null) {
      return "Organisation set to "
          + to
          + " (from email triage)"
          + by(h, n)
          + (newRef == null
              ? ""
              : h.oldReference() == null
                  ? " (new reference " + newRef + ")"
                  : " (reference " + h.oldReference() + " is now " + newRef + ")");
    }
    String fromName = n.organisation(fromId);
    String oldRef = fromName == null ? null : h.oldReference();
    String refs;
    if (newRef == null) {
      refs = "";
    } else if (oldRef == null) {
      refs = " (new reference " + newRef + ")";
    } else {
      refs = " (reference " + oldRef + " is now " + newRef + ")";
    }
    return "Moved from " + orElse(fromName, ANOTHER_ORGANISATION) + " to " + to + by(h, n) + refs;
  }

  private static String participantAdded(HistoryRow h, Names n) {
    String who = participant(h, n);
    ParticipantReason reason = h.reasonAs(ParticipantReason.class);
    if (reason == null) {
      return who + " added as a participant" + by(h, n);
    }
    return switch (reason) {
      case EMAIL_CC -> who + " added as CC (from the client's email)";
      case EMAIL_CC_UNCONFIRMED ->
          who + " added as CC (from the client's email) (waiting for confirmation)";
      case EMAIL_CC_CONFIRMED -> who + " confirmed as CC (from the client's email)";
      case DUPLICATE_MERGED -> who + " added as a participant (from a merged duplicate)" + by(h, n);
      case ORGANISATION_MOVED -> who + " added as a participant" + by(h, n);
      case AGENT_ADDED -> who + " added as CC" + by(h, n);
      case AGENT_ADDED_EXTERNAL -> who + " added as CC (outside address, confirmed)" + by(h, n);
      case AGENT_CONFIRMED_EXTERNAL -> who + " confirmed as CC (outside address)" + by(h, n);
      case AGENT_REMOVED -> who + " added as a participant" + by(h, n);
      case REPLY_ONLY -> who + " CC'd on this reply only" + by(h, n);
      case REPLY_ONLY_EXTERNAL ->
          who + " CC'd on this reply only (outside address, confirmed)" + by(h, n);
      case CONTACT_MOVED -> who + " added as a participant" + by(h, n);
    };
  }

  private static String participantRemoved(HistoryRow h, Names n) {
    String who = participant(h, n);
    ParticipantReason reason = h.reasonAs(ParticipantReason.class);
    if (reason == ParticipantReason.ORGANISATION_MOVED) {
      return who + " removed as a participant (not a contact of the new organisation)" + by(h, n);
    }
    if (reason == ParticipantReason.CONTACT_MOVED) {
      return who + " removed as a participant (moved to another organisation)" + by(h, n);
    }
    if (reason == ParticipantReason.AGENT_REMOVED) {
      return who + " removed from CC" + by(h, n);
    }
    if (reason == ParticipantReason.REPLY_ONLY) {
      return who + " left out of CC on this reply only" + by(h, n);
    }
    return who + " removed as a participant" + by(h, n);
  }

  /** The participant's current name, an email-only participant's address, or a neutral text. */
  private static String participant(HistoryRow h, Names n) {
    if (h.reasonRefId() != null) {
      return orElse(
          n.user(h.reasonRefId()), "A contact of another organisation"); // starts the line
    }
    return h.newValue() == null ? "Someone (address removed)" : h.newValue();
  }

  private static String reporter(HistoryRow h, Names n) {
    String to = user(id(h.newValue()), n, OTHER_CONTACT);
    if (h.reasonAs(ReporterReason.class) == ReporterReason.TRIAGE_CONTACT) {
      return "Sender saved as contact " + to + " and set as reporter" + by(h, n);
    }
    Long from = id(h.oldValue());
    return (from == null
            ? "Reporter set to " + to
            : "Reporter changed from " + user(from, n, OTHER_CONTACT) + " to " + to)
        + by(h, n);
  }

  private static String linkRemoved(HistoryRow h, Names n) {
    LinkKind kind = h.reasonAs(LinkKind.class);
    String what =
        kind == null
            ? "Link"
            : switch (kind) {
              case RELATED -> "Related link";
              case DUPLICATE -> "Duplicate link";
              case FOLLOW_UP -> "Follow-up link";
            };
    return what
        + " to "
        + ticket(h.newValue(), n)
        + " removed (a ticket moved to another organisation)"
        + by(h, n);
  }

  private static String followUpCause(HistoryRow h, Names n, boolean onClosedTicket) {
    return switch (actorKind(h)) {
      case CLIENT ->
          onClosedTicket
              ? " opened by a client reply to this closed ticket"
              : ", opened by a client reply to the closed ticket";
      case API_KEY ->
          onClosedTicket
              ? " opened by a repeat monitoring alert"
              : ", opened by a repeat monitoring alert";
      default -> " created" + by(h, n);
    };
  }

  /** " by Alex Example", " by client reply", " by a monitoring alert" or " automatically". */
  static String by(HistoryRow h, Names n) {
    if (byAutomation(h)) {
      return " by " + automationActor(h);
    }
    return switch (actorKind(h)) {
      case AGENT ->
          h.actorUserId() == null
              ? " automatically"
              : " by " + user(h.actorUserId(), n, AssignmentTimeline.FORMER_AGENT);
      case CLIENT ->
          h.actorUserId() == null
              ? " by client reply"
              : " by " + user(h.actorUserId(), n, FORMER_USER);
      case API_KEY -> " by a monitoring alert";
      case SYSTEM -> " automatically";
    };
  }

  private static String agentName(Long userId, Names n) {
    return userId == null ? null : user(userId, n, AssignmentTimeline.FORMER_AGENT);
  }

  private static String user(Long userId, Names n, String fallback) {
    if (userId == null) {
      return fallback;
    }
    String name = n.user(userId);
    return name == null || name.isBlank() ? fallback : name;
  }

  private static String requestType(String value, Names n) {
    return orElse(n.requestType(id(value)), REMOVED_TYPE);
  }

  private static String ticket(String value, Names n) {
    return orElse(n.reference(id(value)), ANOTHER_TICKET);
  }

  static String priority(String value) {
    Priority p = parse(Priority.class, value);
    if (p == null) {
      return value == null ? "none" : value;
    }
    return switch (p) {
      case P1 -> "P1 Critical";
      case P2 -> "P2 High";
      case P3 -> "P3 Medium";
      case P4 -> "P4 Low";
    };
  }

  private static String statusLabel(TicketStatus s) {
    return s == null ? "unknown" : TicketTransitions.label(s);
  }

  private static String orElse(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** An id stored as text in a history row, or null. */
  static Long id(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value) {
    if (value == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
