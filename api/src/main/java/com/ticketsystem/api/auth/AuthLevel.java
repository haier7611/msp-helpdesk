package com.ticketsystem.api.auth;

/**
 * How far a session has got through sign-in. Customers go straight to {@link #FULL}; supporters
 * stop after the password at {@link #MFA_ENROLMENT_REQUIRED} (no TOTP yet, first sign-in) or {@link
 * #MFA_REQUIRED} (TOTP owed), unless a trusted device skips the code. Below {@code FULL} a session
 * reaches only the MFA endpoints, {@code /api/v1/auth/me} and sign-out; the role trees and every
 * other signed-in endpoint require {@code FULL}.
 */
public enum AuthLevel {
  /** Password checked; the supporter must set up TOTP before anything else. */
  MFA_ENROLMENT_REQUIRED,
  /** Password checked; a TOTP or recovery code is still owed. */
  MFA_REQUIRED,
  /** Fully signed in. */
  FULL;

  /** The Spring Security authority carried by a session at this level. */
  public String authority() {
    return "AUTH_LEVEL_" + name();
  }
}
