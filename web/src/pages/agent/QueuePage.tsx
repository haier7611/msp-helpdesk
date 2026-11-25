import { Fragment, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router";
import {
  exportUrl,
  getPreferences,
  listAgentOrganisations,
  listSupporters,
  listTickets,
  PAGE_SIZE,
  PRIORITY_LABELS,
  STATUS_LABELS,
  type AgentOrganisation,
  type Supporter,
  type TicketStatus,
  type TicketSummary,
} from "../../api/agent";
import { PriorityBadge, SlaBadge, StatusBadge } from "../../components/Badge";
import { DataTable, Identifier, type Column } from "../../components/DataTable";
import { Button, buttonClass, Checkbox, TextField } from "../../components/forms";
import { PageHeader } from "../../components/ui";
import { errorText, formatDateTime, formatShortDateTime } from "../../format";
import styles from "../pages.module.css";
import { QUEUE_VIEWS, queueFilterFrom, SORTS, supporterLabel } from "./queueFilter";
import formStyles from "../../components/forms.module.css";
import { useReportError } from "../../components/useReportError";
import { FirstResponseCell } from "./FirstResponseCell";

/** The queue reloads its rows this often while the tab is visible. */
export const QUEUE_REFRESH_MS = 60_000;

/**
 * A counter that goes up every {@link QUEUE_REFRESH_MS} while the browser tab is visible; hidden
 * tabs do not poll, and coming back to the tab counts up at once (a fresh list straight away).
 */
function useRefreshTick(): number {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let id: number | undefined;
    const start = () => {
      if (id === undefined) id = window.setInterval(() => setTick((t) => t + 1), QUEUE_REFRESH_MS);
    };
    const stop = () => {
      if (id !== undefined) window.clearInterval(id);
      id = undefined;
    };
    const onVisibility = () => {
      if (document.visibilityState === "hidden") {
        stop();
      } else {
        setTick((t) => t + 1);
        start();
      }
    };
    if (document.visibilityState !== "hidden") start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, []);
  return tick;
}

/** "<organisation> - <reporter> - <request type>" under the subject; triage says "Email triage". */
function subtitleParts(row: TicketSummary): ReactNode[] {
  const parts: ReactNode[] = [];
  const org = row.organisationName ?? (row.organisationId === null ? "Email triage" : null);
  if (org) parts.push(org);
  if (row.reporterName) parts.push(row.reporterName);
  else if (row.reporterEmail) parts.push(<Identifier value={row.reporterEmail} />);
  if (row.requestTypeName) parts.push(row.requestTypeName);
  return parts;
}

function NativeSelect({
  label,
  name,
  value,
  options,
}: {
  label: string;
  name: string;
  value: string;
  options: [string, string][];
}) {
  const id = `queue-${name}`;
  return (
    <div className={formStyles.field}>
      <label htmlFor={id} className={formStyles.label}>
        {label}
      </label>
      <select id={id} name={name} defaultValue={value} className={formStyles.control}>
        {options.map(([v, text]) => (
          <option key={v} value={v}>
            {text}
          </option>
        ))}
      </select>
    </div>
  );
}

