import { useEffect, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router";
import {
  createContact,
  inviteContact,
  listAgentOrganisations,
  listContacts,
  PAGE_SIZE,
  type AgentOrganisation,
  type Contact,
} from "../../api/agent";
import { Badge } from "../../components/Badge";
import { DataTable, Identifier, type Column } from "../../components/DataTable";
import { Button, Checkbox, TextField } from "../../components/forms";
import { Modal } from "../../components/Modal";
import { useToast } from "../../components/toastContext";
import { PageHeader } from "../../components/ui";
import { errorText } from "../../format";
import formStyles from "../../components/forms.module.css";
import styles from "../pages.module.css";
import { useReportError } from "../../components/useReportError";

function NewContactDialog({
  organisations,
  onClose,
  onCreated,
}: {
  organisations: AgentOrganisation[];
  onClose: () => void;
  onCreated: (c: Contact, invited: boolean) => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const report = useReportError();
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const organisationId = Number(data.get("organisation") ?? 0);
    if (!organisationId) {
      setError("Choose an organisation.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const invite = data.get("invite") === "on";
      const c = await createContact({
        organisationId,
        name: String(data.get("name") ?? "").trim(),
        email: String(data.get("email") ?? "").trim(),
        invite,
      });
      onCreated(c, invite);
    } catch (e) {
      setError(errorText(e));
      report(e);
      setBusy(false);
    }
  }

  return (
    <Modal open title="New contact" onClose={onClose}>
      <form className={styles.stack} onSubmit={submit} noValidate>
        <div className={formStyles.field}>
          <label htmlFor="contact-organisation" className={formStyles.label}>
            Organisation
          </label>
          <select id="contact-organisation" name="organisation" className={formStyles.control}>
            <option value="">Choose an organisation</option>
            {organisations.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        </div>
        <TextField label="Name" name="name" maxLength={200} required />
        <TextField label="Email" name="email" type="email" maxLength={320} required />
        <Checkbox label="Send a portal invitation (link valid 7 days)" name="invite" />
        <p className={styles.subtle}>
          Without an invitation the contact can email tickets in but cannot sign in to the portal.
        </p>
        {error && (
          <p className={styles.errorBox} role="alert">
            {error}
          </p>
        )}
        <div className={styles.filterActions}>
          <Button type="submit" variant="primary" disabled={busy}>
            Create contact
          </Button>
          <Button onClick={onClose}>Cancel</Button>
        </div>
      </form>
    </Modal>
  );
}

/**
 * Customers: the contacts of the organisations this supporter works on. Search,
 * create and invite. The server limits every call to the supporter's organisations.
 */
export function CustomersPage() {
  const toast = useToast();
  const report = useReportError();
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const org = params.get("org") ?? "";
  const page = Math.max(0, Number(params.get("page") ?? "0") || 0);
  const key = params.toString();
  const [orgs, setOrgs] = useState<AgentOrganisation[]>([]);
  const [result, setResult] = useState<{
    key: string;
    items: Contact[];
    total: number;
    error: string | null;
  } | null>(null);
  const [version, setVersion] = useState(0);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listAgentOrganisations(controller.signal).then(setOrgs, (e: unknown) =>
      report(e, "Organisations could not be loaded."),
    );
    return () => controller.abort();
  }, [report]);

  useEffect(() => {
    const controller = new AbortController();
    const p = new URLSearchParams(key);
    listContacts(
      {
        q: p.get("q") ?? undefined,
        organisationId: p.get("org") ? Number(p.get("org")) : null,
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
  }, [key, version]);

  const current = result && result.key === key ? result : null;
  const pages = Math.max(1, Math.ceil((current?.total ?? 0) / PAGE_SIZE));

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    const text = String(data.get("q") ?? "").trim();
    const o = String(data.get("org") ?? "");
    if (text) next.set("q", text);
    if (o) next.set("org", o);
    setParams(next);
  }

  async function invite(c: Contact) {
    try {
      await inviteContact(c.id);
      toast.show(`Invitation sent to ${c.email}.`);
      setVersion((v) => v + 1);
    } catch (e) {
      report(e, "The invitation was not sent.");
    }
  }

  const columns: Column<Contact>[] = [
    { key: "name", header: "Name", rowHeader: true, render: (c) => c.name },
    { key: "email", header: "Email", render: (c) => <Identifier value={c.email} /> },
    { key: "org", header: "Organisation", render: (c) => c.organisationName ?? "" },
    {
      key: "status",
      header: "Status",
      nowrap: true,
      render: (c) =>
        c.status === "ACTIVE" ? (
          <Badge tone="ok">Active</Badge>
        ) : (
          <Badge tone="neutral">Not signed up</Badge>
        ),
    },
    {
      key: "actions",
      header: "Actions",
      actions: true,
      render: (c) =>
        c.status === "INVITED" ? (
          <Button onClick={() => void invite(c)} aria-label={`Invite ${c.name}`}>
            Invite
          </Button>
        ) : null,
    },
  ];

  return (
    <div className={styles.column}>
      <PageHeader
        title="Customers"
        meta={current ? `${current.total} contact${current.total === 1 ? "" : "s"}` : "Loading..."}
        actions={
          <Button variant="primary" onClick={() => setCreating(true)}>
            New contact
          </Button>
        }
      />
      <form
        className={styles.queueFilters}
        onSubmit={search}
        aria-label="Search contacts"
        key={key}
      >
        <TextField
          label="Search"
          name="q"
          type="search"
          defaultValue={q}
          placeholder="Name or email"
        />
        <div className={formStyles.field}>
          <label htmlFor="customers-org" className={formStyles.label}>
            Organisation
          </label>
          <select id="customers-org" name="org" defaultValue={org} className={formStyles.control}>
            <option value="">All my organisations</option>
            {orgs.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        </div>
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
        caption="Contacts"
        columns={columns}
        rows={current?.items ?? []}
        loading={!current}
        rowKey={(c) => String(c.id)}
        emptyMessage="No contacts match."
      />
      {(current?.total ?? 0) > PAGE_SIZE && (
        <nav className={styles.pager} aria-label="Pages">
          <Button
            disabled={page === 0}
            onClick={() => {
              const next = new URLSearchParams(params);
              next.set("page", String(page - 1));
              setParams(next);
            }}
          >
            Previous page
          </Button>
          <span>
            Page {page + 1} of {pages}
          </span>
          <Button
            disabled={page + 1 >= pages}
            onClick={() => {
              const next = new URLSearchParams(params);
              next.set("page", String(page + 1));
              setParams(next);
            }}
          >
            Next page
          </Button>
        </nav>
      )}
      <p className={styles.subtle}>
        Need a ticket for a caller? <Link to="/agent/tickets/new">Create a new ticket</Link>.
      </p>
      {creating && (
        <NewContactDialog
          organisations={orgs}
          onClose={() => setCreating(false)}
          onCreated={(c, invited) => {
            setCreating(false);
            toast.show(invited ? `${c.name} added and invited.` : `${c.name} added.`);
            setVersion((v) => v + 1);
          }}
        />
      )}
    </div>
  );
}
