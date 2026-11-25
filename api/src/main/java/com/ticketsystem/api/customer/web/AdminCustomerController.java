package com.ticketsystem.api.customer.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import com.ticketsystem.api.customer.AdminCustomerService;
import com.ticketsystem.api.customer.AdminCustomerService.CustomerDetail;
import com.ticketsystem.api.customer.AdminCustomerService.CustomerPage;
import com.ticketsystem.api.customer.AdminCustomerService.Invited;
import com.ticketsystem.api.customer.AdminCustomerService.Moved;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Customers: customers of every organisation and the admin actions on them. Admin tree only
 * (the role gate answers 403 to agents and customers; the service checks the admin scope again).
 * Force sign-out and moving to another organisation need a fresh step-up. Erasure is {@code POST
 * /api/v1/admin/users/{id}/erase} (step-up). Documented in {@code openapi/admin-customers.yaml}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/customers")
public class AdminCustomerController {

  public record MoveRequest(Long organisationId) {}

  private final AdminCustomerService customers;

  public AdminCustomerController(AdminCustomerService customers) {
    this.customers = customers;
  }

  @GetMapping
  CustomerPage search(
      AccessScope scope,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Long organisationId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int limit) {
    return customers.search(scope, q, organisationId, status, sort, page, limit);
  }

  @GetMapping("/{id}")
  CustomerDetail get(AccessScope scope, @PathVariable("id") long id) {
    return customers.get(scope, id);
  }

  @PostMapping("/{id}/invitation")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Invited resendInvitation(
      AccessScope scope, @PathVariable("id") long id, HttpServletRequest request) {
    return customers.resendInvitation(scope, id, request.getRemoteAddr());
  }

  @PostMapping("/{id}/password-reset")
  ResponseEntity<Void> passwordReset(
      AccessScope scope, @PathVariable("id") long id, HttpServletRequest request) {
    customers.sendPasswordReset(scope, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /** Ends every session of the customer. Step-up, as force sign-out for agents. */
  @PostMapping("/{id}/sign-out-everywhere")
  @RequiresStepUp
  ResponseEntity<Void> signOutEverywhere(
      AccessScope scope, @PathVariable("id") long id, HttpServletRequest request) {
    customers.signOutEverywhere(scope, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/deactivate")
  CustomerDetail deactivate(
      AccessScope scope, @PathVariable("id") long id, HttpServletRequest request) {
    return customers.deactivate(scope, id, request.getRemoteAddr());
  }

  @PostMapping("/{id}/reactivate")
  CustomerDetail reactivate(
      AccessScope scope, @PathVariable("id") long id, HttpServletRequest request) {
    return customers.reactivate(scope, id, request.getRemoteAddr());
  }

  /** Moves the customer to another organisation. Step-up. */
  @PutMapping("/{id}/organisation")
  @RequiresStepUp
  Moved move(
      AccessScope scope,
      @PathVariable("id") long id,
      @RequestBody MoveRequest body,
      HttpServletRequest request) {
    return customers.move(scope, id, body.organisationId(), request.getRemoteAddr());
  }
}
