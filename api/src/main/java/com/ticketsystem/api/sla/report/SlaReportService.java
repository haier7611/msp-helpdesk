package com.ticketsystem.api.sla.report;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.sla.SlaAuditEvent;
import com.ticketsystem.api.sla.TargetState;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.sla.clientdata.SlaReportRepository;
import com.ticketsystem.api.sla.clientdata.SlaReportRepository.Filter;
import com.ticketsystem.api.sla.clientdata.SlaReportRepository.ReportRow;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SLA report: any date range, filtered by organisation, engineer, request type and priority.
 * Internal only (agents and admins). Reads the values stored on each ticket.
 *
 * <p>Definitions:
 *
 * <ul>
 *   <li>Range: {@code from} to {@code to} inclusive, as dates in {@code zone} (default: the MSP
 *       timezone).
 *   <li>Opened = created in range; resolved = last set to Resolved in range (a reopened ticket is
 *       not resolved); still open = not Resolved or Closed now and created before the range ends,
 *       aged by creation date (0-2, 3-7, 8-30, over 30 whole days).
 *   <li>First response met % = met / (met + breached) over tickets opened in range; "not
 *       applicable" (resolved without a public reply) and still-running ones are left out.
 *       Resolution met % = met / (met + breached) over tickets resolved in range.
 *   <li>Average and median times: first response over tickets opened in range that have a reply;
 *       resolution over tickets resolved in range. Business minutes for P2-P4 and clock minutes for
 *       P1, reported separately (they are different units).
 *   <li>Breached list: every breached target of a ticket in the report, with by how much.
 *   <li>Per engineer: tickets resolved in range by assignee, resolution met % and the same times.
 *   <li><b>Duplicates</b>: tickets marked as a duplicate (closed, usually straight from New or
 *       Open) are counted in "opened" and as "duplicates" but left out of every SLA measure - met
 *       %, times, breached list and per engineer - because the work is measured on the ticket they
 *       duplicate.
 * </ul>
 */
@Service
public class SlaReportService {

  static final int MAX_ROWS = 20_000;
  static final int MAX_RANGE_DAYS = 400;

  public record Query(
      LocalDate from,
      LocalDate to,
      ZoneId zone,
      Long organisationId,
      Long assigneeUserId,
      Long requestTypeId,
      Priority priority) {}

  public record Totals(int opened, int resolved, int stillOpen, int duplicates) {}

  public record Ageing(int days0to2, int days3to7, int days8to30, int over30) {}

  public record MetRate(
      int measured, int met, int breached, int notApplicable, Double metPercent) {}

  public record TimeStats(int count, Double averageMinutes, Double medianMinutes) {}

  /** First response and resolution times on one clock. */
  public record Times(TimeStats firstResponse, TimeStats resolution) {}

  /** Business-hours tickets (P2-P4, business minutes) and P1 (24/7, clock minutes). */
  public record TimesByClock(Times businessHours, Times clockHours) {}

  public record BreachedItem(
      long ticketId,
      String reference,
      Long organisationId,
      Priority priority,
      Long assigneeUserId,
      String assigneeName,
      String target,
      long overByMinutes) {}

  public record EngineerRow(
      long userId, String name, int handled, MetRate resolution, TimesByClock times) {}

  /** One ticket as reported (also the CSV row). */
  public record TicketRow(
      long ticketId,
      String reference,
      Long organisationId,
      String subject,
      Priority priority,
      TicketStatus status,
      Long assigneeUserId,
      String assigneeName,
      long requestTypeId,
      boolean duplicate,
      Instant createdAt,
      Instant firstRespondedAt,
      Instant firstResponseDueAt,
      Long firstResponseTargetMinutes,
      Long firstResponseUsedMinutes,
      TargetState firstResponseState,
      Instant resolvedAt,
      Instant resolutionDueAt,
      Long resolutionTargetMinutes,
      Long resolutionUsedMinutes,
      TargetState resolutionState,
      Long pausedMinutes,
      String clock) {}

  public record Report(
      LocalDate from,
      LocalDate to,
      String zone,
      Totals totals,
      Ageing ageing,
      MetRate firstResponse,
      MetRate resolution,
      TimesByClock times,
      List<BreachedItem> breached,
      List<EngineerRow> engineers,
      List<TicketRow> tickets,
      boolean truncated) {}

