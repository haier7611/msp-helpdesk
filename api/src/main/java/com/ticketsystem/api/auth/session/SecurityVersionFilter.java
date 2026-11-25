package com.ticketsystem.api.auth.session;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.AuthLevel;
import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.SessionKeys;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.auth.UserStatus;
import com.ticketsystem.api.auth.mfa.MfaAuditEvent;
import com.ticketsystem.api.auth.mfa.MfaExemptionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a signed-in session on its next request - before the security context is loaded, so the
 * request carries on as anonymous and gets 401 on anything protected - when:
 *
 * <ul>
 *   <li>the user's security_version no longer matches the one stamped in the session (password,
 *       MFA, role, organisation change, admin sign-out; C4);
 *   <li>the user is no longer active, or their role differs from the session's (deactivation and
 *       role changes take effect at once even if a caller forgot to bump the version);
 *   <li>the session is still below {@link AuthLevel#FULL} ten minutes after the password step;
 *   <li>the session skipped two-step only because of an engineer's exemption ({@link
 *       SessionKeys#MFA_EXEMPT}) and that exemption no longer applies (ended, revoked, role or
 *       status changed). The engineer signs in again and completes two-step.
 * </ul>
 *
 * One indexed primary-key read of {@code app_user} per signed-in request, through {@link
 * UserRepository} (the auth module's allow-listed repository, so this filter needs no JDBC).
 * Created by SecurityConfiguration and added to the security filter chain only - deliberately not a
 * bean.
 */
public class SecurityVersionFilter extends OncePerRequestFilter {

  /** How long a supporter has to finish the TOTP or enrolment step after the password. */
  public static final Duration PENDING_MFA_TIMEOUT = Duration.ofMinutes(10);

  private final UserRepository users;
  private final MfaExemptionService exemptions;
  private final AuditService audit;
  private final Clock clock;

  public SecurityVersionFilter(
      UserRepository users, MfaExemptionService exemptions, AuditService audit, Clock clock) {
    this.users = users;
    this.exemptions = exemptions;
    this.audit = audit;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    if (session != null) {
      AuthenticatedUser user = principal(session);
      if (user != null) {
        String reason = invalidReason(session, user);
        if (reason != null) {
          session.invalidate();
          audit.record(
              MfaAuditEvent.SESSION_INVALIDATED,
              user.id(),
              user.id(),
              request.getRemoteAddr(),
              Map.of("reason", reason));
        }
      }
    }
    chain.doFilter(request, response);
  }

  private String invalidReason(HttpSession session, AuthenticatedUser user) {
    Object level = session.getAttribute(SessionKeys.AUTH_LEVEL);
    if (!AuthLevel.FULL.name().equals(level)) {
      Object signInAt = session.getAttribute(SessionKeys.SIGN_IN_AT);
      if (!(signInAt instanceof Long millis)
          || clock.millis() - millis >= PENDING_MFA_TIMEOUT.toMillis()) {
        return "mfa_timeout";
      }
    }
    Optional<AppUser> state = users.findById(user.id());
    if (state.isEmpty() || state.get().status() != UserStatus.ACTIVE) {
      return "not_active";
    }
    if (state.get().role() != user.role()) {
      return "role_changed";
    }
    Object stamped = session.getAttribute(SessionKeys.SECURITY_VERSION);
    if (!Objects.equals(stamped, state.get().securityVersion())) {
      return "security_version";
    }
    // Only sessions that skipped two-step because of an exemption pay for this read.
    if (Boolean.TRUE.equals(session.getAttribute(SessionKeys.MFA_EXEMPT))
        && exemptions.activeUntil(state.get()).isEmpty()) {
      return "mfa_exemption_ended";
    }
    return null;
  }

  private static AuthenticatedUser principal(HttpSession session) {
    Object ctx =
        session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    if (ctx instanceof SecurityContext sc
        && sc.getAuthentication() != null
        && sc.getAuthentication().getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    return null;
  }
}
