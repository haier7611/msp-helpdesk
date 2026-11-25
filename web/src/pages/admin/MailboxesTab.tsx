import { useEffect, useState, type FormEvent } from "react";
import {
  createMailbox,
  deleteMailbox,
  listMailboxes,
  probeStatus,
  testMailbox,
  updateMailbox,
  type AuthType,
  type ImapSecurity,
  type Mailbox,
  type MailboxInput,
} from "../../api/admin";
import { Badge } from "../../components/Badge";
import { DataTable, Identifier } from "../../components/DataTable";
import {
  Advanced,
  Button,
  Checkbox,
  FieldRow,
  HelpToggle,
  RadioGroup,
  Select,
  TextField,
} from "../../components/forms";
import { Modal } from "../../components/Modal";
import { useToast } from "../../components/toastContext";
import { Card } from "../../components/ui";
import { useStepUp } from "../../components/useStepUp";
import styles from "./admin.module.css";
import { cancelled, messageOf, when } from "./adminShared";
import { OutgoingEmailFields } from "./OutgoingEmailFields";
import { defaultTrustedFor, TRUSTED_HINT, trustedAfterHostChange } from "./mailboxDefaults";
import { EMPTY_OUTGOING, outgoingDraft, outgoingInput, type OutgoingDraft } from "./outgoingEmail";
import { useReportError } from "../../components/useReportError";

type Draft = Pick<
  MailboxInput,
  | "name"
  | "address"
  | "enabled"
  | "imapHost"
  | "imapSecurity"
  | "imapFolder"
  | "authType"
  | "username"
  | "oauthTenantId"
  | "oauthTokenUrl"
  | "oauthClientId"
  | "oauthScope"
> & {
  id: number | null;
  imapPort: string;
  secret: string;
  secretSet: boolean;
  trusted: string;
  /** The outgoing (SMTP) settings. */
  outgoing: OutgoingDraft;
};

const EMPTY: Draft = {
  id: null,
  name: "",
  address: "",
  enabled: false,
  imapHost: "",
  imapPort: "993",
  imapSecurity: "TLS",
  imapFolder: "INBOX",
  authType: "PASSWORD",
  username: "",
  secret: "",
  secretSet: false,
  oauthTenantId: "",
  oauthTokenUrl: "",
  oauthClientId: "",
  oauthScope: "",
  trusted: "",
  outgoing: EMPTY_OUTGOING,
};

type TestState = { mailboxId: number; message: string; ok: boolean; probeId: number | null };

/**
 * Admin - Email - Mailboxes: any IMAP mailbox with a password or
 * OAuth2 client credentials (Microsoft 365). The password or client secret is write-only:
 * encrypted at rest, never shown again. Saving or removing a mailbox asks for step-up. Test
 * connection signs in and sends a test email to support+internal@ to prove plus-addressing.
 */
