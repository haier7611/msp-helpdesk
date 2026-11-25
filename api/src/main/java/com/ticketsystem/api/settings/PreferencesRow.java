package com.ticketsystem.api.settings;

import java.time.LocalDate;

/** My settings - Preferences, daily digest and Out of office as stored. */
public record PreferencesRow(
    String composerTab,
    String queueView,
    String timezone,
    String dateFormat,
    String theme,
    boolean dailyDigest,
    boolean outOfOffice,
    LocalDate outOfOfficeFrom,
    LocalDate outOfOfficeUntil) {

  /** What a supporter who never saved preferences gets. */
  public static final PreferencesRow DEFAULTS =
      new PreferencesRow(
          "INTERNAL_NOTE",
          "my-open",
          "Asia/Ho_Chi_Minh",
          "DMY",
          "SYSTEM",
          false,
          false,
          null,
          null);
}
