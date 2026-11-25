package com.ticketsystem.api.ticket.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.AssignmentReason;
import org.junit.jupiter.api.Test;

/** WP-16 (ADR-032): the timeline wording for every kind of assignee change. */
class AssignmentTimelineTest {

  @Test
  void manualChanges() {
    assertThat(AssignmentTimeline.text(null, "Alex", "Jo", null, null))
        .isEqualTo("Assigned to Alex by Jo");
    assertThat(AssignmentTimeline.text("Alex", "Sam", "Jo", null, null))
        .isEqualTo("Reassigned from Alex to Sam by Jo");
    assertThat(AssignmentTimeline.text("Alex", null, "Jo", null, null))
        .isEqualTo("Unassigned from Alex by Jo");
    assertThat(AssignmentTimeline.text(null, null, "Jo", null, null)).isEqualTo("Unassigned by Jo");
  }

  @Test
  void automaticAssignmentNamesTheRule() {
    assertThat(
            AssignmentTimeline.text(
                null, "Alex", null, AssignmentReason.DEFAULT_ORGANISATION, "Acme Dental"))
        .isEqualTo("Assigned to Alex automatically (default for Acme Dental)");
    assertThat(
            AssignmentTimeline.text(
                null, "Alex", null, AssignmentReason.DEFAULT_REQUEST_TYPE, "Hardware"))
        .isEqualTo("Assigned to Alex automatically (default for Hardware)");
    // A change by the system without a stored reason (rows from before WP-16).
    assertThat(AssignmentTimeline.text(null, "Alex", null, null, null))
        .isEqualTo("Assigned to Alex automatically");
  }

  @Test
  void deactivationAndOrganisationMove() {
    assertThat(AssignmentTimeline.text("Alex", "Sam", "Pat", AssignmentReason.DEACTIVATED, null))
        .isEqualTo("Reassigned from Alex to Sam (Alex deactivated) by Pat");
    assertThat(AssignmentTimeline.text("Alex", null, "Pat", AssignmentReason.DEACTIVATED, null))
        .isEqualTo("Unassigned from Alex (Alex deactivated) by Pat");
    assertThat(
            AssignmentTimeline.text(
                "Alex", null, "Pat", AssignmentReason.ORGANISATION_MOVED, "Beta Clinic"))
        .isEqualTo("Unassigned from Alex (ticket moved to Beta Clinic) by Pat");
  }
}
