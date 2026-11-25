package com.ticketsystem.api.auth.web;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.AuthLevel;
import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.SessionKeys;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.auth.device.DeviceService;
import com.ticketsystem.api.auth.mfa.MfaService;
import com.ticketsystem.api.auth.mfa.QrCode;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import com.ticketsystem.api.auth.session.StepUp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TOTP endpoints. Which session level may call which endpoint is set in SecurityConfiguration:
 * {@code mfa/verify} and {@code mfa/recovery} need {@code MFA_REQUIRED}; {@code
 * mfa/enrolment[/confirm]} need {@code MFA_ENROLMENT_REQUIRED} (first sign-in) or {@code FULL}
 * (re-enrolment, with step-up); the rest need {@code FULL}. Codes travel in POST bodies only.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class MfaController {

  public record CodeRequest(@Size(max = 20) String code, Boolean trustDevice) {
    @Override
    public String toString() {
      return "CodeRequest[]";
    }
  }

  public record RecoveryRequest(@NotNull @Size(max = 40) String recoveryCode, Boolean trustDevice) {
    @Override
    public String toString() {
      return "RecoveryRequest[]";
    }
  }

  /**
   * Exactly one of: a TOTP {@code code}, a {@code recoveryCode}, or - only for an engineer with a
   * two-step exemption that applies now - their {@code password}.
   */
  public record StepUpRequest(
      @Size(max = 20) String code,
      @Size(max = 40) String recoveryCode,
      @Size(max = 1024) String password) {
    @Override
    public String toString() {
      return "StepUpRequest[]";
    }
  }

  public record EnrolmentResponse(String manualKey, String otpauthUri, int qrSize, String qrPath) {
    static EnrolmentResponse of(MfaService.Enrolment e) {
      QrCode qr = e.qr();
      return new EnrolmentResponse(e.manualKey(), e.otpauthUri(), qr.size(), qr.path());
    }

    @Override
    public String toString() {
      return "EnrolmentResponse[]";
    }
  }

  public record RecoveryCodesResponse(List<String> recoveryCodes, AuthController.Me me) {
    @Override
    public String toString() {
      return "RecoveryCodesResponse[]";
    }
  }

  public record SignedInResponse(AuthController.Me me, Integer recoveryCodesLeft) {}

  public record StepUpResponse(Integer recoveryCodesLeft) {}

  private final MfaService mfa;
  private final UserRepository users;
  private final DeviceService devices;
  private final SessionAuthenticator sessions;
  private final StepUp stepUp;

  public MfaController(
      MfaService mfa,
      UserRepository users,
      DeviceService devices,
      SessionAuthenticator sessions,
      StepUp stepUp) {
    this.mfa = mfa;
    this.users = users;
    this.devices = devices;
    this.sessions = sessions;
    this.stepUp = stepUp;
  }

  @GetMapping("/mfa")
  MfaService.Status status(@AuthenticationPrincipal AuthenticatedUser principal) {
    return mfa.status(principal.id());
  }

  /** New secret and QR code. Re-enrolling (already enrolled) needs step-up. */
  @PostMapping("/mfa/enrolment")
  EnrolmentResponse startEnrolment(
      @AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request) {
    AppUser user = activeUser(principal);
    guardEnrolment(user, request.getSession(false));
    return EnrolmentResponse.of(mfa.startEnrolment(user, request.getRemoteAddr()));
  }

  /**
   * Confirms enrolment with a code from the new authenticator and returns the 10 recovery codes
   * once. A first enrolment raises the session to FULL; every other session of the user ends.
   */
  @PostMapping("/mfa/enrolment/confirm")
  RecoveryCodesResponse confirmEnrolment(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @Validated @RequestBody CodeRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    AppUser user = activeUser(principal);
    HttpSession session = request.getSession(false);
    boolean firstEnrolment = guardEnrolment(user, session);
    String ip = request.getRemoteAddr();
    List<String> codes =
        mfa.confirmEnrolment(user, body.code(), session.getId(), knownDevice(user, request), ip);
    AppUser fresh = activeUser(principal);
    if (firstEnrolment) {
      sessions.signIn(fresh, AuthLevel.FULL, request, response, ip);
      devices.onFullSignIn(fresh, request, response, ip);
    } else {
      request.getSession().setAttribute(SessionKeys.SECURITY_VERSION, fresh.securityVersion());
    }
    // A code from the authenticator was just entered on this session.
    stepUp.markDone(request.getSession());
    return new RecoveryCodesResponse(codes, AuthController.Me.of(fresh, AuthLevel.FULL.name()));
  }

  /** The sign-in TOTP; optionally trusts this browser for 30 days. */
  @PostMapping("/mfa/verify")
  SignedInResponse verify(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @Validated @RequestBody CodeRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    AppUser user = activeUser(principal);
    String ip = request.getRemoteAddr();
    mfa.verifyCode(user, body.code(), knownDevice(user, request), ip);
    completeSignIn(user, request, response, ip);
    if (Boolean.TRUE.equals(body.trustDevice())) {
      devices.trustThisDevice(user.id(), request, response, ip, "totp");
    }
    return new SignedInResponse(AuthController.Me.of(user, AuthLevel.FULL.name()), null);
  }

  /**
   * Sign-in with a recovery code instead of the TOTP; the code is used up. Optionally trusts this
   * browser for 30 days, exactly as the TOTP step does.
   */
  @PostMapping("/mfa/recovery")
  SignedInResponse recover(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @Validated @RequestBody RecoveryRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    AppUser user = activeUser(principal);
    String ip = request.getRemoteAddr();
    int left = mfa.verifyRecoveryCode(user, body.recoveryCode(), knownDevice(user, request), ip);
    completeSignIn(user, request, response, ip);
    if (Boolean.TRUE.equals(body.trustDevice())) {
      devices.trustThisDevice(user.id(), request, response, ip, "recovery_code");
    }
    return new SignedInResponse(AuthController.Me.of(user, AuthLevel.FULL.name()), left);
  }

  /**
   * Step-up: a fresh TOTP, or a recovery code which is then used up; for an engineer with a
   * two-step exemption that applies now, password re-entry.
   */
  @PostMapping("/step-up")
  StepUpResponse stepUp(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @Validated @RequestBody StepUpRequest body,
      HttpServletRequest request) {
    AppUser user = activeUser(principal);
    if (body.password() != null && !body.password().isEmpty()) {
      mfa.verifyPasswordStepUp(
          user, body.password(), knownDevice(user, request), request.getRemoteAddr());
      stepUp.markDone(request.getSession());
      return new StepUpResponse(null);
    }
    int left =
        mfa.verifyStepUp(
            user,
            body.code(),
            body.recoveryCode(),
            knownDevice(user, request),
            request.getRemoteAddr());
    stepUp.markDone(request.getSession());
    return new StepUpResponse(left < 0 ? null : left);
  }

  @PostMapping("/mfa/recovery-codes")
  @RequiresStepUp
  RecoveryCodesResponse regenerateRecoveryCodes(
      @AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request) {
    AppUser user = activeUser(principal);
    return new RecoveryCodesResponse(
        mfa.regenerateRecoveryCodes(user, request.getRemoteAddr()), null);
  }

  private void completeSignIn(
      AppUser user, HttpServletRequest request, HttpServletResponse response, String ip) {
    sessions.signIn(user, AuthLevel.FULL, request, response, ip);
    devices.onFullSignIn(user, request, response, ip);
    // The sign-in code is fresh, so it also counts as a step-up for 10 minutes (never for a
    // trusted-device sign-in, which enters no code).
    stepUp.markDone(request.getSession());
  }

  /**
   * True for a first enrolment (session at MFA_ENROLMENT_REQUIRED, nothing enrolled yet). A
   * re-enrolment (FULL session, already enrolled) needs a fresh step-up.
   */
  private boolean guardEnrolment(AppUser user, HttpSession session) {
    Object level = session == null ? null : session.getAttribute(SessionKeys.AUTH_LEVEL);
    boolean enrolled = mfa.isEnrolled(user.id());
    if (AuthLevel.MFA_ENROLMENT_REQUIRED.name().equals(level)) {
      if (enrolled) {
        throw new ApiException(
            HttpStatus.FORBIDDEN, "forbidden", "You do not have access to this.");
      }
      return true;
    }
    if (enrolled) {
      stepUp.require(session);
    }
    return false;
  }

  private boolean knownDevice(AppUser user, HttpServletRequest request) {
    return devices.isKnown(user.id(), devices.presentedToken(request));
  }

  private AppUser activeUser(AuthenticatedUser principal) {
    return users
        .findById(principal.id())
        .filter(AppUser::isActive)
        .orElseThrow(
            () -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Please sign in."));
  }
}
