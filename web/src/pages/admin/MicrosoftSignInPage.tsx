import { useEffect, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router";
import { ApiError } from "../../api/client";
import {
  browserNavigation,
  fetchMicrosoftSettings,
  saveMicrosoftSettings,
  setMicrosoftEnabled,
  setOrganisationTenant,
  startMicrosoftTest,
  type OrganisationTenant,
  type SettingsPage,
} from "../../api/microsoft";
import { DataTable } from "../../components/DataTable";
import { Button, TextField } from "../../components/forms";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import { StepUpCancelled, useStepUp } from "../../components/useStepUp";
import styles from "../auth/mfa.module.css";
import { useReportError } from "../../components/useReportError";

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : "Something went wrong. Please try again.";
}

function when(iso: string | null): string {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "-"
    : d.toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" });
}

/** Plain words for the Test sign-in result in the page address. */
function testResultText(test: string | null, code: string | null): string | null {
  if (test === "passed") return null;
  if (test !== "failed") return null;
  switch (code) {
    case "wrong_tenant":
      return "Test sign-in failed: the account you used is not in the MSP tenant entered here. Sign in with an account from your own tenant.";
    case "unavailable":
      return "Test sign-in failed: Microsoft could not be reached. Try again in a few minutes.";
    case "cancelled":
      return "Test sign-in was cancelled.";
    case null:
    case "failed":
      return "Test sign-in failed. Check the application ID, the redirect URI and the tenant ID, then try again.";
    default:
      return code.startsWith("AADSTS")
        ? `Test sign-in failed: Microsoft answered ${code}. Check the client secret and its expiry, and that the redirect URI matches exactly.`
        : "Test sign-in failed. Check the details and try again.";
  }
}

/**
 * Admin - Sign-in - Microsoft. Off by default. The admin enters the
 * Entra app registration's application (client) ID, client secret (write-only: never shown again)
 * and its expiry date, and the MSP's own tenant ID; runs Test sign-in; then switches it on. Each
 * organisation can allow one tenant for its customers. Every change asks for a fresh code (step-up)
 * and is audit-logged by the server.
 */
