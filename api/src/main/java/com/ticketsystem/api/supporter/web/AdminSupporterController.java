package com.ticketsystem.api.supporter.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.UserStatus;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import com.ticketsystem.api.supporter.SupporterAdminService;
import com.ticketsystem.api.supporter.SupporterAdminService.ActivityView;
import com.ticketsystem.api.supporter.SupporterAdminService.DeactivationPlan;
import com.ticketsystem.api.supporter.SupporterAdminService.InviteResult;
import com.ticketsystem.api.supporter.SupporterView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Manage supporters (C18). Admin tree only: agents and customers get 403 from the role
 * gate, and the service checks the admin scope again. Organisation access is {@code PUT
 * /api/v1/admin/users/{id}/supporter-access}; reset MFA and sign out everywhere are {@code
 * /api/v1/admin/supporters/{id}/security/*}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/supporters")
public class AdminSupporterController {

  public record InviteRequest(
      @NotNull @Size(max = 320) String email,
      @NotNull @Size(max = 200) String name,
      @NotNull Role role,
      boolean allOrganisations,
      @Size(max = 500) Set<@NotNull Long> organisationIds) {}

  public record UpdateRequest(
      @NotNull @Size(max = 200) String name, @Size(max = 200) String jobTitle) {}

  public record RoleRequest(@NotNull Role role) {}

  public record DeactivateRequest(Long reassignTo) {}

  private final SupporterAdminService supporters;

  public AdminSupporterController(SupporterAdminService supporters) {
    this.supporters = supporters;
  }

  @GetMapping
  List<SupporterView> list(
      AccessScope scope,
      @RequestParam(name = "role", required = false) Role role,
      @RequestParam(name = "status", required = false) UserStatus status) {
    return supporters.list(scope, role, status);
  }

  @GetMapping("/{id}")
  SupporterView get(AccessScope scope, @PathVariable("id") long id) {
    return supporters.get(scope, id);
  }

  /**
   * Invite modal. There is no invitation code: the emailed single-use link is enough. Needs a fresh
   * step-up for every role: an invitation can create an admin, which is the escalation C18's
   * step-up on role change exists to stop. Invitations are rare, so asking for every one is simpler
   * than a role-dependent check.
   */
  @PostMapping("/invitations")
  @RequiresStepUp
  ResponseEntity<InviteResult> invite(
      AccessScope scope, @Validated @RequestBody InviteRequest body, HttpServletRequest request) {
    InviteResult result =
        supporters.invite(
            scope,
            body.email(),
            body.name(),
            body.role(),
            body.allOrganisations(),
            body.organisationIds(),
            request.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.CREATED).body(result);
  }

  /** Resend: a new link for a pending invitation (any role) - fresh step-up, as invite. */
  @PostMapping("/{id}/invitation/resend")
  @RequiresStepUp
  InviteResult resend(AccessScope scope, @PathVariable("id") long id, HttpServletRequest request) {
    return supporters.resendInvitation(scope, id, request.getRemoteAddr());
  }

  @PostMapping("/{id}/invitation/revoke")
  ResponseEntity<Void> revoke(
      AccessScope scope, @PathVariable("id") long id, HttpServletRequest request) {
    supporters.revokeInvitation(scope, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}")
  SupporterView update(
      AccessScope scope,
      @PathVariable("id") long id,
      @Validated @RequestBody UpdateRequest body,
      HttpServletRequest request) {
    return supporters.update(scope, id, body.name(), body.jobTitle(), request.getRemoteAddr());
  }

  /** Role change: needs a fresh step-up (C18), as do invite and resend. */
  @PutMapping("/{id}/role")
  @RequiresStepUp
  SupporterView changeRole(
      AccessScope scope,
      @PathVariable("id") long id,
      @Validated @RequestBody RoleRequest body,
      HttpServletRequest request) {
    return supporters.changeRole(scope, id, body.role(), request.getRemoteAddr());
  }

  /** Deactivate wizard step 1. */
  @GetMapping("/{id}/deactivation")
  DeactivationPlan deactivationPlan(AccessScope scope, @PathVariable("id") long id) {
    return supporters.deactivationPlan(scope, id);
  }

  /** Deactivate wizard step 2 (confirm). {@code reassignTo} null = Unassigned. */
  @PostMapping("/{id}/deactivate")
  SupporterView deactivate(
      AccessScope scope,
      @PathVariable("id") long id,
      @RequestBody DeactivateRequest body,
      HttpServletRequest request) {
    return supporters.deactivate(scope, id, body.reassignTo(), request.getRemoteAddr());
  }

  @PostMapping("/{id}/reactivate")
  SupporterView reactivate(
      AccessScope scope, @PathVariable("id") long id, HttpServletRequest request) {
    return supporters.reactivate(scope, id, request.getRemoteAddr());
  }

  @PostMapping("/{id}/security/password-reset")
  ResponseEntity<Void> passwordReset(
      AccessScope scope, @PathVariable("id") long id, HttpServletRequest request) {
    supporters.sendPasswordReset(scope, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/activity")
  List<ActivityView> activity(AccessScope scope, @PathVariable("id") long id) {
    return supporters.activity(scope, id);
  }
}
