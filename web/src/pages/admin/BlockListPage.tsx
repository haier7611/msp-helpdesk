import { useEffect, useState, type FormEvent } from "react";
import {
  addBlockRule,
  listBlockedMail,
  listBlockRules,
  removeBlockRule,
  restoreBlockedMail,
  type BlockedMail,
  type BlockRule,
  type RuleKind,
} from "../../api/admin";
import { ApiError } from "../../api/client";
import { Badge } from "../../components/Badge";
import { DataTable, Identifier } from "../../components/DataTable";
import { Button, Select, TextField } from "../../components/forms";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import { useStepUp } from "../../components/useStepUp";
import styles from "./admin.module.css";
import { cancelled, messageOf, when } from "./adminShared";
import { useReportError } from "../../components/useReportError";

/**
 * Admin - Block list: blocked sender addresses and domains -
 * the only exception to "every email becomes a ticket" - and the Blocked mail log (30 days) with
 * restore. Blocking a client's verified domain, a saved contact or a supporter shows the server's
 * warning naming the client and needs step-up; the support mailbox's own domain is refused.
 */
export function BlockListPage() {
  const toast = useToast();
  const report = useReportError();
  const { run, dialog } = useStepUp();
  const [rules, setRules] = useState<BlockRule[] | null>(null);
  const [blocked, setBlocked] = useState<BlockedMail[] | null>(null);
  const [loadError, setLoadError] = useState("");
  const [kind, setKind] = useState<RuleKind>("ADDRESS");
  const [value, setValue] = useState("");
  const [warning, setWarning] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    const fail = (e: unknown) => {
      if (!controller.signal.aborted) setLoadError(messageOf(e));
    };
    listBlockRules(controller.signal).then(setRules).catch(fail);
    listBlockedMail(controller.signal).then(setBlocked).catch(fail);
    return () => controller.abort();
  }, []);

  async function submit(confirm: boolean) {
    setError("");
    if (!value.trim()) {
      setError(kind === "ADDRESS" ? "Enter an email address." : "Enter a domain.");
      return;
    }
    setBusy(true);
    try {
      const rule = await run(() => addBlockRule(kind, value.trim(), confirm));
      setRules((r) => [...(r ?? []), rule]);
      setValue("");
      setWarning("");
      toast.show(`Blocked ${rule.value}.`, "success");
    } catch (e) {
      if (e instanceof ApiError && e.code === "confirmation_required") {
        setWarning(e.message);
      } else if (!cancelled(e)) {
        setError(messageOf(e));
        report(e);
      }
    } finally {
      setBusy(false);
    }
  }

  function onAdd(event: FormEvent) {
    event.preventDefault();
    void submit(false);
  }

  async function onRemove(rule: BlockRule) {
    setBusy(true);
    try {
      await removeBlockRule(rule.id);
      setRules((r) => r?.filter((x) => x.id !== rule.id) ?? null);
      toast.show(`Unblocked ${rule.value}.`, "success");
    } catch (e) {
      toast.showError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  async function onRestore(mail: BlockedMail) {
    setBusy(true);
    try {
      const ticketId = await restoreBlockedMail(mail.id);
      setBlocked(
        (b) =>
          b?.map((x) =>
            x.id === mail.id ? { ...x, restoredAt: new Date().toISOString(), ticketId } : x,
          ) ?? null,
      );
      toast.show(`Restored as ticket #${ticketId}.`, "success");
    } catch (e) {
      toast.showError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.page}>
      <PageHeader title="Block list" />
      <p className={styles.muted}>
        Mail from a blocked sender or domain creates no ticket and gets no reply, so spammers learn
        nothing. It is kept in the Blocked mail log for 30 days, where you can restore it as a
        normal ticket. A blocked domain also blocks its subdomains.
      </p>
      {loadError && (
        <p role="alert" className={styles.error}>
          {loadError}
        </p>
      )}
      <Card title="Block a sender or domain">
        <form className={styles.grid} onSubmit={onAdd} noValidate>
          <Select
            label="Block"
            value={kind}
            options={[
              { value: "ADDRESS", label: "Sender address" },
              { value: "DOMAIN", label: "Domain" },
            ]}
            onChange={(e) => {
              setKind(e.target.value as RuleKind);
              setWarning("");
            }}
          />
          <TextField
            label={kind === "ADDRESS" ? "Sender address" : "Domain"}
            autoComplete="off"
            placeholder={kind === "ADDRESS" ? "promo@spam.example" : "spam.example"}
            value={value}
            onChange={(e) => {
              setValue(e.target.value);
              setWarning("");
            }}
          />
          <Button type="submit" variant="primary" disabled={busy}>
            Block
          </Button>
        </form>
        {warning && (
          <div className={styles.body}>
            <p role="alert" className={styles.warning}>
              {warning}
            </p>
            <div className={styles.actions}>
              <Button variant="danger" disabled={busy} onClick={() => void submit(true)}>
                Block anyway
              </Button>
              <Button onClick={() => setWarning("")}>Cancel</Button>
            </div>
          </div>
        )}
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
      </Card>

      <Card title="Blocked senders and domains">
        <DataTable
          stackOnPhone
          caption="Blocked senders and domains"
          minWidth={520}
          loading={rules === null && !loadError}
          rows={rules ?? []}
          rowKey={(r) => String(r.id)}
          emptyMessage="Nothing is blocked."
          columns={[
            {
              key: "value",
              header: "Blocked",
              rowHeader: true,
              render: (r) => <Identifier value={r.value} />,
            },
            {
              key: "kind",
              header: "Type",
              render: (r) => (r.kind === "DOMAIN" ? "Domain" : "Address"),
            },
            { key: "created", header: "Added", nowrap: true, render: (r) => when(r.createdAt) },
            {
              key: "remove",
              header: <span className="visually-hidden">Actions</span>,
              actions: true,
              render: (r) => (
                <Button
                  aria-label={`Unblock ${r.value}`}
                  disabled={busy}
                  onClick={() => void onRemove(r)}
                >
                  Unblock
                </Button>
              ),
            },
          ]}
        />
      </Card>

      <Card title="Blocked mail log (30 days)">
        <DataTable
          stackOnPhone
          caption="Blocked mail log"
          minWidth={760}
          loading={blocked === null && !loadError}
          rows={blocked ?? []}
          rowKey={(m) => String(m.id)}
          emptyMessage="No blocked mail in the last 30 days."
          columns={[
            {
              key: "received",
              header: "Received",
              nowrap: true,
              render: (m) => when(m.receivedAt),
            },
            {
              key: "from",
              header: "From",
              render: (m) => <Identifier value={m.fromAddress} />,
            },
            { key: "subject", header: "Subject", rowHeader: true, render: (m) => m.subject || "-" },
            { key: "rule", header: "Matched rule", render: (m) => m.rule },
            {
              key: "restore",
              header: <span className="visually-hidden">Actions</span>,
              actions: true,
              render: (m) =>
                m.restoredAt ? (
                  <Badge tone="ok">Restored{m.ticketId !== null ? ` as #${m.ticketId}` : ""}</Badge>
                ) : (
                  <Button
                    aria-label={`Restore the email from ${m.fromAddress} as a ticket`}
                    disabled={busy}
                    onClick={() => void onRestore(m)}
                  >
                    Restore as ticket
                  </Button>
                ),
            },
          ]}
        />
      </Card>
      {dialog}
    </div>
  );
}
