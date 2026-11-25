package com.ticketsystem.api.ticket.admin.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.ticket.admin.RequestTypeAdminService;
import com.ticketsystem.api.ticket.admin.RequestTypeAdminService.AdminRequestType;
import com.ticketsystem.api.ticket.admin.RequestTypeAdminService.RequestTypeInput;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Request types. The {@code /api/v1/admin} tree is admin-only (agents and customers get
 * 403) and the service checks the admin scope again. The default assignee is set by {@code PUT
 * /api/v1/admin/request-types/{requestTypeId}/default-assignee}.
 */
@RestController
@RequestMapping("/api/v1/admin/request-types")
public class AdminRequestTypeController {

  public record RequestTypeBody(
      @NotNull @Size(max = 200) String name,
      @Size(max = 400) String description,
      @NotNull @Size(max = 4) String defaultPriority,
      boolean portalVisible,
      boolean archived) {

    RequestTypeInput toInput() {
      return new RequestTypeInput(name, description, defaultPriority, portalVisible, archived);
    }
  }

  public record OrderBody(@NotNull @Size(max = 500) List<Long> ids) {}

  private final RequestTypeAdminService service;

  public AdminRequestTypeController(RequestTypeAdminService service) {
    this.service = service;
  }

  @GetMapping
  List<AdminRequestType> list(AccessScope scope) {
    return service.list(scope);
  }

  @PostMapping
  ResponseEntity<AdminRequestType> create(
      AccessScope scope, @Validated @RequestBody RequestTypeBody body, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.create(scope, body.toInput(), request.getRemoteAddr()));
  }

  @PutMapping("/{requestTypeId}")
  AdminRequestType update(
      AccessScope scope,
      @PathVariable long requestTypeId,
      @Validated @RequestBody RequestTypeBody body,
      HttpServletRequest request) {
    return service.update(scope, requestTypeId, body.toInput(), request.getRemoteAddr());
  }

  @PutMapping("/order")
  List<AdminRequestType> reorder(
      AccessScope scope, @Validated @RequestBody OrderBody body, HttpServletRequest request) {
    return service.reorder(scope, body.ids(), request.getRemoteAddr());
  }
}
