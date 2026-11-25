import { useEffect, useState, type FormEvent } from "react";
import { fetchAccount, renameAccount, type Account } from "../../api/portal";
import { changePassword } from "../../api/security";
import { Button, TextField } from "../../components/forms";
import formStyles from "../../components/forms.module.css";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import { errorText, passwordProblem } from "../auth/authHelpers";
import portal from "./portal.module.css";
import { useReportError } from "../../components/useReportError";

function NameCard({ account, onSaved }: { account: Account; onSaved: (a: Account) => void }) {
  const toast = useToast();
  const report = useReportError();
  const [name, setName] = useState(account.name);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (!name.trim()) {
      setError("Enter your name.");
      return;
    }
    setBusy(true);
    try {
      onSaved(await renameAccount(name.trim()));
      toast.show("Your name was saved.", "success");
    } catch (e) {
      setError(errorText(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Your details">
      <dl className={portal.readOnly}>
        <dt>Email</dt>
        <dd>{account.email}</dd>
        <dt>Organisation</dt>
        <dd>{account.organisationName}</dd>
      </dl>
      <form className={portal.accountForm} onSubmit={onSubmit} noValidate>
        <TextField
          label="Name"
          autoComplete="name"
          maxLength={200}
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={error || undefined}
        />
        <Button type="submit" variant="primary" disabled={busy}>
          Save name
        </Button>
      </form>
    </Card>
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
      <form className={portal.accountForm} onSubmit={onSubmit} noValidate>
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
          <p role="alert" className={formStyles.error}>
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

/**
 * My account (customer portal): name, password, and how to sign in. The Microsoft
 * account section shows only once an admin has switched Microsoft sign-in on.
 */
export function AccountPage() {
  const [state, setState] = useState<
    { kind: "loading" } | { kind: "ready"; account: Account } | { kind: "error" }
  >({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    fetchAccount(controller.signal)
      .then((account) => setState({ kind: "ready", account }))
      .catch(() => {
        if (!controller.signal.aborted) setState({ kind: "error" });
      });
    return () => controller.abort();
  }, []);

  return (
    <div className={portal.accountWrap}>
      <PageHeader title="My account" />
      {state.kind === "loading" && <p role="status">Loading your account...</p>}
      {state.kind === "error" && (
        <p role="alert">We could not load your account. Reload the page to try again.</p>
      )}
      {state.kind === "ready" && (
        <>
          <NameCard
            account={state.account}
            onSaved={(account) => setState({ kind: "ready", account })}
          />
          <PasswordCard />
          <Card title="Signing in">
            <p>
              No password to hand? On the sign-in page choose &quot;Email me a sign-in link&quot;
              and we will send a link to {state.account.email} that works for 15 minutes.
            </p>
          </Card>
          {state.account.microsoftSignInAvailable && (
            <Card title="Microsoft account">
              <p>
                {state.account.microsoftLinked
                  ? "Your Microsoft account is linked."
                  : "You can sign in with your work Microsoft account once it is linked."}
              </p>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
