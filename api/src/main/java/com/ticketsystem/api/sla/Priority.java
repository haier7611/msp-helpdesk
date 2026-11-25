package com.ticketsystem.api.sla;

/**
 * Ticket priority as the SLA engine sees it. The ticket code maps the ticket's priority to this
 * enum, or adopts it as the domain type.
 */
public enum Priority {
  /** P1 Critical: always on the 24/7 clock. */
  P1,
  /** P2 High. */
  P2,
  /** P3 Normal. */
  P3,
  /** P4 Low. */
  P4
}
