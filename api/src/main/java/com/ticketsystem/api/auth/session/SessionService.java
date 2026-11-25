package com.ticketsystem.api.auth.session;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.SecureTokens;
import com.ticketsystem.api.auth.SessionKeys;
import com.ticketsystem.api.auth.mfa.MfaAuditEvent;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * "Active sessions" in My settings - Security: a user lists only their own sessions (found by
 * principal name = their user id) and can end one or all others. Sessions are identified to the
 * browser by a handle - a hash of the session id - never by the id itself, which is the cookie
 * value.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
public class SessionService {

  /** One of the user's sessions as listed. */
  public record SessionInfo(
      String id,
      Instant createdAt,
      Instant lastActiveAt,
      String ipAddress,
      String device,
      String authLevel,
      boolean current) {}

  private final FindByIndexNameSessionRepository<? extends Session> sessions;
  private final SecurityVersionService securityVersion;
  private final AuditService audit;

  public SessionService(
      FindByIndexNameSessionRepository<? extends Session> sessions,
      SecurityVersionService securityVersion,
      AuditService audit) {
    this.sessions = sessions;
    this.securityVersion = securityVersion;
    this.audit = audit;
  }

  static String handle(String sessionId) {
    return SecureTokens.sha256Hex("session:" + sessionId).substring(0, 32);
  }

  public List<SessionInfo> list(long userId, String currentSessionId) {
    return sessions.findByPrincipalName(Long.toString(userId)).values().stream()
        .map(
            s ->
                new SessionInfo(
                    handle(s.getId()),
                    s.getCreationTime(),
                    s.getLastAccessedTime(),
                    s.getAttribute(SessionKeys.CLIENT_IP),
                    s.getAttribute(SessionKeys.DEVICE_LABEL),
                    s.getAttribute(SessionKeys.AUTH_LEVEL),
                    s.getId().equals(currentSessionId)))
        .sorted(
            Comparator.comparing(SessionInfo::current)
                .reversed()
                .thenComparing(SessionInfo::lastActiveAt, Comparator.reverseOrder()))
        .toList();
  }

  /**
   * Ends one of the user's own sessions by handle. Returns true when it was found; a handle of
   * another user's session is simply not found (the lookup is by the caller's principal name).
   */
  public boolean signOut(long userId, String handle, String clientIp) {
    for (String id : sessions.findByPrincipalName(Long.toString(userId)).keySet()) {
      if (SecureTokens.constantTimeEquals(handle(id), handle)) {
        sessions.deleteById(id);
        audit.record(
            MfaAuditEvent.SESSION_REVOKED, userId, userId, clientIp, Map.of("reason", "one"));
        return true;
      }
    }
    return false;
  }

  /** Ends every session of the user except the current one; returns how many. */
  public int signOutOthers(long userId, String currentSessionId, String clientIp) {
    int ended = securityVersion.deleteSessions(userId, currentSessionId);
    audit.record(
        MfaAuditEvent.SESSION_REVOKED,
        userId,
        userId,
        clientIp,
        Map.of("reason", "others", "sessions", ended));
    return ended;
  }
}
