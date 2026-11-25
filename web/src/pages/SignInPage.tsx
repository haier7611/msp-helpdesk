import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { homeFor, requestMagicLink, signIn } from "../api/auth";
import {
  browserNavigation,
  fetchMicrosoftEnabled,
  microsoftResultText,
  startMicrosoftSignIn,
} from "../api/microsoft";
import { mfaPathFor } from "../api/security";
import { Button, TextField } from "../components/forms";
import { AuthCard } from "./auth/AuthCard";
import { errorText } from "./auth/authHelpers";
import styles from "./pages.module.css";
import { useReportError } from "../components/useReportError";

/**
 * Sign-in: email and password for everyone, plus "Email me a sign-in
 * link", shown to everyone because the page cannot know the role - the server only sends a link to
 * customers and gives every caller the same answer. Supporters then go to the TOTP step or its
 * first set-up; "Sign in with Microsoft" only when an admin switches it on.
 */
export function SignInPage() {
  const report = useReportError();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  // A failed Microsoft sign-in comes back here with a short result word.
  const [error, setError] = useState(() => microsoftResultText(params.get("microsoft")) ?? "");
  const [status, setStatus] = useState("");
  const [busy, setBusy] = useState(false);
  const [microsoft, setMicrosoft] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    void fetchMicrosoftEnabled(controller.signal).then((on) => {
      if (!controller.signal.aborted) setMicrosoft(on);
    });
    return () => controller.abort();
  }, []);

  async function onMicrosoft() {
    setError("");
    setStatus("");
    setBusy(true);
    try {
      browserNavigation.go(await startMicrosoftSignIn());
    } catch (e) {
      setError(errorText(e));
      report(e);
      setBusy(false);
    }
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    setStatus("");
    setBusy(true);
    try {
      const me = await signIn(email.trim(), password);
      // Supporters owe the TOTP step (or its first set-up) unless this is a trusted device.
      navigate(mfaPathFor(me.authLevel) ?? homeFor(me.role), { replace: true });
    } catch (e) {
      setError(errorText(e));
      report(e);
      setPassword("");
    } finally {
      setBusy(false);
    }
  }

  async function onMagicLink() {
    setError("");
    setStatus("");
    if (!email.trim()) {
      setError("Enter your email address first.");
      return;
    }
    setBusy(true);
    try {
      await requestMagicLink(email.trim());
      setStatus(
        "If that email address can use a sign-in link, we have sent one. Check your inbox - the link works once, for 15 minutes.",
      );
    } catch (e) {
      setError(errorText(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard title="Sign in">
      <form className={styles.signInForm} onSubmit={onSubmit} noValidate>
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
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Button type="submit" variant="primary" className={styles.fullWidth} disabled={busy}>
          Sign in
        </Button>
      </form>
      {microsoft && (
        <Button className={styles.fullWidth} onClick={onMicrosoft} disabled={busy}>
          Sign in with Microsoft
        </Button>
      )}
      <Button className={styles.fullWidth} onClick={onMagicLink} disabled={busy}>
        Email me a sign-in link
      </Button>
      <p className={styles.subtle}>
        <Link to="/forgot-password">Forgot your password?</Link>
      </p>
      {error && (
        <p role="alert" className={styles.formError}>
          {error}
        </p>
      )}
      <p role="status" className={styles.subtle}>
        {status}
      </p>
    </AuthCard>
  );
}
