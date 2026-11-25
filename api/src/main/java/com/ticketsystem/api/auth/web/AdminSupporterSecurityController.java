package com.ticketsystem.api.auth.web;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.auth.mfa.MfaExemptionService;
import com.ticketsystem.api.auth.mfa.MfaService;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import com.ticketsystem.api.auth.session.SecurityVersionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Manage supporters - Security actions (C18): force sign-out and reset MFA for another
 * supporter, and the time-limited two-step exemption for an engineer. Admin tree only (agents and
 * customers get 403 from the role gate). An admin uses My settings for their own account, so acting
 * on yourself is refused.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/supporters/{id}/security")
public class AdminSupporterSecurityController {

  /** Body of the exemption grant: reason (1-200 characters) and end time (at most 30 days). */
  public record ExemptionRequest(@Size(max = 1000) String reason, Instant endsAt) {}

  private final UserRepository users;
  private final SecurityVersionService securityVersion;
  private final MfaService mfa;
  private final MfaExemptionService exemptions;

  public AdminSupporterSecurityController(
      UserRepository users,
      SecurityVersionService securityVersion,
      MfaService mfa,
      MfaExemptionService exemptions) {
    this.users = users;
    this.securityVersion = securityVersion;
    this.mfa = mfa;
    this.exemptions = exemptions;
  }

  /**
   * Exempts an engineer (role Agent, active) from two-step sign-in until {@code endsAt}, or
   * replaces their exemption. Step-up. Admins, yourself and an end time outside (now, now + 30
   * days] are refused by the service.
   */
  @PutMapping("/mfa-exemption")
  @RequiresStepUp
  MfaExemptionService.Exemption grantExemption(
      @AuthenticationPrincipal AuthenticatedUser admin,
      @PathVariable("id") long id,
      @Validated @RequestBody ExemptionRequest body,
      HttpServletRequest request) {
    return exemptions.grant(admin.id(), id, body.reason(), body.endsAt(), request.getRemoteAddr());
  }

  /** Ends the engineer's exemption now; all their sessions end. Step-up. */
  @DeleteMapping("/mfa-exemption")
  @RequiresStepUp
  ResponseEntity<Void> revokeExemption(
      @AuthenticationPrincipal AuthenticatedUser admin,
      @PathVariable("id") long id,
      HttpServletRequest request) {
    exemptions.revoke(admin.id(), id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /**
   * Ends every session of the supporter at once (trusted devices stay). Needs a fresh step-up like
   * reset-MFA, so a hijacked or unattended admin session cannot force supporters out repeatedly.
   */
  @PostMapping("/sign-out-everywhere")
  @RequiresStepUp
  ResponseEntity<Void> signOutEverywhere(
      @AuthenticationPrincipal AuthenticatedUser admin,
      @PathVariable("id") long id,
      HttpServletRequest request) {
    checkTarget(admin, id);
    securityVersion.bump(
        id,
        SecurityVersionService.Reason.ADMIN_SIGN_OUT,
        null,
        admin.id(),
        request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /** Clears the supporter's TOTP and recovery codes; they enrol again at next sign-in. */
  @PostMapping("/reset-mfa")
  @RequiresStepUp
  ResponseEntity<Void> resetMfa(
      @AuthenticationPrincipal AuthenticatedUser admin,
      @PathVariable("id") long id,
      HttpServletRequest request) {
    checkTarget(admin, id);
    mfa.reset(id, admin.id(), request.getRemoteAddr(), "admin");
    return ResponseEntity.noContent().build();
  }

  private void checkTarget(AuthenticatedUser admin, long id) {
    if (admin.id() == id) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "own_account",
          "Use My settings - Security for your own account.");
    }
    users
        .findById(id)
        .filter(u -> u.role().isSupporter())
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such agent."));
  }
}
