package com.ticketsystem.api.access;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.AuthAuditEvent;
import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.SessionKeys;
import com.ticketsystem.api.auth.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Builds the request's {@link AccessScope} from the signed-in user's current database row (C13) and
 * stores it as a request attribute for {@link AccessScopeArgumentResolver}.
 *
 * <p>The same read also ends a session whose user changed since sign-in (security test 4): if the
 * user is gone or not active, their role changed, or their security_version differs from the one
 * stored in the session at sign-in (bumped on password, organisation or supporter-access change),
 * the session is invalidated and the request carries on anonymous, so it gets 401. MFA reset and
 * deactivation extend this check rather than adding a second one.
 *
 * <p>Created by SecurityConfiguration and added to the security filter chain only, after the
 * security context is loaded and before the anonymous filter - deliberately not a bean, so Boot
 * does not also register it as a servlet filter.
 */
public class AccessScopeFilter extends OncePerRequestFilter {

  private final PrincipalAccessReader reader;
  private final AuditService audit;

  public AccessScopeFilter(PrincipalAccessReader reader, AuditService audit) {
    this.reader = reader;
    this.audit = audit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
      HttpSession session = request.getSession(false);
      Optional<PrincipalAccessReader.PrincipalAccess> access = reader.read(user.id());
      String stale = staleReason(user, access, session);
      if (stale != null) {
        if (session != null) {
          session.invalidate();
        }
        SecurityContextHolder.clearContext();
        audit.record(
            AuthAuditEvent.SESSION_EXPIRED,
            user.id(),
            user.id(),
            request.getRemoteAddr(),
            Map.of("reason", stale));
      } else {
        request.setAttribute(AccessScope.REQUEST_ATTRIBUTE, access.get().toScope());
      }
    }
    chain.doFilter(request, response);
  }

  /** Why the session no longer matches its user, or null when it still does. */
  private static String staleReason(
      AuthenticatedUser user,
      Optional<PrincipalAccessReader.PrincipalAccess> access,
      HttpSession session) {
    if (access.isEmpty() || access.get().status() != UserStatus.ACTIVE) {
      return "user_not_active";
    }
    if (access.get().role() != user.role()) {
      return "role_changed";
    }
    Object version = session == null ? null : session.getAttribute(SessionKeys.SECURITY_VERSION);
    if (!(version instanceof Integer v) || v != access.get().securityVersion()) {
      return "security_version_changed";
    }
    return null;
  }
}
