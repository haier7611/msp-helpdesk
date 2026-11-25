package com.ticketsystem.api.auth.config;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.AuthAuditEvent;
import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.SessionKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session 24 hours after sign-in even when it is in constant use (C3). Spring Session only
 * has an idle limit (8 h, {@code spring.session.timeout}), so the sign-in time is kept in the
 * session and checked here, before the security context is loaded: an expired session is
 * invalidated and the request carries on as anonymous (401 on anything protected).
 *
 * <p>Created by {@link SecurityConfiguration} and added to the security filter chain only - it is
 * deliberately not a bean, so Boot does not also register it as a servlet filter.
 */
class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

  private final Clock clock;
  private final Duration absoluteTimeout;
  private final AuditService audit;

  AbsoluteSessionTimeoutFilter(Clock clock, Duration absoluteTimeout, AuditService audit) {
    this.clock = clock;
    this.absoluteTimeout = absoluteTimeout;
    this.audit = audit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    if (session != null) {
      Object signInAt = session.getAttribute(SessionKeys.SIGN_IN_AT);
      if (signInAt instanceof Long millis
          && clock.millis() - millis >= absoluteTimeout.toMillis()) {
        Long userId = userId(session);
        session.invalidate();
        audit.record(
            AuthAuditEvent.SESSION_EXPIRED,
            userId,
            userId,
            request.getRemoteAddr(),
            Map.of("reason", "absolute_timeout"));
      }
    }
    chain.doFilter(request, response);
  }

  private static Long userId(HttpSession session) {
    Object ctx =
        session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    if (ctx instanceof SecurityContext sc
        && sc.getAuthentication() != null
        && sc.getAuthentication().getPrincipal() instanceof AuthenticatedUser user) {
      return user.id();
    }
    return null;
  }
}
