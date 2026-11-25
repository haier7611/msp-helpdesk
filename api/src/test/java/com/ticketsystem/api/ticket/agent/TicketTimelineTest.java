package com.ticketsystem.api.ticket.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.ticket.agent.AgentTicketViews.TimelineEntryView;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ActorKind;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryRow;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** WP-18: the wording of every timeline line, with a viewer who can see only some things. */
class TicketTimelineTest {

  private static final Instant AT = Instant.parse("2026-11-19T07:00:00Z");

  /** Users 1 (agent) and 2 (contact) resolve; organisation 10 is visible, 20 is not. */
  private static final TicketTimeline.Names NAMES =
      new TicketTimeline.Names() {
        @Override
        public String user(Long id) {
          return Map.of(1L, "Jo Example", 2L, "Sam Contact").get(id);
        }

        @Override
        public String organisation(Long id) {
          return id != null && id == 10L ? "Acme Dental" : null;
        }

        @Override
        public String requestType(Long id) {
          return id != null && id == 5L ? "Hardware" : null;
        }

        @Override
        public String reference(Long id) {
          return id != null && id == 77L ? "ACD-077" : null;
        }
      };

  private static HistoryRow row(
      HistoryType type,
      ActorKind kind,
      Long actor,
      String old,
      String neu,
      String reason,
      Long ref) {
    return new HistoryRow(1, type, actor, old, neu, AT, reason, ref, kind, null, null);
  }

  private static String text(HistoryRow h) {
    List<TimelineEntryView> out = TicketTimeline.build(List.of(h), NAMES);
    return out.isEmpty() ? null : out.get(0).text();
  }

  @Test
  void aSkippedClientEmailNamesTheEmailAndTheOrganisationThroughTheViewersAccess() {
    assertThat(
            text(
                row(
                    HistoryType.CLIENT_EMAIL_SKIPPED,
                    ActorKind.SYSTEM,
                    null,
                    null,
                    "RESOLVED_NOTICE",
                    null,
                    10L)))
        .isEqualTo("Resolved notice not sent - turned off for Acme Dental");
    assertThat(
            text(
                row(
                    HistoryType.CLIENT_EMAIL_SKIPPED,
                    ActorKind.SYSTEM,
                    null,
                    null,
                    "TICKET_RECEIVED",
                    null,
                    20L)))
        .isEqualTo("Ticket received not sent - turned off for another organisation");
    assertThat(
            text(
                row(
                    HistoryType.CLIENT_EMAIL_SKIPPED,
                    ActorKind.SYSTEM,
                    null,
                    null,
                    "SOMETHING_NEW",
                    null,
                    10L)))
        .isEqualTo("Client email not sent - turned off for Acme Dental");
  }

  @Test
  void statusLinesNameTheCause() {
    assertThat(
            text(
                row(
                    HistoryType.STATUS,
                    ActorKind.AGENT,
                    1L,
                    "OPEN",
                    "RESOLVED",
                    "AGENT_SET",
                    null)))
        .isEqualTo("Status changed from Open to Resolved by Jo Example");
    assertThat(
            text(
                row(
                    HistoryType.STATUS,
                    ActorKind.CLIENT,
                    null,
                    "PENDING_ON_VENDOR",
                    "OPEN",
                    "CLIENT_REPLY",
                    null)))
        .isEqualTo("Moved to Open by client reply");
    assertThat(
            text(
                row(
                    HistoryType.STATUS,
                    ActorKind.CLIENT,
                    null,
                    "RESOLVED",
                    "OPEN",
                    "CLIENT_REPLY",
                    null)))
        .isEqualTo("Reopened by client reply");
    assertThat(
            text(
                row(
                    HistoryType.STATUS,
                    ActorKind.SYSTEM,
                    null,
                    "PENDING_ON_CLIENT",
                    "RESOLVED",
                    "AUTO_RESOLVE",
                    null)))
        .isEqualTo("Resolved automatically (no client reply)");
    assertThat(
            text(
                row(
                    HistoryType.STATUS,
                    ActorKind.SYSTEM,
                    null,
                    "RESOLVED",
                    "CLOSED",
                    "AUTO_CLOSE",
                    null)))
        .isEqualTo("Closed automatically");
    // Covered by the duplicate line.
    assertThat(
            text(
                row(
                    HistoryType.STATUS,
                    ActorKind.AGENT,
                    1L,
                    "OPEN",
                    "CLOSED",
                    "MARK_DUPLICATE",
                    null)))
        .isNull();
  }

