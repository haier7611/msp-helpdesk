package com.ticketsystem.api.sla.config;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository;
import com.ticketsystem.api.sla.BusinessCalendar;
import com.ticketsystem.api.sla.ClockType;
import com.ticketsystem.api.sla.DailyHours;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.PriorityTargets;
import com.ticketsystem.api.sla.SlaAuditEvent;
import com.ticketsystem.api.sla.SlaPolicy;
import com.ticketsystem.api.sla.clientdata.SlaConfigRepository;
import com.ticketsystem.api.sla.clientdata.SlaConfigRepository.PolicyRow;
import com.ticketsystem.api.sla.config.SlaConfigTypes.HolidayRow;
import com.ticketsystem.api.sla.config.SlaConfigTypes.HoursRow;
import com.ticketsystem.api.sla.config.SlaConfigTypes.SettingsRow;
import com.ticketsystem.api.sla.config.SlaConfigTypes.TargetRow;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SLA configuration per organisation: reads the calendar and policy a ticket runs on, with safe
 * fallbacks, and the admin operations behind the admin screens. Every new organisation is seeded
 * with the default policy.
 *
 * <p>Fallbacks: an organisation with no business hours uses Monday-Friday 08:30-17:30; an
 * unreadable or incomplete policy falls back to the organisation default, then to the built-in
 * defaults, and is logged (organisation and policy ids only). A policy change applies to open
 * tickets at their next recalculation; stored results of resolved and closed tickets never change
 * (the report reads stored values).
 *
 * <p>Triage tickets (no organisation) run the built-in defaults a new organisation is seeded with:
 * the default policy and Monday-Friday 08:30-17:30 with no holidays, in the MSP timezone ({@code
 * app.notifications.default-timezone}, the same setting as the report's default range and the
 * supporters' default timezone).
 */
@Service
public class SlaConfigService {

  private static final Logger log = LoggerFactory.getLogger(SlaConfigService.class);

  static final int MAX_HOLIDAYS = 366;
  static final int MAX_TARGET_MINUTES = 60 * 24 * 366;

  /** An organisation's effective policy and whether a fallback was used. */
  public record EffectivePolicy(SlaPolicy policy, boolean fallback) {}

  /** Everything the admin screen shows for one organisation. */
  public record OrganisationSla(
      long organisationId,
      String timezone,
      SettingsRow settings,
      List<HoursRow> businessHours,
      boolean defaultHours,
      List<HolidayRow> holidays,
      List<PolicyView> policies) {}

  public record PolicyView(
      Long requestTypeId, boolean pendingOnVendorPauses, Map<Priority, TargetRow> targets) {}

  private final SlaConfigRepository config;
  private final OrganisationRepository organisations;
  private final RequestTypeRepository requestTypes;
  private final TicketPeopleRepository people;
  private final AuditService audit;
  private final Clock clock;
  private final ZoneId mspZone;

  public SlaConfigService(
      SlaConfigRepository config,
      OrganisationRepository organisations,
      RequestTypeRepository requestTypes,
      TicketPeopleRepository people,
      AuditService audit,
      Clock clock,
      @Value("${app.notifications.default-timezone:Asia/Ho_Chi_Minh}") String mspZone) {
    this.config = config;
    this.organisations = organisations;
    this.requestTypes = requestTypes;
    this.people = people;
    this.audit = audit;
    this.clock = clock;
    this.mspZone = ZoneId.of(mspZone);
  }

  // ---------------------------------------------------------------------------------------------
  // Reads used by the SLA wiring

  /**
   * The organisation's business calendar (its timezone, hours and holidays); for a triage ticket
   * ({@code organisationId} null) the default Monday-Friday 08:30-17:30 in the MSP timezone.
   */
  @Transactional(readOnly = true)
  public BusinessCalendar calendar(AccessScope scope, Long organisationId) {
    if (organisationId == null) {
      return BusinessCalendar.standard(mspZone, List.of());
    }
    ZoneId zone = zone(scope, organisationId);
    List<HoursRow> rows = config.businessHours(scope, organisationId);
    List<java.time.LocalDate> holidays =
        config.holidays(scope, organisationId).stream().map(HolidayRow::date).toList();
    if (rows.isEmpty()) {
      return BusinessCalendar.standard(zone, holidays);
    }
    Map<DayOfWeek, DailyHours> hours = new EnumMap<>(DayOfWeek.class);
    for (HoursRow h : rows) {
      hours.put(DayOfWeek.of(h.dayOfWeek()), new DailyHours(h.opens(), h.closes()));
    }
    return BusinessCalendar.of(zone, hours, holidays);
  }

  /** The organisation's timezone (Asia/Ho_Chi_Minh if it cannot be read, logged). */
  @Transactional(readOnly = true)
  public ZoneId zone(AccessScope scope, long organisationId) {
    String tz =
        organisations
            .find(scope, organisationId)
            .map(o -> o.timezone())
            .orElseThrow(AccessScope::notFound);
    try {
      return ZoneId.of(tz);
    } catch (RuntimeException e) {
      log.warn(
          "organisation {} has an unreadable timezone; using Asia/Ho_Chi_Minh", organisationId);
      return ZoneId.of("Asia/Ho_Chi_Minh");
    }
  }

  /** The request type's default assignee, if any. */
  @Transactional(readOnly = true)
  public Optional<Long> requestTypeDefaultAssignee(AccessScope scope, long requestTypeId) {
    return config.requestTypeDefaultAssignee(scope, requestTypeId);
  }

  /** Timer settings (defaults when the row is missing). */
  @Transactional(readOnly = true)
  public SettingsRow settings(AccessScope scope, long organisationId) {
    return config.settings(scope, organisationId).orElseGet(SettingsRow::defaults);
  }

  /**
   * The policy for a ticket of {@code requestTypeId} in the organisation: the request-type
   * override, else the organisation default, else the built-in defaults; an unreadable policy is
   * skipped and logged. A triage ticket ({@code organisationId} null) runs the built-in defaults -
   * not marked as a fallback, as that is its policy by design.
   */
  @Transactional(readOnly = true)
  public EffectivePolicy policyFor(AccessScope scope, Long organisationId, long requestTypeId) {
    if (organisationId == null) {
      return new EffectivePolicy(SlaPolicy.defaults(), false);
    }
    List<PolicyRow> rows = config.policies(scope, organisationId);
    PolicyRow specific = null;
    PolicyRow general = null;
    for (PolicyRow p : rows) {
      if (p.requestTypeId() == null) {
        general = p;
      } else if (p.requestTypeId() == requestTypeId) {
        specific = p;
      }
    }
    boolean fallback = false;
    for (PolicyRow candidate : new PolicyRow[] {specific, general}) {
      if (candidate == null) {
        continue;
      }
      Optional<SlaPolicy> policy = toPolicy(candidate);
      if (policy.isPresent()) {
        return new EffectivePolicy(policy.get(), fallback);
      }
      fallback = true;
      log.warn(
          "SLA policy {} of organisation {} is unreadable; falling back",
          candidate.id(),
          organisationId);
    }
    if (general == null) {
      log.warn("organisation {} has no SLA policy; using the ADR-017 defaults", organisationId);
    }
    return new EffectivePolicy(SlaPolicy.defaults(), true);
  }

  static Optional<SlaPolicy> toPolicy(PolicyRow row) {
    try {
      Map<Priority, PriorityTargets> targets = new EnumMap<>(Priority.class);
      row.targets()
          .forEach(
              (p, t) ->
                  targets.put(
                      p, new PriorityTargets(t.firstResponse(), t.resolution(), t.clock())));
      return Optional.of(new SlaPolicy(targets, row.pendingOnVendorPauses()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Seeding (every new organisation starts with the default policy)

  /** Seeds settings, Monday-Friday 08:30-17:30 and the default policy. Admin or SYSTEM. */
  @Transactional
  public void seedDefaults(AccessScope scope, long organisationId) {
    Instant now = clock.instant();
    config.saveTimers(scope, organisationId, SettingsRow.defaults(), now);
    List<HoursRow> hours = new ArrayList<>();
    for (int d = 1; d <= 5; d++) {
      hours.add(new HoursRow(d, LocalTime.of(8, 30), LocalTime.of(17, 30)));
    }
    config.replaceBusinessHours(scope, organisationId, hours);
    config.savePolicy(scope, organisationId, null, false, targetsOf(SlaPolicy.defaults()), now);
  }

  static Map<Priority, TargetRow> targetsOf(SlaPolicy policy) {
    Map<Priority, TargetRow> out = new EnumMap<>(Priority.class);
    policy
        .targets()
        .forEach((p, t) -> out.put(p, new TargetRow(t.firstResponse(), t.resolution(), t.clock())));
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Admin API

  @Transactional(readOnly = true)
  public OrganisationSla get(AccessScope scope, long organisationId) {
    scope.requireAdmin();
    ZoneId zone = zone(scope, organisationId);
    List<HoursRow> hours = config.businessHours(scope, organisationId);
    boolean defaultHours = hours.isEmpty();
    if (defaultHours) {
      hours = new ArrayList<>();
      for (int d = 1; d <= 5; d++) {
        hours.add(new HoursRow(d, LocalTime.of(8, 30), LocalTime.of(17, 30)));
      }
    }
    List<PolicyView> policies =
        config.policies(scope, organisationId).stream()
            .map(p -> new PolicyView(p.requestTypeId(), p.pendingOnVendorPauses(), p.targets()))
            .toList();
    return new OrganisationSla(
        organisationId,
        zone.getId(),
        settings(scope, organisationId),
        hours,
        defaultHours,
        config.holidays(scope, organisationId),
        policies);
  }

  @Transactional
  public OrganisationSla saveTimers(
      AccessScope scope, long organisationId, SettingsRow s, String clientIp) {
    return saveTimers(scope, organisationId, s, false, clientIp);
  }

  /**
   * As above; {@code keepPendingReminder} keeps the stored Pending reminder switch instead of
   * {@code s.pendingReminderEnabled()} (the switch is edited in Admin - Organisations - "Client
   * emails", so the SLA timers form leaves it out).
   */
  @Transactional
  public OrganisationSla saveTimers(
      AccessScope scope,
      long organisationId,
      SettingsRow s,
      boolean keepPendingReminder,
      String clientIp) {
    scope.requireAdmin();
    if (keepPendingReminder) {
      requireOrganisation(scope, organisationId);
      SettingsRow current = settings(scope, organisationId);
      s =
          new SettingsRow(
              current.pendingReminderEnabled(),
              s.pendingReminderBusinessDays(),
              s.autoResolveEnabled(),
              s.autoResolveBusinessDays(),
              s.autoCloseEnabled(),
              s.autoCloseCalendarDays(),
              s.apiAlertClientEmails(),
              s.defaultAssigneeUserId());
    }
    requireOrganisation(scope, organisationId);
    if (s.pendingReminderBusinessDays() < 1
        || s.pendingReminderBusinessDays() > 60
        || s.autoResolveBusinessDays() < 1
        || s.autoResolveBusinessDays() > 120
        || s.autoCloseCalendarDays() < 1
        || s.autoCloseCalendarDays() > 90) {
      throw ApiException.invalidRequest(
          "Reminder days must be 1-60, auto-resolve days 1-120 and auto-close days 1-90.");
    }
    config.saveTimers(scope, organisationId, s, clock.instant());
    audited(scope, organisationId, "timers", clientIp);
    return get(scope, organisationId);
  }

  @Transactional
  public OrganisationSla setDefaultAssignee(
      AccessScope scope, long organisationId, Long userId, String clientIp) {
    scope.requireAdmin();
    requireOrganisation(scope, organisationId);
    if (userId != null && !people.isActiveSupporterFor(scope, userId, organisationId)) {
      throw ApiException.invalidRequest(
          "The default assignee must be an active agent who works this organisation.");
    }
    config.setDefaultAssignee(scope, organisationId, userId, clock.instant());
    audited(scope, organisationId, "default_assignee", clientIp);
    return get(scope, organisationId);
  }

  @Transactional
  public OrganisationSla replaceBusinessHours(
      AccessScope scope, long organisationId, List<HoursRow> hours, String clientIp) {
    scope.requireAdmin();
    requireOrganisation(scope, organisationId);
    if (hours == null || hours.isEmpty() || hours.size() > 7) {
      throw ApiException.invalidRequest("Give business hours for one to seven weekdays.");
    }
    Set<Integer> days = new HashSet<>();
    for (HoursRow h : hours) {
      if (h.dayOfWeek() < 1 || h.dayOfWeek() > 7 || !days.add(h.dayOfWeek())) {
        throw ApiException.invalidRequest("Each weekday (1 = Monday ... 7 = Sunday) at most once.");
      }
      if (h.opens() == null || h.closes() == null) {
        throw ApiException.invalidRequest("Give an opening and a closing time.");
      }
      try {
        new DailyHours(h.opens(), h.closes());
      } catch (IllegalArgumentException e) {
        throw ApiException.invalidRequest("Closing time must be after opening time.");
      }
    }
    config.replaceBusinessHours(scope, organisationId, hours);
    audited(scope, organisationId, "business_hours", clientIp);
    return get(scope, organisationId);
  }

  @Transactional
  public OrganisationSla replaceHolidays(
      AccessScope scope, long organisationId, List<HolidayRow> holidays, String clientIp) {
    scope.requireAdmin();
    requireOrganisation(scope, organisationId);
    if (holidays == null || holidays.size() > MAX_HOLIDAYS) {
      throw ApiException.invalidRequest("Give at most " + MAX_HOLIDAYS + " holidays.");
    }
    Map<java.time.LocalDate, HolidayRow> unique = new LinkedHashMap<>();
    for (HolidayRow h : holidays) {
      String name = h.name() == null ? "" : h.name().strip().replaceAll("\\p{Cntrl}", " ");
      if (h.date() == null || name.isEmpty() || name.length() > 100) {
        throw ApiException.invalidRequest(
            "Each holiday needs a date and a name (1-100 characters).");
      }
      if (unique.put(h.date(), new HolidayRow(h.date(), name)) != null) {
        throw ApiException.invalidRequest("Each date at most once.");
      }
    }
    config.replaceHolidays(scope, organisationId, new ArrayList<>(unique.values()));
    audited(scope, organisationId, "holidays", clientIp);
    return get(scope, organisationId);
  }

  /** Saves the organisation default ({@code requestTypeId} null) or a request-type override. */
  @Transactional
  public OrganisationSla savePolicy(
      AccessScope scope,
      long organisationId,
      Long requestTypeId,
      boolean pendingOnVendorPauses,
      Map<Priority, TargetRow> targets,
      String clientIp) {
    scope.requireAdmin();
    requireOrganisation(scope, organisationId);
    if (requestTypeId != null && requestTypes.find(scope, requestTypeId).isEmpty()) {
      throw AccessScope.notFound();
    }
    if (targets == null || targets.size() != Priority.values().length) {
      throw ApiException.invalidRequest("Give targets for P1, P2, P3 and P4.");
    }
    BusinessCalendar standard = BusinessCalendar.standard(ZoneId.of("UTC"), List.of());
    for (Map.Entry<Priority, TargetRow> e : targets.entrySet()) {
      TargetRow t = e.getValue();
      if (t == null || t.firstResponse() == null || t.resolution() == null || t.clock() == null) {
        throw ApiException.invalidRequest("Each priority needs both targets and a clock.");
      }
      if (e.getKey() == Priority.P1 && t.clock() != ClockType.TWENTY_FOUR_SEVEN) {
        throw ApiException.invalidRequest("P1 always runs on the 24/7 clock.");
      }
      if (t.firstResponse().toMinutes(standard) > MAX_TARGET_MINUTES
          || t.resolution().toMinutes(standard) > MAX_TARGET_MINUTES) {
        throw ApiException.invalidRequest("A target can be at most one year.");
      }
      // Resolution cannot be due before the first response. Days are
      // compared on the priority's own clock (24 h, or one standard business day).
      BusinessCalendar own =
          t.clock() == ClockType.TWENTY_FOUR_SEVEN
              ? BusinessCalendar.twentyFourSeven(ZoneId.of("UTC"))
              : standard;
      if (t.resolution().toMinutes(own) < t.firstResponse().toMinutes(own)) {
        throw ApiException.invalidRequest(
            e.getKey() + ": the resolution target cannot be shorter than the first response.");
      }
    }
    config.savePolicy(
        scope, organisationId, requestTypeId, pendingOnVendorPauses, targets, clock.instant());
    audited(
        scope, organisationId, requestTypeId == null ? "policy" : "request_type_policy", clientIp);
    return get(scope, organisationId);
  }

  @Transactional
  public OrganisationSla deleteRequestTypePolicy(
      AccessScope scope, long organisationId, long requestTypeId, String clientIp) {
    scope.requireAdmin();
    requireOrganisation(scope, organisationId);
    if (!config.deleteRequestTypePolicy(scope, organisationId, requestTypeId)) {
      throw AccessScope.notFound();
    }
    audited(scope, organisationId, "request_type_policy_removed", clientIp);
    return get(scope, organisationId);
  }

  /** Request-type default assignee (shared by every organisation; checked per ticket). */
  @Transactional
  public void setRequestTypeDefaultAssignee(
      AccessScope scope, long requestTypeId, Long userId, String clientIp) {
    scope.requireAdmin();
    if (userId != null && !people.isActiveSupporterFor(scope, userId, null)) {
      throw ApiException.invalidRequest("The default assignee must be an active agent.");
    }
    if (!config.setRequestTypeDefaultAssignee(scope, requestTypeId, userId)) {
      throw AccessScope.notFound();
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("reason", "request_type_default_assignee");
    audit.record(
        SlaAuditEvent.SLA_SETTINGS_CHANGED,
        scope.principalId().orElse(null),
        userId,
        clientIp,
        details);
  }

  /**
   * Clears a supporter from every default-assignee setting ("Deactivating a supporter clears
   * them"). For the deactivation wizard ({@code DeactivationContributor}); auto- assignment also
   * ignores inactive supporters on its own.
   */
  @Transactional
  public int clearDefaultAssignee(AccessScope scope, long userId) {
    return config.clearDefaultAssignee(scope, userId, clock.instant());
  }

  private void requireOrganisation(AccessScope scope, long organisationId) {
    organisations.find(scope, organisationId).orElseThrow(AccessScope::notFound);
  }

  private void audited(AccessScope scope, long organisationId, String what, String clientIp) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("organisation", organisationId);
    details.put("reason", what);
    audit.record(
        SlaAuditEvent.SLA_SETTINGS_CHANGED,
        scope.principalId().orElse(null),
        null,
        clientIp,
        details);
  }

  /** Visible for tests: the default targets as stored rows. */
  public static Map<Priority, TargetRow> defaultTargets() {
    return targetsOf(SlaPolicy.defaults());
  }
}
