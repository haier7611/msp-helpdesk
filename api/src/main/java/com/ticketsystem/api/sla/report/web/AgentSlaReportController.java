package com.ticketsystem.api.sla.report.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.report.SlaReportService;
import com.ticketsystem.api.sla.report.SlaReportService.Query;
import com.ticketsystem.api.sla.report.SlaReportService.Report;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SLA report page's API and its CSV export. Agents and admins only (the {@code /api/v1/agent}
 * tree); a restricted agent sees only their organisations. Every CSV export is audit-logged.
 * Internal only: nothing here is reachable from the portal or an API key.
 */
@RestController
@RequestMapping("/api/v1/agent/sla-report")
public class AgentSlaReportController {

  private final SlaReportService reports;

  public AgentSlaReportController(SlaReportService reports) {
    this.reports = reports;
  }

  @GetMapping
  Report report(
      AccessScope scope,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String zone,
      @RequestParam(required = false) Long organisationId,
      @RequestParam(required = false) Long engineer,
      @RequestParam(required = false) Long requestTypeId,
      @RequestParam(required = false) Priority priority) {
    return reports.report(
        scope, query(from, to, zone, organisationId, engineer, requestTypeId, priority));
  }

  @GetMapping("/csv")
  ResponseEntity<byte[]> csv(
      AccessScope scope,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String zone,
      @RequestParam(required = false) Long organisationId,
      @RequestParam(required = false) Long engineer,
      @RequestParam(required = false) Long requestTypeId,
      @RequestParam(required = false) Priority priority,
      HttpServletRequest request) {
    String body =
        reports.csv(
            scope,
            query(from, to, zone, organisationId, engineer, requestTypeId, priority),
            request.getRemoteAddr());
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"sla-report-" + from + "-to-" + to + ".csv\"")
        .header("X-Content-Type-Options", "nosniff")
        .body(body.getBytes(StandardCharsets.UTF_8));
  }

  private Query query(
      LocalDate from,
      LocalDate to,
      String zone,
      Long organisationId,
      Long engineer,
      Long requestTypeId,
      Priority priority) {
    ZoneId z;
    try {
      z = zone == null || zone.isBlank() ? reports.defaultZone() : ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw ApiException.invalidRequest("Unknown timezone.");
    }
    return new Query(from, to, z, organisationId, engineer, requestTypeId, priority);
  }
}
