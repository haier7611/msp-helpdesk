import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { Link } from "react-router";
import {
  listAgentOrganisations,
  listRequestTypes,
  listSupporters,
  PRIORITY_LABELS,
  type AgentOrganisation,
  type RequestType,
  type Supporter,
} from "../../api/agent";
import {
  downloadSlaReportCsv,
  getSlaReport,
  type BreachedItem,
  type EngineerRow,
  type MetRate,
  type SlaReport,
  type SlaReportFilter,
  type TimesByClock,
  type TimeStats,
} from "../../api/slaReport";
import { PriorityBadge } from "../../components/Badge";
import { DataTable, type Column } from "../../components/DataTable";
import { Button, Select, TextField } from "../../components/forms";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import { useReportError } from "../../components/useReportError";
import { formatDay } from "../../format";
import styles from "../pages.module.css";
import { formatMinutes, localDay } from "./reportFormat";

function percent(rate: MetRate): string {
  return rate.metPercent === null ? "-" : `${rate.metPercent}%`;
}

function metSub(rate: MetRate): string {
  if (rate.measured === 0) return "Nothing measured yet";
  return `${rate.met} of ${rate.measured} met`;
}

/** Average with the P1 (24/7 clock) figure underneath when there were P1 tickets. */
function TimeCell({ business, clock }: { business: TimeStats; clock: TimeStats }) {
  return (
    <>
      {formatMinutes(business.averageMinutes)}
      {clock.count > 0 && (
        <div className={styles.subtle}>P1: {formatMinutes(clock.averageMinutes)}</div>
      )}
    </>
  );
}

const TARGET_LABELS: Record<BreachedItem["target"], string> = {
  FIRST_RESPONSE: "First response",
  RESOLUTION: "Resolution",
};

type TimesRow = { key: string; label: string; stats: TimeStats };

function timesRows(times: TimesByClock): TimesRow[] {
  return [
    {
      key: "fr-bh",
      label: "First response - P2 to P4 (business hours)",
      stats: times.businessHours.firstResponse,
    },
    {
      key: "fr-p1",
      label: "First response - P1 (24/7)",
      stats: times.clockHours.firstResponse,
    },
    {
      key: "res-bh",
      label: "Resolution - P2 to P4 (business hours)",
      stats: times.businessHours.resolution,
    },
    { key: "res-p1", label: "Resolution - P1 (24/7)", stats: times.clockHours.resolution },
  ];
}

const timesColumns: Column<TimesRow>[] = [
  { key: "label", header: "Measure", rowHeader: true, render: (r) => r.label },
  { key: "count", header: "Tickets", render: (r) => r.stats.count },
  {
    key: "avg",
    header: "Average",
    nowrap: true,
    render: (r) => formatMinutes(r.stats.averageMinutes),
  },
  {
    key: "median",
    header: "Median",
    nowrap: true,
    render: (r) => formatMinutes(r.stats.medianMinutes),
  },
];

function isEmpty(r: SlaReport): boolean {
  return (
    r.totals.opened === 0 &&
    r.totals.resolved === 0 &&
    r.totals.stillOpen === 0 &&
    r.breached.length === 0 &&
    r.ticketCount === 0
  );
}

function Tile({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <li className={styles.tile}>
      <div className={styles.tileLabel}>{label}</div>
      <div className={styles.tileValue}>{value}</div>
      {sub && <div className={styles.tileSub}>{sub}</div>}
    </li>
  );
}

/**
 * Agent - Reports - SLA: the real SLA report for a date range, filtered by
 * organisation, engineer, request type and priority, with the CSV export (audit-logged). Internal
 * only. The server scopes every figure to the organisations the supporter can work.
 */
