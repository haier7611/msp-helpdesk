package com.ticketsystem.api.sla;

import java.util.Objects;

/** The first-response and resolution targets for one priority, and the clock they run on. */
public record PriorityTargets(SlaTarget firstResponse, SlaTarget resolution, ClockType clock) {

  public PriorityTargets {
    Objects.requireNonNull(firstResponse, "firstResponse");
    Objects.requireNonNull(resolution, "resolution");
    Objects.requireNonNull(clock, "clock");
  }
}