  private final SlaReportRepository repository;
  private final ReferenceService references;
  private final TicketPeopleRepository people;
  private final AuditService audit;
  private final Clock clock;
  private final ZoneId defaultZone;

  public SlaReportService(
      SlaReportRepository repository,
      ReferenceService references,
      TicketPeopleRepository people,
      AuditService audit,
      Clock clock,
      @Value("${app.notifications.default-timezone:Asia/Ho_Chi_Minh}") String defaultZone) {
    this.repository = repository;
    this.references = references;
    this.people = people;
    this.audit = audit;
    this.clock = clock;
    this.defaultZone = ZoneId.of(defaultZone);
  }

  public ZoneId defaultZone() {
    return defaultZone;
  }

  @Transactional(readOnly = true)
  public Report report(AccessScope scope, Query q) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    if (q.from() == null || q.to() == null || q.to().isBefore(q.from())) {
      throw ApiException.invalidRequest("Choose a date range: from on or before to.");
    }
    if (q.from().plusDays(MAX_RANGE_DAYS).isBefore(q.to())) {
      throw ApiException.invalidRequest("The range can be at most " + MAX_RANGE_DAYS + " days.");
    }
    if (q.organisationId() != null) {
      scope.require(q.organisationId());
    }
    ZoneId zone = q.zone() == null ? defaultZone : q.zone();
    Instant from = q.from().atStartOfDay(zone).toInstant();
    Instant to = q.to().plusDays(1).atStartOfDay(zone).toInstant();
    List<ReportRow> rows =
        repository.rows(
            scope,
            new Filter(q.organisationId(), q.assigneeUserId(), q.requestTypeId(), q.priority()),
            from,
            to,
            MAX_ROWS + 1);
    boolean truncated = rows.size() > MAX_ROWS;
    if (truncated) {
      rows = rows.subList(0, MAX_ROWS);
    }
    return build(scope, q.from(), q.to(), zone, from, to, rows, truncated);
  }

  Report build(
      AccessScope scope,
      LocalDate fromDate,
      LocalDate toDate,
      ZoneId zone,
      Instant from,
      Instant to,
      List<ReportRow> rows,
      boolean truncated) {
    Instant now = clock.instant();
    Predicate<ReportRow> opened = r -> in(r.createdAt(), from, to);
    Predicate<ReportRow> resolved = r -> in(r.resolvedAt(), from, to);
    Predicate<ReportRow> open =
        r -> r.status() != TicketStatus.RESOLVED && r.status() != TicketStatus.CLOSED;
    Predicate<ReportRow> measurable = r -> r.duplicateOfId() == null;

    int nOpened = 0;
    int nResolved = 0;
    int nOpen = 0;
    int nDuplicates = 0;
    int[] ageing = new int[4];
    for (ReportRow r : rows) {
      if (opened.test(r)) {
        nOpened++;
        if (!measurable.test(r)) {
          nDuplicates++;
        }
      }
      if (resolved.test(r)) {
        nResolved++;
      }
      if (open.test(r)) {
        nOpen++;
        long days = Duration.between(r.createdAt(), now).toDays();
        ageing[days <= 2 ? 0 : days <= 7 ? 1 : days <= 30 ? 2 : 3]++;
      }
    }
    List<ReportRow> openedMeasured = rows.stream().filter(opened.and(measurable)).toList();
    List<ReportRow> resolvedMeasured = rows.stream().filter(resolved.and(measurable)).toList();

    Set<Long> userIds = new HashSet<>();
    rows.stream().map(ReportRow::assigneeUserId).filter(id -> id != null).forEach(userIds::add);
    Map<Long, String> names = people.names(scope, userIds);
    Map<Long, TicketReference> refs =
        references.displayReferences(scope, rows.stream().map(ReportRow::id).toList());

    List<BreachedItem> breached = new ArrayList<>();
    for (ReportRow r : rows) {
      if (!measurable.test(r)) {
        continue;
      }
      if (r.firstResponseState() == TargetState.BREACHED) {
        breached.add(
            breach(
                r,
                "FIRST_RESPONSE",
                r.firstResponseUsedMinutes(),
                r.firstResponseTargetMinutes(),
                refs,
                names));
      }
      if (r.resolutionState() == TargetState.BREACHED) {
        breached.add(
            breach(
                r,
                "RESOLUTION",
                r.resolutionUsedMinutes(),
                r.resolutionTargetMinutes(),
                refs,
                names));
      }
    }

    Map<Long, List<ReportRow>> byEngineer = new TreeMap<>();
    for (ReportRow r : resolvedMeasured) {
      if (r.assigneeUserId() != null) {
        byEngineer.computeIfAbsent(r.assigneeUserId(), k -> new ArrayList<>()).add(r);
      }
    }
    List<EngineerRow> engineers = new ArrayList<>();
    byEngineer.forEach(
        (user, list) ->
            engineers.add(
                new EngineerRow(
                    user,
                    names.get(user),
                    list.size(),
                    resolutionRate(list),
                    times(
                        rows.stream()
                            .filter(opened.and(measurable))
                            .filter(r -> user.equals(r.assigneeUserId()))
                            .toList(),
                        list))));
    engineers.sort(Comparator.comparing(EngineerRow::userId));

    List<TicketRow> tickets =
        rows.stream()
            .map(
                r ->
                    new TicketRow(
                        r.id(),
                        display(refs.get(r.id())),
                        r.organisationId(),
                        r.subject(),
                        r.priority(),
                        r.status(),
                        r.assigneeUserId(),
                        r.assigneeUserId() == null ? null : names.get(r.assigneeUserId()),
                        r.requestTypeId(),
                        r.duplicateOfId() != null,
                        r.createdAt(),
                        r.firstRespondedAt(),
                        r.firstResponseDueAt(),
                        r.firstResponseTargetMinutes(),
                        r.firstResponseUsedMinutes(),
                        r.firstResponseState(),
                        r.resolvedAt(),
                        r.resolutionDueAt(),
                        r.resolutionTargetMinutes(),
                        r.resolutionUsedMinutes(),
                        r.resolutionState(),
                        r.pausedMinutes(),
                        r.clock()))
            .toList();

    return new Report(
        fromDate,
        toDate,
        zone.getId(),
        new Totals(nOpened, nResolved, nOpen, nDuplicates),
        new Ageing(ageing[0], ageing[1], ageing[2], ageing[3]),
        firstResponseRate(openedMeasured),
        resolutionRate(resolvedMeasured),
        times(openedMeasured, resolvedMeasured),
        breached,
        engineers,
        tickets,
        truncated);
  }

  static MetRate firstResponseRate(List<ReportRow> rows) {
    int met = 0;
    int breached = 0;
    int na = 0;
    for (ReportRow r : rows) {
      if (r.firstResponseState() == TargetState.MET) {
        met++;
      } else if (r.firstResponseState() == TargetState.BREACHED) {
        breached++;
      } else if (r.firstResponseState() == TargetState.STOPPED) {
        na++;
      }
    }
    return rate(met, breached, na);
  }

  static MetRate resolutionRate(List<ReportRow> rows) {
    int met = 0;
    int breached = 0;
    for (ReportRow r : rows) {
      if (r.resolutionState() == TargetState.MET) {
        met++;
      } else if (r.resolutionState() == TargetState.BREACHED) {
        breached++;
      }
    }
    return rate(met, breached, 0);
  }

  private static MetRate rate(int met, int breached, int na) {
    int measured = met + breached;
    Double pct = measured == 0 ? null : round1(met * 100.0 / measured);
    return new MetRate(measured, met, breached, na, pct);
  }

  static TimesByClock times(List<ReportRow> openedRows, List<ReportRow> resolvedRows) {
    return new TimesByClock(
        new Times(
            stats(firstResponseTimes(openedRows, false)),
            stats(resolutionTimes(resolvedRows, false))),
        new Times(
            stats(firstResponseTimes(openedRows, true)),
            stats(resolutionTimes(resolvedRows, true))));
  }

  private static List<Long> firstResponseTimes(List<ReportRow> rows, boolean p1) {
    return rows.stream()
        .filter(r -> (r.priority() == Priority.P1) == p1)
        .filter(r -> r.firstRespondedAt() != null && r.firstResponseUsedMinutes() != null)
        .filter(
            r ->
                r.firstResponseState() == TargetState.MET
                    || r.firstResponseState() == TargetState.BREACHED)
        .map(ReportRow::firstResponseUsedMinutes)
        .toList();
  }

  private static List<Long> resolutionTimes(List<ReportRow> rows, boolean p1) {
    return rows.stream()
        .filter(r -> (r.priority() == Priority.P1) == p1)
        .filter(r -> r.resolutionUsedMinutes() != null)
        .map(ReportRow::resolutionUsedMinutes)
        .toList();
  }

  static TimeStats stats(List<Long> values) {
    if (values.isEmpty()) {
      return new TimeStats(0, null, null);
    }
    List<Long> sorted = values.stream().sorted().toList();
    double sum = 0;
    for (long v : sorted) {
      sum += v;
    }
    int n = sorted.size();
    double median =
        n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    return new TimeStats(n, round1(sum / n), round1(median));
  }

  private static Double round1(double v) {
    return Math.round(v * 10.0) / 10.0;
  }

  private static boolean in(Instant t, Instant from, Instant to) {
    return t != null && !t.isBefore(from) && t.isBefore(to);
  }

  private static BreachedItem breach(
      ReportRow r,
      String target,
      Long used,
      Long targetMinutes,
      Map<Long, TicketReference> refs,
      Map<Long, String> names) {
    long over = used == null || targetMinutes == null ? 0 : Math.max(0, used - targetMinutes);
    return new BreachedItem(
        r.id(),
        display(refs.get(r.id())),
        r.organisationId(),
        r.priority(),
        r.assigneeUserId(),
        r.assigneeUserId() == null ? null : names.get(r.assigneeUserId()),
        target,
        over);
  }

  private static String display(TicketReference ref) {
    return ref == null ? null : ref.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // CSV (audit-logged)

  static final String[] CSV_HEADER = {
    "reference",
    "organisation_id",
    "subject",
    "priority",
    "status",
    "assignee",
    "request_type_id",
    "duplicate",
    "created_at",
    "first_responded_at",
    "first_response_due_at",
    "first_response_target_minutes",
    "first_response_used_minutes",
    "first_response_state",
    "resolved_at",
    "resolution_due_at",
    "resolution_target_minutes",
    "resolution_used_minutes",
    "resolution_state",
    "paused_minutes",
    "clock"
  };

  /** The report's tickets as CSV (the same rows as the page); the export is audit-logged. */
  @Transactional
  public String csv(AccessScope scope, Query q, String clientIp) {
    Report report = report(scope, q);
    StringBuilder out = new StringBuilder();
    out.append(String.join(",", CSV_HEADER)).append("\r\n");
    for (TicketRow t : report.tickets()) {
      Object[] values = {
        t.reference(),
        t.organisationId(),
        t.subject(),
        t.priority(),
        t.status(),
        t.assigneeName(),
        t.requestTypeId(),
        t.duplicate() ? "yes" : "no",
        t.createdAt(),
        t.firstRespondedAt(),
        t.firstResponseDueAt(),
        t.firstResponseTargetMinutes(),
        t.firstResponseUsedMinutes(),
        t.firstResponseState(),
        t.resolvedAt(),
        t.resolutionDueAt(),
        t.resolutionTargetMinutes(),
        t.resolutionUsedMinutes(),
        t.resolutionState(),
        t.pausedMinutes(),
        t.clock()
      };
      for (int i = 0; i < values.length; i++) {
        if (i > 0) {
          out.append(',');
        }
        out.append(cell(values[i]));
      }
      out.append("\r\n");
    }
    Map<String, Object> details = new LinkedHashMap<>();
    if (q.organisationId() != null) {
      details.put("organisation", q.organisationId());
    }
    details.put("from", report.from().toString());
    details.put("to", report.to().toString());
    details.put("rows", report.tickets().size());
    audit.record(
        SlaAuditEvent.SLA_REPORT_EXPORTED,
        scope.principalId().orElse(null),
        null,
        clientIp,
        details);
    return out.toString();
  }

  /**
   * One CSV cell: quoted when needed, and a leading {@code = + - @} (or tab/CR) neutralised with a
   * quote so a spreadsheet never runs a client-written subject as a formula.
   */
  static String cell(Object value) {
    if (value == null) {
      return "";
    }
    String s = value.toString();
    if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
      s = "'" + s;
    }
    if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
      s = "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
  }
}
