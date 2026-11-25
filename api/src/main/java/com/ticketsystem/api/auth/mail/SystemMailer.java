package com.ticketsystem.api.auth.mail;

import java.time.Instant;

/**
 * The auth module's outbound mail, one method per mail type. Callers are inside the transaction
 * that issues the token, so a mail is queued exactly when its token commits. Links carry the raw
 * token and must never be logged. Production: {@link OutboxSystemMailer} (outbox and templates);
 * tests may record calls instead.
 */
public interface SystemMailer {

  /** Supporter invitation link (48 h). The 6-digit code is never mailed. */
  void supporterInvitation(String to, String name, String link, Instant expiresAt);

  /** Customer invitation link (7 days). */
  void customerInvitation(String to, String name, String link, Instant expiresAt);

  /** Password reset link (15 minutes). */
  void passwordReset(String to, String name, String link, Instant expiresAt);

  /** Customer magic sign-in link (15 minutes, customers only). */
  void customerMagicLink(String to, String name, String link, Instant expiresAt);

  /** Confirmation to the first admin after setup; carries no token. */
  void firstAdminSetup(String to, String name, String signInLink);

  /** A security notice; carries no token. */
  void securityNotice(String to, String name, SecurityNotice notice);

  /** Security notice: a sign-in from a browser this user has not used before. */
  void newDeviceSignIn(String to, String name, String device);

  /**
   * Security notice: an admin paused two-step sign-in for this engineer until {@code endsAt}.
   * Carries no token and no reason text.
   */
  void mfaExemptionGranted(String to, String name, Instant endsAt);
}
