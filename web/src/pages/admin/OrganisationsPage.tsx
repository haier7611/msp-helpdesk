import { useEffect, useState, type FormEvent } from "react";
import { ApiError } from "../../api/client";
import {
  addDomain,
  changePrefix,
  createOrganisation,
  getClientEmails,
  listOrganisations,
  removeDomain,
  saveClientEmails,
  splitDomains,
  type ClientEmailSwitches,
  type Organisation,
} from "../../api/organisations";
import { DataTable, type Column } from "../../components/DataTable";
import { Button, Checkbox, TextField } from "../../components/forms";
import { Modal } from "../../components/Modal";
import { SidePanel } from "../../components/SidePanel";
import { useToast } from "../../components/toastContext";
import { PageHeader } from "../../components/ui";
import styles from "../pages.module.css";
import { useReportError } from "../../components/useReportError";

/** The server's plain-English message, or a calm fallback. */
function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : "Something went wrong. Please try again.";
}

function OrganisationPanel({
  org,
  onChanged,
  onClose,
}: {
  org: Organisation;
  onChanged: (org: Organisation) => void;
  onClose: () => void;
}) {
  const toast = useToast();
  const report = useReportError();
  const [prefix, setPrefix] = useState(org.prefix ?? "");
  const [prefixError, setPrefixError] = useState<string>();
  const [domain, setDomain] = useState("");
  const [domainError, setDomainError] = useState<string>();
  const [busy, setBusy] = useState(false);

  async function run(
    action: () => Promise<Organisation>,
    done: string,
    onError: (m: string) => void,
  ) {
    setBusy(true);
    try {
      const updated = await action();
      onChanged(updated);
      toast.show(done);
      return true;
    } catch (error) {
      onError(messageOf(error));
      report(error);
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function savePrefix(event: FormEvent) {
    event.preventDefault();
    setPrefixError(undefined);
    await run(() => changePrefix(org.id, prefix), "Ticket prefix changed.", setPrefixError);
  }

  async function saveDomain(event: FormEvent) {
    event.preventDefault();
    setDomainError(undefined);
    if (await run(() => addDomain(org.id, domain), "Domain added.", setDomainError)) {
      setDomain("");
    }
  }

  return (
    <SidePanel
      label="Organisation detail"
      onClose={onClose}
      header={
        <div>
          <h2 className={styles.panelName}>{org.name}</h2>
          <div className={styles.subtle}>
            {org.prefix} - {org.timezone}
          </div>
        </div>
      }
    >
      <form onSubmit={savePrefix} className={styles.tabBody}>
        <TextField
          label="Ticket prefix"
          hint="2 to 6 letters or digits, starting with a letter. Old tickets keep their references; new tickets carry on the numbering."
          value={prefix}
          error={prefixError}
          maxLength={6}
          autoComplete="off"
          onChange={(e) => setPrefix(e.target.value)}
        />
        {org.retiredPrefixes.length > 0 && (
          <p className={styles.subtle}>Earlier prefixes: {org.retiredPrefixes.join(", ")}</p>
        )}
        <Button type="submit" disabled={busy}>
          Change prefix
        </Button>
      </form>

      <section className={styles.tabBody} aria-labelledby={`domains-${org.id}`}>
        <h3 id={`domains-${org.id}`} className={styles.panelName}>
          Verified domains
        </h3>
        {org.verifiedDomains.length === 0 ? (
          <p className={styles.subtle}>No verified domains yet.</p>
        ) : (
          <ul className={styles.plainList}>
            {org.verifiedDomains.map((d) => (
              <li key={d} className={styles.row}>
                <span>{d}</span>
                <Button
                  disabled={busy}
                  aria-label={`Remove ${d}`}
                  onClick={() =>
                    void run(() => removeDomain(org.id, d), "Domain removed.", setDomainError)
                  }
                >
                  Remove
                </Button>
              </li>
            ))}
          </ul>
        )}
        <form onSubmit={saveDomain}>
          <TextField
            label="Add a domain"
            hint="For example acme-dental.example. Public email domains such as gmail.com cannot be added."
            value={domain}
            error={domainError}
            autoComplete="off"
            onChange={(e) => setDomain(e.target.value)}
          />
          <Button type="submit" disabled={busy || domain.trim() === ""}>
            Add domain
          </Button>
        </form>
      </section>

      <ClientEmailsSection org={org} />
    </SidePanel>
  );
}

/** The four client emails an admin can turn off for one organisation. */
const CLIENT_EMAILS: { key: keyof ClientEmailSwitches; label: string; hint: string }[] = [
  {
    key: "ticketReceived",
    label: "Ticket received",
    hint: "The confirmation with the ticket reference when the client raises a ticket.",
  },
  {
    key: "resolvedNotice",
    label: "Resolved notice",
    hint: "When an agent resolves a ticket, or it is resolved automatically.",
  },
  {
    key: "closedNotice",
    label: "Closed notice",
    hint: "When a resolved ticket is closed automatically.",
  },
  {
    key: "pendingReminder",
    label: "Pending reminder",
    hint: "The reminder while a ticket waits for the client's reply.",
  },
];

function ClientEmailsSection({ org }: { org: Organisation }) {
  const toast = useToast();
  const report = useReportError();
  const [state, setState] = useState<
    | { kind: "loading" }
    | { kind: "ready"; switches: ClientEmailSwitches }
    | { kind: "error"; message: string }
  >({ kind: "loading" });
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    getClientEmails(org.id, controller.signal)
      .then((switches) => setState({ kind: "ready", switches }))
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setState({ kind: "error", message: messageOf(e) });
      });
    return () => controller.abort();
  }, [org.id]);

  async function toggle(key: keyof ClientEmailSwitches, label: string, on: boolean) {
    if (state.kind !== "ready") return;
    setError(undefined);
    setBusy(true);
    try {
      const saved = await saveClientEmails(org.id, { ...state.switches, [key]: on });
      setState({ kind: "ready", switches: saved });
      toast.show(`${label} turned ${on ? "on" : "off"} for ${org.name}.`);
    } catch (e) {
      setError(messageOf(e));
      report(e, "The client email setting was not changed.");
    } finally {
      setBusy(false);
    }
  }

  const headingId = `client-emails-${org.id}`;
  return (
    <section className={styles.tabBody} aria-labelledby={headingId}>
      <h3 id={headingId} className={styles.panelName}>
        Client emails
      </h3>
      <p className={styles.subtle}>
        Automatic emails to this organisation&apos;s contacts. Agent replies and tickets an agent
        opens are always sent. When an email is turned off, the ticket timeline says it was not
        sent.
      </p>
      {state.kind === "loading" && <p className={styles.subtle}>Loading client emails...</p>}
      {state.kind === "error" && (
        <p role="alert" className={styles.reason}>
          The client email settings could not be loaded. {state.message}
        </p>
      )}
      {state.kind === "ready" &&
        CLIENT_EMAILS.map((c) => (
          <div key={c.key}>
            <Checkbox
              label={c.label}
              checked={state.switches[c.key]}
              disabled={busy}
              aria-describedby={`${headingId}-${c.key}`}
              onChange={(e) => void toggle(c.key, c.label, e.target.checked)}
            />
            <p id={`${headingId}-${c.key}`} className={styles.subtle}>
              {c.hint}
            </p>
          </div>
        ))}
      {error && (
        <p role="alert" className={styles.reason}>
          {error}
        </p>
      )}
    </section>
  );
}

function NewOrganisationModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (org: Organisation) => void;
}) {
  const [name, setName] = useState("");
  const [prefix, setPrefix] = useState("");
  const [domains, setDomains] = useState("");
  const [timezone, setTimezone] = useState("Asia/Ho_Chi_Minh");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const report = useReportError();

  async function submit() {
    setError(undefined);
    setBusy(true);
    try {
      const created = await createOrganisation({
        name,
        prefix,
        verifiedDomains: splitDomains(domains),
        timezone,
        entraTenantId: null,
      });
      setName("");
      setPrefix("");
      setDomains("");
      onCreated(created);
    } catch (e) {
      setError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      title="New organisation"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy} onClick={() => void submit()}>
            Create organisation
          </Button>
        </>
      }
    >
      <TextField
        label="Name"
        value={name}
        autoComplete="off"
        onChange={(e) => setName(e.target.value)}
      />
      <TextField
        label="Ticket prefix"
        hint="2 to 6 letters or digits, starting with a letter, for example ACD. TRI is reserved."
        value={prefix}
        maxLength={6}
        autoComplete="off"
        onChange={(e) => setPrefix(e.target.value)}
      />
      <TextField
        label="Verified domains (optional)"
        hint="Separate several with commas. Public email domains are not allowed."
        value={domains}
        autoComplete="off"
        onChange={(e) => setDomains(e.target.value)}
      />
      <TextField
        label="Time zone"
        hint="An IANA time zone such as Asia/Ho_Chi_Minh or Europe/London."
        value={timezone}
        autoComplete="off"
        onChange={(e) => setTimezone(e.target.value)}
      />
      {error && (
        <p role="alert" className={styles.reason}>
          {error}
        </p>
      )}
    </Modal>
  );
}

