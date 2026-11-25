package com.ticketsystem.api.sla;

/**
 * Ticket status as the SLA engine sees it, with its effect on the SLA clock (the status table). The
 * ticket code maps the ticket's status to this enum, or adopts it as the domain type.
 */
public enum TicketStatus {
  /** Created, nobody has picked it up. Clock running. */
  NEW,
  /** Being worked on by the MSP. Clock running. */
  OPEN,
  /** Waiting for the client. Clock always paused. */
  PENDING_ON_CLIENT,
  /**
   * Waiting for a third party. Keeps running by default; paused only when the SLA policy says so.
   */
  PENDING_ON_VENDOR,
  /** MSP believes it is fixed. Clock stopped; a reopen continues from where it stopped (4.7). */
  RESOLVED,
  /** Finished, read-only. Clock stopped. */
  CLOSED;

  /**
   * The effect of this status on the SLA clock.
   *
   * @param pendingOnVendorPauses the policy's "Pending on vendor pauses SLA" flag
   */
  public ClockEffect clockEffect(boolean pendingOnVendorPauses) {
    return switch (this) {
      case NEW, OPEN -> ClockEffect.RUNNING;
      case PENDING_ON_CLIENT -> ClockEffect.PAUSED;
      case PENDING_ON_VENDOR -> pendingOnVendorPauses ? ClockEffect.PAUSED : ClockEffect.RUNNING;
      case RESOLVED, CLOSED -> ClockEffect.STOPPED;
    };
  }

  /** What a status does to the SLA clock. */
  public enum ClockEffect {
    /** Time counts towards the targets. */
    RUNNING,
    /** Time does not count and is reported as paused time. */
    PAUSED,
    /** Time does not count and is not paused time (Resolved, Closed). */
    STOPPED
  }
}
