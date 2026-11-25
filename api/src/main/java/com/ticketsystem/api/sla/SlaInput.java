package com.ticketsystem.api.sla;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What the calculator needs to know about one ticket.
 *
 * @param createdAt when the ticket was created; both clocks start here
 * @param priority the current priority (a change recalculates from {@code createdAt})
 * @param policy the SLA policy that applies (organisation or request type)
 * @param businessCalendar the organisation's business calendar; ignored for a 24/7 priority except
 *     for its zone
 * @param history status changes in time order; the first is the status at {@code createdAt}
 * @param firstRespondedAt the first public reply by a supporter, or {@code null} if none yet (not
 *     the automatic confirmation, not an internal note, not a status change - the caller decides
 *     which comment that is)
 */
public record SlaInput(
    Instant createdAt,
    Priority priority,
    SlaPolicy policy,
    BusinessCalendar businessCalendar,
    List<StatusChange> history,
    Instant firstRespondedAt) {

  public SlaInput {
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(priority, "priority");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(businessCalendar, "businessCalendar");
    history = List.copyOf(Objects.requireNonNull(history, "history"));
    if (history.isEmpty()) {
      throw new IllegalArgumentException("history needs at least the status at creation");
    }
    if (!history.get(0).at().equals(createdAt)) {
      throw new IllegalArgumentException("the first status change must be at createdAt");
    }
    for (int i = 1; i < history.size(); i++) {
      if (history.get(i).at().isBefore(history.get(i - 1).at())) {
        throw new IllegalArgumentException(
            "status changes must be in time order (entry " + i + ")");
      }
    }
    if (firstRespondedAt != null && firstRespondedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("first response cannot be before creation");
    }
  }

  /** A copy with a different priority, for "a priority change recalculates". */
  public SlaInput withPriority(Priority newPriority) {
    return new SlaInput(
        createdAt, newPriority, policy, businessCalendar, history, firstRespondedAt);
  }
}