export function OrganisationsPage() {
  const toast = useToast();
  const [state, setState] = useState<
    | { kind: "loading" }
    | { kind: "ready"; rows: Organisation[] }
    | { kind: "error"; message: string }
  >({ kind: "loading" });
  const [selectedId, setSelectedId] = useState<number>();
  const [createOpen, setCreateOpen] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listOrganisations(controller.signal)
      .then((rows) => setState({ kind: "ready", rows }))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setState({ kind: "error", message: messageOf(error) });
      });
    return () => controller.abort();
  }, []);

  const rows = state.kind === "ready" ? state.rows : [];
  const selected = rows.find((r) => r.id === selectedId);

  function replace(org: Organisation) {
    setState({
      kind: "ready",
      rows: rows.some((r) => r.id === org.id)
        ? rows.map((r) => (r.id === org.id ? org : r))
        : [...rows, org].sort((a, b) => a.name.localeCompare(b.name)),
    });
  }

  const columns: Column<Organisation>[] = [
    {
      key: "name",
      header: "Name",
      rowHeader: true,
      render: (row) => (
        <button
          type="button"
          className={styles.personName}
          aria-pressed={row.id === selectedId}
          onClick={() => setSelectedId(row.id)}
        >
          {row.name}
        </button>
      ),
    },
    { key: "prefix", header: "Prefix", nowrap: true, render: (row) => row.prefix },
    {
      key: "domains",
      header: "Verified domains",
      render: (row) => row.verifiedDomains.join(", ") || "-",
    },
    { key: "timezone", header: "Time zone", muted: true, render: (row) => row.timezone },
  ];

  return (
    <>
      <div className={styles.split}>
        <div className={styles.columnNarrow}>
          <PageHeader
            title="Organisations"
            meta={
              state.kind === "ready"
                ? `${rows.length} organisation${rows.length === 1 ? "" : "s"}`
                : undefined
            }
            actions={
              <Button variant="primary" onClick={() => setCreateOpen(true)}>
                New organisation
              </Button>
            }
          />
          {state.kind === "error" ? (
            <p role="alert">{state.message}</p>
          ) : (
            <DataTable
              stackOnPhone
              caption="Organisations"
              columns={columns}
              rows={rows}
              rowKey={(row) => String(row.id)}
              loading={state.kind === "loading"}
              emptyMessage="No organisations yet. Create the first one."
              selectedKey={selectedId === undefined ? undefined : String(selectedId)}
            />
          )}
        </div>
        {selected && (
          <OrganisationPanel
            key={selected.id}
            org={selected}
            onChanged={replace}
            onClose={() => setSelectedId(undefined)}
          />
        )}
      </div>
      <NewOrganisationModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(org) => {
          replace(org);
          setCreateOpen(false);
          setSelectedId(org.id);
          toast.show(`${org.name} created with prefix ${org.prefix ?? ""}.`);
        }}
      />
    </>
  );
}
