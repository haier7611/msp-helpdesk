package com.ticketsystem.api.sla.config;

import com.ticketsystem.api.sla.ClockType;
import com.ticketsystem.api.sla.SlaTarget;
import java.time.LocalDate;
import java.time.LocalTime;

/** Value types of the SLA configuration, shared by the repository, service and admin API. */
public final class SlaConfigTypes {

  private SlaConfigTypes() {}

  /** Per-organisation timers, API alert client emails and default assignee. */
  public record SettingsRow(
      boolean pendingReminderEnabled,
      int pendingReminderBusinessDays,
      boolean autoResolveEnabled,
      int autoResolveBusinessDays,
      boolean autoCloseEnabled,
      int autoCloseCalendarDays,
      boolean apiAlertClientEmails,
      Long defaultAssigneeUserId) {

    /** The defaults (a missing row reads as these). */
    public static SettingsRow defaults() {
      return new SettingsRow(true, 3, true, 7, true, 5, false, null);
    }
  }

  /** Business hours on one ISO weekday (1 = Monday); {@code closes} 00:00 = until midnight. */
  public record HoursRow(int dayOfWeek, LocalTime opens, LocalTime closes) {}

  public record HolidayRow(LocalDate date, String name) {}

  public record TargetRow(SlaTarget firstResponse, SlaTarget resolution, ClockType clock) {}
}
