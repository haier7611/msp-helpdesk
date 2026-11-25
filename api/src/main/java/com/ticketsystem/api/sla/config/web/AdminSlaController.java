package com.ticketsystem.api.sla.config.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.config.SlaConfigService;
import com.ticketsystem.api.sla.config.SlaConfigService.OrganisationSla;
import com.ticketsystem.api.sla.config.SlaConfigTypes.HolidayRow;
import com.ticketsystem.api.sla.config.SlaConfigTypes.HoursRow;
import com.ticketsystem.api.sla.config.SlaConfigTypes.SettingsRow;
import com.ticketsystem.api.sla.config.SlaConfigTypes.TargetRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for SLA policies, business hours, holidays, Pending timers, API alert client emails and
 * default assignees. Admins only (the {@code /api/v1/admin} tree, and the service checks again);
 * every change is audit-logged.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminSlaController {

  /**
   * {@code pendingReminderEnabled} is optional: the switch lives in Admin - Organisations - "Client
   * emails" (same stored value); left out or null keeps the stored value.
   */
  public record TimersRequest(
      Boolean pendingReminderEnabled,
      int pendingReminderBusinessDays,
      boolean autoResolveEnabled,
      int autoResolveBusinessDays,
      boolean autoCloseEnabled,
      int autoCloseCalendarDays,
      boolean apiAlertClientEmails) {}

  public record AssigneeRequest(Long userId) {}

  public record PolicyRequest(
      boolean pendingOnVendorPauses, @NotNull Map<Priority, TargetRow> targets) {}

  private final SlaConfigService service;

  public AdminSlaController(SlaConfigService service) {
    this.service = service;
  }

  @GetMapping("/organisations/{id}/sla")
  OrganisationSla get(AccessScope scope, @PathVariable long id) {
    return service.get(scope, id);
  }

  @PutMapping("/organisations/{id}/sla/timers")
  OrganisationSla timers(
      AccessScope scope,
      @PathVariable long id,
      @RequestBody TimersRequest body,
      HttpServletRequest request) {
    return service.saveTimers(
        scope,
        id,
        new SettingsRow(
            body.pendingReminderEnabled() == null || body.pendingReminderEnabled(),
            body.pendingReminderBusinessDays(),
            body.autoResolveEnabled(),
            body.autoResolveBusinessDays(),
            body.autoCloseEnabled(),
            body.autoCloseCalendarDays(),
            body.apiAlertClientEmails(),
            null),
        body.pendingReminderEnabled() == null,
        request.getRemoteAddr());
  }

  @PutMapping("/organisations/{id}/sla/default-assignee")
  OrganisationSla defaultAssignee(
      AccessScope scope,
      @PathVariable long id,
      @RequestBody AssigneeRequest body,
      HttpServletRequest request) {
    return service.setDefaultAssignee(scope, id, body.userId(), request.getRemoteAddr());
  }

  @PutMapping("/organisations/{id}/sla/business-hours")
  OrganisationSla businessHours(
      AccessScope scope,
      @PathVariable long id,
      @Validated @RequestBody @Size(max = 7) List<HoursRow> body,
      HttpServletRequest request) {
    return service.replaceBusinessHours(scope, id, body, request.getRemoteAddr());
  }

  @PutMapping("/organisations/{id}/sla/holidays")
  OrganisationSla holidays(
      AccessScope scope,
      @PathVariable long id,
      @RequestBody List<HolidayRow> body,
      HttpServletRequest request) {
    return service.replaceHolidays(scope, id, body, request.getRemoteAddr());
  }

  @PutMapping("/organisations/{id}/sla/policies/default")
  OrganisationSla defaultPolicy(
      AccessScope scope,
      @PathVariable long id,
      @Validated @RequestBody PolicyRequest body,
      HttpServletRequest request) {
    return service.savePolicy(
        scope, id, null, body.pendingOnVendorPauses(), body.targets(), request.getRemoteAddr());
  }

  @PutMapping("/organisations/{id}/sla/policies/request-types/{requestTypeId}")
  OrganisationSla requestTypePolicy(
      AccessScope scope,
      @PathVariable long id,
      @PathVariable long requestTypeId,
      @Validated @RequestBody PolicyRequest body,
      HttpServletRequest request) {
    return service.savePolicy(
        scope,
        id,
        requestTypeId,
        body.pendingOnVendorPauses(),
        body.targets(),
        request.getRemoteAddr());
  }

  @DeleteMapping("/organisations/{id}/sla/policies/request-types/{requestTypeId}")
  OrganisationSla deleteRequestTypePolicy(
      AccessScope scope,
      @PathVariable long id,
      @PathVariable long requestTypeId,
      HttpServletRequest request) {
    return service.deleteRequestTypePolicy(scope, id, requestTypeId, request.getRemoteAddr());
  }

  @PutMapping("/request-types/{requestTypeId}/default-assignee")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void requestTypeDefaultAssignee(
      AccessScope scope,
      @PathVariable long requestTypeId,
      @RequestBody AssigneeRequest body,
      HttpServletRequest request) {
    service.setRequestTypeDefaultAssignee(
        scope, requestTypeId, body.userId(), request.getRemoteAddr());
  }
}
