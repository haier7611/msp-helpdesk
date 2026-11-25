import { useEffect, useState, type FormEvent } from "react";
import {
  deleteSlaOverride,
  fetchOrganisationSla,
  listRequestTypes,
  PRIORITIES,
  saveBusinessHours,
  saveHolidays,
  saveOrganisationAssignee,
  saveSlaPolicy,
  saveTimers,
  type AdminRequestType,
  type ClockType,
  type Holiday,
  type OrganisationSla,
  type Policy,
  type Priority,
  type PriorityTargets,
  type TargetUnit,
} from "../../api/admin";
import { listOrganisations, type Organisation } from "../../api/organisations";
import { listSupporters, type Supporter } from "../../api/supporters";
import { DataTable } from "../../components/DataTable";
import { Button, Checkbox, Select, TextField } from "../../components/forms";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import { PRIORITY_LABELS } from "../../api/agent";
import formStyles from "../../components/forms.module.css";
import { formatDay } from "../../format";
import styles from "./admin.module.css";
import { messageOf } from "./adminShared";
import { useReportError } from "../../components/useReportError";

const DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
const UNIT_OPTIONS: { value: TargetUnit; label: string }[] = [
  { value: "MINUTES", label: "minutes" },
  { value: "HOURS", label: "hours" },
  { value: "DAYS", label: "days" },
];
const CLOCK_OPTIONS: { value: ClockType; label: string }[] = [
  { value: "BUSINESS_HOURS", label: "Business hours" },
  { value: "TWENTY_FOUR_SEVEN", label: "24/7" },
];

type Targets = Record<Priority, PriorityTargets>;

function cx(...names: (string | false | undefined)[]): string {
  return names.filter(Boolean).join(" ");
}

/** Targets as editable strings would be overkill: amounts are whole numbers >= 1. */
function validTargets(t: Targets): string | null {
  for (const p of PRIORITIES) {
    const row = t[p];
    for (const target of [row.firstResponse, row.resolution]) {
      if (!Number.isInteger(target.amount) || target.amount < 1) {
        return `${p}: enter whole numbers of 1 or more.`;
      }
    }
  }
  return null;
}

const TARGET_ROWS = [
  { key: "firstResponse", label: "First response", suffix: "fr", name: "first response" },
  { key: "resolution", label: "Resolution", suffix: "res", name: "resolution" },
] as const;

/**
 * One box per priority: "First response [n] [unit]", "Resolution [n] [unit]" and the clock, each
 * on one row. Visible labels do not repeat the priority (the box legend says it); each control's
 * accessible name still does ("P2 first response unit"), so screen readers hear which box.
 */
