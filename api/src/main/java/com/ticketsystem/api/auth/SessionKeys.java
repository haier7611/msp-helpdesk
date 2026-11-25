package com.ticketsystem.api.auth;

/** Session attribute names set at sign-in. Values are simple serialisable types. */
public final class SessionKeys {

  /** Epoch milliseconds of the sign-in; the 24 h absolute limit counts from here. */
  public static final String SIGN_IN_AT = "ts.signInAt";

  /** {@link AuthLevel} name. */
  public static final String AUTH_LEVEL = "ts.authLevel";

  /** The user's security_version at sign-in (Integer); compared on every request. */
  public static final String SECURITY_VERSION = "ts.securityVersion";

  /** Epoch milliseconds of the last step-up (fresh TOTP or recovery code); valid 10 minutes. */
  public static final String STEP_UP_AT = "ts.stepUpAt";

  /**
   * Boolean.TRUE when the session is FULL only because of a two-step exemption; such a session ends
   * on its next request once the exemption has ended or been revoked.
   */
  public static final String MFA_EXEMPT = "ts.mfaExempt";

  /** Client IP at sign-in (String), for the session list. */
  public static final String CLIENT_IP = "ts.clientIp";

  /** Browser label from the User-Agent at sign-in (String), for the session list. */
  public static final String DEVICE_LABEL = "ts.device";

  private SessionKeys() {}
}
