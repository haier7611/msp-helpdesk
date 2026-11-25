package com.ticketsystem.api.auth.web;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.AuthLevel;
import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.SessionKeys;
import com.ticketsystem.api.auth.device.DeviceLabels;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Turns a verified user into a server-side session, and ends one. At sign-in the session id is
 * changed (fixation), the CSRF token is replaced, and the sign-in time, auth level,
 * security_version, client IP and browser label are stored for the absolute limit, the
 * security_version check and the session list.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Component
public class SessionAuthenticator {

  private final SecurityContextRepository contextRepository;
  private final SessionAuthenticationStrategy sessionStrategy;
  private final CsrfTokenRepository csrfTokenRepository;
  private final Clock clock;

  public SessionAuthenticator(
      SecurityContextRepository contextRepository,
      SessionAuthenticationStrategy sessionStrategy,
      CsrfTokenRepository csrfTokenRepository,
      Clock clock) {
    this.contextRepository = contextRepository;
    this.sessionStrategy = sessionStrategy;
    this.csrfTokenRepository = csrfTokenRepository;
    this.clock = clock;
  }

  /**
   * Starts (or raises) the session at {@code level}. Called again with {@link AuthLevel#FULL} after
   * the MFA step, which changes the session id once more (privilege change) and re-stamps the
   * user's current security_version.
   */
  public void signIn(
      AppUser user,
      AuthLevel level,
      HttpServletRequest request,
      HttpServletResponse response,
      String clientIp) {
    signIn(user, level, false, request, response, clientIp);
  }

  /**
   * As {@link #signIn(AppUser, AuthLevel, HttpServletRequest, HttpServletResponse, String)}; {@code
   * viaExemption} marks a session that is FULL only because of a two-step exemption, which {@code
   * SecurityVersionFilter} then re-checks on every request.
   */
  public void signIn(
      AppUser user,
      AuthLevel level,
      boolean viaExemption,
      HttpServletRequest request,
      HttpServletResponse response,
      String clientIp) {
    Authentication auth =
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUser(user.id(), user.role()),
            null,
            List.of(
                new SimpleGrantedAuthority(user.role().authority()),
                new SimpleGrantedAuthority(level.authority())));
    sessionStrategy.onAuthentication(auth, request, response);
    // The CSRF strategy dropped the old token and left a deferred new one; load it now so the new
    // cookie goes out with this response and the web app can make its next POST straight away.
    if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken fresh) {
      fresh.getToken();
    }
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    HttpSession session = request.getSession(true);
    session.setAttribute(SessionKeys.SIGN_IN_AT, clock.millis());
    session.setAttribute(SessionKeys.AUTH_LEVEL, level.name());
    session.setAttribute(SessionKeys.SECURITY_VERSION, user.securityVersion());
    session.setAttribute(SessionKeys.CLIENT_IP, clientIp);
    session.setAttribute(
        SessionKeys.DEVICE_LABEL, DeviceLabels.fromUserAgent(request.getHeader("User-Agent")));
    session.removeAttribute(SessionKeys.STEP_UP_AT);
    if (viaExemption && level == AuthLevel.FULL) {
      session.setAttribute(SessionKeys.MFA_EXEMPT, Boolean.TRUE);
    } else {
      session.removeAttribute(SessionKeys.MFA_EXEMPT);
    }
    contextRepository.saveContext(context, request, response);
  }

  public void signOut(HttpServletRequest request, HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    csrfTokenRepository.saveToken(null, request, response);
  }
}
