package com.ticketsystem.api.auth;

/** The three user roles. "Supporter" means an agent or an admin. */
public enum Role {
  CUSTOMER,
  AGENT,
  ADMIN;

  public boolean isSupporter() {
    return this == AGENT || this == ADMIN;
  }

  /** The Spring Security authority for this role, e.g. {@code ROLE_ADMIN}. */
  public String authority() {
    return "ROLE_" + name();
  }
}
