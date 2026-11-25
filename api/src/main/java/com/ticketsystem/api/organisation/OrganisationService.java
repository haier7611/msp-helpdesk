package com.ticketsystem.api.organisation;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditEvent;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.entra.EntraAuditEvent;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository.OrganisationRow;
import com.ticketsystem.api.reference.PrefixService;
import com.ticketsystem.api.reference.clientdata.PrefixRepository.PrefixRow;
import com.ticketsystem.api.reference.clientdata.TicketReferenceRepository;
import com.ticketsystem.api.sla.config.SlaConfigService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Client organisations (7 "Admin - other settings", 8, 11a, 11b). Reads are scoped: an agent
 * limited to some organisations gets 404 for the others. Create, edit, prefix change,
 * verified-domain edits and delete are admin only - enforced by the {@code /api/v1/admin} gate and
 * again here and in the repositories.
 */
@Service
public class OrganisationService {

  /** Most verified domains one organisation may have. */
  static final int MAX_DOMAINS = 50;

  /** What the API shows about an organisation. */
  public record OrganisationView(
      long id,
      String name,
      String prefix,
      List<String> retiredPrefixes,
      List<String> verifiedDomains,
      String timezone,
      UUID entraTenantId,
      String status,
      Instant createdAt,
      Instant updatedAt) {}

  /** Admin input for create (prefix and domains included) and edit (prefix and domains ignored). */
  public record Details(
      String name,
      String prefix,
      List<String> verifiedDomains,
      String timezone,
      String entraTenantId) {}

  private final OrganisationRepository organisations;
  private final PrefixService prefixes;
  private final TicketReferenceRepository references;
  private final VerifiedDomains domains;
  private final AuditService audit;
  private final Clock clock;
  private final SlaConfigService sla;