function TargetsEditor({
  idPrefix,
  targets,
  onChange,
}: {
  idPrefix: string;
  targets: Targets;
  onChange: (t: Targets) => void;
}) {
  function set(p: Priority, row: PriorityTargets) {
    onChange({ ...targets, [p]: row });
  }
  return (
    <div className={styles.targets}>
      {PRIORITIES.map((p) => {
        const row = targets[p];
        return (
          <fieldset key={p} className={cx(formStyles.fieldset, formStyles.boxed)}>
            <legend className={formStyles.legend}>{PRIORITY_LABELS[p]}</legend>
            <div className={styles.targetRows}>
              {TARGET_ROWS.map((t) => {
                const id = `${idPrefix}-${p}-${t.suffix}`;
                const target = row[t.key];
                return (
                  <div key={t.key} className={styles.targetRow}>
                    <label htmlFor={id} className={formStyles.label}>
                      {t.label}
                    </label>
                    <input
                      id={id}
                      className={formStyles.control}
                      aria-label={`${p} ${t.name}`}
                      type="number"
                      min={1}
                      inputMode="numeric"
                      value={String(target.amount)}
                      onChange={(e) =>
                        set(p, { ...row, [t.key]: { ...target, amount: Number(e.target.value) } })
                      }
                    />
                    <select
                      className={formStyles.control}
                      aria-label={`${p} ${t.name} unit`}
                      value={target.unit}
                      onChange={(e) =>
                        set(p, {
                          ...row,
                          [t.key]: { ...target, unit: e.target.value as TargetUnit },
                        })
                      }
                    >
                      {UNIT_OPTIONS.map((o) => (
                        <option key={o.value} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  </div>
                );
              })}
              <div className={styles.targetRow}>
                {p === "P1" ? (
                  <>
                    <span className={formStyles.label} id={`${idPrefix}-P1-clock-label`}>
                      Clock
                    </span>
                    <span
                      className={styles.clockText}
                      role="note"
                      aria-labelledby={`${idPrefix}-P1-clock-label ${idPrefix}-P1-clock`}
                      id={`${idPrefix}-P1-clock`}
                    >
                      24/7 (always)
                    </span>
                  </>
                ) : (
                  <>
                    <label htmlFor={`${idPrefix}-${p}-clock`} className={formStyles.label}>
                      Clock
                    </label>
                    <select
                      id={`${idPrefix}-${p}-clock`}
                      className={cx(formStyles.control, styles.clockSelect)}
                      aria-label={`${p} clock`}
                      value={row.clock}
                      onChange={(e) => set(p, { ...row, clock: e.target.value as ClockType })}
                    >
                      {CLOCK_OPTIONS.map((o) => (
                        <option key={o.value} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  </>
                )}
              </div>
            </div>
          </fieldset>
        );
      })}
    </div>
  );
}

function PolicyCard({
  title,
  policy,
  idPrefix,
  onSave,
  onRemove,
}: {
  title: string;
  policy: Policy;
  idPrefix: string;
  onSave: (p: Policy) => Promise<void>;
  onRemove?: () => Promise<void>;
}) {
  const report = useReportError();
  const [targets, setTargets] = useState<Targets>(policy.targets);
  const [pauses, setPauses] = useState(policy.pendingOnVendorPauses);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const problem = validTargets(targets);
    setError(problem ?? "");
    if (problem) return;
    setBusy(true);
    try {
      await onSave({ ...policy, targets, pendingOnVendorPauses: pauses });
    } catch (e) {
      setError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title={title}>
      <form className={styles.body} onSubmit={submit} noValidate>
        <TargetsEditor idPrefix={idPrefix} targets={targets} onChange={setTargets} />
        <Checkbox
          label='"Pending on vendor" pauses the SLA'
          checked={pauses}
          onChange={(e) => setPauses(e.target.checked)}
        />
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <div className={styles.actions}>
          <Button type="submit" variant="primary" disabled={busy}>
            Save {title.toLowerCase()}
          </Button>
          {onRemove && (
            <Button variant="danger" disabled={busy} onClick={() => void onRemove()}>
              Remove override
            </Button>
          )}
        </div>
      </form>
    </Card>
  );
}

/**
 * Admin - SLA policies: per organisation, the default
 * targets and request-type overrides, business hours, holidays, Pending timers, API alert client
 * emails and the default assignee. New targets apply to tickets at their next recalculation;
 * stored results of resolved tickets never change.
 */
export function SlaPoliciesPage() {
  const toast = useToast();
  const report = useReportError();
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [types, setTypes] = useState<AdminRequestType[]>([]);
  const [supporters, setSupporters] = useState<Supporter[]>([]);
  const [orgId, setOrgId] = useState("");
  const [sla, setSla] = useState<OrganisationSla | null>(null);
  const [loadError, setLoadError] = useState("");
  const [version, setVersion] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    listOrganisations(controller.signal)
      .then((list) => {
        setOrganisations(list);
        const first = list[0];
        if (first) setOrgId(String(first.id));
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setLoadError(messageOf(e));
      });
    listRequestTypes(controller.signal)
      .then(setTypes)
      .catch((e: unknown) => report(e, "Request types could not be loaded."));
    listSupporters({ status: "ACTIVE" }, controller.signal)
      .then(setSupporters)
      .catch((e: unknown) => report(e, "Agents could not be loaded."));
    return () => controller.abort();
  }, [report]);

  useEffect(() => {
    if (!orgId) return;
    const controller = new AbortController();
    fetchOrganisationSla(Number(orgId), controller.signal)
      .then((s) => {
        setSla(s);
        setVersion((v) => v + 1);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setLoadError(messageOf(e));
      });
    return () => controller.abort();
  }, [orgId]);

  function saved(s: OrganisationSla, what: string) {
    setSla(s);
    setVersion((v) => v + 1);
    toast.show(`${what} saved.`, "success");
  }

  const defaultPolicy = sla?.policies.find((p) => p.requestTypeId === null) ?? null;
  const overrides = sla?.policies.filter((p) => p.requestTypeId !== null) ?? [];
  const typeName = (id: number | null) => types.find((t) => t.id === id)?.name ?? `Type #${id}`;

  return (
    <div className={styles.page}>
      <PageHeader title="SLA policies" />
      <p className={styles.muted}>
        Targets per priority for each client organisation (defaults: P1 30 min / 4 h on the 24/7
        clock; P2 1 h / 8 business hours; P3 4 business hours / 3 business days; P4 1 business day /
        10 business days). Changes apply to open tickets at their next recalculation. SLA details
        are never shown to clients.
      </p>
      {loadError && (
        <p role="alert" className={styles.error}>
          {loadError}
        </p>
      )}
      <Card>
        {organisations.length === 0 && !loadError ? (
          <p className={styles.muted}>Add an organisation first (Admin - Organisations).</p>
        ) : (
          <Select
            label="Organisation"
            fieldSize="md"
            value={orgId}
            options={organisations.map((o) => ({ value: String(o.id), label: o.name }))}
            onChange={(e) => setOrgId(e.target.value)}
          />
        )}
      </Card>

      {sla && (
        <div key={`${sla.organisationId}-${version}`} className={styles.body}>
          {defaultPolicy && (
            <PolicyCard
              title="Default targets"
              idPrefix="default"
              policy={defaultPolicy}
              onSave={async (p) =>
                saved(
                  await saveSlaPolicy(sla.organisationId, null, p.pendingOnVendorPauses, p.targets),
                  "Default targets",
                )
              }
            />
          )}
          {overrides.map((p) => (
            <PolicyCard
              key={p.requestTypeId}
              title={`Override - ${typeName(p.requestTypeId)}`}
              idPrefix={`rt-${p.requestTypeId}`}
              policy={p}
              onSave={async (next) =>
                saved(
                  await saveSlaPolicy(
                    sla.organisationId,
                    next.requestTypeId,
                    next.pendingOnVendorPauses,
                    next.targets,
                  ),
                  "Override",
                )
              }
              onRemove={async () => {
                try {
                  saved(
                    await deleteSlaOverride(sla.organisationId, p.requestTypeId ?? 0),
                    "Override removal",
                  );
                } catch (e) {
                  toast.showError(messageOf(e));
                }
              }}
            />
          ))}
          {defaultPolicy && (
            <AddOverride
              types={types.filter(
                (t) => !t.archived && !overrides.some((o) => o.requestTypeId === t.id),
              )}
              onAdd={async (typeId) =>
                saved(
                  await saveSlaPolicy(
                    sla.organisationId,
                    typeId,
                    defaultPolicy.pendingOnVendorPauses,
                    defaultPolicy.targets,
                  ),
                  "Override",
                )
              }
            />
          )}
          <BusinessHoursCard
            sla={sla}
            onSave={async (rows) =>
              saved(await saveBusinessHours(sla.organisationId, rows), "Business hours")
            }
          />
          <HolidaysCard
            holidays={sla.holidays}
            onSave={async (rows) => saved(await saveHolidays(sla.organisationId, rows), "Holidays")}
          />
          <TimersCard
            sla={sla}
            supporters={supporters}
            onSave={async (timers, assignee) => {
              const {
                defaultAssigneeUserId: _ignored,
                pendingReminderEnabled: _movedToClientEmails,
                ...rest
              } = timers;
              void _ignored;
              void _movedToClientEmails;
              let next = await saveTimers(sla.organisationId, rest);
              if (assignee !== sla.settings.defaultAssigneeUserId) {
                next = await saveOrganisationAssignee(sla.organisationId, assignee);
              }
              saved(next, "Timers and assignee");
            }}
          />
        </div>
      )}
    </div>
  );
}

function AddOverride({
  types,
  onAdd,
}: {
  types: AdminRequestType[];
  onAdd: (typeId: number) => Promise<void>;
}) {
  const toast = useToast();
  const [choice, setChoice] = useState("");
  if (types.length === 0) return null;
  return (
    <Card title="Request-type override">
      <p className={styles.muted}>
        A request type can have its own targets for this organisation. It starts as a copy of the
        default targets.
      </p>
      <div className={styles.row}>
        <Select
          label="Request type"
          fieldSize="md"
          value={choice}
          options={[
            { value: "", label: "Choose a request type" },
            ...types.map((t) => ({ value: String(t.id), label: t.name })),
          ]}
          onChange={(e) => setChoice(e.target.value)}
        />
        <Button
          disabled={choice === ""}
          onClick={() =>
            void onAdd(Number(choice)).catch((e: unknown) => toast.showError(messageOf(e)))
          }
        >
          Add override
        </Button>
      </div>
    </Card>
  );
}

type DayDraft = { open: boolean; opens: string; closes: string };

function BusinessHoursCard({
  sla,
  onSave,
}: {
  sla: OrganisationSla;
  onSave: (rows: { dayOfWeek: number; opens: string; closes: string }[]) => Promise<void>;
}) {
  const report = useReportError();
  const [days, setDays] = useState<DayDraft[]>(() =>
    DAYS.map((_, i) => {
      const row = sla.businessHours.find((h) => h.dayOfWeek === i + 1);
      return row
        ? { open: true, opens: row.opens, closes: row.closes }
        : { open: false, opens: "08:30", closes: "17:30" };
    }),
  );
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const rows = days
      .map((d, i) => ({ dayOfWeek: i + 1, ...d }))
      .filter((d) => d.open)
      .map(({ dayOfWeek, opens, closes }) => ({ dayOfWeek, opens, closes }));
    if (rows.length === 0) {
      setError("Open on at least one day.");
      return;
    }
    setError("");
    setBusy(true);
    try {
      await onSave(rows);
    } catch (e) {
      setError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title={`Business hours (${sla.timezone})`}>
      <form className={styles.body} onSubmit={submit} noValidate>
        <p className={styles.muted}>A closing time of 00:00 means open until midnight.</p>
        {days.map((d, i) => (
          <div key={DAYS[i]} className={styles.grid}>
            <Checkbox
              label={`Open on ${DAYS[i]}`}
              checked={d.open}
              onChange={(e) =>
                setDays(days.map((x, j) => (j === i ? { ...x, open: e.target.checked } : x)))
              }
            />
            <TextField
              label={`${DAYS[i]} opens`}
              type="time"
              fieldSize="sm"
              value={d.opens}
              disabled={!d.open}
              onChange={(e) =>
                setDays(days.map((x, j) => (j === i ? { ...x, opens: e.target.value } : x)))
              }
            />
            <TextField
              label={`${DAYS[i]} closes`}
              type="time"
              fieldSize="sm"
              value={d.closes}
              disabled={!d.open}
              onChange={(e) =>
                setDays(days.map((x, j) => (j === i ? { ...x, closes: e.target.value } : x)))
              }
            />
          </div>
        ))}
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <div className={styles.actions}>
          <Button type="submit" variant="primary" disabled={busy}>
            Save business hours
          </Button>
        </div>
      </form>
    </Card>
  );
}

function HolidaysCard({
  holidays,
  onSave,
}: {
  holidays: Holiday[];
  onSave: (rows: Holiday[]) => Promise<void>;
}) {
  const report = useReportError();
  const [rows, setRows] = useState<Holiday[]>(holidays);
  const [date, setDate] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function save(next: Holiday[]) {
    setError("");
    setBusy(true);
    try {
      await onSave(next);
    } catch (e) {
      setError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  function add() {
    if (!date || !name.trim()) {
      setError("Enter a date and a name.");
      return;
    }
    const next = [...rows, { date, name: name.trim() }].sort((a, b) =>
      a.date.localeCompare(b.date),
    );
    setRows(next);
    setDate("");
    setName("");
    void save(next);
  }

  return (
    <Card title="Holidays">
      <div className={styles.body}>
        <p className={styles.muted}>Business-hours targets do not count these days.</p>
        <DataTable
          caption="Holidays"
          compact
          minWidth={420}
          rows={rows}
          rowKey={(h) => h.date}
          emptyMessage="No holidays yet."
          columns={[
            { key: "date", header: "Date", nowrap: true, render: (h) => formatDay(h.date) },
            { key: "name", header: "Name", rowHeader: true, render: (h) => h.name },
            {
              key: "remove",
              header: <span className="visually-hidden">Actions</span>,
              actions: true,
              render: (h) => (
                <Button
                  aria-label={`Remove ${h.name} on ${formatDay(h.date)}`}
                  disabled={busy}
                  onClick={() => {
                    const next = rows.filter((x) => x.date !== h.date);
                    setRows(next);
                    void save(next);
                  }}
                >
                  Remove
                </Button>
              ),
            },
          ]}
        />
        <div className={styles.row}>
          <TextField
            label="Holiday date"
            type="date"
            fieldSize="sm"
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
          <TextField
            label="Holiday name"
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <Button disabled={busy} onClick={add}>
            Add holiday
          </Button>
        </div>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
      </div>
    </Card>
  );
}

function TimersCard({
  sla,
  supporters,
  onSave,
}: {
  sla: OrganisationSla;
  supporters: Supporter[];
  onSave: (timers: OrganisationSla["settings"], assignee: number | null) => Promise<void>;
}) {
  const report = useReportError();
  const [t, setT] = useState(sla.settings);
  const [assignee, setAssignee] = useState(
    sla.settings.defaultAssigneeUserId === null ? "" : String(sla.settings.defaultAssigneeUserId),
  );
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      await onSave(t, assignee === "" ? null : Number(assignee));
    } catch (e) {
      setError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  const eligible = supporters.filter(
    (s) => s.allOrganisations || s.organisationIds.includes(sla.organisationId),
  );

  return (
    <Card title="Timers, alerts and default assignee">
      <form className={styles.body} onSubmit={submit} noValidate>
        <div className={styles.grid}>
          <TextField
            label="Reminder after (business days)"
            hint="Turn the reminder email on or off in Admin - Organisations - Client emails."
            type="number"
            fieldSize="sm"
            min={1}
            max={60}
            value={String(t.pendingReminderBusinessDays)}
            onChange={(e) => setT({ ...t, pendingReminderBusinessDays: Number(e.target.value) })}
          />
          <Checkbox
            label="Resolve automatically while Pending on client"
            checked={t.autoResolveEnabled}
            onChange={(e) => setT({ ...t, autoResolveEnabled: e.target.checked })}
          />
          <TextField
            label="Auto-resolve after (business days)"
            type="number"
            fieldSize="sm"
            min={1}
            max={120}
            value={String(t.autoResolveBusinessDays)}
            onChange={(e) => setT({ ...t, autoResolveBusinessDays: Number(e.target.value) })}
          />
          <Checkbox
            label="Close resolved tickets automatically"
            checked={t.autoCloseEnabled}
            onChange={(e) => setT({ ...t, autoCloseEnabled: e.target.checked })}
          />
          <TextField
            label="Auto-close after (calendar days)"
            type="number"
            fieldSize="sm"
            min={1}
            max={90}
            value={String(t.autoCloseCalendarDays)}
            onChange={(e) => setT({ ...t, autoCloseCalendarDays: Number(e.target.value) })}
          />
        </div>
        <Checkbox
          label="Email the client about tickets raised by monitoring alerts (API)"
          checked={t.apiAlertClientEmails}
          onChange={(e) => setT({ ...t, apiAlertClientEmails: e.target.checked })}
        />
        <Select
          label="Organisation default assignee"
          fieldSize="md"
          hint="Used when the request type has no default assignee. Only agents who work this organisation are listed."
          value={assignee}
          options={[
            { value: "", label: "None (Unassigned)" },
            ...eligible.map((s) => ({ value: String(s.id), label: s.name })),
          ]}
          onChange={(e) => setAssignee(e.target.value)}
        />
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <div className={styles.actions}>
          <Button type="submit" variant="primary" disabled={busy}>
            Save timers and assignee
          </Button>
        </div>
      </form>
    </Card>
  );
}