export function ReportsPage() {
  const toast = useToast();
  const report = useReportError();
  const [filters, setFilters] = useState<SlaReportFilter>(() => ({
    from: localDay(-29),
    to: localDay(),
    organisationId: "",
    engineer: "",
    requestTypeId: "",
    priority: "",
  }));
  const [applied, setApplied] = useState<SlaReportFilter>(filters);
  const [data, setData] = useState<SlaReport | null>(null);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [rangeError, setRangeError] = useState("");
  const [busy, setBusy] = useState(false);
  const [orgs, setOrgs] = useState<AgentOrganisation[]>([]);
  const [supporters, setSupporters] = useState<Supporter[]>([]);
  const [requestTypes, setRequestTypes] = useState<RequestType[]>([]);

  useEffect(() => {
    const controller = new AbortController();
    listAgentOrganisations(controller.signal).then(setOrgs, (e: unknown) =>
      report(e, "Organisations could not be loaded for the filters."),
    );
    listSupporters(null, controller.signal).then(setSupporters, (e: unknown) =>
      report(e, "Engineers could not be loaded for the filters."),
    );
    listRequestTypes(controller.signal).then(setRequestTypes, (e: unknown) =>
      report(e, "Request types could not be loaded for the filters."),
    );
    return () => controller.abort();
  }, [report]);

  useEffect(() => {
    const controller = new AbortController();
    getSlaReport(applied, controller.signal)
      .then((r) => {
        setData(r);
        setFailed(false);
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        setData(null);
        setFailed(true);
        report(e, "The SLA report could not be loaded.");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [applied, report]);

  function onFilter(event: FormEvent) {
    event.preventDefault();
    if (!filters.from || !filters.to) {
      setRangeError("Choose both dates.");
      return;
    }
    if (filters.to < filters.from) {
      setRangeError("The end date must be on or after the start date.");
      return;
    }
    setRangeError("");
    setLoading(true);
    setApplied({ ...filters });
  }

  async function exportCsv() {
    setBusy(true);
    try {
      const blob = await downloadSlaReportCsv(applied);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `sla-report-${applied.from}-to-${applied.to}.csv`;
      document.body.append(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      toast.show("SLA report exported. The export is recorded in the audit log.", "success");
    } catch (e) {
      report(e, "The CSV was not exported.");
    } finally {
      setBusy(false);
    }
  }

  const orgName = (id: number | null) =>
    id === null ? "Email triage" : (orgs.find((o) => o.id === id)?.name ?? "-");

  const breachColumns: Column<BreachedItem>[] = [
    {
      key: "ref",
      header: "Ticket",
      nowrap: true,
      render: (row) =>
        row.reference ? (
          <Link
            to={`/agent/tickets/${encodeURIComponent(row.reference)}`}
            className={styles.refLink}
          >
            {row.reference}
          </Link>
        ) : (
          "-"
        ),
    },
    { key: "org", header: "Organisation", render: (row) => orgName(row.organisationId) },
    {
      key: "priority",
      header: "Priority",
      nowrap: true,
      render: (row) => <PriorityBadge priority={row.priority} />,
    },
    { key: "target", header: "Target", render: (row) => TARGET_LABELS[row.target] },
    {
      key: "over",
      header: "Over by",
      nowrap: true,
      render: (row) => (
        <span className={styles.overBy}>
          {formatMinutes(row.overByMinutes)}
          {row.priority === "P1" ? "" : " (business)"}
        </span>
      ),
    },
    {
      key: "assignee",
      header: "Assignee",
      render: (row) => row.assigneeName ?? <span className={styles.subtle}>Unassigned</span>,
    },
  ];

  const engineerColumns: Column<EngineerRow>[] = [
    {
      key: "name",
      header: "Engineer",
      rowHeader: true,
      render: (row) => <strong>{row.name ?? "-"}</strong>,
    },
    { key: "handled", header: "Resolved", render: (row) => row.handled },
    {
      key: "res",
      header: "Resolution met",
      nowrap: true,
      render: (row) => percent(row.resolution),
    },
    {
      key: "afr",
      header: "Avg first response",
      nowrap: true,
      render: (row) => (
        <TimeCell
          business={row.times.businessHours.firstResponse}
          clock={row.times.clockHours.firstResponse}
        />
      ),
    },
    {
      key: "ares",
      header: "Avg resolution",
      nowrap: true,
      render: (row) => (
        <TimeCell
          business={row.times.businessHours.resolution}
          clock={row.times.clockHours.resolution}
        />
      ),
    },
  ];

  const set = (key: keyof SlaReportFilter) => (value: string) =>
    setFilters((f) => ({ ...f, [key]: value }));

  return (
    <div className={styles.column}>
      <PageHeader
        title="Reports - SLA"
        meta="Internal only - never shown to clients"
        actions={
          <Button disabled={busy || loading || failed} onClick={() => void exportCsv()}>
            Export CSV
          </Button>
        }
      />
      <form className={styles.queueFilters} onSubmit={onFilter} aria-label="Filters" noValidate>
        <TextField
          label="From"
          type="date"
          fieldSize="sm"
          value={filters.from}
          onChange={(e) => set("from")(e.target.value)}
          error={rangeError || undefined}
        />
        <TextField
          label="To"
          type="date"
          fieldSize="sm"
          value={filters.to}
          onChange={(e) => set("to")(e.target.value)}
        />
        <Select
          label="Organisation"
          value={filters.organisationId}
          options={[
            { value: "", label: "All organisations" },
            ...orgs.map((o) => ({ value: String(o.id), label: o.name })),
          ]}
          onChange={(e) => set("organisationId")(e.target.value)}
        />
        <Select
          label="Engineer"
          value={filters.engineer}
          options={[
            { value: "", label: "All engineers" },
            ...supporters.map((s) => ({ value: String(s.id), label: s.name })),
          ]}
          onChange={(e) => set("engineer")(e.target.value)}
        />
        <Select
          label="Request type"
          value={filters.requestTypeId}
          options={[
            { value: "", label: "All request types" },
            ...requestTypes.map((t) => ({ value: String(t.id), label: t.name })),
          ]}
          onChange={(e) => set("requestTypeId")(e.target.value)}
        />
        <Select
          label="Priority"
          value={filters.priority}
          options={[
            { value: "", label: "All priorities" },
            ...Object.entries(PRIORITY_LABELS).map(([value, label]) => ({ value, label })),
          ]}
          onChange={(e) => set("priority")(e.target.value)}
        />
        <div className={styles.filterActions}>
          <Button type="submit" variant="primary">
            Apply filters
          </Button>
        </div>
      </form>

      {loading && !data && <p className={styles.subtle}>Loading the report...</p>}
      {failed && !loading && (
        <p className={styles.subtle}>
          The report could not be loaded. Change the filters or try again.
        </p>
      )}

      {data && (
        <>
          <p className={styles.subtle}>
            {formatDay(data.from)} to {formatDay(data.to)}, whole days in {data.zone}.
            {data.totals.duplicates > 0 &&
              ` ${data.totals.duplicates} duplicate${data.totals.duplicates === 1 ? "" : "s"} counted as opened but left out of every SLA measure.`}
          </p>
          {data.truncated && (
            <p role="note" className={styles.subtle}>
              <strong>Only part of the period is included</strong> - too many tickets match. Narrow
              the dates or filters.
            </p>
          )}
          {isEmpty(data) ? (
            <Card>
              <p className={styles.subtle}>No tickets in this period for these filters.</p>
            </Card>
          ) : (
            <>
              <ul className={styles.tiles} aria-label="Summary">
                <Tile
                  label="Tickets opened"
                  value={data.totals.opened}
                  sub={`${data.totals.resolved} resolved in the period`}
                />
                <Tile
                  label="Still open"
                  value={data.totals.stillOpen}
                  sub={`0-2 days ${data.ageing.days0to2}, 3-7 ${data.ageing.days3to7}, 8-30 ${data.ageing.days8to30}, over 30 ${data.ageing.over30}`}
                />
                <Tile
                  label="First response met"
                  value={percent(data.firstResponse)}
                  sub={metSub(data.firstResponse)}
                />
                <Tile
                  label="Resolution met"
                  value={percent(data.resolution)}
                  sub={metSub(data.resolution)}
                />
                <Tile
                  label="Breaches"
                  value={data.breached.length}
                  sub={`${data.firstResponse.breached} first response, ${data.resolution.breached} resolution`}
                />
              </ul>

              <div className={styles.reportRow}>
                <Card title="Breached tickets">
                  <DataTable
                    stackOnPhone
                    caption="Breached tickets"
                    columns={breachColumns}
                    rows={data.breached}
                    rowKey={(row) => `${row.ticketId}-${row.target}`}
                    minWidth={560}
                    compact
                    emptyMessage="No breaches in this period."
                  />
                </Card>
                <Card title="Response and resolution times">
                  <DataTable
                    stackOnPhone
                    caption="Response and resolution times"
                    columns={timesColumns}
                    rows={timesRows(data.times)}
                    rowKey={(row) => row.key}
                    minWidth={420}
                    compact
                  />
                </Card>
              </div>

              <Card title="By engineer">
                <DataTable
                  stackOnPhone
                  caption="SLA by engineer"
                  columns={engineerColumns}
                  rows={data.engineers}
                  rowKey={(row) => String(row.userId)}
                  minWidth={560}
                  compact
                  emptyMessage="No tickets resolved by an assigned engineer in this period."
                />
              </Card>
            </>
          )}
        </>
      )}
    </div>
  );
}
