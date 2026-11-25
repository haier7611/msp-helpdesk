import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router";
import {
  CUSTOMER_PAGE_SIZE,
  deactivateCustomer,
  eraseCustomer,
  fetchCustomer,
  listCustomers,
  moveCustomer,
  reactivateCustomer,
  resendCustomerInvitation,
  sendCustomerPasswordReset,
  signOutCustomerEverywhere,
  type CustomerDetail,
  type CustomerSort,
  type CustomerStatus,
  type CustomerSummary,
} from "../../api/customers";
import { listOrganisations, type Organisation } from "../../api/organisations";
import { Badge, type BadgeTone } from "../../components/Badge";
import { DataTable, Identifier, type Column } from "../../components/DataTable";
import { Button, Select, TextField } from "../../components/forms";
import { Modal } from "../../components/Modal";
import { SidePanel } from "../../components/SidePanel";
import { useToast } from "../../components/toastContext";
import { PageHeader } from "../../components/ui";
import { useReportError } from "../../components/useReportError";
import { StepUpCancelled, useStepUp } from "../../components/useStepUp";
import { formatDateTime, formatShortDateTime } from "../../format";
import { errorText } from "../auth/authHelpers";
import styles from "../pages.module.css";

const STATUS_LABEL: Record<CustomerStatus, string> = {
  ACTIVE: "Active",
  INVITED: "Invited",
  DEACTIVATED: "Deactivated",
  ERASED: "Erased",
};

const STATUS_TONE: Record<CustomerStatus, BadgeTone> = {
  ACTIVE: "ok",
  INVITED: "brand",
  DEACTIVATED: "neutral",
  ERASED: "neutral",
};

const STATUS_OPTIONS = [
  { value: "", label: "All except erased" },
  { value: "ACTIVE", label: "Active" },
  { value: "INVITED", label: "Invited (not signed in yet)" },
  { value: "DEACTIVATED", label: "Deactivated" },
  { value: "ERASED", label: "Erased" },
];

const SORT_OPTIONS = [
  { value: "name", label: "Name" },
  { value: "organisation", label: "Organisation" },
  { value: "lastSignIn", label: "Last sign-in (newest first)" },
];

function asStatus(v: string | null): CustomerStatus | "" {
  return v === "ACTIVE" || v === "INVITED" || v === "DEACTIVATED" || v === "ERASED" ? v : "";
}

function asSort(v: string | null): CustomerSort {
  return v === "organisation" || v === "lastSignIn" ? v : "name";
}

