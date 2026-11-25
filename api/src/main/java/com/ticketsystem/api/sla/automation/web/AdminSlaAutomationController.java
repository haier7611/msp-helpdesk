package com.ticketsystem.api.sla.automation.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.automation.SlaAutomationAdminService;
import com.ticketsystem.api.sla.automation.SlaAutomationAdminService.RuleInput;
import com.ticketsystem.api.sla.automation.SlaAutomationRule;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Action;
import com.ticketsystem.api.sla.automation.SlaAutomationRule.Conditions;
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
 * Admin - SLA automation. The {@code /api/v1/admin} tree is admin-only (agents and customers get
 * 403) and the service checks the admin scope again. No step-up. Documented in {@code
 * openapi/sla-automation.yaml}.
 */
@RestController
@RequestMapping("/api/v1/admin/sla-automation")
public class AdminSlaAutomationController {

  public record RuleBody(
      @NotNull @Size(max = 200) String name,
      Boolean enabled,
      @NotNull @Size(max = 40) String trigger,
      Conditions conditions,
      @Size(max = 20) List<Action> actions) {

    RuleInput toInput() {
      return new RuleInput(name, enabled, trigger, conditions, actions);
    }
  }

  public record EnabledBody(@NotNull Boolean enabled) {}

  public record OrderBody(@NotNull @Size(max = 500) List<Long> ids) {}

  private final SlaAutomationAdminService service;

  public AdminSlaAutomationController(SlaAutomationAdminService service) {
    this.service = service;
  }

  @GetMapping("/rules")
  List<SlaAutomationRule> list(AccessScope scope) {
    return service.list(scope);
  }

  @PostMapping("/rules")
  ResponseEntity<SlaAutomationRule> create(
      AccessScope scope, @Validated @RequestBody RuleBody body, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.create(scope, body.toInput(), request.getRemoteAddr()));
  }

  @PutMapping("/rules/{ruleId}")
  SlaAutomationRule update(
      AccessScope scope,
      @PathVariable long ruleId,
      @Validated @RequestBody RuleBody body,
      HttpServletRequest request) {
    return service.update(scope, ruleId, body.toInput(), request.getRemoteAddr());
  }

  @PutMapping("/rules/{ruleId}/enabled")
  SlaAutomationRule setEnabled(
      AccessScope scope,
      @PathVariable long ruleId,
      @Validated @RequestBody EnabledBody body,
      HttpServletRequest request) {
    return service.setEnabled(scope, ruleId, body.enabled(), request.getRemoteAddr());
  }

  @DeleteMapping("/rules/{ruleId}")
  ResponseEntity<Void> delete(
      AccessScope scope, @PathVariable long ruleId, HttpServletRequest request) {
    service.delete(scope, ruleId, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/order")
  List<SlaAutomationRule> reorder(
      AccessScope scope, @Validated @RequestBody OrderBody body, HttpServletRequest request) {
    return service.reorder(scope, body.ids(), request.getRemoteAddr());
  }
}