export function MicrosoftSignInPage() {
  const toast = useToast();
  const report = useReportError();
  const [params, setParams] = useSearchParams();
  const { run, dialog } = useStepUp();
  const [page, setPage] = useState<SettingsPage | null>(null);
  const [loadError, setLoadError] = useState("");
  const [busy, setBusy] = useState(false);
  const [clientId, setClientId] = useState("");
  const [secret, setSecret] = useState("");
  const [expires, setExpires] = useState("");
  const [tenant, setTenant] = useState("");
  const [formError, setFormError] = useState("");
  const test = params.get("test");
  const testProblem = testResultText(test, params.get("code"));

  function show(p: SettingsPage) {
    setPage(p);
    setClientId(p.settings.clientId ?? "");
    setTenant(p.settings.mspTenantId ?? "");
    setExpires(p.settings.secretExpiresOn ?? "");
    setSecret("");
  }

  useEffect(() => {
    const controller = new AbortController();
    fetchMicrosoftSettings(controller.signal)
      .then(show)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setLoadError(messageOf(e));
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (test === "passed") {
      toast.show("Test sign-in passed. You can switch Microsoft sign-in on.", "success");
      setParams({}, { replace: true });
    }
  }, [test, setParams, toast]);

  async function onSave(event: FormEvent) {
    event.preventDefault();
    setFormError("");
    setBusy(true);
    try {
      show(
        await run(() =>
          saveMicrosoftSettings({
            clientId: clientId.trim(),
            clientSecret: secret.trim() === "" ? null : secret.trim(),
            secretExpiresOn: expires,
            mspTenantId: tenant.trim(),
          }),
        ),
      );
      toast.show("Microsoft sign-in details saved.", "success");
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) setFormError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  async function onTest() {
    setBusy(true);
    try {
      browserNavigation.go(await startMicrosoftTest());
    } catch (e) {
      toast.showError(messageOf(e));
      setBusy(false);
    }
  }

  async function onSwitch(on: boolean) {
    setBusy(true);
    try {
      show(await run(() => setMicrosoftEnabled(on)));
      toast.show(on ? "Microsoft sign-in is on." : "Microsoft sign-in is off.", "success");
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) toast.showError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  const s = page?.settings;
  return (
    <div className={styles.page}>
      <PageHeader title="Microsoft sign-in" />
      {loadError && (
        <p role="alert" className={styles.error}>
          {loadError}
        </p>
      )}
      {testProblem && (
        <p role="alert" className={styles.error}>
          {testProblem}
        </p>
      )}

      {page && s && (
        <>
          <Card
            title="Status"
            aside={
              s.enabled ? (
                <Button variant="danger" disabled={busy} onClick={() => void onSwitch(false)}>
                  Switch off
                </Button>
              ) : (
                <Button
                  variant="primary"
                  disabled={busy || !s.tested}
                  onClick={() => void onSwitch(true)}
                >
                  Switch on
                </Button>
              )
            }
          >
            <div className={styles.cardBody}>
              <p className={styles.muted}>
                {s.enabled
                  ? 'On: the sign-in page shows "Sign in with Microsoft".'
                  : "Off: everyone signs in with their password. Linked Microsoft accounts are kept."}
              </p>
              <p className={styles.muted}>
                {s.tested
                  ? `Test sign-in passed on ${when(s.testedAt)}.`
                  : "These details have not passed Test sign-in yet. Save them, then run Test sign-in before switching on."}
              </p>
              <div className={styles.actions}>
                <Button disabled={busy || !s.secretSet} onClick={() => void onTest()}>
                  Test sign-in
                </Button>
              </div>
            </div>
          </Card>

          <Card title="App registration">
            <form className={styles.form} onSubmit={onSave} noValidate>
              <TextField
                label="Redirect URI"
                hint="Enter this exact address as a Web redirect URI in the Entra app registration."
                value={page.redirectUri}
                readOnly
              />
              <TextField
                label="Application (client) ID"
                autoComplete="off"
                spellCheck={false}
                value={clientId}
                onChange={(e) => setClientId(e.target.value)}
              />
              <TextField
                label="Client secret"
                type="password"
                autoComplete="new-password"
                hint={
                  s.secretSet
                    ? "A secret is saved. It is never shown again. Enter a new one only to replace it."
                    : "Paste the secret Value (not the Secret ID) from Certificates & secrets."
                }
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
              />
              <TextField
                label="Client secret expires on"
                type="date"
                fieldSize="sm"
                hint="From the Expires column in Entra. Admins are warned 30 days before."
                value={expires}
                onChange={(e) => setExpires(e.target.value)}
              />
              <TextField
                label="MSP tenant ID"
                hint="Your own Directory (tenant) ID. Agents sign in from this tenant only, and only this tenant's MFA counts."
                autoComplete="off"
                spellCheck={false}
                value={tenant}
                onChange={(e) => setTenant(e.target.value)}
              />
              {formError && (
                <p role="alert" className={styles.error}>
                  {formError}
                </p>
              )}
              <Button type="submit" variant="primary" disabled={busy}>
                Save details
              </Button>
            </form>
          </Card>

          <OrganisationTenants
            rows={page.organisations}
            onSaved={(row) =>
              setPage((p) =>
                p
                  ? {
                      ...p,
                      organisations: p.organisations.map((o) => (o.id === row.id ? row : o)),
                    }
                  : p,
              )
            }
            run={run}
          />
        </>
      )}
      {dialog}
    </div>
  );
}

function OrganisationTenants({
  rows,
  onSaved,
  run,
}: {
  rows: OrganisationTenant[];
  onSaved: (row: OrganisationTenant) => void;
  run: <T>(action: () => Promise<T>) => Promise<T>;
}) {
  const toast = useToast();
  const [drafts, setDrafts] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState<number | null>(null);

  async function save(row: OrganisationTenant) {
    const value = (drafts[row.id] ?? row.tenantId ?? "").trim();
    setBusy(row.id);
    try {
      onSaved(await run(() => setOrganisationTenant(row.id, value === "" ? null : value)));
      toast.show(`Saved for ${row.name}.`, "success");
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) toast.showError(messageOf(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card title="Customer organisations">
      <p className={styles.muted}>
        Customers can use &quot;Sign in with Microsoft&quot; only from the one tenant set on their
        organisation. Leave it empty to turn it off for that organisation.
      </p>
      <DataTable
        caption="Allowed Microsoft tenant per organisation"
        compact
        minWidth={560}
        rows={rows}
        rowKey={(o) => String(o.id)}
        emptyMessage="No organisations yet."
        columns={[
          { key: "name", header: "Organisation", render: (o) => o.name, rowHeader: true },
          {
            key: "tenant",
            header: "Tenant ID",
            render: (o) => (
              <TextField
                label={<span className="visually-hidden">Tenant ID for {o.name}</span>}
                autoComplete="off"
                spellCheck={false}
                value={drafts[o.id] ?? o.tenantId ?? ""}
                onChange={(e) => setDrafts((d) => ({ ...d, [o.id]: e.target.value }))}
              />
            ),
          },
          {
            key: "save",
            header: <span className="visually-hidden">Actions</span>,
            actions: true,
            render: (o) => (
              <Button
                aria-label={`Save tenant for ${o.name}`}
                disabled={busy !== null}
                onClick={() => void save(o)}
              >
                Save
              </Button>
            ),
          },
        ]}
      />
    </Card>
  );
}
