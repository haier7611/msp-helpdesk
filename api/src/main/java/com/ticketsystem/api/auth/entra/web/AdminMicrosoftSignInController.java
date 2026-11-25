package com.ticketsystem.api.auth.entra.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AuthenticatedUser;
import com.ticketsystem.api.auth.entra.EntraSettingsService;
import com.ticketsystem.api.auth.entra.EntraSettingsService.SettingsView;
import com.ticketsystem.api.auth.entra.MicrosoftSignInService;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import com.ticketsystem.api.organisation.OrganisationService;
import com.ticketsystem.api.organisation.OrganisationService.OrganisationView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Sign-in - Microsoft. Admins only (the {@code /api/v1/admin} gate). Every change needs
 * step-up and is audit-logged; Test sign-in only checks the details. The client secret is accepted
 * but never returned - the view says only whether one is set.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/sign-in/microsoft")
public class AdminMicrosoftSignInController {

  /** One organisation's allowed Microsoft tenant. */
  public record OrganisationTenant(long id, String name, String tenantId) {}

  public record SettingsPage(
      SettingsView settings, String redirectUri, List<OrganisationTenant> organisations) {}

  public record UpdateRequest(
      @NotNull @Size(max = 64) String clientId,
      @Size(max = 1024) String clientSecret,
      @NotNull LocalDate secretExpiresOn,
      @NotNull @Size(max = 64) String mspTenantId) {
    @Override
    public String toString() {
      return "UpdateRequest[]";
    }
  }

  public record EnabledRequest(@NotNull Boolean enabled) {}

  public record TenantRequest(@Size(max = 64) String tenantId) {}

  private final EntraSettingsService settings;
  private final MicrosoftSignInService microsoft;
  private final OrganisationService organisations;

  public AdminMicrosoftSignInController(
      EntraSettingsService settings,
      MicrosoftSignInService microsoft,
      OrganisationService organisations) {
    this.settings = settings;
    this.microsoft = microsoft;
    this.organisations = organisations;
  }

  @GetMapping
  SettingsPage get(AccessScope scope) {
    return page(scope, settings.view());
  }

  @PutMapping
  @RequiresStepUp
  SettingsPage update(
      AccessScope scope,
      @AuthenticationPrincipal AuthenticatedUser admin,
      @Validated @RequestBody UpdateRequest body,
      HttpServletRequest request) {
    scope.requireAdmin();
    SettingsView view =
        settings.update(
            admin.id(),
            body.clientId(),
            body.clientSecret(),
            body.secretExpiresOn(),
            body.mspTenantId(),
            request.getRemoteAddr());
    return page(scope, view);
  }

  @PutMapping("/enabled")
  @RequiresStepUp
  SettingsPage setEnabled(
      AccessScope scope,
      @AuthenticationPrincipal AuthenticatedUser admin,
      @Validated @RequestBody EnabledRequest body,
      HttpServletRequest request) {
    scope.requireAdmin();
    return page(scope, settings.setEnabled(admin.id(), body.enabled(), request.getRemoteAddr()));
  }

  /** Starts Test sign-in: answers the Microsoft URL; the result comes back on the callback. */
  @PostMapping("/test")
  MicrosoftAuthController.Redirect test(
      AccessScope scope,
      @AuthenticationPrincipal AuthenticatedUser admin,
      HttpServletRequest request) {
    scope.requireAdmin();
    return new MicrosoftAuthController.Redirect(
        microsoft.startTest(admin.id(), request.getSession(false)).toString());
  }

  @PutMapping("/organisations/{id}")
  @RequiresStepUp
  OrganisationTenant setOrganisationTenant(
      AccessScope scope,
      @PathVariable long id,
      @Validated @RequestBody TenantRequest body,
      HttpServletRequest request) {
    return tenantOf(
        organisations.setEntraTenant(scope, id, body.tenantId(), request.getRemoteAddr()));
  }

  private SettingsPage page(AccessScope scope, SettingsView view) {
    return new SettingsPage(
        view,
        microsoft.redirectUri(),
        organisations.list(scope).stream().map(AdminMicrosoftSignInController::tenantOf).toList());
  }

  private static OrganisationTenant tenantOf(OrganisationView o) {
    return new OrganisationTenant(
        o.id(), o.name(), o.entraTenantId() == null ? null : o.entraTenantId().toString());
  }
}