export function MailboxesTab() {
  const toast = useToast();
  const report = useReportError();
  const { run, dialog } = useStepUp();
  const [rows, setRows] = useState<Mailbox[] | null>(null);
  const [loadError, setLoadError] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [formError, setFormError] = useState("");
  const [busy, setBusy] = useState(false);
  const [test, setTest] = useState<TestState | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    listMailboxes(controller.signal)
      .then(setRows)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setLoadError(messageOf(e));
      });
    return () => controller.abort();
  }, []);

  function edit(m: Mailbox) {
    setFormError("");
    setDraft({
      id: m.id,
      name: m.name,
      address: m.address,
      enabled: m.enabled,
      imapHost: m.imapHost,
      imapPort: String(m.imapPort),
      imapSecurity: m.imapSecurity,
      imapFolder: m.imapFolder,
      authType: m.authType,
      username: m.username,
      secret: "",
      secretSet: m.secretSet,
      oauthTenantId: "",
      oauthTokenUrl: m.oauthTokenUrl ?? "",
      oauthClientId: m.oauthClientId ?? "",
      oauthScope: m.oauthScope ?? "",
      // An empty list on a Gmail / Microsoft 365 server gets the recommended value.
      trusted: m.trustedAuthservIds.join(", ") || (defaultTrustedFor(m.imapHost) ?? ""),
      outgoing: outgoingDraft(m),
    });
  }

  async function onSave(event: FormEvent) {
    event.preventDefault();
    if (!draft) return;
    setFormError("");
    const port = draft.imapPort.trim() === "" ? null : Number(draft.imapPort);
    if (port !== null && (!Number.isInteger(port) || port < 1 || port > 65535)) {
      setFormError("Enter a port from 1 to 65535.");
      return;
    }
    const outgoing = outgoingInput(draft.outgoing);
    if (typeof outgoing === "string") {
      setFormError(outgoing);
      return;
    }
    const oauth = draft.authType === "OAUTH2";
    const input: MailboxInput = {
      name: draft.name.trim(),
      address: draft.address.trim(),
      enabled: draft.enabled,
      imapHost: draft.imapHost.trim(),
      imapPort: port,
      imapSecurity: draft.imapSecurity,
      imapFolder: draft.imapFolder.trim() || "INBOX",
      authType: draft.authType,
      username: draft.username.trim(),
      secret: draft.secret === "" ? null : draft.secret,
      oauthTenantId: oauth && draft.oauthTenantId?.trim() ? draft.oauthTenantId.trim() : null,
      oauthTokenUrl: oauth && draft.oauthTokenUrl?.trim() ? draft.oauthTokenUrl.trim() : null,
      oauthClientId: oauth && draft.oauthClientId?.trim() ? draft.oauthClientId.trim() : null,
      oauthScope: oauth && draft.oauthScope?.trim() ? draft.oauthScope.trim() : null,
      trustedAuthservIds: draft.trusted
        .split(/[\s,]+/)
        .map((s) => s.trim())
        .filter(Boolean),
      ...outgoing,
    };
    setBusy(true);
    try {
      const id = draft.id;
      await run(() => (id === null ? createMailbox(input) : updateMailbox(id, input)));
      setRows(await listMailboxes());
      setDraft(null);
      toast.show("Mailbox saved.", "success");
    } catch (e) {
      if (!cancelled(e)) setFormError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  async function onDelete(m: Mailbox) {
    setBusy(true);
    try {
      await run(() => deleteMailbox(m.id));
      setRows((r) => r?.filter((x) => x.id !== m.id) ?? null);
      toast.show(`Removed ${m.address}.`, "success");
    } catch (e) {
      if (!cancelled(e)) toast.showError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  async function onTest(m: Mailbox) {
    setBusy(true);
    setTest({ mailboxId: m.id, message: "Testing the connection...", ok: true, probeId: null });
    try {
      const r = await testMailbox(m.id);
      setTest({ mailboxId: m.id, message: r.message, ok: r.loginOk, probeId: r.probeId });
    } catch (e) {
      setTest({ mailboxId: m.id, message: messageOf(e), ok: false, probeId: null });
      report(e, "The connection test could not run.");
    } finally {
      setBusy(false);
    }
  }

  async function onCheckProbe() {
    if (!test?.probeId) return;
    try {
      const p = await probeStatus(test.mailboxId, test.probeId);
      setTest({
        ...test,
        message: p.message,
        ok: !p.expired,
        probeId: p.arrived || p.expired ? null : test.probeId,
      });
    } catch (e) {
      setTest({ ...test, message: messageOf(e), ok: false });
      report(e, "The connection test could not run.");
    }
  }

  const testedName = rows?.find((m) => m.id === test?.mailboxId)?.address;

  return (
    <div className={styles.body}>
      <HelpToggle
        topic="mailboxes"
        detail="Any IMAP provider works; Microsoft 365 needs OAuth2 (IMAP.AccessAsApp). Plus-addressing (support+internal@) must be on: Test connection checks it."
      >
        The support mailbox receives client email and sends replies.
      </HelpToggle>
      {loadError && (
        <p role="alert" className={styles.error}>
          {loadError}
        </p>
      )}
      <Card
        aside={
          <Button
            variant="primary"
            onClick={() => {
              setFormError("");
              setDraft({ ...EMPTY });
            }}
          >
            Add mailbox
          </Button>
        }
        title="Mailboxes"
      >
        {test && (
          <div
            role="status"
            className={`${styles.notice} ${busy ? styles.noticeInfo : test.ok ? styles.noticeSuccess : styles.noticeError}`}
          >
            <p className={styles.noticeText}>
              <strong>{testedName}:</strong> {test.message}
            </p>
            {test.probeId !== null && (
              <Button onClick={() => void onCheckProbe()}>Check the test email</Button>
            )}
          </div>
        )}
        <DataTable
          stackOnPhone
          caption="Mailboxes"
          minWidth={860}
          loading={rows === null && !loadError}
          rows={rows ?? []}
          rowKey={(m) => String(m.id)}
          emptyMessage="No mailbox yet. Until one is added, mail goes out from the server's default sender."
          columns={[
            {
              key: "address",
              header: "Mailbox",
              rowHeader: true,
              render: (m) => (
                <>
                  <strong>{m.name}</strong>
                  <div>
                    <Identifier value={m.address} />
                  </div>
                </>
              ),
            },
            {
              key: "status",
              header: "Status",
              render: (m) =>
                !m.enabled ? (
                  <Badge tone="neutral">Off</Badge>
                ) : m.lastError ? (
                  <Badge tone="breached">Failing</Badge>
                ) : (
                  <Badge tone="ok">On</Badge>
                ),
            },
            {
              key: "auth",
              header: "Sign-in",
              nowrap: true,
              render: (m) =>
                `${m.authType === "OAUTH2" ? "OAuth2" : "Password"}${m.secretSet ? " (set)" : " (not set)"}`,
            },
            { key: "poll", header: "Last poll", nowrap: true, render: (m) => when(m.lastPollAt) },
            {
              key: "error",
              header: "Last error",
              // The incoming (poll) error and the last sending error, one per line.
              render: (m) =>
                m.lastError || m.smtpLastError ? (
                  <>
                    {m.lastError && (
                      <span className={styles.secondLine}>
                        <strong>Incoming:</strong> {m.lastError}
                      </span>
                    )}
                    {m.smtpLastError && (
                      <span className={styles.secondLine}>
                        <strong>Sending:</strong> {m.smtpLastError}
                      </span>
                    )}
                  </>
                ) : (
                  <span className={styles.muted}>None</span>
                ),
            },
            {
              key: "actions",
              header: <span className="visually-hidden">Actions</span>,
              actions: true,
              render: (m) => (
                <>
                  <Button
                    aria-label={`Test connection for ${m.address}`}
                    disabled={busy}
                    onClick={() => void onTest(m)}
                  >
                    Test connection
                  </Button>
                  <Button aria-label={`Edit ${m.address}`} onClick={() => edit(m)}>
                    Edit
                  </Button>
                  <Button
                    variant="danger"
                    aria-label={`Remove ${m.address}`}
                    disabled={busy}
                    onClick={() => void onDelete(m)}
                  >
                    Remove
                  </Button>
                </>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        open={draft !== null}
        size="wide"
        title={draft?.id === null ? "Add mailbox" : "Edit mailbox"}
        onClose={() => setDraft(null)}
      >
        {draft && (
          <form className={`${styles.form} ${styles.formWide}`} onSubmit={onSave} noValidate>
            <div className={styles.fieldPair}>
              <TextField
                label="Name"
                value={draft.name}
                maxLength={100}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              />
              <TextField
                label="Support address"
                type="email"
                autoComplete="off"
                placeholder="support@helpdesk.example"
                hint="No plus tag."
                value={draft.address}
                onChange={(e) => setDraft({ ...draft, address: e.target.value })}
              />
            </div>
            <div>
              <Checkbox
                label="Poll this mailbox"
                checked={draft.enabled}
                onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })}
              />
              <p className={styles.muted}>
                Only emails that arrive after the mailbox is connected become tickets.
              </p>
            </div>
            <div className={styles.sections}>
              <fieldset className={styles.section}>
                <legend className={styles.sectionLegend}>Incoming (IMAP)</legend>
                <HelpToggle
                  topic="incoming email"
                  detail="New client emails become tickets and replies are added to their ticket. Microsoft 365 needs OAuth2 client credentials with IMAP.AccessAsApp; Gmail works with an app password."
                >
                  Where client emails are read from.
                </HelpToggle>
                <TextField
                  label="IMAP server"
                  autoComplete="off"
                  placeholder="imap.gmail.com"
                  value={draft.imapHost}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      imapHost: e.target.value,
                      trusted: trustedAfterHostChange(
                        draft.imapHost,
                        e.target.value,
                        draft.trusted,
                      ),
                    })
                  }
                />
                <FieldRow>
                  <TextField
                    label="Port"
                    inputMode="numeric"
                    fieldSize="xs"
                    value={draft.imapPort}
                    onChange={(e) => setDraft({ ...draft, imapPort: e.target.value })}
                  />
                  <Select
                    label="Security"
                    fieldSize="sm"
                    value={draft.imapSecurity}
                    options={[
                      { value: "TLS", label: "TLS" },
                      { value: "STARTTLS", label: "STARTTLS" },
                      { value: "NONE", label: "None (local test only)" },
                    ]}
                    onChange={(e) =>
                      setDraft({ ...draft, imapSecurity: e.target.value as ImapSecurity })
                    }
                  />
                </FieldRow>
                <RadioGroup
                  inline
                  legend="Sign-in method"
                  name="authType"
                  value={draft.authType}
                  options={[
                    { value: "PASSWORD", label: "Password" },
                    { value: "OAUTH2", label: "OAuth2 (Microsoft 365)" },
                  ]}
                  onChange={(v) => setDraft({ ...draft, authType: v as AuthType })}
                />
                <TextField
                  label="User name"
                  autoComplete="off"
                  value={draft.username}
                  onChange={(e) => setDraft({ ...draft, username: e.target.value })}
                />
                <TextField
                  label={draft.authType === "OAUTH2" ? "Client secret" : "Password"}
                  type="password"
                  autoComplete="new-password"
                  hint={
                    draft.secretSet
                      ? "Saved and never shown again. Enter a new one only to replace it."
                      : "Stored encrypted and never shown again."
                  }
                  value={draft.secret}
                  onChange={(e) => setDraft({ ...draft, secret: e.target.value })}
                />
                {draft.authType === "OAUTH2" && (
                  <>
                    <TextField
                      label="Tenant ID"
                      hint="Directory (tenant) ID; fills in the token URL."
                      autoComplete="off"
                      value={draft.oauthTenantId ?? ""}
                      onChange={(e) => setDraft({ ...draft, oauthTenantId: e.target.value })}
                    />
                    <TextField
                      label="Client ID"
                      autoComplete="off"
                      value={draft.oauthClientId ?? ""}
                      onChange={(e) => setDraft({ ...draft, oauthClientId: e.target.value })}
                    />
                  </>
                )}
                <Advanced>
                  <TextField
                    label="Folder"
                    fieldSize="md"
                    value={draft.imapFolder}
                    onChange={(e) => setDraft({ ...draft, imapFolder: e.target.value })}
                  />
                  <TextField
                    label="Trusted authserv-ids"
                    hint={`The server name in Authentication-Results, comma separated. ${TRUSTED_HINT}`}
                    autoComplete="off"
                    value={draft.trusted}
                    onChange={(e) => setDraft({ ...draft, trusted: e.target.value })}
                  />
                  {draft.authType === "OAUTH2" && (
                    <>
                      <TextField
                        label="Token URL (optional)"
                        autoComplete="off"
                        value={draft.oauthTokenUrl ?? ""}
                        onChange={(e) => setDraft({ ...draft, oauthTokenUrl: e.target.value })}
                      />
                      <TextField
                        label="Scope (optional)"
                        autoComplete="off"
                        value={draft.oauthScope ?? ""}
                        onChange={(e) => setDraft({ ...draft, oauthScope: e.target.value })}
                      />
                    </>
                  )}
                </Advanced>
              </fieldset>
              <OutgoingEmailFields
                value={draft.outgoing}
                onChange={(outgoing) => setDraft({ ...draft, outgoing })}
              />
            </div>
            {formError && (
              <p role="alert" className={styles.error}>
                {formError}
              </p>
            )}
            <div className={styles.actions}>
              <Button type="submit" variant="primary" disabled={busy}>
                Save mailbox
              </Button>
              <Button onClick={() => setDraft(null)}>Cancel</Button>
            </div>
          </form>
        )}
      </Modal>
      {dialog}
    </div>
  );
}
