package com.ticketsystem.api.ticket.agent;

import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.AssignmentReason;

/**
 * The one-line wording of an assignee change in the agent ticket timeline. Built at read time from
 * the {@code ASSIGNEE} history row (user ids resolved to today's names), only in the agent read
 * model - never in a portal response, an email or an export.
 */
public final class AssignmentTimeline {

  /** Shown when a user's name cannot be resolved (for example an account the viewer cannot see). */
  public static final String FORMER_AGENT = "a former agent";

  private AssignmentTimeline() {}

  /**
   * @param from the previous assignee's name, or null when the ticket was unassigned
   * @param to the new assignee's name, or null when the ticket is now unassigned
   * @param actor who made the change, or null for the system (auto-assignment, a job)
   * @param reason why, or null for a plain change by {@code actor}
   * @param reasonName the organisation or request type the reason refers to (never null when the
   *     reason needs one; the caller supplies a fallback)
   */
  static String text(
      String from, String to, String actor, AssignmentReason reason, String reasonName) {
    String change;
    if (to == null) {
      change = from == null ? "Unassigned" : "Unassigned from " + from;
    } else if (from == null) {
      change = "Assigned to " + to;
    } else {
      change = "Reassigned from " + from + " to " + to;
    }
    String why =
        reason == null
            ? ""
            : switch (reason) {
              case DEFAULT_REQUEST_TYPE, DEFAULT_ORGANISATION ->
                  " (default for " + reasonName + ")";
              case DEACTIVATED -> " (" + (from == null ? FORMER_AGENT : from) + " deactivated)";
              case ORGANISATION_MOVED -> " (ticket moved to " + reasonName + ")";
            };
    if (actor == null) {
      return change + " automatically" + why;
    }
    return change + why + " by " + actor;
  }
}
