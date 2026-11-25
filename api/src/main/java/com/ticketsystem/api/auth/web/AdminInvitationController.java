package com.ticketsystem.api.auth.web;

import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.InvitationService;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.session.StepUp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal invitation endpoint for admins (the Invite modal calls it). Role gate: the {@code
 * /api/v1/admin} tree (SecurityConfiguration). No invitation code (see docs/10-decision-log.md
 * ADR-046): the emailed single-use link plus a password is enough. Because of that, inviting an
 * agent or admin here needs a fresh step-up, exactly as {@code POST
 * /api/v1/admin/supporters/invitations} does (the missing code is the risk it covers); customer
 * invitations do not. Checked before anything is created or emailed.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/invitations")
public class AdminInvitationController {

  public record InviteRequest(
      @NotNull @Size(max = 320) String email,
      @NotNull @Size(max = 200) String name,
      @NotNull Role role,
      // Required for customers, must be empty for supporters.
      Long organisationId) {}

  public record InviteResponse(long userId, Instant expiresAt) {}

  private final InvitationService invitations;
  private final StepUp stepUp;

  public AdminInvitationController(InvitationService invitations, StepUp stepUp) {
    this.invitations = invitations;
    this.stepUp = stepUp;
  }

  @PostMapping
  ResponseEntity<InviteResponse> invite(
      @AuthenticationPrincipal AuthenticatedUser admin,
      @Validated @RequestBody InviteRequest body,
      HttpServletRequest request) {
    if (body.role() != null && body.role().isSupporter()) {
      stepUp.require(request.getSession(false));
    }
    InvitationService.Created created =
        invitations.invite(
            admin.id(),
            body.email(),
            body.name(),
            body.role(),
            body.organisationId(),
            request.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new InviteResponse(created.userId(), created.expiresAt()));
  }
}
