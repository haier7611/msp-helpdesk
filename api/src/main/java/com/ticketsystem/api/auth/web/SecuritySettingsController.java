package com.ticketsystem.api.auth.web;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.device.DeviceService;
import com.ticketsystem.api.auth.mfa.MfaAuditEvent;
import com.ticketsystem.api.auth.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * My settings - Security: the signed-in user's own sessions and trusted devices. Every lookup is by
 * the caller's own user id, so another user's session or device is never found. FULL sessions only
 * (SecurityConfiguration).
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/auth")
public class SecuritySettingsController {

  public record Ended(int count) {}

  private final SessionService sessionService;
  private final DeviceService devices;
  private final SessionAuthenticator sessions;
  private final AuditService audit;

  public SecuritySettingsController(
      SessionService sessionService,
      DeviceService devices,
      SessionAuthenticator sessions,
      AuditService audit) {
    this.sessionService = sessionService;
    this.devices = devices;
    this.sessions = sessions;
    this.audit = audit;
  }

  @GetMapping("/sessions")
  List<SessionService.SessionInfo> listSessions(
      @AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request) {
    return sessionService.list(principal.id(), currentId(request));
  }

  /** Ends one of the caller's sessions; ending the current one signs this browser out. */
  @DeleteMapping("/sessions/{id}")
  ResponseEntity<Void> signOutSession(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable("id") String id,
      HttpServletRequest request,
      HttpServletResponse response) {
    String current = currentId(request);
    if (current != null
        && sessionService.list(principal.id(), current).stream()
            .anyMatch(s -> s.current() && s.id().equals(id))) {
      audit.record(
          MfaAuditEvent.SESSION_REVOKED,
          principal.id(),
          principal.id(),
          request.getRemoteAddr(),
          Map.of("reason", "current"));
      sessions.signOut(request, response);
      return ResponseEntity.noContent().build();
    }
    if (!sessionService.signOut(principal.id(), id, request.getRemoteAddr())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "That session has already ended.");
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/sessions/sign-out-others")
  Ended signOutOthers(
      @AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request) {
    return new Ended(
        sessionService.signOutOthers(principal.id(), currentId(request), request.getRemoteAddr()));
  }

  @GetMapping("/trusted-devices")
  List<DeviceService.TrustedDevice> listTrustedDevices(
      @AuthenticationPrincipal AuthenticatedUser principal) {
    return devices.listTrusted(principal.id());
  }

  @DeleteMapping("/trusted-devices/{id}")
  ResponseEntity<Void> removeTrustedDevice(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable("id") long id,
      HttpServletRequest request) {
    if (!devices.untrust(principal.id(), id, request.getRemoteAddr())) {
      throw new ApiException(
          HttpStatus.NOT_FOUND, "not_found", "That device is not trusted any more.");
    }
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/trusted-devices")
  Ended removeAllTrustedDevices(
      @AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request) {
    int count = devices.untrustAll(principal.id());
    audit.record(
        MfaAuditEvent.TRUSTED_DEVICE_REMOVED,
        principal.id(),
        principal.id(),
        request.getRemoteAddr(),
        Map.of("reason", "all", "devices", count));
    return new Ended(count);
  }

  private static String currentId(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    return session == null ? null : session.getId();
  }
}