function StatusBadge({ status }: { status: CustomerStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{STATUS_LABEL[status]}</Badge>;
}

function when(iso: string | null, empty = "Never"): string {
  if (!iso) return empty;
  return formatDateTime(iso) || "-";
}

function lastSignIn(iso: string | null) {
  if (!iso) return "Never";
  const short = formatShortDateTime(iso);
  if (!short) return "-";
  return (
    <time dateTime={iso} title={formatDateTime(iso)}>
      {short}
    </time>
  );
}

function invitationText(c: CustomerDetail): string {
  if (c.status === "ACTIVE") return "Accepted - they have signed up";
  if (c.status !== "INVITED") return "-";
  if (!c.invitationExpiresAt) return "Not sent yet (contact created from email or by an agent)";
  return new Date(c.invitationExpiresAt).getTime() < Date.now()
    ? `Expired ${when(c.invitationExpiresAt)}`
    : `Sent - link valid until ${when(c.invitationExpiresAt)}`;
}

/** Simple yes/no confirmation for deactivate (reversible). */
function DeactivateDialog({
  customer,
  onClose,
  onDone,
}: {
  customer: CustomerDetail;
  onClose: () => void;
  onDone: (c: CustomerDetail) => void;
}) {
  const report = useReportError();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function confirm() {
    setBusy(true);
    setError("");
    try {
      onDone(await deactivateCustomer(customer.id));
    } catch (e) {
      setError(errorText(e));
      report(e, "The customer was not deactivated.");
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title={`Deactivate ${customer.name}?`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={busy} onClick={() => void confirm()}>
            Deactivate
          </Button>
        </>
      }
    >
      <p>
        They are signed out everywhere, cannot sign in or use any link they were sent, and stop
        getting ticket emails. Their tickets stay as they are. You can reactivate them later.
      </p>
      {error && (
        <p role="alert" className={styles.errorText}>
          {error}
        </p>
      )}
    </Modal>
  );
}

function MoveDialog({
  customer,
  organisations,
  run,
  onClose,
  onDone,
}: {
  customer: CustomerDetail;
  organisations: Organisation[];
  run: <T>(action: () => Promise<T>) => Promise<T>;
  onClose: () => void;
  onDone: (c: CustomerDetail, removed: number) => void;
}) {
  const report = useReportError();
  const others = organisations.filter((o) => o.id !== customer.organisationId);
  const [target, setTarget] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function confirm() {
    if (!target) {
      setError("Choose the organisation to move them to.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const r = await run(() => moveCustomer(customer.id, Number(target)));
      onDone(r.customer, r.participantTicketsRemoved);
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) {
        setError(errorText(e));
        report(e, "The customer was not moved.");
      }
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title={`Move ${customer.name} to another organisation`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !target} onClick={() => void confirm()}>
            Move
          </Button>
        </>
      }
    >
      <p>Now in: {customer.organisationName}</p>
      <Select
        label="Move to"
        fieldSize="sm"
        value={target}
        onChange={(e) => setTarget(e.target.value)}
        options={[
          { value: "", label: "Choose an organisation" },
          ...others.map((o) => ({ value: String(o.id), label: o.name })),
        ]}
      />
      <ul className={styles.plainList}>
        <li>They are signed out everywhere and sign in again as part of the new organisation.</li>
        <li>
          They are removed as a participant (CC) from tickets of {customer.organisationName} and any
          other organisation.
        </li>
        <li>
          Tickets they raised stay with {customer.organisationName} and they stop getting emails
          about them. An agent can change a ticket&apos;s reporter if needed.
        </li>
      </ul>
      <p className={styles.formNote}>Asks for your two-step code.</p>
      {error && (
        <p role="alert" className={styles.errorText}>
          {error}
        </p>
      )}
    </Modal>
  );
}

function EraseDialog({
  customer,
  run,
  onClose,
  onDone,
}: {
  customer: CustomerDetail;
  run: <T>(action: () => Promise<T>) => Promise<T>;
  onClose: () => void;
  onDone: () => void;
}) {
  const report = useReportError();
  const [typed, setTyped] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const email = customer.email ?? "";
  const matches = email !== "" && typed.trim().toLowerCase() === email.toLowerCase();

  async function confirm() {
    if (!matches) return;
    setBusy(true);
    setError("");
    try {
      await run(() => eraseCustomer(customer.id));
      onDone();
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) {
        setError(errorText(e));
        report(e, "The personal data was not erased.");
      }
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title={`Erase ${customer.name}'s personal data`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={busy || !matches} onClick={() => void confirm()}>
            Erase permanently
          </Button>
        </>
      }
    >
      <p>
        <strong>This cannot be undone.</strong> For a privacy request only. Their name becomes
        &quot;Former user&quot;, their email address is removed everywhere, they are signed out and
        can never sign in again. Their tickets and the text of their messages stay.
      </p>
      <TextField
        label={`Type ${email} to confirm`}
        fieldSize="md"
        value={typed}
        autoComplete="off"
        spellCheck={false}
        onChange={(e) => setTyped(e.target.value)}
      />
      <p className={styles.formNote}>Asks for your two-step code.</p>
      {error && (
        <p role="alert" className={styles.errorText}>
          {error}
        </p>
      )}
    </Modal>
  );
}

function CustomerPanel({
  summary,
  organisations,
  onChanged,
  onClose,
}: {
  summary: CustomerSummary;
  organisations: Organisation[];
  onChanged: () => void;
  onClose: () => void;
}) {
  const toast = useToast();
  const report = useReportError();
  const { run, dialog } = useStepUp();
  const [detail, setDetail] = useState<CustomerDetail | null>(null);
  const [error, setError] = useState("");
  const [reloads, setReloads] = useState(0);
  const [open, setOpen] = useState<"deactivate" | "move" | "erase" | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetchCustomer(summary.id, controller.signal)
      .then((d) => {
        setDetail(d);
        setError("");
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(errorText(e));
      });
    return () => controller.abort();
  }, [summary.id, reloads]);

  function changed(next?: CustomerDetail) {
    if (next) setDetail(next);
    setReloads((n) => n + 1);
    onChanged();
  }

  async function act(action: () => Promise<unknown>, done: string, failed: string) {
    setBusy(true);
    try {
      await action();
      toast.show(done, "success");
      changed();
    } catch (e) {
      report(e, failed);
    } finally {
      setBusy(false);
    }
  }

  const c = detail;
  const name = c?.name ?? summary.name;
  const live = c !== null && c.status !== "ERASED";

  return (
    <SidePanel
      label="Customer detail"
      onClose={onClose}
      header={
        <div>
          <h2 className={styles.panelName}>{name}</h2>
          <div className={styles.subtle}>
            {STATUS_LABEL[c?.status ?? summary.status]} -{" "}
            {c?.organisationName ?? summary.organisationName}
          </div>
        </div>
      }
      footer={
        live ? (
          <div className={styles.actions}>
            {c.status === "DEACTIVATED" ? (
              <Button
                disabled={busy}
                onClick={() =>
                  void act(
                    () => reactivateCustomer(c.id),
                    `${c.name} is active again.`,
                    "The customer was not reactivated.",
                  )
                }
              >
                Reactivate
              </Button>
            ) : (
              <Button variant="danger" onClick={() => setOpen("deactivate")}>
                Deactivate
              </Button>
            )}
            <Button variant="danger" onClick={() => setOpen("erase")}>
              Erase personal data
            </Button>
          </div>
        ) : undefined
      }
    >
      {error && (
        <p role="alert" className={styles.errorText}>
          {error}
        </p>
      )}
      {!c && !error && <p>Loading...</p>}
      {c && (
        <div className={styles.tabBody}>
          <dl className={styles.facts}>
            <dt>Email</dt>
            <dd>{c.email ? <Identifier value={c.email} /> : "Removed (erased)"}</dd>
            <dt>Organisation</dt>
            <dd>{c.organisationName}</dd>
            <dt>Status</dt>
            <dd>
              <StatusBadge status={c.status} />
            </dd>
            <dt>Sign-in methods</dt>
            <dd>
              {[
                c.passwordSet ? "Password set" : "No password yet",
                c.microsoftLinked ? "Microsoft linked" : null,
              ]
                .filter(Boolean)
                .join(", ")}
            </dd>
            <dt>Last sign-in</dt>
            <dd>{when(c.lastSignInAt, "Never (or over a year ago)")}</dd>
            <dt>Invitation</dt>
            <dd>{invitationText(c)}</dd>
            <dt>Tickets raised</dt>
            <dd>
              {c.ticketsRaised}
              {c.email && c.ticketsRaised > 0 && (
                <>
                  {" - "}
                  <Link
                    to={`/agent/queue?${new URLSearchParams({ view: "all", closed: "1", q: c.email }).toString()}`}
                  >
                    Search the queue for their email
                  </Link>
                </>
              )}
            </dd>
            <dt>Created</dt>
            <dd>{when(c.createdAt, "-")}</dd>
          </dl>
          {live && (
            <div className={styles.actions}>
              {c.status === "INVITED" && (
                <Button
                  disabled={busy}
                  onClick={() =>
                    void act(
                      () => resendCustomerInvitation(c.id),
                      "Invitation sent. The link is valid for 7 days.",
                      "The invitation was not sent.",
                    )
                  }
                >
                  {c.invitationExpiresAt ? "Resend invitation" : "Send invitation"}
                </Button>
              )}
              {c.status === "ACTIVE" && (
                <Button
                  disabled={busy}
                  onClick={() =>
                    void act(
                      () => sendCustomerPasswordReset(c.id),
                      "Password reset link sent to their email.",
                      "The password reset link was not sent.",
                    )
                  }
                >
                  Send password reset link
                </Button>
              )}
              <Button
                disabled={busy}
                onClick={() =>
                  void act(
                    () => run(() => signOutCustomerEverywhere(c.id)),
                    "Signed out everywhere.",
                    "They were not signed out.",
                  )
                }
              >
                Sign out everywhere
              </Button>
              <Button onClick={() => setOpen("move")}>Move to another organisation</Button>
            </div>
          )}
          {c.status === "ERASED" && (
            <p className={styles.subtle}>This customer&apos;s personal data was erased.</p>
          )}
        </div>
      )}
      {c && open === "deactivate" && (
        <DeactivateDialog
          customer={c}
          onClose={() => setOpen(null)}
          onDone={(next) => {
            setOpen(null);
            toast.show(`${c.name} is deactivated and signed out everywhere.`, "success");
            changed(next);
          }}
        />
      )}
      {c && open === "move" && (
        <MoveDialog
          customer={c}
          organisations={organisations}
          run={run}
          onClose={() => setOpen(null)}
          onDone={(next, removed) => {
            setOpen(null);
            toast.show(
              `${c.name} moved to ${next.organisationName}.` +
                (removed > 0
                  ? ` Removed as a participant from ${removed} ticket${removed === 1 ? "" : "s"}.`
                  : ""),
              "success",
            );
            changed(next);
          }}
        />
      )}
      {c && open === "erase" && (
        <EraseDialog
          customer={c}
          run={run}
          onClose={() => setOpen(null)}
          onDone={() => {
            setOpen(null);
            toast.show("Personal data erased.", "success");
            changed();
            onClose();
          }}
        />
      )}
      {dialog}
    </SidePanel>
  );
}

