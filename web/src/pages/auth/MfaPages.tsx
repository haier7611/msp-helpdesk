import { useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { homeFor, signOut, type Me } from "../../api/auth";
import {
  confirmEnrolment,
  startEnrolment,
  verifyCode,
  verifyRecoveryCode,
  type Enrolment,
} from "../../api/security";
import { Button, Checkbox, TextField } from "../../components/forms";
import { StepUpCancelled, useStepUp } from "../../components/useStepUp";
import { useToast } from "../../components/toastContext";
import pageStyles from "../pages.module.css";
import { AuthCard } from "./AuthCard";
import { errorText } from "./authHelpers";
import styles from "./mfa.module.css";
import { ApiError } from "../../api/client";
import { useReportError } from "../../components/useReportError";

/**
 * The sign-in code step: a 6-digit code from the authenticator app, or a
 * recovery code. "Trust this device for 30 days" (offered for both) skips this step on this
 * browser next time; it never skips the confirmation asked for sensitive actions.
 */
export function MfaVerifyPage() {
  const report = useReportError();
  const navigate = useNavigate();
  const toast = useToast();
  const [useRecovery, setUseRecovery] = useState(false);
  const [value, setValue] = useState("");
  const [trust, setTrust] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (!value.trim()) {
      setError(useRecovery ? "Enter one of your recovery codes." : "Enter the 6-digit code.");
      return;
    }
    setBusy(true);
    try {
      const result = useRecovery
        ? await verifyRecoveryCode(value.trim(), trust)
        : await verifyCode(value.trim(), trust);
      if (result.recoveryCodesLeft !== null) {
        toast.show(
          result.recoveryCodesLeft <= 3
            ? `You have ${result.recoveryCodesLeft} recovery codes left. Make new ones in My settings - Security.`
            : `Recovery code used. ${result.recoveryCodesLeft} left.`,
        );
      }
      navigate(homeFor(result.me.role), { replace: true });
    } catch (e) {
      setError(errorText(e));
      report(e);
      setValue("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard title="Two-step sign-in">
      <form className={pageStyles.signInForm} onSubmit={onSubmit} noValidate>
        <p className={styles.muted}>
          {useRecovery
            ? "Enter one of the recovery codes you saved when you set up two-step sign-in. Each code works once."
            : "Open your authenticator app and enter the 6-digit code for this account."}
        </p>
        {useRecovery ? (
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
        <Checkbox
          label="Trust this device for 30 days"
          checked={trust}
          onChange={(e) => setTrust(e.target.checked)}
        />
        <Button type="submit" variant="primary" className={pageStyles.fullWidth} disabled={busy}>
          Continue
        </Button>
      </form>
      <button
        type="button"
        className={styles.linkButton}
        onClick={() => {
          setUseRecovery((r) => !r);
          setValue("");
          setError("");
        }}
      >
        {useRecovery ? "Use my authenticator app instead" : "Use a recovery code instead"}
      </button>
      {error && (
        <p role="alert" className={pageStyles.formError}>
          {error}
        </p>
      )}
      <CancelAndSignOut />
    </AuthCard>
  );
}

/**
 * "Cancel and sign out" on the step pages: ends the half-signed-in session on the server (the
 * real sign-out) and goes back to the sign-in page, so nobody is left with a session that owes the
 * step.
 */
function CancelAndSignOut() {
  const navigate = useNavigate();
  const report = useReportError();
  const [busy, setBusy] = useState(false);
  async function onCancel() {
    setBusy(true);
    try {
      await signOut();
    } catch (e) {
      // 401: the session had already ended, so the person is signed out either way.
      if (!(e instanceof ApiError && e.status === 401)) {
        setBusy(false);
        report(e, "We could not sign you out.");
        return;
      }
    }
    navigate("/sign-in", { replace: true });
  }
  return (
    <button type="button" className={styles.linkButton} onClick={onCancel} disabled={busy}>
      Cancel and sign out
    </button>
  );
}

/** The 10 recovery codes, shown once, with a copy button. */
export function RecoveryCodeList({ codes }: { codes: string[] }) {
  const toast = useToast();
  const text = codes.join("\n");
  return (
    <>
      <ul className={styles.codes} aria-label="Recovery codes">
        {codes.map((c) => (
          <li key={c}>{c}</li>
        ))}
      </ul>
      <div className={styles.actions}>
        <Button
          onClick={() => {
            navigator.clipboard
              ?.writeText(text)
              .then(() => toast.show("Recovery codes copied."))
              .catch(() =>
                toast.showError("Could not copy - select the codes and copy them by hand."),
              );
          }}
        >
          Copy codes
        </Button>
      </div>
    </>
  );
}

/**
 * Set up the authenticator app: forced on a supporter's first sign-in, and used from My settings -
 * Security to move to a new phone (which asks for a code from the current app first). Shows the QR
 * code and the same key for typing in, then the recovery codes once.
 */
export function MfaEnrolPage() {
  const report = useReportError();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const fromSettings = params.get("from") === "settings";
  const { run, dialog } = useStepUp();
  const [enrolment, setEnrolment] = useState<Enrolment | null>(null);
  const [code, setCode] = useState("");
  const [codes, setCodes] = useState<string[] | null>(null);
  const [me, setMe] = useState<Me | null>(null);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function onStart() {
    setError("");
    setBusy(true);
    try {
      setEnrolment(await run(startEnrolment));
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) setError(errorText(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  async function onConfirm(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (!code.trim()) {
      setError("Enter the 6-digit code from the app.");
      return;
    }
    setBusy(true);
    try {
      const result = await run(() => confirmEnrolment(code.trim()));
      setCodes(result.recoveryCodes);
      setMe(result.me);
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) setError(errorText(e));
      report(e);
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  function onDone() {
    if (fromSettings) {
      navigate("/agent/settings/security", { replace: true });
    } else {
      navigate(me ? homeFor(me.role) : "/agent/queue", { replace: true });
    }
  }

  if (codes) {
    return (
      <AuthCard title="Save your recovery codes">
        <p className={styles.muted}>
          If you lose your phone, each of these codes lets you sign in once. Keep them somewhere
          safe, such as a password manager. They will not be shown again.
        </p>
        <RecoveryCodeList codes={codes} />
        <Checkbox
          label="I have saved my recovery codes"
          checked={saved}
          onChange={(e) => setSaved(e.target.checked)}
        />
        <Button
          variant="primary"
          className={pageStyles.fullWidth}
          disabled={!saved}
          onClick={onDone}
        >
          Continue
        </Button>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Set up two-step sign-in">
      {!enrolment ? (
        <>
          <p className={styles.muted}>
            {fromSettings
              ? "Set up a new authenticator app. Your current app keeps working until the new one is confirmed."
              : "Support accounts need a second step at sign-in. You need an authenticator app on your phone, such as Microsoft Authenticator or Google Authenticator."}
          </p>
          <Button
            variant="primary"
            className={pageStyles.fullWidth}
            disabled={busy}
            onClick={onStart}
          >
            Show the QR code
          </Button>
        </>
      ) : (
        <form className={pageStyles.signInForm} onSubmit={onConfirm} noValidate>
          <ol className={styles.steps}>
            <li>In your authenticator app, add an account and scan this QR code.</li>
            <li>Then enter the 6-digit code the app shows.</li>
          </ol>
          <div className={styles.qrBox}>
            <svg
              className={styles.qr}
              viewBox={`0 0 ${enrolment.qrSize} ${enrolment.qrSize}`}
              role="img"
              aria-label="QR code for your authenticator app"
              shapeRendering="crispEdges"
            >
              <rect width={enrolment.qrSize} height={enrolment.qrSize} fill="#ffffff" />
              <path d={enrolment.qrPath} fill="#000000" />
            </svg>
          </div>
          <p className={styles.muted}>
            Cannot scan it? Type this key into the app instead:{" "}
            <span className={styles.manualKey}>{enrolment.manualKey}</span>
          </p>
          <TextField
            label="6-digit code"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={7}
            value={code}
            onChange={(e) => setCode(e.target.value)}
          />
          <Button type="submit" variant="primary" className={pageStyles.fullWidth} disabled={busy}>
            Confirm
          </Button>
        </form>
      )}
      {error && (
        <p role="alert" className={pageStyles.formError}>
          {error}
        </p>
      )}
      {!fromSettings && <CancelAndSignOut />}
      {dialog}
    </AuthCard>
  );
}
