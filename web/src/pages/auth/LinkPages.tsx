import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import {
  acceptInvitation,
  completeSetup,
  consumeMagicLink,
  fetchSetupAvailable,
  homeFor,
  inspectInvitation,
  type PendingInvitation,
} from "../../api/auth";
import { Button, TextField } from "../../components/forms";
import styles from "../pages.module.css";
import { AuthCard } from "./AuthCard";
import { errorText, passwordProblem, useFragmentToken } from "./authHelpers";
import { useReportError } from "../../components/useReportError";

function IncompleteLink() {
  return (
    <p role="alert" className={styles.formError}>
      This link is not complete. Open the link from your email again.
    </p>
  );
}

/**
 * Customer magic link (#token=...). The token is spent only when the person presses the button,
 * so a mail scanner that opens the link does not use it up.
 */
export function MagicLinkPage() {
  const report = useReportError();
  const token = useFragmentToken();
  const navigate = useNavigate();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function onContinue() {
    setError("");
    setBusy(true);
    try {
      const me = await consumeMagicLink(token ?? "");
      navigate(homeFor(me.role), { replace: true });
    } catch (e) {
      setError(errorText(e));
      report(e);
      setBusy(false);
    }
  }

  return (
    <AuthCard title="Sign in with your link">
      {token ? (
        <Button variant="primary" className={styles.fullWidth} onClick={onContinue} disabled={busy}>
          Continue to sign in
        </Button>
      ) : (
        <IncompleteLink />
      )}
      {error && (
        <p role="alert" className={styles.formError}>
          {error}
        </p>
      )}
      <p className={styles.subtle}>
        <Link to="/sign-in">Back to sign in</Link>
      </p>
    </AuthCard>
  );
}

type InviteState =
  | { kind: "loading" }
  | { kind: "invalid"; message: string }
  | { kind: "ready"; invitation: PendingInvitation }
  | { kind: "done" };

/**
 * Accept an invitation (#token=...): set a password. No code - opening the single-use
 * link and choosing a password is enough.
 */
export function AcceptInvitationPage() {
  const report = useReportError();
  const token = useFragmentToken();
  const [state, setState] = useState<InviteState>(
    token ? { kind: "loading" } : { kind: "invalid", message: "" },
  );
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!token) {
      return;
    }
    let live = true;
    inspectInvitation(token)
      .then((invitation) => live && setState({ kind: "ready", invitation }))
      .catch((e: unknown) => live && setState({ kind: "invalid", message: errorText(e) }));
    return () => {
      live = false;
    };
  }, [token]);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    const problem = passwordProblem(password, confirm);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    try {
      await acceptInvitation(token ?? "", password);
      setState({ kind: "done" });
    } catch (e) {
      setError(errorText(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard title="Set up your account">
      {state.kind === "loading" && <p role="status">Checking your invitation...</p>}
      {state.kind === "invalid" &&
        (state.message ? (
          <p role="alert" className={styles.formError}>
            {state.message}
          </p>
        ) : (
          <IncompleteLink />
        ))}
      {state.kind === "done" && (
        <>
          <p role="status">Your account is ready. You can now sign in.</p>
          <p>
            <Link to="/sign-in">Go to sign in</Link>
          </p>
        </>
      )}
      {state.kind === "ready" && (
        <form className={styles.signInForm} onSubmit={onSubmit} noValidate>
          <p>
            Hello {state.invitation.name}. Choose a password for{" "}
            <strong>{state.invitation.email}</strong>.
          </p>
          <TextField
            label="Password"
            type="password"
            autoComplete="new-password"
            hint="At least 12 characters. Common or breached passwords are refused."
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <TextField
            label="Repeat password"
            type="password"
            autoComplete="new-password"
            required
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
          <Button type="submit" variant="primary" className={styles.fullWidth} disabled={busy}>
            Create account
          </Button>
        </form>
      )}
      {error && (
        <p role="alert" className={styles.formError}>
          {error}
        </p>
      )}
    </AuthCard>
  );
}

/** First-admin setup with the one-time token from the server environment (C8). */
export function SetupPage() {
  const report = useReportError();
  const [available, setAvailable] = useState<boolean | null>(null);
  const [token, setToken] = useState("");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState("");
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetchSetupAvailable(controller.signal)
      .then(setAvailable)
      .catch(() => !controller.signal.aborted && setAvailable(false));
    return () => controller.abort();
  }, []);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (!token.trim() || !name.trim() || !email.trim()) {
      setError("Fill in every field.");
      return;
    }
    const problem = passwordProblem(password, confirm);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    try {
      await completeSetup(token.trim(), email.trim(), name.trim(), password);
      setDone(true);
    } catch (e) {
      setError(errorText(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard title="First-time setup">
      {available === null && <p role="status">Checking...</p>}
      {available === false && !done && (
        <p role="status">Setup is not available on this system. Sign in instead.</p>
      )}
      {done && (
        <>
          <p role="status">The administrator account is ready. You can now sign in.</p>
          <p>
            <Link to="/sign-in">Go to sign in</Link>
          </p>
        </>
      )}
      {available && !done && (
        <form className={styles.signInForm} onSubmit={onSubmit} noValidate>
          <TextField
            label="Setup token"
            type="password"
            autoComplete="off"
            hint="From the server's TS_SETUP_TOKEN setting."
            required
            value={token}
            onChange={(e) => setToken(e.target.value)}
          />
          <TextField
            label="Your name"
            autoComplete="name"
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <TextField
            label="Email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <TextField
            label="Password"
            type="password"
            autoComplete="new-password"
            hint="At least 12 characters. Common or breached passwords are refused."
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <TextField
            label="Repeat password"
            type="password"
            autoComplete="new-password"
            required
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
          <Button type="submit" variant="primary" className={styles.fullWidth} disabled={busy}>
            Create administrator
          </Button>
        </form>
      )}
      {error && (
        <p role="alert" className={styles.formError}>
          {error}
        </p>
      )}
    </AuthCard>
  );
}