  public OrganisationService(
      OrganisationRepository organisations,
      PrefixService prefixes,
      TicketReferenceRepository references,
      VerifiedDomains domains,
      AuditService audit,
      Clock clock,
      SlaConfigService sla) {
    this.sla = sla;
    this.organisations = organisations;
    this.prefixes = prefixes;
    this.references = references;
    this.domains = domains;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<OrganisationView> list(AccessScope scope) {
    return organisations.list(scope).stream().map(row -> view(scope, row)).toList();
  }

  /** 404 when the organisation does not exist or the scope may not see it. */
  @Transactional(readOnly = true)
  public OrganisationView get(AccessScope scope, long id) {
    return organisations
        .find(scope, id)
        .map(row -> view(scope, row))
        .orElseThrow(AccessScope::notFound);
  }

  /**
   * Creates an organisation. Setting a Microsoft tenant on it is a sign-in setting: {@code
   * tenantChangeGuard} (the controller's step-up check) runs first.
   */
  @Transactional
  public OrganisationView create(
      AccessScope scope, Details details, String clientIp, Runnable tenantChangeGuard) {
    scope.requireAdmin();
    String name = requireName(details.name());
    String timezone = requireTimezone(details.timezone());
    UUID tenant = parseTenant(details.entraTenantId());
    if (tenant != null) {
      tenantChangeGuard.run();
    }
    Set<String> verified = requireDomains(details.verifiedDomains());
    Instant now = clock.instant();
    long id;
    try {
      id = organisations.insert(scope, name, timezone, tenant, now);
    } catch (DuplicateKeyException e) {
      throw nameTaken();
    }
    String prefix = prefixes.setUp(scope, id, details.prefix());
    for (String domain : verified) {
      insertDomain(scope, id, domain, now);
    }
    // Every new organisation starts with the default SLA policy.
    sla.seedDefaults(scope, id);
    audit(
        scope,
        OrganisationAuditEvent.ORGANISATION_CREATED,
        clientIp,
        details(id, "prefix", prefix));
    if (tenant != null) {
      auditTenant(scope, id, tenant, clientIp);
    }
    return get(scope, id);
  }

  /**
   * Edits name, timezone and Entra tenant id. Changing the tenant (the customers' allowed Microsoft
   * tenant) runs {@code tenantChangeGuard} first - the controller's step-up check.
   */
  @Transactional
  public OrganisationView update(
      AccessScope scope, long id, Details details, String clientIp, Runnable tenantChangeGuard) {
    scope.requireAdmin();
    OrganisationRow current =
        organisations.findForUpdate(scope, id).orElseThrow(AccessScope::notFound);
    String name = requireName(details.name());
    String timezone = requireTimezone(details.timezone());
    UUID tenant = parseTenant(details.entraTenantId());
    boolean tenantChanged = !Objects.equals(tenant, current.entraTenantId());
    if (tenantChanged) {
      tenantChangeGuard.run();
    }
    try {
      organisations.update(scope, id, name, timezone, tenant, clock.instant());
    } catch (DuplicateKeyException e) {
      throw nameTaken();
    }
    audit(scope, OrganisationAuditEvent.ORGANISATION_UPDATED, clientIp, details(id, null, null));
    if (tenantChanged) {
      auditTenant(scope, id, tenant, clientIp);
    }
    return get(scope, id);
  }

  /**
   * Admin - Sign-in - Microsoft: sets or clears only the organisation's allowed Microsoft tenant.
   * The endpoint requires step-up.
   */
  @Transactional
  public OrganisationView setEntraTenant(
      AccessScope scope, long id, String rawTenant, String clientIp) {
    scope.requireAdmin();
    OrganisationRow current =
        organisations.findForUpdate(scope, id).orElseThrow(AccessScope::notFound);
    UUID tenant = parseTenant(rawTenant);
    if (!Objects.equals(tenant, current.entraTenantId())) {
      organisations.update(scope, id, current.name(), current.timezone(), tenant, clock.instant());
      auditTenant(scope, id, tenant, clientIp);
    }
    return get(scope, id);
  }

  private void auditTenant(AccessScope scope, long id, UUID tenant, String clientIp) {
    audit(
        scope,
        EntraAuditEvent.ORGANISATION_ENTRA_TENANT_CHANGED,
        clientIp,
        details(id, "tenant", tenant == null ? "none" : tenant.toString()));
  }

  /** Changes the ticket prefix; the old one is retired and the number sequence continues. */
  @Transactional
  public OrganisationView changePrefix(AccessScope scope, long id, String raw, String clientIp) {
    scope.requireAdmin();
    lock(scope, id);
    PrefixService.Change change = prefixes.change(scope, id, raw);
    if (change.changed()) {
      organisations.touch(scope, id, clock.instant());
      Map<String, Object> d = details(id, "prefix", change.newPrefix());
      d.put("old_prefix", change.oldPrefix());
      audit(scope, OrganisationAuditEvent.ORGANISATION_PREFIX_CHANGED, clientIp, d);
    }
    return get(scope, id);
  }

  @Transactional
  public OrganisationView addDomain(AccessScope scope, long id, String raw, String clientIp) {
    scope.requireAdmin();
    lock(scope, id);
    String domain = requireDomain(raw);
    if (organisations.domains(scope, id).size() >= MAX_DOMAINS) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "too_many_domains",
          "An organisation can have up to " + MAX_DOMAINS + " verified domains.");
    }
    Optional<Long> holder = organisations.domainHolder(scope, domain);
    if (holder.isPresent() && holder.get() == id) {
      return get(scope, id);
    }
    insertDomain(scope, id, domain, clock.instant());
    organisations.touch(scope, id, clock.instant());
    audit(
        scope,
        OrganisationAuditEvent.ORGANISATION_DOMAIN_ADDED,
        clientIp,
        details(id, "domain", domain));
    return get(scope, id);
  }

  @Transactional
  public OrganisationView removeDomain(AccessScope scope, long id, String raw, String clientIp) {
    scope.requireAdmin();
    lock(scope, id);
    String domain = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    if (organisations.deleteDomain(scope, id, domain) == 0) {
      throw AccessScope.notFound();
    }
    organisations.touch(scope, id, clock.instant());
    audit(
        scope,
        OrganisationAuditEvent.ORGANISATION_DOMAIN_REMOVED,
        clientIp,
        details(id, "domain", domain));
    return get(scope, id);
  }

  /**
   * Deletes an organisation that has no customer accounts and no issued references. One with
   * history cannot be deleted (it would orphan tickets and free its prefixes).
   */
  @Transactional
  public void delete(AccessScope scope, long id, String clientIp) {
    scope.requireAdmin();
    lock(scope, id);
    if (organisations.countCustomers(scope, id) > 0 || references.anyForOrganisation(scope, id)) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "organisation_in_use",
          "This organisation has customers or tickets, so it cannot be deleted.");
    }
    organisations.delete(scope, id);
    audit(scope, OrganisationAuditEvent.ORGANISATION_DELETED, clientIp, details(id, null, null));
  }

  private void lock(AccessScope scope, long id) {
    organisations.findForUpdate(scope, id).orElseThrow(AccessScope::notFound);
  }

  private OrganisationView view(AccessScope scope, OrganisationRow row) {
    List<PrefixRow> history = prefixes.history(scope, row.id());
    String active =
        history.stream()
            .filter(p -> "ACTIVE".equals(p.status()))
            .map(PrefixRow::prefix)
            .findFirst()
            .orElse(null);
    List<String> retired =
        history.stream().filter(p -> "RETIRED".equals(p.status())).map(PrefixRow::prefix).toList();
    return new OrganisationView(
        row.id(),
        row.name(),
        active,
        retired,
        organisations.domains(scope, row.id()),
        row.timezone(),
        row.entraTenantId(),
        row.status(),
        row.createdAt(),
        row.updatedAt());
  }

  private void insertDomain(AccessScope scope, long id, String domain, Instant now) {
    Optional<Long> holder = organisations.domainHolder(scope, domain);
    if (holder.isPresent()) {
      throw domainTaken(domain);
    }
    try {
      organisations.insertDomain(scope, id, domain, now);
    } catch (DuplicateKeyException e) {
      throw domainTaken(domain);
    }
  }

  private void audit(
      AccessScope scope, AuditEvent type, String clientIp, Map<String, Object> details) {
    audit.record(type, scope.principalId().orElse(null), null, clientIp, details);
  }

  private static Map<String, Object> details(long id, String key, String value) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("organisation", id);
    if (key != null) {
      d.put(key, value);
    }
    return d;
  }

  static String requireName(String raw) {
    String name = raw == null ? "" : raw.strip();
    if (name.isEmpty() || name.length() > 200) {
      throw ApiException.invalidRequest("Enter an organisation name of up to 200 characters.");
    }
    return name;
  }

  static String requireTimezone(String raw) {
    String tz = raw == null ? "" : raw.strip();
    if (!ZoneId.getAvailableZoneIds().contains(tz)) {
      throw ApiException.invalidRequest(
          "Choose a time zone such as Asia/Ho_Chi_Minh or Europe/London.");
    }
    return tz;
  }

  static UUID parseTenant(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String s = raw.strip();
    if (!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
      throw ApiException.invalidRequest(
          "The Microsoft tenant ID is a GUID such as 00000000-0000-0000-0000-000000000000.");
    }
    return UUID.fromString(s);
  }

  private String requireDomain(String raw) {
    return switch (domains.check(raw)) {
      case VerifiedDomains.Check.Valid v -> v.domain();
      case VerifiedDomains.Check.Invalid i ->
          throw new ApiException(HttpStatus.BAD_REQUEST, i.code(), i.message());
    };
  }

  private Set<String> requireDomains(List<String> raw) {
    Set<String> out = new LinkedHashSet<>();
    if (raw == null) {
      return out;
    }
    if (raw.size() > MAX_DOMAINS) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "too_many_domains",
          "An organisation can have up to " + MAX_DOMAINS + " verified domains.");
    }
    for (String d : raw) {
      out.add(requireDomain(d));
    }
    return out;
  }

  private static ApiException nameTaken() {
    return new ApiException(
        HttpStatus.CONFLICT, "name_taken", "Another organisation already has this name.");
  }

  private static ApiException domainTaken(String domain) {
    return new ApiException(
        HttpStatus.CONFLICT,
        "domain_taken",
        domain
            + " is already a verified domain of another organisation. A domain can belong to"
            + " one organisation only.");
  }
}
