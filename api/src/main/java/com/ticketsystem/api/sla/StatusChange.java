package com.ticketsystem.api.sla;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry in a ticket's status history: from {@code at} the ticket is in {@code status} until the
 * next change. The first entry of a history is the status at creation.
 */
public record StatusChange(Instant at, TicketStatus status) {

  public StatusChange {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(status, "status");
  }
}
