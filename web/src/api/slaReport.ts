import type { Priority } from "./agent";
import { apiFetch, errorFromResponse, fetchOrNetworkError } from "./client";

/**
 * SLA report (`GET /api/v1/agent/sla-report` and `/csv`; documented in
 * api/src/main/resources/openapi/sla-notifications.yaml). Agents and admins only and internal only:
 * the server scopes every row to the organisations the supporter can work, and audit-logs each CSV
 * export. Times are business minutes for P2-P4 and clock minutes for P1 (separate units).
 */

export type SlaReportFilter = {
  /** First day, "YYYY-MM-DD" (inclusive, in the MSP timezone). */
  from: string;
  /** Last day, "YYYY-MM-DD" (inclusive). */
  to: string;
  organisationId: string;
  engineer: string;
  requestTypeId: string;
  priority: string;
};

export type MetRate = {
  measured: number;
  met: number;
  breached: number;
  notApplicable: number;
  /** Null when nothing was measured. */
  metPercent: number | null;
};

export type TimeStats = {
  count: number;
  averageMinutes: number | null;
  medianMinutes: number | null;
};
export type Times = { firstResponse: TimeStats; resolution: TimeStats };
/** Business-hours tickets (P2-P4) and P1 (24/7 clock), reported separately. */
export type TimesByClock = { businessHours: Times; clockHours: Times };

export type BreachedItem = {
  ticketId: number;
  reference: string | null;
  /** Null for an Email triage ticket (triage runs the default SLA). */
  organisationId: number | null;
  priority: Priority;
  assigneeName: string | null;
  target: "FIRST_RESPONSE" | "RESOLUTION";
  overByMinutes: number;
};

export type EngineerRow = {
  userId: number;
  name: string | null;
  handled: number;
  resolution: MetRate;
  times: TimesByClock;
};

export type SlaReport = {
  from: string;
  to: string;
  zone: string;
  totals: { opened: number; resolved: number; stillOpen: number; duplicates: number };
  ageing: { days0to2: number; days3to7: number; days8to30: number; over30: number };
  firstResponse: MetRate;
  resolution: MetRate;
  times: TimesByClock;
  breached: BreachedItem[];
  engineers: EngineerRow[];
  /** How many ticket rows the report (and its CSV) holds; the rows themselves are not kept. */
  ticketCount: number;
  truncated: boolean;
};

const BASE = "/api/v1/agent/sla-report";

function shapeError(): Error {
  return new Error("API answered with an unexpected shape");
}

function obj(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw shapeError();
  return value as Record<string, unknown>;
}

function int(v: unknown): number {
  if (typeof v !== "number") throw shapeError();
  return v;
}

function maybeNum(v: unknown): number | null {
  return typeof v === "number" ? v : null;
}

function maybeStr(v: unknown): string | null {
  return typeof v === "string" ? v : null;
}

function arr(v: unknown): unknown[] {
  if (!Array.isArray(v)) throw shapeError();
  return v;
}

const PRIORITIES = new Set(["P1", "P2", "P3", "P4"]);

function priority(v: unknown): Priority {
  if (typeof v !== "string" || !PRIORITIES.has(v)) throw shapeError();
  return v as Priority;
}

function metRate(v: unknown): MetRate {
  const o = obj(v);
  return {
    measured: int(o.measured),
    met: int(o.met),
    breached: int(o.breached),
    notApplicable: int(o.notApplicable),
    metPercent: maybeNum(o.metPercent),
  };
}

function timeStats(v: unknown): TimeStats {
  const o = obj(v);
  return {
    count: int(o.count),
    averageMinutes: maybeNum(o.averageMinutes),
    medianMinutes: maybeNum(o.medianMinutes),
  };
}

function times(v: unknown): Times {
  const o = obj(v);
  return { firstResponse: timeStats(o.firstResponse), resolution: timeStats(o.resolution) };
}

function timesByClock(v: unknown): TimesByClock {
  const o = obj(v);
  return { businessHours: times(o.businessHours), clockHours: times(o.clockHours) };
}

export function parseSlaReport(value: unknown): SlaReport {
  const o = obj(value);
  const totals = obj(o.totals);
  const ageing = obj(o.ageing);
  return {
    from: maybeStr(o.from) ?? "",
    to: maybeStr(o.to) ?? "",
    zone: maybeStr(o.zone) ?? "",
    totals: {
      opened: int(totals.opened),
      resolved: int(totals.resolved),
      stillOpen: int(totals.stillOpen),
      duplicates: int(totals.duplicates),
    },
    ageing: {
      days0to2: int(ageing.days0to2),
      days3to7: int(ageing.days3to7),
      days8to30: int(ageing.days8to30),
      over30: int(ageing.over30),
    },
    firstResponse: metRate(o.firstResponse),
    resolution: metRate(o.resolution),
    times: timesByClock(o.times),
    breached: arr(o.breached).map((b) => {
      const r = obj(b);
      return {
        ticketId: int(r.ticketId),
        reference: maybeStr(r.reference),
        organisationId: maybeNum(r.organisationId),
        priority: priority(r.priority),
        assigneeName: maybeStr(r.assigneeName),
        target: r.target === "FIRST_RESPONSE" ? "FIRST_RESPONSE" : "RESOLUTION",
        overByMinutes: int(r.overByMinutes),
      };
    }),
    engineers: arr(o.engineers).map((e) => {
      const r = obj(e);
      return {
        userId: int(r.userId),
        name: maybeStr(r.name),
        handled: int(r.handled),
        resolution: metRate(r.resolution),
        times: timesByClock(r.times),
      };
    }),
    ticketCount: arr(o.tickets).length,
    truncated: o.truncated === true,
  };
}

export function slaReportQuery(f: SlaReportFilter): string {
  const p = new URLSearchParams({ from: f.from, to: f.to });
  if (f.organisationId) p.set("organisationId", f.organisationId);
  if (f.engineer) p.set("engineer", f.engineer);
  if (f.requestTypeId) p.set("requestTypeId", f.requestTypeId);
  if (f.priority) p.set("priority", f.priority);
  return p.toString();
}

export function getSlaReport(f: SlaReportFilter, signal?: AbortSignal): Promise<SlaReport> {
  return apiFetch(`${BASE}?${slaReportQuery(f)}`, parseSlaReport, { signal });
}

/**
 * Fetches the CSV export (the server audit-logs it). Fetched rather than linked so a refusal (a
 * bad range, an organisation the supporter cannot see) shows as a pop-up instead of a JSON page.
 */
export async function downloadSlaReportCsv(f: SlaReportFilter): Promise<Blob> {
  const response = await fetchOrNetworkError(`${BASE}/csv?${slaReportQuery(f)}`, {
    credentials: "same-origin",
    headers: { Accept: "text/csv, application/json" },
  });
  if (!response.ok) throw await errorFromResponse(response);
  return response.blob();
}
