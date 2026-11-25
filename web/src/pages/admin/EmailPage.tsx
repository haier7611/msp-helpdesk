import { useEffect, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router";
import {
  addSenderRule,
  fetchReplyMode,
  listPublicDomains,
  listRequestTypes,
  listSenderRules,
  removeSenderRule,
  saveReplyMode,
  type AdminRequestType,
  type ReplyMode,
  type RuleKind,
  type SenderRule,
} from "../../api/admin";
import { listOrganisations, type Organisation } from "../../api/organisations";
import { DataTable, Identifier } from "../../components/DataTable";
import { Button, RadioGroup, Select, TextField } from "../../components/forms";
import { Tabs } from "../../components/Tabs";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import { useStepUp } from "../../components/useStepUp";
import styles from "./admin.module.css";
import { cancelled, messageOf, when } from "./adminShared";
import { CompanySignatureTab } from "./CompanySignatureTab";
import { MailboxesTab } from "./MailboxesTab";
import { useReportError } from "../../components/useReportError";

const TAB_IDS = ["mailboxes", "replies", "signature", "sender-rules", "public-domains"] as const;

/** Admin - Email and signature. */
export function EmailPage() {
  const [params, setParams] = useSearchParams();
  const tab = TAB_IDS.find((t) => t === params.get("tab")) ?? "mailboxes";
  return (
    <div className={styles.page}>
      <PageHeader title="Email and signature" />
      <Tabs
        label="Email settings"
        selectedId={tab}
        onSelect={(id) => setParams({ tab: id }, { replace: true })}
        items={[
          { id: "mailboxes", label: "Mailboxes", content: <MailboxesTab /> },
          { id: "replies", label: "Agent email replies", content: <ReplyModeTab /> },
          { id: "signature", label: "Company signature", content: <CompanySignatureTab /> },
          { id: "sender-rules", label: "Sender rules", content: <SenderRulesTab /> },
          { id: "public-domains", label: "Public domains", content: <PublicDomainsTab /> },
        ]}
      />
    </div>
  );
}

const MODES: { value: ReplyMode; label: string }[] = [
  { value: "OFF", label: "Off - agent emails to the ticket go to Email triage" },
  {
    value: "INTERNAL_ONLY",
    label: "Internal notes only (recommended) - an agent's emailed reply becomes an internal note",
  },
  {
    value: "NOTES_AND_PUBLIC",
    label: "Notes and public replies - an emailed reply to a client thread is sent to the client",
  },
];

/** The three-way "Agent email replies" setting, with its risk on screen. */
function ReplyModeTab() {
  const toast = useToast();
  const report = useReportError();
  const { run, dialog } = useStepUp();
  const [saved, setSaved] = useState<ReplyMode | null>(null);
  const [mode, setMode] = useState<ReplyMode>("INTERNAL_ONLY");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetchReplyMode(controller.signal)
      .then((m) => {
        setSaved(m);
        setMode(m);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(messageOf(e));
      });
    return () => controller.abort();
  }, []);

  async function onSave(event: FormEvent) {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      const m = await run(() => saveReplyMode(mode));
      setSaved(m);
      setMode(m);
      toast.show("Agent email replies setting saved.", "success");
    } catch (e) {
      if (!cancelled(e)) setError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Agent email replies">
      <form className={styles.form} onSubmit={onSave} noValidate>
        <p className={styles.warning}>
          Risk: email replies skip two-step sign-in. If an agent&apos;s mailbox is taken over,
          whoever holds it can write on tickets - and with &quot;Notes and public replies&quot;,
          write to clients. Each emailed reply must pass DMARC and come from an active agent, and
          every one is audit-logged. Changing this asks for a code from your authenticator.
        </p>
        <RadioGroup
          legend="When an agent replies by email"
          name="replyMode"
          value={mode}
          options={MODES}
          onChange={(v) => setMode(v as ReplyMode)}
        />
        <p className={styles.muted}>
          Replies to internal notifications (support+internal@) are always internal notes and never
          reach a client.
        </p>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <Button type="submit" variant="primary" disabled={busy || saved === null || saved === mode}>
          Save setting
        </Button>
      </form>
      {dialog}
    </Card>
  );
}