/**
 * Admin - Customers: customers of every organisation, with resend invitation, password
 * reset, force sign-out, deactivate / reactivate, move and erase. Admin only on the server; the
 * agent Customers page is separate.
 */
export function CustomersAdminPage() {
  const report = useReportError();
  const [params, setParams] = useSearchParams();
  const key = params.toString();
  const page = Math.max(0, Number(params.get("page") ?? "0") || 0);
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [result, setResult] = useState<{
    key: string;
    items: CustomerSummary[];
    total: number;
    error: string | null;
  } | null>(null);
  const [selected, setSelected] = useState<CustomerSummary | null>(null);
  const [reloads, setReloads] = useState(0);
  const reload = useCallback(() => setReloads((n) => n + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    listOrganisations(controller.signal)
      .then(setOrganisations)
      .catch((e: unknown) => {
        setOrganisations([]);
        report(e, "Organisations could not be loaded.");
      });
    return () => controller.abort();
  }, [report]);

  useEffect(() => {
    const controller = new AbortController();
    const p = new URLSearchParams(key);
    listCustomers(
      {
        q: p.get("q") ?? undefined,
        organisationId: p.get("org") ? Number(p.get("org")) : null,
        status: asStatus(p.get("status")),
        sort: asSort(p.get("sort")),
        page: Number(p.get("page") ?? "0") || 0,
      },
      controller.signal,
    ).then(
      (r) => setResult({ key, items: r.items, total: r.total, error: null }),
      (e: unknown) => {
        if (!controller.signal.aborted) {
          setResult({ key, items: [], total: 0, error: errorText(e) });
        }
      },
    );
    return () => controller.abort();
  }, [key, reloads]);

  const current = result && result.key === key ? result : null;
  const pages = Math.max(1, Math.ceil((current?.total ?? 0) / CUSTOMER_PAGE_SIZE));

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    for (const name of ["q", "org", "status", "sort"]) {
      const v = String(data.get(name) ?? "").trim();
      if (v && !(name === "sort" && v === "name")) next.set(name, v);
    }
    setParams(next);
  }

  function goTo(n: number) {
    const next = new URLSearchParams(params);
    next.set("page", String(n));
    setParams(next);
  }

  const columns: Column<CustomerSummary>[] = [
    {
      key: "name",
      header: "Name",
      render: (row) => (
        <div className={styles.personCell}>
          <button
            type="button"
            className={styles.personName}
            aria-pressed={row.id === selected?.id}
            onClick={() => setSelected(row)}
          >
            {row.name}
          </button>
          <div className={styles.subtle}>
            {row.email ? <Identifier value={row.email} /> : "Email removed"}
          </div>
        </div>
      ),
    },
    {
      key: "org",
      header: "Organisation",
      render: (row) => <span className={styles.orgsCell}>{row.organisationName}</span>,
    },
    {
      key: "status",
      header: "Status",
      nowrap: true,
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: "last",
      header: <span className={styles.noWrap}>Last sign-in</span>,
      label: "Last sign-in",
      muted: true,
      nowrap: true,
      render: (row) => lastSignIn(row.lastSignInAt),
    },
  ];

  return (
    <div className={styles.split}>
      <div className={styles.columnNarrow}>
        <PageHeader
          title="Customers"
          meta={
            current ? `${current.total} customer${current.total === 1 ? "" : "s"}` : "Loading..."
          }
        />
        <form
          className={styles.queueFilters}
          onSubmit={search}
          aria-label="Search customers"
          key={key}
        >
          <TextField
            label="Search"
            name="q"
            type="search"
            fieldSize="md"
            defaultValue={params.get("q") ?? ""}
            placeholder="Name or email"
            maxLength={200}
          />
          <Select
            label="Organisation"
            name="org"
            fieldSize="sm"
            defaultValue={params.get("org") ?? ""}
            options={[
              { value: "", label: "All organisations" },
              ...organisations.map((o) => ({ value: String(o.id), label: o.name })),
            ]}
          />
          <Select
            label="Status"
            name="status"
            fieldSize="sm"
            defaultValue={asStatus(params.get("status"))}
            options={STATUS_OPTIONS}
          />
          <Select
            label="Sort by"
            name="sort"
            fieldSize="sm"
            defaultValue={asSort(params.get("sort"))}
            options={SORT_OPTIONS}
          />
          <div className={styles.filterActions}>
            <Button type="submit">Search</Button>
          </div>
        </form>
        {current?.error && (
          <p className={styles.errorBox} role="alert">
            {current.error}
          </p>
        )}
        <DataTable
          stackOnPhone
          caption="Customers"
          columns={columns}
          rows={current?.items ?? []}
          loading={!current}
          rowKey={(row) => String(row.id)}
          selectedKey={selected ? String(selected.id) : undefined}
          emptyMessage="No customers match."
        />
        {(current?.total ?? 0) > CUSTOMER_PAGE_SIZE && (
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
      {selected && (
        <CustomerPanel
          key={selected.id}
          summary={selected}
          organisations={organisations}
          onChanged={reload}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}
