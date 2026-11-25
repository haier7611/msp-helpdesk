import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import {
  changePassword,
  fetchMfaStatus,
  fetchSessions,
  fetchTrustedDevices,
  regenerateRecoveryCodes,
  removeAllTrustedDevices,
  removeTrustedDevice,
  signOutOtherSessions,
  signOutSession,
  type MfaStatus,
  type SessionInfo,
  type TrustedDevice,
} from "../../api/security";
import { DataTable, Identifier } from "../../components/DataTable";
import { Button, TextField } from "../../components/forms";
import { Modal } from "../../components/Modal";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import { StepUpCancelled, useStepUp } from "../../components/useStepUp";
import { errorText, passwordProblem } from "../auth/authHelpers";
import { MicrosoftAccountCard } from "./MicrosoftAccountCard";
import { RecoveryCodeList } from "../auth/MfaPages";
import styles from "../auth/mfa.module.css";
import { useReportError } from "../../components/useReportError";

function when(iso: string | null): string {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "-"
    : d.toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" });
}

/**
 * My settings - Security: change password, two-step sign-in (new
 * authenticator, new recovery codes), trusted devices and active sessions. Sensitive changes ask
 * for a fresh code first (step-up).
 */
export function SettingsSecurityPage() {
  const toast = useToast();
  const navigate = useNavigate();
  const { run, dialog } = useStepUp();
  const [mfa, setMfa] = useState<MfaStatus | null>(null);
  const [devices, setDevices] = useState<TrustedDevice[] | null>(null);
  const [sessions, setSessions] = useState<SessionInfo[] | null>(null);
  const [loadError, setLoadError] = useState("");
  const [newCodes, setNewCodes] = useState<string[] | null>(null);

  const [reloads, setReloads] = useState(0);
  const load = useCallback(() => setReloads((n) => n + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    const signal = controller.signal;
    Promise.all([fetchMfaStatus(signal), fetchTrustedDevices(signal), fetchSessions(signal)])
      .then(([m, d, s]) => {
        setMfa(m);
        setDevices(d);
        setSessions(s);
        setLoadError("");
      })
      .catch((e: unknown) => {
        if (!signal.aborted) setLoadError(errorText(e));
      });
    return () => controller.abort();
  }, [reloads]);

  async function act(action: () => Promise<unknown>, done: string) {
    try {
      await action();
      toast.show(done, "success");
      load();
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) toast.showError(errorText(e));
    }
  }

  async function onNewCodes() {
    try {
      setNewCodes(await run(regenerateRecoveryCodes));
      load();
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) toast.showError(errorText(e));
    }
  }

  return (
    <div className={styles.page}>
      <PageHeader title="Security" />
      {loadError && (
        <p role="alert" className={styles.error}>
          {loadError}
        </p>
      )}

      <PasswordCard />

      <Card title="Two-step sign-in">
        <div className={styles.cardBody}>
          {mfa === null ? (
            <p className={styles.muted} role="status">
              Loading...
            </p>
          ) : mfa.enrolled ? (
            <>
              <p className={styles.muted}>
                On since {when(mfa.enrolledAt)}. {mfa.recoveryCodesLeft} of 10 recovery codes left.
              </p>
              <div className={styles.actions}>
                <Button onClick={() => navigate("/mfa/enrol?from=settings")}>
                  Set up a new authenticator app
                </Button>
                <Button onClick={onNewCodes}>Make new recovery codes</Button>
              </div>
            </>
          ) : (
            <p className={styles.muted}>Two-step sign-in is not set up.</p>
          )}
        </div>
      </Card>

      <MicrosoftAccountCard />

      <Card
        title="Trusted devices"
        aside={
          devices && devices.length > 0 ? (
            <Button
              variant="danger"
              onClick={() => act(removeAllTrustedDevices, "All trusted devices removed.")}
            >
              Remove all
            </Button>
          ) : undefined
        }
      >
        <p className={styles.muted}>
          These browsers skip the code at sign-in for 30 days. They still ask for a code before
          sensitive changes.
        </p>
        <DataTable
          stackOnPhone
          caption="Trusted devices"
          compact
          minWidth={480}
          loading={devices === null}
          rows={devices ?? []}
          rowKey={(d) => String(d.id)}
          emptyMessage="No trusted devices."
          columns={[
            { key: "label", header: "Browser", render: (d) => d.label, rowHeader: true },
            { key: "used", header: "Last used", render: (d) => when(d.lastUsedAt), nowrap: true },
            {
              key: "until",
              header: "Trusted until",
              render: (d) => when(d.trustedUntil),
              nowrap: true,
            },
            {
              key: "remove",
              header: <span className="visually-hidden">Actions</span>,
              actions: true,
              render: (d) => (
                <Button
                  aria-label={`Remove ${d.label}`}
                  onClick={() => act(() => removeTrustedDevice(d.id), "Device removed.")}
                >
                  Remove
                </Button>
              ),
            },
          ]}
        />
      </Card>

      <Card
        title="Active sessions"
        aside={
          sessions && sessions.length > 1 ? (
            <Button
              variant="danger"
              onClick={() => act(signOutOtherSessions, "Signed out of all other sessions.")}
            >
              Sign out all others
            </Button>
          ) : undefined
        }
      >
        <DataTable
          stackOnPhone
          caption="Active sessions"
          compact
          minWidth={560}
          loading={sessions === null}
          rows={sessions ?? []}
          rowKey={(s) => s.id}
          emptyMessage="No active sessions."
          columns={[
            {
              key: "device",
              header: "Browser",
              rowHeader: true,
              render: (s) => (s.current ? `${s.device ?? "Unknown"} (this browser)` : s.device),
            },
            {
              key: "ip",
              header: "IP address",
              render: (s) => (s.ipAddress ? <Identifier value={s.ipAddress} /> : "-"),
            },
            { key: "start", header: "Signed in", render: (s) => when(s.createdAt), nowrap: true },
            {
              key: "last",
              header: "Last active",
              render: (s) => when(s.lastActiveAt),
              nowrap: true,
            },
            {
              key: "out",
              header: <span className="visually-hidden">Actions</span>,
              actions: true,
              render: (s) =>
                s.current ? null : (
                  <Button
                    aria-label={`Sign out ${s.device ?? "session"}`}
                    onClick={() => act(() => signOutSession(s.id), "Session signed out.")}
                  >
                    Sign out
                  </Button>
                ),
            },
          ]}
        />
      </Card>

      <Modal
        open={newCodes !== null}
        title="Your new recovery codes"
        onClose={() => setNewCodes(null)}
      >
        <div className={styles.cardBody}>
          <p className={styles.muted}>
            Your old codes no longer work. Save these somewhere safe - they will not be shown again.
          </p>
          {newCodes && <RecoveryCodeList codes={newCodes} />}
        </div>
      </Modal>
      {dialog}
    </div>
  );
}

function PasswordCard() {
  const toast = useToast();
  const report = useReportError();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    const problem = passwordProblem(next, confirm);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    try {
      await changePassword(current, next);
      setCurrent("");
      setNext("");
      setConfirm("");
      toast.show("Password changed. Your other sessions were signed out.", "success");
    } catch (e) {
      setError(errorText(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Password">
      <form className={styles.form} onSubmit={onSubmit} noValidate>
        <TextField
          label="Current password"
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
        />
        <TextField
          label="New password"
          type="password"
          autoComplete="new-password"
          hint="At least 12 characters. Common or breached passwords are refused."
          value={next}
          onChange={(e) => setNext(e.target.value)}
        />
        <TextField
          label="Repeat new password"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
        />
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <Button type="submit" variant="primary" disabled={busy}>
          Change password
        </Button>
      </form>
    </Card>
  );
}
