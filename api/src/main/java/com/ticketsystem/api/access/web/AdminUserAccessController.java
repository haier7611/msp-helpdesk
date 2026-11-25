package com.ticketsystem.api.access.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.UserAccess;
import com.ticketsystem.api.access.UserAccessService;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for a user's organisation access: a customer's organisation, an agent's organisations.
 * Role gate: {@code /api/v1/admin}.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}")
public class AdminUserAccessController {

  public record OrganisationRequest(@NotNull Long organisationId) {}

  public record AgentAccessRequest(
      boolean allOrganisations, @Size(max = 500) Set<@NotNull Long> organisationIds) {}

  private final UserAccessService access;

  public AdminUserAccessController(UserAccessService access) {
    this.access = access;
  }

  @GetMapping("/access")
  UserAccess get(AccessScope scope, @PathVariable long userId) {
    return access.get(scope, userId);
  }

  /**
   * Moves a customer to another organisation. Needs step-up (as Admin - Customers' move, {@code PUT
   * /api/v1/admin/customers/{id}/organisation}), with the same participant re-check.
   */
  @PutMapping("/organisation")
  @RequiresStepUp
  UserAccess setOrganisation(
      AccessScope scope,
      @PathVariable long userId,
      @Validated @RequestBody OrganisationRequest body,
      HttpServletRequest request) {
    return access.setCustomerOrganisation(
        scope, userId, body.organisationId(), request.getRemoteAddr());
  }

  @PutMapping("/supporter-access")
  UserAccess setAgentAccess(
      AccessScope scope,
      @PathVariable long userId,
      @Validated @RequestBody AgentAccessRequest body,
      HttpServletRequest request) {
    return access.setAgentAccess(
        scope, userId, body.allOrganisations(), body.organisationIds(), request.getRemoteAddr());
  }
}
