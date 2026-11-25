package com.ticketsystem.api.organisation.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.session.StepUp;
import com.ticketsystem.api.organisation.OrganisationService;
import com.ticketsystem.api.organisation.OrganisationService.Details;
import com.ticketsystem.api.organisation.OrganisationService.OrganisationView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Organisations: list, create, edit, change prefix, add/remove verified domains, delete.
 * Role gate: the {@code /api/v1/admin} tree; the service checks the admin scope again.
 */
@RestController
@RequestMapping("/api/v1/admin/organisations")
public class AdminOrganisationController {

  public record CreateRequest(
      @NotNull @Size(max = 200) String name,
      @NotNull @Size(max = 20) String prefix,
      @Size(max = 50) List<@Size(max = 260) String> verifiedDomains,
      @NotNull @Size(max = 64) String timezone,
      @Size(max = 64) String entraTenantId) {}

  public record UpdateRequest(
      @NotNull @Size(max = 200) String name,
      @NotNull @Size(max = 64) String timezone,
      @Size(max = 64) String entraTenantId) {}

  public record PrefixRequest(@NotNull @Size(max = 20) String prefix) {}

  public record DomainRequest(@NotNull @Size(max = 260) String domain) {}

  private final OrganisationService organisations;
  private final StepUp stepUp;

  public AdminOrganisationController(OrganisationService organisations, StepUp stepUp) {
    this.organisations = organisations;
    this.stepUp = stepUp;
  }

  /** Setting or changing an organisation's Microsoft tenant needs step-up. */
  private Runnable tenantGuard(HttpServletRequest request) {
    return () -> stepUp.require(request.getSession(false));
  }

  @GetMapping
  List<OrganisationView> list(AccessScope scope) {
    return organisations.list(scope);
  }

  @GetMapping("/{id}")
  OrganisationView get(AccessScope scope, @PathVariable long id) {
    return organisations.get(scope, id);
  }

  @PostMapping
  ResponseEntity<OrganisationView> create(
      AccessScope scope, @Validated @RequestBody CreateRequest body, HttpServletRequest request) {
    OrganisationView created =
        organisations.create(
            scope,
            new Details(
                body.name(),
                body.prefix(),
                body.verifiedDomains(),
                body.timezone(),
                body.entraTenantId()),
            request.getRemoteAddr(),
            tenantGuard(request));
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @PutMapping("/{id}")
  OrganisationView update(
      AccessScope scope,
      @PathVariable long id,
      @Validated @RequestBody UpdateRequest body,
      HttpServletRequest request) {
    return organisations.update(
        scope,
        id,
        new Details(body.name(), null, null, body.timezone(), body.entraTenantId()),
        request.getRemoteAddr(),
        tenantGuard(request));
  }

  @PutMapping("/{id}/prefix")
  OrganisationView changePrefix(
      AccessScope scope,
      @PathVariable long id,
      @Validated @RequestBody PrefixRequest body,
      HttpServletRequest request) {
    return organisations.changePrefix(scope, id, body.prefix(), request.getRemoteAddr());
  }

  @PostMapping("/{id}/domains")
  OrganisationView addDomain(
      AccessScope scope,
      @PathVariable long id,
      @Validated @RequestBody DomainRequest body,
      HttpServletRequest request) {
    return organisations.addDomain(scope, id, body.domain(), request.getRemoteAddr());
  }

  @DeleteMapping("/{id}/domains/{domain}")
  OrganisationView removeDomain(
      AccessScope scope,
      @PathVariable long id,
      @PathVariable String domain,
      HttpServletRequest request) {
    return organisations.removeDomain(scope, id, domain, request.getRemoteAddr());
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(
      AccessScope scope, @PathVariable long id, HttpServletRequest request) {
    organisations.delete(scope, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }
}