export function QueuePage() {
  const report = useReportError();
  const [params, setParams] = useSearchParams();
  const { filter, view, org } = queueFilterFrom(params);
  const page = Math.max(0, Number(params.get("page") ?? "0") || 0);
  const key = params.toString();

  const [result, setResult] = useState<{
    key: string;
    rows: TicketSummary[];
    total: number;
    error: string | null;
    /** A background reload failed: the rows shown are from `loadedAt` (one quiet notice). */
    stale: boolean;
    loadedAt: number;
  } | null>(null);
  const tick = useRefreshTick();
  const [orgs, setOrgs] = useState<AgentOrganisation[]>([]);
  const [supporters, setSupporters] = useState<Supporter[]>([]);

  // A bare /agent/queue opens the supporter's default view (the My settings preference, else My tickets).
  const bare = key === "";
  const [defaultView, setDefaultView] = useState<string | null>(null);
  useEffect(() => {
    if (!bare) return;
    const controller = new AbortController();
    getPreferences(controller.signal).then((p) => {
      if (controller.signal.aborted) return;
      if (p.queueView !== "mine") setParams({ view: p.queueView }, { replace: true });
      else setDefaultView("mine");
    });
    return () => controller.abort();
  }, [bare, setParams]);
  const waiting = bare && defaultView === null;

  useEffect(() => {
    const controller = new AbortController();
    listAgentOrganisations(controller.signal).then(setOrgs, (e: unknown) =>
      report(e, "Organisations could not be loaded for the filters."),
    );
    listSupporters(null, controller.signal).then(setSupporters, (e: unknown) =>
      report(e, "Agents could not be loaded for the filters."),
    );
    return () => controller.abort();
  }, [report]);

  useEffect(() => {
    if (waiting) return;
    const controller = new AbortController();
    // Also re-run on every refresh tick. The rows on screen stay until the new ones arrive
    // (no spinner, no flicker), and a failed background reload keeps them with one quiet notice.
    listTickets(queueFilterFrom(new URLSearchParams(key)).filter, page, controller.signal).then(
      (r) =>
        setResult({
          key,
          rows: r.rows,
          total: r.total,
          error: null,
          stale: false,
          loadedAt: Date.now(),
        }),
      (e: unknown) => {
        if (controller.signal.aborted) return;
        setResult((prev) =>
          prev && prev.key === key && prev.error === null
            ? { ...prev, stale: true }
            : {
                key,
                rows: [],
                total: 0,
                error: errorText(e, "The queue could not be loaded."),
                stale: false,
                loadedAt: Date.now(),
              },
        );
      },
    );
    return () => controller.abort();
  }, [key, page, waiting, tick]);

  const current = result && result.key === key ? result : null;
  const rows = current ? current.rows : null;
  const total = current?.total ?? 0;
  const error = current?.error ?? null;
  const stale = current?.stale ?? false;
  const orgName = org ? orgs.find((o) => String(o.id) === org)?.name : undefined;
  const title = org
    ? (orgName ?? "Organisation")
    : (QUEUE_VIEWS.find((v) => v.id === view)?.label ?? "My tickets");
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const showSla = (rows ?? []).some((r) => r.sla);
  const byId = new Map(supporters.map((s) => [s.id, s]));

  function onFilter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    if (org) next.set("org", org);
    else if (view) next.set("view", view);
    for (const name of ["q", "status", "priority", "assignee", "sort"]) {
      const value = String(data.get(name) ?? "").trim();
      if (value) next.set(name, value);
    }
    if (data.get("closed")) next.set("closed", "1");
    setParams(next);
  }

  function goTo(p: number) {
    const next = new URLSearchParams(params);
    if (p === 0) next.delete("page");
    else next.set("page", String(p));
    setParams(next);
  }

  const columns: Column<TicketSummary>[] = [
    {
      key: "ref",
      header: "Ticket",
      nowrap: true,
      render: (row) => (
        <Link to={`/agent/tickets/${encodeURIComponent(row.reference)}`} className={styles.refLink}>
          {row.reference}
        </Link>
      ),
    },
    {
      key: "subject",
      header: "Subject",
      render: (row) => (
        <>
          <div className={styles.subject}>{row.subject}</div>
          <div className={styles.subtle}>
            {subtitleParts(row).map((part, i) => (
              <Fragment key={i}>
                {i > 0 && " - "}
                {part}
              </Fragment>
            ))}
          </div>
        </>
      ),
    },
    {
      key: "firstResponse",
      // May wrap onto two lines, so the header is no wider than the values under it.
      header: <span className={styles.wrapHeader}>First response</span>,
      label: "First response",
      nowrap: true,
      render: (row) => <FirstResponseCell timer={row.firstResponseTimer} />,
    },
    {
      key: "priority",
      header: "Priority",
      nowrap: true,
      render: (row) => <PriorityBadge priority={row.priority} />,
    },
    {
      key: "status",
      header: "Status",
      nowrap: true,
      render: (row) => <StatusBadge status={STATUS_LABELS[row.status]} />,
    },
    {
      key: "assignee",
      header: "Assignee",
      render: (row) =>
        row.assigneeUserId === null ? (
          <span className={styles.subtle}>Unassigned</span>
        ) : (
          <>
            {byId.get(row.assigneeUserId)?.name ?? row.assigneeName}
            {byId.get(row.assigneeUserId)?.outOfOffice && (
              <span className={styles.ooo}>(out of office)</span>
            )}
          </>
        ),
    },
    ...(showSla
      ? [
          {
            key: "sla",
            header: "SLA",
            nowrap: true,
            render: (row: TicketSummary) =>
              row.sla ? <SlaBadge state={row.sla.state} text={row.sla.text} /> : null,
          },
        ]
      : []),
    {
      key: "updated",
      header: "Updated",
      nowrap: true,
      muted: true,
      // Compact ("21 Nov, 02:41") so the subject keeps room at 1280 px; the full date on hover.
      render: (row) => (
        <time dateTime={row.updatedAt} title={formatDateTime(row.updatedAt)}>
          {formatShortDateTime(row.updatedAt)}
        </time>
      ),
    },
  ];

  const statusOptions: [string, string][] = [
    ["", "Any open status"],
    ...(Object.keys(STATUS_LABELS) as TicketStatus[]).map(
      (s) => [s, STATUS_LABELS[s]] as [string, string],
    ),
  ];
  const assigneeOptions: [string, string][] = [
    ["", "View default"],
    ["any", "Anyone"],
    ["me", "Me"],
    ["none", "Unassigned"],
    ...supporters.map((s) => [String(s.id), supporterLabel(s, null)] as [string, string]),
  ];

  return (
    <div className={styles.split}>
      <nav className={styles.views} aria-label="Views">
        <h2 className={styles.viewsHeading}>Views</h2>
        <ul className={styles.viewsList}>
          {QUEUE_VIEWS.map((v) => (
            <li key={v.id}>
              <Link
                to={`/agent/queue?view=${v.id}`}
                className={styles.viewLink}
                aria-current={v.id === view ? "page" : undefined}
              >
                {v.label}
              </Link>
            </li>
          ))}
        </ul>
        <h2 className={styles.viewsHeading}>Organisations</h2>
        <ul className={styles.viewsList}>
          {orgs.map((o) => (
            <li key={o.id}>
              <Link
                to={`/agent/queue?org=${o.id}`}
                className={styles.viewLink}
                aria-current={org === String(o.id) ? "page" : undefined}
              >
                {o.name}
                {o.prefix ? ` (${o.prefix})` : ""}
              </Link>
            </li>
          ))}
        </ul>
      </nav>

      <div className={styles.column}>
        <PageHeader
          title={title}
          meta={rows === null ? "Loading..." : `${total} ticket${total === 1 ? "" : "s"}`}
          actions={
            <>
              <a className={buttonClass()} href={exportUrl(filter)} download>
                Export CSV
              </a>
              <Link className={buttonClass("primary")} to="/agent/tickets/new">
                New ticket
              </Link>
            </>
          }
        />
        <form className={styles.queueFilters} onSubmit={onFilter} aria-label="Filters" key={key}>
          <TextField
            label="Search"
            name="q"
            type="search"
            defaultValue={params.get("q") ?? ""}
            placeholder="Subject, email or ref"
            maxLength={200}
          />
          <NativeSelect
            label="Status"
            name="status"
            value={params.get("status") ?? ""}
            options={statusOptions}
          />
          <NativeSelect
            label="Priority"
            name="priority"
            value={params.get("priority") ?? ""}
            options={[
              ["", "Any priority"],
              ...(Object.entries(PRIORITY_LABELS) as [string, string][]),
            ]}
          />
          <NativeSelect
            label="Assignee"
            name="assignee"
            value={params.get("assignee") ?? ""}
            options={assigneeOptions}
          />
          <NativeSelect
            label="Sort"
            name="sort"
            value={params.get("sort") ?? "newest"}
            options={SORTS}
          />
          <div className={styles.filterActions}>
            <Checkbox
              label="Include closed"
              name="closed"
              defaultChecked={params.get("closed") === "1"}
            />
            <Button type="submit">Apply filters</Button>
          </div>
        </form>
        {error && (
          <p className={styles.errorBox} role="alert">
            {error}
          </p>
        )}
        {stale && current && (
          <p className={styles.subtle} role="status" data-testid="queue-stale">
            The queue could not be refreshed. Showing the list from{" "}
            {formatShortDateTime(new Date(current.loadedAt).toISOString())}; it will try again in a
            minute.
          </p>
        )}
        <DataTable
          stackOnPhone
          caption={`${title} queue`}
          columns={columns}
          rows={rows ?? []}
          loading={rows === null}
          rowKey={(row) => String(row.id)}
          minWidth={860}
          emptyMessage="No tickets match this view."
        />
        {total > PAGE_SIZE && (
          <nav className={styles.pager} aria-label="Pages">
            <Button disabled={page === 0} onClick={() => goTo(page - 1)}>
              Previous page
            </Button>
            <span>
              Page {page + 1} of {pages}
            </span>
            <Button disabled={page + 1 >= pages} onClick={() => goTo(page + 1)}>
              Next page
            </Button>
          </nav>
        )}
      </div>
    </div>
  );
}
