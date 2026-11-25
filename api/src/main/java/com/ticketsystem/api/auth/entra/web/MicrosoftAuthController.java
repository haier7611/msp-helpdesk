package com.ticketsystem.api.auth.entra.web;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.AuthLevel;
import com.ticketsystem.api.auth.AuthProperties;
import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.auth.device.DeviceService;
import com.ticketsystem.api.auth.entra.EntraSettingsService;
import com.ticketsystem.api.auth.entra.MicrosoftSignInService;
import com.ticketsystem.api.auth.entra.MicrosoftSignInService.Outcome;
import com.ticketsystem.api.auth.session.StepUp;
import com.ticketsystem.api.auth.web.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Sign in with Microsoft" under the public sign-in tree.
 *
 * <ul>
 *   <li>{@code GET /api/v1/auth/microsoft} - is it switched on (the sign-in page shows the button).
 *   <li>{@code POST /api/v1/auth/microsoft/sign-in} - starts a sign-in; answers the Microsoft URL
 *       the page sends the browser to.
 *   <li>{@code GET /api/v1/auth/microsoft/callback} - the redirect URI registered in Entra. Always
 *       answers a redirect to a web page; failures carry only a short word, never token content.
 *   <li>{@code GET|POST|DELETE /api/v1/auth/microsoft/link} - the signed-in user's link: status,
 *       start linking, unlink (FULL sessions only; supporters need step-up to link and unlink).
 * </ul>
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/auth/microsoft")
public class MicrosoftAuthController {

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_]{1,64}");

  public record Availability(boolean enabled) {}

  public record Redirect(String authorizationUrl) {}

  private final MicrosoftSignInService microsoft;
  private final EntraSettingsService settings;
  private final SessionAuthenticator sessions;
  private final DeviceService devices;
  private final UserRepository users;
  private final StepUp stepUp;
  private final AuthProperties props;

  public MicrosoftAuthController(
      MicrosoftSignInService microsoft,
      EntraSettingsService settings,
      SessionAuthenticator sessions,
      DeviceService devices,
      UserRepository users,
      StepUp stepUp,
      AuthProperties props) {
    this.microsoft = microsoft;
    this.settings = settings;
    this.sessions = sessions;
    this.devices = devices;
    this.users = users;
    this.stepUp = stepUp;
    this.props = props;
  }

  @GetMapping
  Availability availability() {
    return new Availability(settings.enabled());
  }

  @PostMapping("/sign-in")
  Redirect startSignIn(HttpServletRequest request) {
    URI url = microsoft.startSignIn(request.getSession(true), request.getRemoteAddr());
    return new Redirect(url.toString());
  }

  @GetMapping("/callback")
  ResponseEntity<Void> callback(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "error", required = false) String error,
      @AuthenticationPrincipal AuthenticatedUser principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    String ip = request.getRemoteAddr();
    Outcome outcome =
        microsoft.complete(
            request.getSession(false),
            principal == null ? null : principal.id(),
            state,
            code,
            error,
            devices.presentedTrustToken(request),
            ip);
    String target =
        switch (outcome) {
          case Outcome.SignedIn s -> {
            sessions.signIn(s.user(), s.level(), s.viaExemption(), request, response, ip);
            if (s.level() == AuthLevel.FULL) {
              devices.onFullSignIn(s.user(), request, response, ip);
            }
            yield switch (s.level()) {
              case FULL -> "/";
              case MFA_REQUIRED -> "/mfa/verify";
              case MFA_ENROLMENT_REQUIRED -> "/mfa/enrol";
            };
          }
          case Outcome.Linked l -> settingsPage(l.role()) + "?microsoft=linked";
          case Outcome.TestPassed t -> "/admin/sign-in?test=passed";
          case Outcome.Failed f ->
              switch (f.mode()) {
                case SIGN_IN -> "/sign-in?microsoft=" + safe(f.code());
                case LINK -> settingsPage(f.role()) + "?microsoft=" + safe(f.code());
                case TEST -> "/admin/sign-in?test=failed&code=" + safe(f.code());
              };
        };
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(props.page(target)))
        .header("Cache-Control", "no-store")
        .build();
  }

  @GetMapping("/link")
  MicrosoftSignInService.LinkStatus linkStatus(
      @AuthenticationPrincipal AuthenticatedUser principal) {
    return microsoft.status(current(principal));
  }

  @PostMapping("/link")
  Redirect startLink(
      @AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request) {
    AppUser user = current(principal);
    if (user.role().isSupporter()) {
      stepUp.require(request.getSession(false));
    }
    return new Redirect(microsoft.startLink(user, request.getSession(false)).toString());
  }

  @DeleteMapping("/link")
  ResponseEntity<Void> unlink(
      @AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request) {
    AppUser user = current(principal);
    if (user.role().isSupporter()) {
      stepUp.require(request.getSession(false));
    }
    microsoft.unlink(user, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  private AppUser current(AuthenticatedUser principal) {
    if (principal == null) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Please sign in.");
    }
    return users
        .findById(principal.id())
        .filter(AppUser::isActive)
        .orElseThrow(
            () -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Please sign in."));
  }

  private static String settingsPage(Role role) {
    return role == Role.CUSTOMER ? "/portal/account" : "/agent/settings/security";
  }

  private static String safe(String code) {
    return code != null && SAFE.matcher(code).matches() ? code : "failed";
  }
}
