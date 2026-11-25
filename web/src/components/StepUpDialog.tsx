import { useEffect, useState, type FormEvent } from "react";
import { fetchMe } from "../api/auth";
import { ApiError } from "../api/client";
import { stepUp } from "../api/security";
import { Button, TextField } from "./forms";
import { Modal } from "./Modal";
import styles from "./StepUp.module.css";
import { useReportError } from "./useReportError";

type Mode = "code" | "recovery" | "password";

/**
 * Asks for a fresh authenticator code (or a recovery code) and sends it to the step-up endpoint.
 * An engineer whose two-step sign-in is paused is asked for their password
 * instead; the server accepts a password only from them.
 */
export function StepUpDialog({
  open,
  onConfirmed,
  onCancel,
}: {
  open: boolean;
  onConfirmed: () => void;
  onCancel: () => void;
}) {
  const report = useReportError();
  const [mode, setMode] = useState<Mode>("code");
  const [exempt, setExempt] = useState(false);
  const [value, setValue] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    fetchMe(controller.signal)
      .then((me) => {
        const paused = Boolean(me.mfaExemptUntil);
        setExempt(paused);
        if (paused) setMode("password");
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, [open]);

  function reset() {
    setValue("");
    setError("");
    setMode(exempt ? "password" : "code");
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (!value.trim()) {
      setError(
        mode === "password"
          ? "Enter your password."
          : mode === "recovery"
            ? "Enter one of your recovery codes."
            : "Enter the 6-digit code.",
      );
      return;
    }
    setBusy(true);
    try {
      await stepUp(
        mode === "password"
          ? { password: value }
          : mode === "recovery"
            ? { recoveryCode: value.trim() }
            : { code: value.trim() },
      );
      reset();
      onConfirmed();
    } catch (e) {
      if (e instanceof ApiError && e.code === "password_step_up_not_allowed") {
        // The pause ended while the dialog was open: offer the code step instead.
        setExempt(false);
        setMode("code");
      }
      setError(
        e instanceof ApiError && e.status < 500
          ? e.message
          : "Something went wrong on our side. Please try again in a minute.",
      );
      setValue("");
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      title="Confirm it is you"
      onClose={() => {
        reset();
        onCancel();
      }}
    >
      <form className={styles.form} onSubmit={onSubmit} noValidate>
        <p className={styles.lead}>
          {mode === "password"
            ? "Two-step sign-in is paused for your account, so this action needs your password again."
            : mode === "recovery"
              ? "Enter one of your recovery codes. It will be used up."
              : "This action needs a fresh code from your authenticator app."}
        </p>
        {mode === "password" ? (
          <TextField
            label="Password"
            type="password"
            autoComplete="current-password"
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
        ) : mode === "recovery" ? (
          <TextField
            label="Recovery code"
            autoComplete="off"
            spellCheck={false}
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
        ) : (
          <TextField
            label="6-digit code"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={7}
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
        )}
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <div className={styles.actions}>
          <Button type="submit" variant="primary" disabled={busy}>
            Confirm
          </Button>
          {mode !== "password" && (
            <Button
              onClick={() => {
                setMode((m) => (m === "recovery" ? "code" : "recovery"));
                setValue("");
                setError("");
              }}
            >
              {mode === "recovery" ? "Use my authenticator app" : "Use a recovery code"}
            </Button>
          )}
        </div>
      </form>
    </Modal>
  );
}
