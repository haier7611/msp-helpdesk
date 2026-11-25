package com.ticketsystem.api.audit;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.admin.AdminConfigAuditEvent;
import com.ticketsystem.api.audit.clientdata.AuditLogRepository;
import com.ticketsystem.api.audit.clientdata.AuditLogRepository.EventRow;
import com.ticketsystem.api.audit.clientdata.AuditLogRepository.Filter;
import com.ticketsystem.api.auth.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Admin - Audit log viewer and CSV export (C14). Admins only. The log is kept one year, so the
 * viewer looks back at most 366 days. Dates are whole UTC days.
 *
 * <p>Every value in the log can be attacker-chosen (a device label from a User-Agent, an email
 * address typed at sign-in), so: the API returns them as data for the web app to show as text
 * (never HTML), and the CSV export neutralises spreadsheet formulas and strips control and
 * bidirectional-override characters in every cell. The export is itself audit-logged with its
 * filters and row count, and refuses (rather than silently truncates) more than {@link
 * #MAX_EXPORT_ROWS} rows.
 */
@Service
public class AuditLogService {

  public static final int PAGE_SIZE = 100;
  public static final int MAX_EXPORT_ROWS = 10_000;
  static final int MAX_DAYS = 366;
  private static final Pattern UNSAFE = Pattern.compile("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]");

  public record Query(
      LocalDate from, LocalDate to, String actor, String type, Long organisationId) {}

  public record Event(
      long id,
      Instant occurredAt,
      String type,
      Long actorUserId,
      String actorName,
      String actorEmail,
      Long targetUserId,
      String targetName,
      String targetEmail,
      String clientIp,
      Map<String, Object> details) {}

  public record Page(
      List<Event> events, Long nextBefore, LocalDate from, LocalDate to, List<String> types) {}

  private static final List<String> TYPES = List.copyOf(AuditEventRegistry.events().keySet());

  private final AuditLogRepository log;
  private final AuditService audit;
  private final JsonMapper json;
  private final Clock clock;

  public AuditLogService(AuditLogRepository log, AuditService audit, JsonMapper json, Clock clock) {
    this.log = log;
    this.audit = audit;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Page page(AccessScope scope, Query q, Long beforeId) {
    scope.requireAdmin();
    Range r = range(q);
    List<EventRow> rows = log.find(scope, filter(q, r), beforeId, PAGE_SIZE + 1);
    boolean more = rows.size() > PAGE_SIZE;
    List<Event> events = rows.stream().limit(PAGE_SIZE).map(this::event).toList();
    Long next = more ? events.get(events.size() - 1).id() : null;
    return new Page(events, next, r.fromDate(), r.toDate(), TYPES);
  }

  /** The filtered log as CSV (UTF-8, CRLF); the export writes its own audit event. */
  @Transactional
  public String csv(AccessScope scope, Query q, String clientIp) {
    scope.requireAdmin();
    Range r = range(q);
    List<EventRow> rows = log.find(scope, filter(q, r), null, MAX_EXPORT_ROWS + 1);
    if (rows.size() > MAX_EXPORT_ROWS) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "too_many_rows",
          "More than "
              + MAX_EXPORT_ROWS
              + " events match. Narrow the dates or filters and export again.");
    }
    StringBuilder out = new StringBuilder();
    out.append(
        "occurred_at_utc,event,actor_email,actor_name,target_email,target_name,client_ip,details\r\n");
    for (EventRow e : rows) {
      Object[] values = {
        e.occurredAt(),
        e.type(),
        e.actorEmail(),
        e.actorName(),
        e.targetEmail(),
        e.targetName(),
        e.clientIp(),
        detailsText(e.detailsJson())
      };
      for (int i = 0; i < values.length; i++) {
        if (i > 0) {
          out.append(',');
        }
        out.append(cell(values[i]));
      }
      out.append("\r\n");
    }
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("from", r.fromDate().toString());
    d.put("to", r.toDate().toString());
    d.put("rows", rows.size());
    // The actor search text (may be an email address) is deliberately not stored.
    if (q.type() != null && !q.type().isBlank()) d.put("action", q.type().strip());
    if (q.organisationId() != null) d.put("organisation", q.organisationId());
    audit.record(
        AdminConfigAuditEvent.AUDIT_LOG_EXPORTED,
        scope.principalId().orElse(null),
        null,
        clientIp,
        d);
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------

  private record Range(LocalDate fromDate, LocalDate toDate, Instant from, Instant to) {}

  private Range range(Query q) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    LocalDate to = q.to() == null ? today : q.to();
    LocalDate from = q.from() == null ? to.minusDays(29) : q.from();
    if (from.isAfter(to)) {
      throw ApiException.invalidRequest("The start date must be on or before the end date.");
    }
    if (from.isBefore(today.minusDays(MAX_DAYS))) {
      throw ApiException.invalidRequest("The audit log keeps one year. Choose a later start date.");
    }
    return new Range(
        from,
        to,
        from.atStartOfDay(ZoneOffset.UTC).toInstant(),
        to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
  }

  private static Filter filter(Query q, Range r) {
    String actor = q.actor() == null ? null : q.actor().strip().toLowerCase(Locale.ROOT);
    if (actor != null && actor.isEmpty()) {
      actor = null;
    }
    if (actor != null && actor.length() > 320) {
      throw ApiException.invalidRequest("Search for at most 320 characters of an email address.");
    }
    String type = q.type() == null || q.type().isBlank() ? null : q.type().strip();
    if (type != null && !TYPES.contains(type)) {
      throw ApiException.invalidRequest("Choose an event type from the list.");
    }
    return new Filter(r.from(), r.to(), actor, type, q.organisationId());
  }

  private Event event(EventRow r) {
    return new Event(
        r.id(),
        r.occurredAt(),
        r.type(),
        r.actorUserId(),
        r.actorName(),
        r.actorEmail(),
        r.targetUserId(),
        r.targetName(),
        r.targetEmail(),
        r.clientIp(),
        details(r.detailsJson()));
  }

  private Map<String, Object> details(String raw) {
    if (raw == null || raw.isBlank()) {
      return Map.of();
    }
    try {
      return json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  private String detailsText(String raw) {
    StringBuilder b = new StringBuilder();
    details(raw)
        .forEach(
            (k, v) -> {
              if (!b.isEmpty()) {
                b.append("; ");
              }
              b.append(k).append('=').append(v);
            });
    return b.toString();
  }

  /**
   * One CSV cell: control and bidi-override characters become spaces; a value starting (after any
   * spaces) with {@code = + - @} is prefixed with a quote so a spreadsheet never runs it as a
   * formula; quoted when it holds a comma, quote or line break.
   */
  static String cell(Object value) {
    if (value == null) {
      return "";
    }
    String s = UNSAFE.matcher(value.toString()).replaceAll(" ");
    String lead = s.stripLeading();
    if (!lead.isEmpty() && "=+-@".indexOf(lead.charAt(0)) >= 0) {
      s = "'" + s;
    }
    if (s.contains(",") || s.contains("\"")) {
      s = "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
  }
}
