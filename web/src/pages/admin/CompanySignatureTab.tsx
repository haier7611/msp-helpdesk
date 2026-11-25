import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import {
  fetchTemplate,
  MAX_LOGO_BYTES,
  previewTemplate,
  removeLogo,
  saveTemplate,
  uploadLogo,
  type EmailMode,
  type SignatureTemplate,
  type TemplateInput,
} from "../../api/admin";
import type { RenderedSignature } from "../../api/settings";
import { Button, RadioGroup, TextField } from "../../components/forms";
import { SignaturePreview } from "../../components/SignaturePreview";
import { useToast } from "../../components/toastContext";
import { Card } from "../../components/ui";
import { useStepUp } from "../../components/useStepUp";
import styles from "./admin.module.css";
import { cancelled, messageOf } from "./adminShared";
import { useReportError } from "../../components/useReportError";

function inputOf(t: SignatureTemplate): TemplateInput {
  return {
    companyName: t.companyName,
    address: t.address,
    brandColour: t.brandColour,
    emailMode: t.emailMode,
    supportEmail: t.supportEmail,
    logoAlt: t.logoAlt,
    confidentialityLine: t.confidentialityLine,
  };
}

/** The company logo with upload and remove (shared by the signature tab and Branding). */
export function LogoControl({
  logo,
  onChanged,
}: {
  logo: { logoUploaded: boolean; logoDataUri: string; logoWidth: number; logoHeight: number };
  onChanged: () => void;
}) {
  const toast = useToast();
  const report = useReportError();
  const { run, dialog } = useStepUp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const input = useRef<HTMLInputElement>(null);

  async function onFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    setError("");
    if (file.size > MAX_LOGO_BYTES) {
      setError("The logo file can be at most 2 MB.");
      return;
    }
    setBusy(true);
    try {
      await run(() => uploadLogo(file));
      onChanged();
      toast.show("Logo uploaded. It is embedded in every signature from now on.", "success");
    } catch (e) {
      if (!cancelled(e)) setError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  async function onRemove() {
    setBusy(true);
    try {
      await run(() => removeLogo());
      onChanged();
      toast.show("Logo removed. The placeholder is used until you upload one.", "success");
    } catch (e) {
      if (!cancelled(e)) setError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.body}>
      <div className={styles.logoBox}>
        <img
          src={logo.logoDataUri}
          alt={logo.logoUploaded ? "Current company logo" : "Placeholder logo"}
          width={logo.logoWidth}
          height={logo.logoHeight}
        />
      </div>
      <p className={styles.muted}>
        {logo.logoUploaded
          ? "Your logo, as embedded in every email."
          : "No logo uploaded yet: emails show this placeholder."}{" "}
        PNG, JPEG or WebP up to 2 MB. It is resized to about 150 pixels wide and re-encoded to stay
        under 30 KB per email.
      </p>
      <input
        ref={input}
        type="file"
        accept="image/png,image/jpeg,image/webp"
        className="visually-hidden"
        tabIndex={-1}
        aria-label="Logo file"
        onChange={(e) => void onFile(e)}
      />
      <div className={styles.actions}>
        <Button disabled={busy} onClick={() => input.current?.click()}>
          {logo.logoUploaded ? "Replace logo" : "Upload logo"}
        </Button>
        {logo.logoUploaded && (
          <Button variant="danger" disabled={busy} onClick={() => void onRemove()}>
            Remove logo
          </Button>
        )}
      </div>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      {dialog}
    </div>
  );
}

/**
 * Admin - Email - Company signature template: company name,
 * address, brand colour, logo, alt text, confidentiality line and what the E: line shows. The
 * preview is rendered by the server - the same renderer outbound mail uses - with a sample
 * supporter, so it shows exactly what clients receive. Saving asks for step-up.
 */
export function CompanySignatureTab() {
  const toast = useToast();
  const report = useReportError();
  const { run, dialog } = useStepUp();
  const [template, setTemplate] = useState<SignatureTemplate | null>(null);
  const [form, setForm] = useState<TemplateInput | null>(null);
  const [preview, setPreview] = useState<RenderedSignature | null>(null);
  const [previewError, setPreviewError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [formError, setFormError] = useState("");
  const [busy, setBusy] = useState(false);
  const [logoVersion, setLogoVersion] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    fetchTemplate(controller.signal)
      .then((t) => {
        setTemplate(t);
        setForm((f) => f ?? inputOf(t));
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setLoadError(messageOf(e));
      });
    return () => controller.abort();
  }, [logoVersion]);

  // Live preview of the unsaved values, a moment after typing stops.
  useEffect(() => {
    if (!form) return;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      previewTemplate(form, controller.signal)
        .then((p) => {
          setPreview(p);
          setPreviewError("");
        })
        .catch((e: unknown) => {
          if (!controller.signal.aborted) setPreviewError(messageOf(e));
        });
    }, 300);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [form, logoVersion]);

  async function onSave(event: FormEvent) {
    event.preventDefault();
    if (!form) return;
    setFormError("");
    setBusy(true);
    try {
      const saved = await run(() => saveTemplate(form));
      setTemplate(saved);
      setForm(inputOf(saved));
      toast.show("Signature template saved. The next emails use it.", "success");
    } catch (e) {
      if (!cancelled(e)) setFormError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  if (loadError) {
    return (
      <p role="alert" className={styles.error}>
        {loadError}
      </p>
    );
  }
  if (!form || !template) {
    return <p className={styles.muted}>Loading the template...</p>;
  }
  const set = (patch: Partial<TemplateInput>) => setForm({ ...form, ...patch });

  return (
    <div className={styles.split}>
      <div className={styles.splitMain}>
        <Card title="Company template">
          <form className={styles.form} onSubmit={onSave} noValidate>
            <TextField
              label="Company name"
              maxLength={100}
              value={form.companyName}
              onChange={(e) => set({ companyName: e.target.value })}
            />
            <TextField
              label="Address (A: line)"
              maxLength={200}
              hint="Set once for everyone. Leave empty to leave the line out."
              value={form.address}
              onChange={(e) => set({ address: e.target.value })}
            />
            <div className={styles.row}>
              <TextField
                label="Brand colour"
                hint="#RRGGBB, dark enough to read on white. Also shown in Admin - Branding (same setting)."
                maxLength={7}
                value={form.brandColour}
                onChange={(e) => set({ brandColour: e.target.value })}
              />
              <input
                type="color"
                className={styles.colour}
                aria-label="Pick the brand colour"
                value={/^#[0-9a-fA-F]{6}$/.test(form.brandColour) ? form.brandColour : "#2B5797"}
                onChange={(e) => set({ brandColour: e.target.value.toUpperCase() })}
              />
            </div>
            <RadioGroup
              legend="The E: line shows"
              name="emailMode"
              value={form.emailMode}
              options={[
                { value: "SUPPORTER", label: "Each agent's own email address (default)" },
                {
                  value: "SUPPORT_MAILBOX",
                  label: "The support mailbox, so client replies stay in the ticket",
                },
              ]}
              hint="Clients who email an agent directly bypass the ticket and its SLA."
              onChange={(v) => set({ emailMode: v as EmailMode })}
            />
            <TextField
              label="Support mailbox address"
              type="email"
              fieldSize="md"
              hint="Shown on the E: line when the support mailbox is chosen."
              value={form.supportEmail}
              onChange={(e) => set({ supportEmail: e.target.value })}
            />
            <TextField
              label="Logo alt text"
              maxLength={100}
              hint="Read out by screen readers and shown when images are off."
              value={form.logoAlt}
              onChange={(e) => set({ logoAlt: e.target.value })}
            />
            <TextField
              label="Confidentiality line (optional)"
              maxLength={300}
              hint="Small grey text under the logo."
              value={form.confidentialityLine}
              onChange={(e) => set({ confidentialityLine: e.target.value })}
            />
            {formError && (
              <p role="alert" className={styles.error}>
                {formError}
              </p>
            )}
            <Button type="submit" variant="primary" disabled={busy}>
              Save template
            </Button>
          </form>
        </Card>
        <Card title="Company logo">
          <LogoControl logo={template} onChanged={() => setLogoVersion((v) => v + 1)} />
        </Card>
      </div>
      <div className={styles.splitSide}>
        {previewError && (
          <p role="alert" className={styles.error}>
            Preview: {previewError}
          </p>
        )}
        <SignaturePreview rendered={preview} />
        <p className={styles.muted}>
          Sample agent: the real email uses each agent&apos;s own name, title and phone from My
          settings.
        </p>
      </div>
      {dialog}
    </div>
  );
}
