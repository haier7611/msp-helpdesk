package com.ticketsystem.api.mail;

import java.util.Set;

/**
 * The system email templates: HTML in {@code mail-templates/html/<name>.html}, plain text in {@code
 * mail-templates/text/<name>.txt}, sharing one layout. Templates are code, not admin-editable, in
 * v1. Subjects take the support name as {@code %s}.
 */
public enum SystemEmail {
  /**
   * Supporter invitation: link valid 48 h; the 6-digit code is given by the admin, never mailed.
   */
  SUPPORTER_INVITATION(
      "supporter-invitation",
      "You have been invited to %s",
      true,
      Set.of("recipientName", "link", "validFor")),
  /** Customer invitation: link valid 7 days. */
  CUSTOMER_INVITATION(
      "customer-invitation",
      "Your %s account is ready to set up",
      true,
      Set.of("recipientName", "organisationName", "link", "validFor")),
  /** Password reset: link valid 15 minutes. */
  PASSWORD_RESET(
      "password-reset",
      "Reset your %s password",
      true,
      Set.of("recipientName", "link", "validFor")),
  /** Customer magic link: link valid 15 minutes (customers only). */
  CUSTOMER_MAGIC_LINK(
      "customer-magic-link",
      "Your %s sign-in link",
      true,
      Set.of("recipientName", "link", "validFor")),
  /** Confirmation to the first admin after setup; no token. */
  FIRST_ADMIN_SETUP(
      "first-admin-setup",
      "Your %s administrator account is ready",
      false,
      Set.of("recipientName", "signInLink")),
  /**
   * Security notice: the password was changed or reset. No token; the link is the "forgot password"
   * page in case the change was not made by the recipient.
   */
  PASSWORD_CHANGED(
      "password-changed", "Your %s password was changed", false, Set.of("recipientName", "link")),
  /** Security notice: two-step sign-in was set up or replaced. No token. */
  MFA_ENROLLED(
      "mfa-enrolled",
      "Two-step sign-in was set up on your %s account",
      false,
      Set.of("recipientName", "link")),
  /** Security notice: an admin reset two-step sign-in. No token. */
  MFA_RESET(
      "mfa-reset",
      "Two-step sign-in was reset on your %s account",
      false,
      Set.of("recipientName", "link")),
  /**
   * Security notice: a sign-in from a browser not used before. The device label comes from the
   * User-Agent (escaped like every variable). No token.
   */
  NEW_DEVICE_SIGN_IN(
      "new-device-sign-in",
      "New sign-in to your %s account",
      false,
      Set.of("recipientName", "device", "link")),
  /**
   * Security notice: an admin paused two-step sign-in for this engineer until a date. No token, no
   * reason text.
   */
  MFA_EXEMPTION_GRANTED(
      "mfa-exemption-granted",
      "Two-step sign-in was paused on your %s account",
      false,
      Set.of("recipientName", "endsAt", "link")),
  /** Security notice: an admin changed the user's role. No token. */
  ROLE_CHANGED(
      "role-changed", "Your role on %s was changed", false, Set.of("recipientName", "link")),
  /** Security notice: an admin deactivated the account. No token. */
  ACCOUNT_DEACTIVATED(
      "account-deactivated", "Your %s account was deactivated", false, Set.of("recipientName")),
  /**
   * Agent-only (sent as INTERNAL, the subject is set by the caller): a notification about a ticket
   * per the supporter's preferences, or a P1 alert.
   */
  SUPPORTER_NOTIFICATION(
      "supporter-notification",
      "%s notification",
      false,
      Set.of("recipientName", "headline", "ticketSubject", "link")),
  /** Agent-only: the optional daily digest at 08:00 in the supporter's timezone. */
  DAILY_DIGEST(
      "daily-digest",
      "Your %s daily digest",
      false,
      Set.of("recipientName", "summary", "items", "link")),
  /**
   * Client email matrix: Pending-on-client reminder. Sent as CLIENT_FACING with the ticket
   * reference; never mentions SLA.
   */
  CLIENT_PENDING_REMINDER(
      "client-pending-reminder",
      "We are waiting for your reply - %s",
      false,
      Set.of("ticketSubject")),
  /** Client email matrix: Closed notice after the auto-close. */
  CLIENT_TICKET_CLOSED(
      "client-ticket-closed", "Your request has been closed - %s", false, Set.of("ticketSubject"));

  private final String templateName;
  private final String subjectFormat;
  private final boolean sensitive;
  private final Set<String> requiredVariables;

  SystemEmail(
      String templateName, String subjectFormat, boolean sensitive, Set<String> requiredVariables) {
    this.templateName = templateName;
    this.subjectFormat = subjectFormat;
    this.sensitive = sensitive;
    this.requiredVariables = requiredVariables;
  }

  public String templateName() {
    return templateName;
  }

  /** True when the mail carries a live single-use token (bodies blanked after sending). */
  public boolean sensitive() {
    return sensitive;
  }

  public Set<String> requiredVariables() {
    return requiredVariables;
  }

  String subject(String supportName) {
    return subjectFormat.formatted(supportName);
  }
}
