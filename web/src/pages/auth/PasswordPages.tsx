import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { confirmPasswordReset, requestPasswordReset } from "../../api/auth";
import { Button, TextField } from "../../components/forms";
import styles from "../pages.module.css";
import { AuthCard } from "./AuthCard";
import { errorText, passwordProblem, useFragmentToken } from "./authHelpers";
import { useReportError } from "../../components/useReportError";

/** "Forgot password": always the same answer, whether or not the address has an account. */
export function ForgotPasswordPage() {
  const report = useReportError();
  const [email, setEmail] = useState("");
  const [error, setError] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (!email.trim()) {
      setError("Enter your email address.");
      return;
    }
    setBusy(true);
    try {
      await requestPasswordReset(email.trim());
      setSent(true);
    } catch (e) {
      setError(errorText(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard title="Reset your password">
      {sent ? (
        <p role="status">
          If that email address has an account, we have sent a link to reset the password. The link
          works once, for 15 minutes.
        </p>
      ) : (
        <form className={styles.signInForm} onSubmit={onSubmit} noValidate>
          <TextField
            label="Email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <Button type="submit" variant="primary" className={styles.fullWidth} disabled={busy}>
            Send reset link
          </Button>
        </form>
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

/** Choose a new password from a reset link (#token=...). */
export function ResetPasswordPage() {
  const report = useReportError();
  const token = useFragmentToken();
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState("");
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

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
      await confirmPasswordReset(token ?? "", password);
      setDone(true);
    } catch (e) {
      setError(errorText(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <AuthCard title="Reset your password">
        <p role="alert" className={styles.formError}>
          This link is not complete. Open the link from your email again, or ask for a new one.
        </p>
        <p className={styles.subtle}>
          <Link to="/forgot-password">Ask for a new link</Link>
        </p>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Choose a new password">
      {done ? (
        <>
          <p role="status">Your password has been changed. You can now sign in with it.</p>
          <p>
            <Link to="/sign-in">Go to sign in</Link>
          </p>
        </>
      ) : (
        <form className={styles.signInForm} onSubmit={onSubmit} noValidate>
          <TextField
            label="New password"
            type="password"
            autoComplete="new-password"
            hint="At least 12 characters. Common or breached passwords are refused."
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <TextField
            label="Repeat new password"
            type="password"
            autoComplete="new-password"
            required
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
          <Button type="submit" variant="primary" className={styles.fullWidth} disabled={busy}>
            Change password
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
