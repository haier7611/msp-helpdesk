package com.ticketsystem.api.settings;

/** My settings - Profile as stored; the display name is {@code app_user.name}. */
public record ProfileRow(
    String email,
    String displayName,
    String jobTitle,
    String phone,
    String mobile,
    String freeLine,
    boolean hasPhoto) {
  @Override
  public String toString() {
    return "ProfileRow[...]";
  }
}
