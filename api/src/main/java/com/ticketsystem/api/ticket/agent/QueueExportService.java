package com.ticketsystem.api.ticket.agent;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.ticket.CloseReason;
import com.ticketsystem.api.ticket.TicketAuditEvent;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.QueueQuery;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketSummary;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV export of the filtered queue. Uses the same query as the queue screen, so the file matches
 * what the supporter sees; every export is audit-logged with the row count and the organisation and
 * status filters - never the search text, which may hold personal data. The last column is the
 * close reason of a closed ticket (with the Other note), neutralised against CSV injection like
 * every cell.
 */
@Service
public class QueueExportService {

  /** Most rows in one export; the response says when the file was cut short. */
  public static final int MAX_ROWS = 10_000;

  /** The CSV text and whether it was capped at {@link #MAX_ROWS}. */
  public record Export(String csv, int rows, boolean truncated) {}

  static final String HEADER =
      "Reference,Organisation,Subject,Status,Priority,Request type,Assignee,Reporter,Source,"
          + "Created,Updated,First response time,Resolution time,SLA met,Close reason";

  private final AgentTicketQueryService queries;
  private final AuditService audit;

  public QueueExportService(AgentTicketQueryService queries, AuditService audit) {
    this.queries = queries;
    this.audit = audit;
  }

  @Transactional
  public Export export(AccessScope scope, QueueQuery query, String clientIp) {
    List<TicketSummary> rows = queries.exportRows(scope, query, MAX_ROWS + 1);
    boolean truncated = rows.size() > MAX_ROWS;
    if (truncated) {
      rows = rows.subList(0, MAX_ROWS);
    }
    StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
    for (TicketSummary t : rows) {
      String organisation =
          t.organisationName() != null
              ? t.organisationName()
              : (t.organisationId() == null ? "Email triage" : "");
      line(
          csv,
          t.reference(),
          organisation,
          t.subject(),
          t.status().name(),
          t.priority().name(),
          t.requestTypeName(),
          t.assigneeName(),
          t.reporterName() != null ? t.reporterName() : t.reporterEmail(),
          t.source().name(),
          iso(t.createdAt()),
          iso(t.updatedAt()),
          t.sla() == null ? "" : iso(t.sla().firstRespondedAt()),
          t.sla() == null ? "" : iso(t.sla().resolvedAt()),
          slaMet(t.sla()),
          CloseReason.describe(CloseReason.parseOrNull(t.closeReason()), t.closeNote()));
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("rows", rows.size());
    if (query.organisationId() != null) {
      details.put("organisation", query.organisationId());
    }
    if (query.status() != null) {
      details.put("status", query.status().name());
    }
    audit.record(
        TicketAuditEvent.QUEUE_EXPORTED, scope.principalId().orElse(null), null, clientIp, details);
    return new Export(csv.toString(), rows.size(), truncated);
  }

  /** "No" once either target breached, "Yes" when both were met, blank while still open. */
  private static String slaMet(AgentTicketViews.SlaView sla) {
    if (sla == null) {
      return "";
    }
    String first = sla.firstResponse().state();
    String resolution = sla.resolution().state();
    if ("BREACHED".equals(first) || "BREACHED".equals(resolution)) {
      return "No";
    }
    return "MET".equals(first) && "MET".equals(resolution) ? "Yes" : "";
  }

  private static String iso(Instant i) {
    return i == null ? "" : i.toString();
  }

  private static void line(StringBuilder out, String... cells) {
    for (int i = 0; i < cells.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(cell(cells[i]));
    }
    out.append("\r\n");
  }

  /**
   * One RFC 4180 cell. A value a spreadsheet would run as a formula gets a leading apostrophe
   * (OWASP CSV injection guidance): it starts with =, +, -, @, tab or carriage return, once leading
   * whitespace and control characters (which spreadsheets skip) are ignored.
   */
  public static String cell(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    String v = value;
    if (startsLikeFormula(v)) {
      v = "'" + v;
    }
    if (v.indexOf(',') >= 0
        || v.indexOf('"') >= 0
        || v.indexOf('\n') >= 0
        || v.indexOf('\r') >= 0) {
      v = '"' + v.replace("\"", "\"\"") + '"';
    }
    return v;
  }

  private static boolean startsLikeFormula(String v) {
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c == '\t' || c == '\r') {
        return true;
      }
      if (c <= ' ' || c == '\u007f' || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
        continue;
      }
      return c == '=' || c == '+' || c == '-' || c == '@';
    }
    return false;
  }
}
