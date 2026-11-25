package com.ticketsystem.api.auth.mail;

/**
 * Security notice emails that carry nothing but the fact. {@link #PASSWORD_CHANGED} covers password
 * change and reset; {@link #MFA_ENROLLED} and {@link #MFA_RESET} cover MFA, and the new-device
 * notice ({@link SystemMailer#newDeviceSignIn}). Role changed and account deactivated are sent by
 * the code that performs those actions.
 */
public enum SecurityNotice {
  PASSWORD_CHANGED,
  /** Two-step sign-in was set up or replaced (new authenticator). */
  MFA_ENROLLED,
  /** An admin (or the break-glass command) reset the user's two-step sign-in. */
  MFA_RESET,
  /** An admin changed the user's role. */
  ROLE_CHANGED,
  /** An admin deactivated the account. */
  ACCOUNT_DEACTIVATED
}
