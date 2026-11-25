package com.ticketsystem.api.audit.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditLogService;
import com.ticketsystem.api.audit.AuditLogService.Page;
import com.ticketsystem.api.audit.AuditLogService.Query;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Audit log: filtered pages and the CSV export (itself audit-logged). The {@code
 * /api/v1/admin} tree is admin-only; the service checks the admin scope again.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {

  private final AuditLogService service;

  public AdminAuditController(AuditLogService service) {
    this.service = service;
  }

  @GetMapping
  Page page(
      AccessScope scope,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) Long organisationId,
      @RequestParam(required = false) Long before) {
    return service.page(scope, new Query(from, to, actor, type, organisationId), before);
  }

  @GetMapping("/csv")
  ResponseEntity<byte[]> csv(
      AccessScope scope,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) Long organisationId,
      HttpServletRequest request) {
    String body =
        service.csv(
            scope, new Query(from, to, actor, type, organisationId), request.getRemoteAddr());
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"")
        .header("X-Content-Type-Options", "nosniff")
        .body(body.getBytes(StandardCharsets.UTF_8));
  }
}
