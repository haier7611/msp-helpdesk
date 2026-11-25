import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import {
  downloadAuditCsv,
  fetchAuditPage,
  type AuditEvent,
  type AuditFilters,
  type AuditPage,
} from "../../api/admin";
import { listOrganisations, type Organisation } from "../../api/organisations";
import { DataTable, Identifier } from "../../components/DataTable";
import { Button, Select, TextField } from "../../components/forms";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import styles from "./admin.module.css";
import { messageOf, utcDate, when } from "./adminShared";
import { auditEventLabel } from "./auditLabels";
import { useReportError } from "../../components/useReportError";

/** Strips control and bidirectional-override characters so a value cannot reorder the row. */
function safe(text: string | null): string {
  // eslint-disable-next-line no-control-regex
  return (text ?? "").replace(/[\u0000-\u001f\u007f‪-‮⁦-⁩]/g, " ");
}

function person(name: string | null, email: string | null): ReactNode {
  if (!name && !email) return "-";
  if (!email) return safe(name);
  // Name on one line, the address below it (broken at the "@" if the column is narrow).
  return (
    <>
      {name && <div>{safe(name)}</div>}
      <Identifier value={safe(email)} />
    </>
  );
}

const readable = auditEventLabel;

/**
 * Admin - Audit log (C14): filter by date (UTC days, up to one year back),
 * actor, event and organisation; export the same rows as CSV (the export is audit-logged). Every
 * value is shown as plain text - names, emails and device labels can be chosen by an attacker.
 */
export function AuditLogPage() {
  const toast = useToast();
  const report = useReportError();
  const [filters, setFilters] = useState<AuditFilters>({
    from: utcDate(-29),
    to: utcDate(),
    actor: "",
    type: "",
    organisationId: "",
  });
  const [applied, setApplied] = useState<AuditFilters>(filters);
  const [page, setPage] = useState<AuditPage | null>(null);
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [types, setTypes] = useState<string[]>([]);
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listOrganisations(controller.signal)
      .then(setOrganisations)
      .catch((e: unknown) => report(e, "Organisations could not be loaded for the filter."));
    return () => controller.abort();
  }, [report]);

  useEffect(() => {
    const controller = new AbortController();
    fetchAuditPage(applied, null, controller.signal)
      .then((p) => {
        setPage(p);
        setEvents(p.events);
        setTypes(p.types);
        setError("");
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(messageOf(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [applied]);

  function onFilter(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setApplied({ ...filters });
  }

  async function more() {
    if (!page?.nextBefore) return;
    setBusy(true);
    try {
      const next = await fetchAuditPage(applied, page.nextBefore);
      setPage(next);
      setEvents((e) => [...e, ...next.events]);
    } catch (e) {
      toast.showError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  async function exportCsv() {
    setBusy(true);
    try {
      const blob = await downloadAuditCsv(applied);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `audit-log-${applied.from}-to-${applied.to}.csv`;
      document.body.append(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      toast.show("Audit log exported. The export itself is recorded in the log.", "success");
    } catch (e) {
      toast.showError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  const orgName = (id: string) => organisations.find((o) => String(o.id) === id)?.name ?? id;

  return (
    <div className={styles.page}>
      <PageHeader
        title="Audit log"
        actions={
          <Button disabled={busy || loading} onClick={() => void exportCsv()}>
            Export CSV
          </Button>
        }
      />
      <p className={styles.muted}>
        Security and configuration events, kept for one year. Dates are whole days in UTC; times are
        shown in your timezone.
      </p>
      <Card title="Filters">
        <form className={styles.grid} onSubmit={onFilter} noValidate>
          <TextField
            label="From (UTC)"
            type="date"
            fieldSize="sm"
            value={filters.from}
            onChange={(e) => setFilters({ ...filters, from: e.target.value })}
          />
          <TextField
            label="To (UTC)"
            type="date"
            fieldSize="sm"
            value={filters.to}
            onChange={(e) => setFilters({ ...filters, to: e.target.value })}
          />
          <TextField
            label="Actor email contains"
            autoComplete="off"
            value={filters.actor}
            onChange={(e) => setFilters({ ...filters, actor: e.target.value })}
          />
          <Select
            label="Event"
            value={filters.type}
            options={[
              { value: "", label: "All events" },
              ...types.map((t) => ({ value: t, label: readable(t) })),
            ]}
            onChange={(e) => setFilters({ ...filters, type: e.target.value })}
          />
          <Select
            label="Organisation"
            value={filters.organisationId}
            options={[
              { value: "", label: "All organisations" },
              ...organisations.map((o) => ({ value: String(o.id), label: o.name })),
            ]}
            onChange={(e) => setFilters({ ...filters, organisationId: e.target.value })}
          />
          <Button type="submit" variant="primary">
            Apply filters
          </Button>
        </form>
      </Card>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      <Card>
        <DataTable
          stackOnPhone
          caption="Audit events"
          minWidth={900}
          loading={loading}
          rows={events}
          rowKey={(e) => String(e.id)}
          emptyMessage="No events match these filters."
          columns={[
            { key: "when", header: "When", nowrap: true, render: (e) => when(e.occurredAt) },
            { key: "type", header: "Event", rowHeader: true, render: (e) => readable(e.type) },
            { key: "actor", header: "Actor", render: (e) => person(e.actorName, e.actorEmail) },
            {
              key: "target",
              header: "Affected user",
              render: (e) => person(e.targetName, e.targetEmail),
            },
            {
              key: "details",
              header: "Details",
              render: (e) =>
                e.details.length === 0 ? (
                  "-"
                ) : (
                  <ul className={styles.details}>
                    {e.details.map(([k, v]) => (
                      <li key={k}>
                        {k}: {k === "organisation" ? safe(orgName(v)) : safe(v)}
                      </li>
                    ))}
                  </ul>
                ),
            },
            {
              key: "ip",
              header: "IP address",
              render: (e) => (e.clientIp ? <Identifier value={safe(e.clientIp)} /> : "-"),
            },
          ]}
        />
        {page?.nextBefore != null && (
          <div className={styles.actions}>
            <Button disabled={busy} onClick={() => void more()}>
              Show older events
            </Button>
          </div>
        )}
      </Card>
    </div>
  );
}
