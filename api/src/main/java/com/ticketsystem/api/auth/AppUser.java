package com.ticketsystem.api.auth;

/** A row of {@code app_user}. {@code passwordHash} never leaves the auth services. */
public record AppUser(
    long id,
    String email,
    String name,
    Role role,
    UserStatus status,
    String passwordHash,
    int securityVersion) {

  public boolean isActive() {
    return status == UserStatus.ACTIVE;
  }

  @Override
  public String toString() {
    // Never print the hash or the email if a user object ends up in a log line.
    return "AppUser[id=" + id + ", role=" + role + ", status=" + status + "]";
  }
}
