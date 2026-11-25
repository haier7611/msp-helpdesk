import { useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import {
  browserNavigation,
  fetchLinkStatus,
  microsoftResultText,
  startMicrosoftLink,
  unlinkMicrosoft,
  type LinkStatus,
} from "../../api/microsoft";
import { Button } from "../../components/forms";
import { useToast } from "../../components/toastContext";
import { Card } from "../../components/ui";
import { StepUpCancelled, useStepUp } from "../../components/useStepUp";
import { errorText } from "../auth/authHelpers";
import styles from "../auth/mfa.module.css";

function when(iso: string | null): string {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "-" : d.toLocaleDateString("en-GB", { dateStyle: "medium" });
}

/**
 * My settings - Security - Microsoft account. Linking sends the browser to
 * Microsoft and back; only an account in the allowed tenant can be linked (the MSP's own tenant for
 * supporters). Linking and unlinking ask for a fresh code first (step-up). The link is the
 * Microsoft account's tenant and object id - never the email address.
 */
export function MicrosoftAccountCard() {
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const { run, dialog } = useStepUp();
  const [status, setStatus] = useState<LinkStatus | null>(null);
  const [reloads, setReloads] = useState(0);
  const [busy, setBusy] = useState(false);
  const result = params.get("microsoft");
  const problem = microsoftResultText(result);

  useEffect(() => {
    const controller = new AbortController();
    fetchLinkStatus(controller.signal)
      .then(setStatus)
      .catch(() => {
        if (!controller.signal.aborted) setStatus(null);
      });
    return () => controller.abort();
  }, [reloads]);

  useEffect(() => {
    if (result === "linked") {
      toast.show("Microsoft account linked.", "success");
      setParams({}, { replace: true });
    }
  }, [result, setParams, toast]);

  async function onLink() {
    setBusy(true);
    try {
      browserNavigation.go(await run(startMicrosoftLink));
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) toast.showError(errorText(e));
      setBusy(false);
    }
  }

  async function onUnlink() {
    setBusy(true);
    try {
      await run(unlinkMicrosoft);
      toast.show("Microsoft account unlinked. Sign in with your password.", "success");
      setReloads((n) => n + 1);
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) toast.showError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Microsoft account">
      <div className={styles.cardBody}>
        {problem && result !== "linked" && (
          <p role="alert" className={styles.error}>
            {problem}
          </p>
        )}
        {status === null ? (
          <p className={styles.muted} role="status">
            Loading...
          </p>
        ) : status.linked ? (
          <>
            <p className={styles.muted}>
              Linked on {when(status.linkedAt)}. You can sign in with Microsoft
              {status.available ? "" : " once it is switched on again"}.
            </p>
            <div className={styles.actions}>
              <Button variant="danger" onClick={onUnlink} disabled={busy}>
                Unlink Microsoft account
              </Button>
            </div>
          </>
        ) : status.available ? (
          <>
            <p className={styles.muted}>
              Link your work Microsoft account to use &quot;Sign in with Microsoft&quot;. Your
              password keeps working.
            </p>
            <div className={styles.actions}>
              <Button onClick={onLink} disabled={busy}>
                Link Microsoft account
              </Button>
            </div>
          </>
        ) : (
          <p className={styles.muted}>Microsoft sign-in is not switched on.</p>
        )}
      </div>
      {dialog}
    </Card>
  );
}
