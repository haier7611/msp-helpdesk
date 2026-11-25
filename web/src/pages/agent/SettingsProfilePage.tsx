import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { Link } from "react-router";
import {
  deletePhoto,
  fetchProfile,
  fetchSignature,
  MAX_PHOTO_BYTES,
  photoUrl,
  previewSignature,
  saveProfile,
  uploadPhoto,
  type Profile,
  type ProfileFields,
  type RenderedSignature,
} from "../../api/settings";
import { initials } from "../../components/initials";
import { Button, TextField } from "../../components/forms";
import { SignaturePreview } from "../../components/SignaturePreview";
import { useToast } from "../../components/toastContext";
import { Avatar } from "../../components/ui";
import { errorText } from "../auth/authHelpers";
import styles from "../pages.module.css";
import { useReportError } from "../../components/useReportError";

/** How long typing must pause before the preview is re-rendered on the server. */
const PREVIEW_DELAY_MS = 300;

function fieldsOf(p: Profile): ProfileFields {
  return {
    displayName: p.displayName,
    jobTitle: p.jobTitle,
    phone: p.phone,
    mobile: p.mobile,
    freeLine: p.freeLine,
  };
}

/**
 * My settings - Profile and signature. The preview is rendered by
 * the server from the unsaved fields as you type, with the same renderer that signs client emails.
 */
export function SettingsProfilePage() {
  const toast = useToast();
  const report = useReportError();
  const [profile, setProfile] = useState<Profile | null>(null);
  const [fields, setFields] = useState<ProfileFields | null>(null);
  const [rendered, setRendered] = useState<RenderedSignature | null>(null);
  const [previewError, setPreviewError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [saving, setSaving] = useState(false);
  const [photoVersion, setPhotoVersion] = useState(0);
  const [photoError, setPhotoError] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);
  const firstFields = useRef(true);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([fetchProfile(controller.signal), fetchSignature(controller.signal)])
      .then(([p, s]) => {
        setProfile(p);
        setFields(fieldsOf(p));
        setRendered(s);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setLoadError(errorText(e));
      });
    return () => controller.abort();
  }, []);

  // Live preview: re-render on the server after a short pause in typing.
  useEffect(() => {
    if (!fields) return;
    if (firstFields.current) {
      firstFields.current = false;
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setBusy(true);
      previewSignature(fields, controller.signal)
        .then((r) => {
          setRendered(r);
          setPreviewError("");
        })
        .catch((e: unknown) => {
          if (!controller.signal.aborted) setPreviewError(errorText(e));
        })
        .finally(() => {
          if (!controller.signal.aborted) setBusy(false);
        });
    }, PREVIEW_DELAY_MS);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [fields]);

  const field = (key: keyof ProfileFields) => (event: ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    setFields((current) => (current ? { ...current, [key]: value } : current));
  };

  async function onSave(event: FormEvent) {
    event.preventDefault();
    if (!fields) return;
    setSaving(true);
    try {
      const saved = await saveProfile(fields);
      setProfile(saved);
      toast.show("Profile saved.", "success");
    } catch (e) {
      toast.showError(errorText(e));
    } finally {
      setSaving(false);
    }
  }

  async function onPhoto(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    setPhotoError("");
    if (file.size > MAX_PHOTO_BYTES) {
      setPhotoError("The picture can be at most 5 MB.");
      return;
    }
    try {
      await uploadPhoto(file);
      setProfile((p) => (p ? { ...p, hasPhoto: true } : p));
      setPhotoVersion((v) => v + 1);
      toast.show("Photo updated.", "success");
    } catch (e) {
      setPhotoError(errorText(e));
      report(e);
    }
  }

  async function onRemovePhoto() {
    try {
      await deletePhoto();
      setProfile((p) => (p ? { ...p, hasPhoto: false } : p));
      toast.showSuccess("Photo removed.");
    } catch (e) {
      setPhotoError(errorText(e));
      report(e);
    }
  }

  if (loadError) {
    return (
      <div className={styles.profileCard}>
        <h1>Profile and signature</h1>
        <p role="alert">{loadError}</p>
      </div>
    );
  }

  return (
    <div className={styles.settingsGrid}>
      <form className={styles.profileCard} onSubmit={onSave} noValidate>
        <h1>Profile and signature</h1>
        {!profile || !fields ? (
          <p>Loading...</p>
        ) : (
          <>
            <div className={styles.photoRow}>
              {profile.hasPhoto ? (
                <img
                  className={styles.photo}
                  src={photoUrl(photoVersion)}
                  alt="Your profile photo"
                  width={64}
                  height={64}
                />
              ) : (
                <Avatar initials={initials(fields.displayName || profile.email)} size="xl" />
              )}
              <div className={styles.photoActions}>
                <label className={styles.fileButton}>
                  Change photo
                  <input
                    ref={fileInput}
                    type="file"
                    accept="image/png,image/jpeg,image/webp"
                    className="visually-hidden"
                    onChange={onPhoto}
                  />
                </label>
                {profile.hasPhoto && (
                  <Button type="button" onClick={onRemovePhoto}>
                    Remove photo
                  </Button>
                )}
              </div>
            </div>
            <p className={styles.formNote}>
              PNG, JPEG or WebP, up to 5 MB. Shown in the app only, never in emails.
            </p>
            {photoError && (
              <p role="alert" className={styles.errorText}>
                {photoError}
              </p>
            )}
            <TextField
              label="Display name"
              hint="Shown as the sender name on client emails"
              value={fields.displayName}
              onChange={field("displayName")}
              autoComplete="name"
              maxLength={80}
              required
            />
            <TextField
              label="Job title"
              value={fields.jobTitle}
              onChange={field("jobTitle")}
              autoComplete="organization-title"
              maxLength={80}
            />
            <div className={styles.row}>
              <TextField
                label="Phone"
                type="tel"
                value={fields.phone}
                onChange={field("phone")}
                autoComplete="tel"
                maxLength={30}
              />
              <TextField
                label="Mobile (optional)"
                type="tel"
                placeholder="Leave empty to hide"
                value={fields.mobile}
                onChange={field("mobile")}
                maxLength={30}
              />
            </div>
            <TextField
              label="Extra line (optional)"
              hint='For example "Microsoft 365 specialist"'
              value={fields.freeLine}
              onChange={field("freeLine")}
              maxLength={100}
            />
            <p className={styles.formNote}>
              Address, logo and colours come from the company template. The E: line shows your own
              email address ({profile.email}) unless the company template uses the support mailbox.
              Password and two-step sign-in are under{" "}
              <Link to="/agent/settings/security">Security</Link>.
            </p>
            {previewError && (
              <p role="alert" className={styles.errorText}>
                {previewError}
              </p>
            )}
            <Button type="submit" variant="primary" className={styles.alignStart} disabled={saving}>
              {saving ? "Saving..." : "Save"}
            </Button>
          </>
        )}
      </form>
      <div className={styles.previewColumn}>
        <SignaturePreview rendered={rendered} busy={busy} />
      </div>
    </div>
  );
}