/** Sender rules: routing only - there is no "ignore" rule; every email is a ticket. */
function SenderRulesTab() {
  const toast = useToast();
  const report = useReportError();
  const [rules, setRules] = useState<SenderRule[] | null>(null);
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [types, setTypes] = useState<AdminRequestType[]>([]);
  const [kind, setKind] = useState<RuleKind>("ADDRESS");
  const [value, setValue] = useState("");
  const [orgId, setOrgId] = useState("");
  const [typeId, setTypeId] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listSenderRules(controller.signal)
      .then(setRules)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(messageOf(e));
      });
    listOrganisations(controller.signal)
      .then(setOrganisations)
      .catch((e: unknown) => report(e, "Organisations could not be loaded."));
    listRequestTypes(controller.signal)
      .then(setTypes)
      .catch((e: unknown) => report(e, "Request types could not be loaded."));
    return () => controller.abort();
  }, [report]);

  const orgName = (id: number) => organisations.find((o) => o.id === id)?.name ?? `#${id}`;
  const typeName = (id: number) => types.find((t) => t.id === id)?.name ?? `#${id}`;

  async function onAdd(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (!value.trim() || !orgId || !typeId) {
      setError("Enter the sender and choose an organisation and a request type.");
      return;
    }
    setBusy(true);
    try {
      const rule = await addSenderRule({
        kind,
        value: value.trim(),
        organisationId: Number(orgId),
        requestTypeId: Number(typeId),
      });
      setRules((r) => [...(r ?? []), rule]);
      setValue("");
      toast.show("Sender rule added. It applies to the next email.", "success");
    } catch (e) {
      setError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  async function onRemove(rule: SenderRule) {
    setBusy(true);
    try {
      await removeSenderRule(rule.id);
      setRules((r) => r?.filter((x) => x.id !== rule.id) ?? null);
      toast.show(`Removed the rule for ${rule.value}.`, "success");
    } catch (e) {
      toast.showError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.body}>
      <p className={styles.muted}>
        &quot;Always create the ticket for this organisation as this request type&quot; - for alert
        senders that cannot use the API. Rules only route mail: there is no way to ignore mail (use
        the Block list for spam). A rule needs the sender to pass DMARC.
      </p>
      <Card title="Add a sender rule">
        <form className={styles.grid} onSubmit={onAdd} noValidate>
          <Select
            label="Match"
            value={kind}
            options={[
              { value: "ADDRESS", label: "Sender address" },
              { value: "DOMAIN", label: "Sender domain" },
            ]}
            onChange={(e) => setKind(e.target.value as RuleKind)}
          />
          <TextField
            label={kind === "ADDRESS" ? "Sender address" : "Sender domain"}
            autoComplete="off"
            placeholder={kind === "ADDRESS" ? "alerts@monitor.example" : "monitor.example"}
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
          <Select
            label="Organisation"
            value={orgId}
            options={[
              { value: "", label: "Choose an organisation" },
              ...organisations.map((o) => ({ value: String(o.id), label: o.name })),
            ]}
            onChange={(e) => setOrgId(e.target.value)}
          />
          <Select
            label="Request type"
            value={typeId}
            options={[
              { value: "", label: "Choose a request type" },
              ...types
                .filter((t) => !t.archived)
                .map((t) => ({ value: String(t.id), label: t.name })),
            ]}
            onChange={(e) => setTypeId(e.target.value)}
          />
          <Button type="submit" variant="primary" disabled={busy}>
            Add rule
          </Button>
        </form>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
      </Card>
      <Card title="Sender rules">
        <DataTable
          stackOnPhone
          caption="Sender rules"
          minWidth={640}
          loading={rules === null && !error}
          rows={rules ?? []}
          rowKey={(r) => String(r.id)}
          emptyMessage="No sender rules."
          columns={[
            {
              key: "value",
              header: "Sender",
              rowHeader: true,
              render: (r) => <Identifier value={r.value} />,
            },
            {
              key: "kind",
              header: "Match",
              render: (r) => (r.kind === "DOMAIN" ? "Domain" : "Address"),
            },
            { key: "org", header: "Organisation", render: (r) => orgName(r.organisationId) },
            { key: "type", header: "Request type", render: (r) => typeName(r.requestTypeId) },
            { key: "created", header: "Added", nowrap: true, render: (r) => when(r.createdAt) },
            {
              key: "remove",
              header: <span className="visually-hidden">Actions</span>,
              actions: true,
              render: (r) => (
                <Button
                  aria-label={`Remove the rule for ${r.value}`}
                  disabled={busy}
                  onClick={() => void onRemove(r)}
                >
                  Remove
                </Button>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}

/** The maintained public email domain list, read-only. */
function PublicDomainsTab() {
  const [domains, setDomains] = useState<string[] | null>(null);
  const [filter, setFilter] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    listPublicDomains(controller.signal)
      .then(setDomains)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(messageOf(e));
      });
    return () => controller.abort();
  }, []);

  const shown = (domains ?? []).filter((d) => d.includes(filter.trim().toLowerCase()));

  return (
    <Card title="Public email domains">
      <div className={styles.body}>
        <p className={styles.muted}>
          These domains (and their subdomains) can never be an organisation&apos;s verified domain,
          so their senders are matched only as saved contacts. They may still be blocked. The list
          is maintained with the application; ask your developer to add a domain.
        </p>
        <TextField label="Filter" value={filter} onChange={(e) => setFilter(e.target.value)} />
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        {domains === null && !error ? (
          <p className={styles.muted}>Loading...</p>
        ) : (
          <>
            <p className={styles.muted}>
              {shown.length} of {domains?.length ?? 0} domains
            </p>
            <ul className={styles.domainList} aria-label="Public email domains">
              {shown.map((d) => (
                <li key={d} className={styles.mono}>
                  {d}
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </Card>
  );
}
