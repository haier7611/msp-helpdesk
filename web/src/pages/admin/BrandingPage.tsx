import { useEffect, useState, type FormEvent } from "react";
import { fetchBranding, saveBranding, type Branding } from "../../api/admin";
import { Button, TextField } from "../../components/forms";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import { useStepUp } from "../../components/useStepUp";
import styles from "./admin.module.css";
import { cancelled, messageOf } from "./adminShared";
import { LogoControl } from "./CompanySignatureTab";
import { useReportError } from "../../components/useReportError";

/**
 * Admin - Branding: the support name used in every email's From
 * ("Alex Tran (Example MSP Support)") and in system emails, the brand colour of signatures, and
 * the company logo (shared with the signature template). Saving asks for step-up. The app's own
 * colours stay fixed so they keep their tested contrast.
 */
export function BrandingPage() {
  const toast = useToast();
  const report = useReportError();
  const { run, dialog } = useStepUp();
  const [branding, setBranding] = useState<Branding | null>(null);
  const [name, setName] = useState("");
  const [colour, setColour] = useState("");
  const [loadError, setLoadError] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    fetchBranding(controller.signal)
      .then((b) => {
        setBranding(b);
        setName(b.supportNameCustom ? b.supportName : "");
        setColour(b.brandColour);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setLoadError(messageOf(e));
      });
    return () => controller.abort();
  }, [version]);

  async function onSave(event: FormEvent) {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      const b = await run(() => saveBranding(name.trim(), colour.trim()));
      setBranding(b);
      setName(b.supportNameCustom ? b.supportName : "");
      setColour(b.brandColour);
      toast.show("Branding saved. The next emails use it.", "success");
    } catch (e) {
      if (!cancelled(e)) setError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.page}>
      <PageHeader title="Branding" />
      <p className={styles.muted}>
        The brand colour and the company logo set here are the same ones shown in Admin - Email and
        signature, used in agent email signatures. Changing them in either place changes both.
      </p>
      {loadError && (
        <p role="alert" className={styles.error}>
          {loadError}
        </p>
      )}
      {branding && (
        <>
          <Card title="Support name and colour">
            <form className={styles.form} onSubmit={onSave} noValidate>
              <TextField
                label="Support name"
                maxLength={100}
                placeholder={branding.defaultSupportName}
                hint={`Shown as the sender name, e.g. "Alex Tran (${name.trim() || branding.defaultSupportName})", and in system emails. Leave empty to use the server default "${branding.defaultSupportName}".`}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
              <div className={styles.row}>
                <TextField
                  label="Brand colour"
                  hint="#RRGGBB. Used for names and labels in email signatures; it must be dark enough to read on white."
                  maxLength={7}
                  value={colour}
                  onChange={(e) => setColour(e.target.value)}
                />
                <input
                  type="color"
                  className={styles.colour}
                  aria-label="Pick the brand colour"
                  value={/^#[0-9a-fA-F]{6}$/.test(colour) ? colour : "#2B5797"}
                  onChange={(e) => setColour(e.target.value.toUpperCase())}
                />
              </div>
              {error && (
                <p role="alert" className={styles.error}>
                  {error}
                </p>
              )}
              <Button type="submit" variant="primary" disabled={busy}>
                Save branding
              </Button>
            </form>
          </Card>
          <Card title="Company logo">
            <LogoControl logo={branding} onChanged={() => setVersion((v) => v + 1)} />
          </Card>
        </>
      )}
      {dialog}
    </div>
  );
}
