package com.ticketsystem.api.auth.session;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.device.DeviceService;
import com.ticketsystem.api.auth.mfa.MfaAuditEvent;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * security_version (C4): every change to a user's security increments it and deletes all their
 * other sessions at once, in the same transaction as the change. The filter ({@link
 * SecurityVersionFilter}) also compares it on every request, so a session that survived the delete
 * (for example a request already in flight) is refused on its next request.
 *
 * <p>Callers: password change and reset (wired here), MFA enrolment, re-enrolment and reset, admin
 * "sign out everywhere"; role, organisation access and deactivation call {@link #bump}. Trusted
 * devices are revoked too for password, MFA, role and deactivation.
 */
@Service
public class SecurityVersionService {

  /** Why the version moved; decides whether trusted devices are revoked. */
  public enum Reason {
    PASSWORD(true),
    MFA(true),
    ROLE(true),
    DEACTIVATION(true),
    ORGANISATION(false),
    ADMIN_SIGN_OUT(false),
    /** An admin ended an engineer's two-step exemption; trusted devices stay. */
    MFA_EXEMPTION(false);

    private final boolean revokesTrustedDevices;

    Reason(boolean revokesTrustedDevices) {
      this.revokesTrustedDevices = revokesTrustedDevices;
    }

    public boolean revokesTrustedDevices() {
      return revokesTrustedDevices;
    }
  }

  private final JdbcClient jdbc;
  private final DeviceService devices;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  public SecurityVersionService(
      JdbcClient jdbc,
      DeviceService devices,
      AuditService audit,
      TransactionTemplate tx,
      Clock clock) {
    this.jdbc = jdbc;
    this.devices = devices;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Increments the user's security_version, deletes every session of theirs except {@code
   * keepSessionId} (null = delete all), and revokes trusted devices when the reason says so.
   * Returns the new version; the caller re-stamps the kept session with it. Joins the caller's
   * transaction when there is one.
   */
  public int bump(
      long userId, Reason reason, String keepSessionId, Long actorUserId, String clientIp) {
    return tx.execute(
        status -> {
          int version =
              jdbc.sql(
                      "UPDATE app_user SET security_version = security_version + 1, updated_at = ?"
                          + " WHERE id = ? RETURNING security_version")
                  .params(Timestamp.from(clock.instant()), userId)
                  .query(Integer.class)
                  .single();
          afterVersionChanged(userId, reason, keepSessionId, actorUserId, clientIp);
          return version;
        });
  }

  /**
   * For callers that already incremented the version in their own update (password change and
   * reset): ends the other sessions and revokes trusted devices as {@link #bump} does.
   */
  public void afterVersionChanged(
      long userId, Reason reason, String keepSessionId, Long actorUserId, String clientIp) {
    tx.executeWithoutResult(
        status -> {
          int ended = deleteSessions(userId, keepSessionId);
          int untrusted = reason.revokesTrustedDevices() ? devices.untrustAll(userId) : 0;
          audit.record(
              MfaAuditEvent.SECURITY_VERSION_BUMPED,
              actorUserId,
              userId,
              clientIp,
              Map.of("reason", reason, "sessions", ended, "devices", untrusted));
        });
  }

  /** Deletes the user's sessions except one; returns how many were deleted. */
  int deleteSessions(long userId, String keepSessionId) {
    return jdbc.sql(
            "DELETE FROM spring_session WHERE principal_name = ? AND session_id IS DISTINCT FROM ?")
        .params(Long.toString(userId), keepSessionId)
        .update();
  }

  /** The current security_version of a user, for re-stamping a kept session. */
  public int current(long userId) {
    return jdbc.sql("SELECT security_version FROM app_user WHERE id = ?")
        .param(userId)
        .query(Integer.class)
        .single();
  }
}
