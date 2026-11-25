package com.ticketsystem.api.auth.web;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.AuthAuditEvent;
import com.ticketsystem.api.auth.AuthLevel;
import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.InvitationService;
import com.ticketsystem.api.auth.LinkRequestService;
import com.ticketsystem.api.auth.PasswordChangeService;
import com.ticketsystem.api.auth.SessionKeys;
import com.ticketsystem.api.auth.SetupService;
import com.ticketsystem.api.auth.SignInService;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.auth.device.DeviceService;
import com.ticketsystem.api.auth.mfa.MfaExemptionService;
import com.ticketsystem.api.auth.mfa.MfaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public sign-in tree {@code /api/v1/auth}. Every POST needs the CSRF header, sign-in included.
 * Link tokens arrive in request bodies (the web pages read them from the URL fragment), never in
 * URLs, so they stay out of access logs.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

  /**
   * The signed-in user as the web app sees it. {@code mfaExemptUntil} is set only for an engineer
   * whose two-step exemption applies now: the banner and the password step-up use it.
   */
  public record Me(
      long id, String email, String name, String role, String authLevel, Instant mfaExemptUntil) {
    static Me of(AppUser u, String authLevel) {
      return of(u, authLevel, null);
    }

    static Me of(AppUser u, String authLevel, Instant mfaExemptUntil) {
      return new Me(u.id(), u.email(), u.name(), u.role().name(), authLevel, mfaExemptUntil);
    }
  }

  public record SignInRequest(
      @NotNull @Size(max = 320) String email, @NotNull @Size(max = 1024) String password) {
    @Override
    public String toString() {
      return "SignInRequest[]";
    }
  }

  public record EmailRequest(@NotNull @Size(max = 320) String email) {}

  public record TokenRequest(@NotNull @Size(max = 100) String token) {
    @Override
    public String toString() {
      return "TokenRequest[]";
    }
  }

  public record ResetConfirmRequest(
      @NotNull @Size(max = 100) String token, @NotNull @Size(max = 1024) String password) {
    @Override
    public String toString() {
      return "ResetConfirmRequest[]";
    }
  }

  public record AcceptInvitationRequest(
      @NotNull @Size(max = 100) String token,
      // Ignored (invitations no longer have a code); still accepted so older clients do not break.
      @Size(max = 20) String code,
      @NotNull @Size(max = 1024) String password) {
    @Override
    public String toString() {
      return "AcceptInvitationRequest[]";
    }
  }

  public record PasswordChangeRequest(
      @NotNull @Size(max = 1024) String currentPassword,
      @NotNull @Size(max = 1024) String newPassword) {
    @Override
    public String toString() {
      return "PasswordChangeRequest[]";
    }
  }

  public record SetupRequest(
      @NotNull @Size(max = 200) String token,
      @NotNull @Size(max = 320) String email,
      @NotNull @Size(max = 200) String name,
      @NotNull @Size(max = 1024) String password) {
    @Override
    public String toString() {
      return "SetupRequest[]";
    }
  }

  /** The same body for every link request, whatever happened. */
  static final Map<String, String> LINK_REQUEST_ACCEPTED =
      Map.of(
          "message",
          "If that email address can use this, we have sent a link to it. Check your inbox.");

  private final SignInService signIn;
  private final LinkRequestService links;
  private final InvitationService invitations;
  private final SetupService setup;
  private final PasswordChangeService passwordChange;
  private final SessionAuthenticator sessions;
  private final UserRepository users;
  private final AuditService audit;
  private final MfaService mfa;
  private final DeviceService devices;
  private final MfaExemptionService exemptions;

  public AuthController(
      SignInService signIn,
      LinkRequestService links,
      InvitationService invitations,
      SetupService setup,
      PasswordChangeService passwordChange,
      SessionAuthenticator sessions,
      UserRepository users,
      AuditService audit,
      MfaService mfa,
      DeviceService devices,
      MfaExemptionService exemptions) {
    this.signIn = signIn;
    this.links = links;
    this.invitations = invitations;
    this.setup = setup;
    this.passwordChange = passwordChange;
    this.sessions = sessions;
    this.users = users;
    this.audit = audit;
    this.mfa = mfa;
    this.devices = devices;
    this.exemptions = exemptions;
  }

  /** Issues the CSRF cookie; the web app calls it once before its first POST. */
  @GetMapping("/csrf")
  ResponseEntity<Void> csrf(CsrfToken token) {
    token.getToken(); // loading the deferred token writes the cookie
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/sign-in")
  Me signIn(
      @Validated @RequestBody SignInRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    String ip = request.getRemoteAddr();
    String deviceToken = devices.presentedToken(request);
    AppUser user = signIn.signIn(body.email(), body.password(), ip, deviceToken);
    // Supporters stop at an MFA level unless this browser is one of their trusted devices;
    // Or they are an engineer with a two-step exemption that applies now.
    MfaService.FirstFactorLevel first =
        mfa.levelAfterPassword(user, devices.presentedTrustToken(request), ip);
    AuthLevel level = first.level();
    sessions.signIn(user, level, first.viaExemption(), request, response, ip);
    if (level == AuthLevel.FULL) {
      devices.onFullSignIn(user, request, response, ip);
    }
    return Me.of(user, level.name(), exemptions.activeUntil(user).orElse(null));
  }

  @PostMapping("/sign-out")
  ResponseEntity<Void> signOut(
      @AuthenticationPrincipal AuthenticatedUser principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (principal != null) {
      audit.record(
          AuthAuditEvent.SIGN_OUT,
          principal.id(),
          principal.id(),
          request.getRemoteAddr(),
          Map.of());
    }
    sessions.signOut(request, response);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  ResponseEntity<?> me(
      @AuthenticationPrincipal AuthenticatedUser principal,
      CsrfToken csrf,
      HttpServletRequest request,
      HttpServletResponse response) {
    csrf.getToken();
    AppUser user =
        principal == null
            ? null
            : users.findById(principal.id()).filter(AppUser::isActive).orElse(null);
    if (user == null) {
      // Deactivated or deleted since sign-in: end the session.
      sessions.signOut(request, response);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new ApiExceptionHandler.ApiError("unauthenticated", "Please sign in."));
    }
    HttpSession session = request.getSession(false);
    Object level = session == null ? null : session.getAttribute(SessionKeys.AUTH_LEVEL);
    return ResponseEntity.ok(
        Me.of(
            user,
            level == null ? AuthLevel.FULL.name() : level.toString(),
            exemptions.activeUntil(user).orElse(null)));
  }

  @PostMapping("/password/change")
  ResponseEntity<Void> changePassword(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @Validated @RequestBody PasswordChangeRequest body,
      HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    int version =
        passwordChange.change(
            principal.id(),
            body.currentPassword(),
            body.newPassword(),
            request.getRemoteAddr(),
            session == null ? null : session.getId());
    if (session != null) {
      // This session made the change: it is kept and re-stamped; every other session was ended.
      session.setAttribute(SessionKeys.SECURITY_VERSION, version);
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/password-reset/request")
  ResponseEntity<Map<String, String>> requestReset(
      @Validated @RequestBody EmailRequest body, HttpServletRequest request) {
    links.requestPasswordReset(body.email(), request.getRemoteAddr());
    return ResponseEntity.accepted().body(LINK_REQUEST_ACCEPTED);
  }

  @PostMapping("/password-reset/confirm")
  ResponseEntity<Void> confirmReset(
      @Validated @RequestBody ResetConfirmRequest body, HttpServletRequest request) {
    links.confirmPasswordReset(body.token(), body.password(), request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/magic-link/request")
  ResponseEntity<Map<String, String>> requestMagicLink(
      @Validated @RequestBody EmailRequest body, HttpServletRequest request) {
    links.requestMagicLink(body.email(), request.getRemoteAddr());
    return ResponseEntity.accepted().body(LINK_REQUEST_ACCEPTED);
  }

  /** POST, not GET: mail scanners that pre-fetch links must not spend the token. */
  @PostMapping("/magic-link/consume")
  Me consumeMagicLink(
      @Validated @RequestBody TokenRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    String ip = request.getRemoteAddr();
    AppUser user = links.consumeMagicLink(body.token(), ip);
    sessions.signIn(user, AuthLevel.FULL, request, response, ip);
    devices.onFullSignIn(user, request, response, ip);
    return Me.of(user, AuthLevel.FULL.name());
  }

  @PostMapping("/invitations/inspect")
  InvitationService.Pending inspectInvitation(
      @Validated @RequestBody TokenRequest body, HttpServletRequest request) {
    return invitations.inspect(body.token(), request.getRemoteAddr());
  }

  @PostMapping("/invitations/accept")
  ResponseEntity<Void> acceptInvitation(
      @Validated @RequestBody AcceptInvitationRequest body, HttpServletRequest request) {
    invitations.accept(body.token(), body.password(), request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/setup")
  Map<String, Boolean> setupStatus() {
    return Map.of("available", setup.available());
  }

  @PostMapping("/setup")
  ResponseEntity<Me> completeSetup(
      @Validated @RequestBody SetupRequest body, HttpServletRequest request) {
    AppUser admin =
        setup.createFirstAdmin(
            body.token(), body.email(), body.name(), body.password(), request.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.CREATED).body(Me.of(admin, null));
  }
}