  private static HistoryRow close(
      ActorKind kind, Long actor, String trigger, String closeReason, String note) {
    return new HistoryRow(
        1,
        HistoryType.STATUS,
        actor,
        "RESOLVED",
        "CLOSED",
        AT,
        trigger,
        null,
        kind,
        null,
        null,
        closeReason,
        note);
  }

  @Test
  void adr041CloseAndReopenLinesCarryTheReason() {
    assertThat(text(close(ActorKind.AGENT, 1L, "AGENT_SET", "SPAM", null)))
        .isEqualTo("Closed by Jo Example: Spam / junk");
    assertThat(text(close(ActorKind.AGENT, 1L, "AGENT_SET", "OTHER", "Contract ended")))
        .isEqualTo("Closed by Jo Example: Other - Contract ended");
    assertThat(text(close(ActorKind.SYSTEM, null, "AUTO_CLOSE", "AUTO_CLOSED_NO_REPLY", null)))
        .isEqualTo("Closed automatically: No client reply after it was resolved");
    // A close recorded before ADR-041 keeps its plain wording.
    assertThat(text(close(ActorKind.AGENT, 1L, "AGENT_SET", null, null)))
        .isEqualTo("Status changed from Resolved to Closed by Jo Example");
    assertThat(
            text(
                new HistoryRow(
                    1,
                    HistoryType.STATUS,
                    1L,
                    "CLOSED",
                    "OPEN",
                    AT,
                    "ADMIN_REOPEN",
                    null,
                    ActorKind.AGENT,
                    null,
                    null,
                    null,
                    "Client came back")))
        .isEqualTo("Reopened by Jo Example: Client came back");
  }

  @Test
  void rowsOlderThanWp18ReadInThePlainForm() {
    // No actor kind and no trigger: an agent when a user is recorded, else automatic.
    assertThat(text(row(HistoryType.STATUS, null, 1L, "NEW", "OPEN", null, null)))
        .isEqualTo("Status changed from New to Open by Jo Example");
    assertThat(text(row(HistoryType.STATUS, null, null, "RESOLVED", "CLOSED", null, null)))
        .isEqualTo("Status changed from Resolved to Closed automatically");
    assertThat(
            text(row(HistoryType.PARTICIPANTS_RECHECKED, null, 1L, null, "2 removed", null, null)))
        .isEqualTo("Participants re-checked after the move (2 removed) by Jo Example");
  }

  @Test
  void priorityRequestTypeAndUnknownUsers() {
    assertThat(text(row(HistoryType.PRIORITY, ActorKind.AGENT, 1L, "P3", "P1", null, null)))
        .isEqualTo("Priority changed from P3 Medium to P1 Critical by Jo Example");
    assertThat(text(row(HistoryType.REQUEST_TYPE, ActorKind.AGENT, 99L, "5", "6", null, null)))
        .isEqualTo(
            "Request type changed from Hardware to a removed request type by a former agent");
  }

  @Test
  void slaRecalculationLinesNameWhatChanged() {
    assertThat(text(row(HistoryType.SLA_DUE_CHANGED, null, null, "a", "b", null, null)))
        .isEqualTo("SLA due times recalculated for the new priority");
    assertThat(
            text(row(HistoryType.SLA_DUE_CHANGED, null, null, "a", "b", "ORGANISATION_MOVED", 10L)))
        .isEqualTo("SLA due times recalculated for the new organisation");
    // ADR-047: no organisation now (triage) - neutral, never "for the new organisation".
    assertThat(
            text(
                row(HistoryType.SLA_DUE_CHANGED, null, null, "a", "b", "ORGANISATION_MOVED", null)))
        .isEqualTo("SLA due times recalculated for the triage defaults");
  }

  @Test
  void hiddenOrganisationsContactsAndTicketsReadNeutrally() {
    HistoryRow move =
        new HistoryRow(
            1,
            HistoryType.ORGANISATION,
            1L,
            "20",
            "10",
            AT,
            null,
            null,
            ActorKind.AGENT,
            "BCL-004",
            "ACD-012");
    assertThat(text(move))
        .isEqualTo(
            "Moved from another organisation to Acme Dental by Jo Example (new reference"
                + " ACD-012)");
    HistoryRow away =
        new HistoryRow(
            1,
            HistoryType.ORGANISATION,
            1L,
            "10",
            "20",
            AT,
            null,
            null,
            ActorKind.AGENT,
            "ACD-012",
            "BCL-004");
    assertThat(text(away))
        .isEqualTo("Moved from Acme Dental to another organisation by Jo Example");
    assertThat(
            text(
                row(
                    HistoryType.PARTICIPANT_REMOVED,
                    ActorKind.AGENT,
                    1L,
                    null,
                    null,
                    "ORGANISATION_MOVED",
                    3L)))
        .isEqualTo(
            "A contact of another organisation removed as a participant (not a contact of the new"
                + " organisation) by Jo Example");
    assertThat(text(row(HistoryType.DUPLICATE_OF, ActorKind.AGENT, 1L, null, "88", null, null)))
        .isEqualTo(
            "Marked as a duplicate of another ticket and closed by Jo Example (close reason: Duplicate)");
    assertThat(text(row(HistoryType.LINKED, ActorKind.AGENT, 1L, null, "77", null, null)))
        .isEqualTo("Linked to related ticket ACD-077 by Jo Example");
  }

  @Test
  void reporterParticipantsAndFollowUps() {
    assertThat(
            text(row(HistoryType.REPORTER, ActorKind.AGENT, 1L, null, "2", "TRIAGE_CONTACT", null)))
        .isEqualTo("Sender saved as contact Sam Contact and set as reporter by Jo Example");
    assertThat(
            text(
                row(
                    HistoryType.PARTICIPANT_ADDED,
                    ActorKind.CLIENT,
                    null,
                    null,
                    "cc@acme.example",
                    "EMAIL_CC",
                    null)))
        .isEqualTo("cc@acme.example added as CC (from the client's email)");
    assertThat(
            text(
                row(
                    HistoryType.PARTICIPANT_ADDED,
                    ActorKind.CLIENT,
                    null,
                    null,
                    "cc@acme.example",
                    "EMAIL_CC_UNCONFIRMED",
                    null)))
        .isEqualTo(
            "cc@acme.example added as CC (from the client's email) (waiting for confirmation)");
    assertThat(
            text(
                row(
                    HistoryType.PARTICIPANT_ADDED,
                    ActorKind.CLIENT,
                    null,
                    null,
                    "cc@acme.example",
                    "EMAIL_CC_CONFIRMED",
                    null)))
        .isEqualTo("cc@acme.example confirmed as CC (from the client's email)");
    // An erased email-only participant (address scrubbed).
    assertThat(
            text(
                row(
                    HistoryType.PARTICIPANT_ADDED,
                    ActorKind.CLIENT,
                    null,
                    null,
                    null,
                    "EMAIL_CC",
                    null)))
        .isEqualTo("Someone (address removed) added as CC (from the client's email)");
    assertThat(
            text(row(HistoryType.FOLLOW_UP_ADDED, ActorKind.API_KEY, null, null, "77", null, null)))
        .isEqualTo("Follow-up ACD-077 opened by a repeat monitoring alert");
  }

  @Test
  void aFollowUpHasNoSeparateCreatedLine() {
    HistoryRow created =
        new HistoryRow(
            1,
            HistoryType.CREATED,
            null,
            null,
            "EMAIL",
            AT,
            null,
            null,
            ActorKind.CLIENT,
            null,
            null);
    HistoryRow followUp =
        new HistoryRow(
            2,
            HistoryType.FOLLOW_UP_OF,
            null,
            null,
            "77",
            AT,
            null,
            null,
            ActorKind.CLIENT,
            null,
            null);
    assertThat(TicketTimeline.build(List.of(created, followUp), NAMES))
        .extracting(TimelineEntryView::text)
        .containsExactly("Follow-up of ACD-077, opened by a client reply to the closed ticket");
    assertThat(TicketTimeline.build(List.of(created), NAMES))
        .extracting(TimelineEntryView::text)
        .containsExactly("Ticket created from an email");
  }
}
