import { useEffect, useId, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { ApiError } from "../../api/client";
import {
  fetchRequestTypes,
  MAX_FILE_BYTES,
  newIdempotencyKey,
  raiseTicket,
  textToHtml,
  uploadFile,
  type Impact,
  type RequestType,
  type Upload,
  type Urgency,
} from "../../api/portal";
import { Button, RadioGroup, TextField, Textarea } from "../../components/forms";
import formStyles from "../../components/forms.module.css";
import styles from "../pages.module.css";
import portal from "./portal.module.css";
import { useReportError } from "../../components/useReportError";

// Guidance for the agent only; the request type sets the priority.
const IMPACT: { value: Impact; label: string }[] = [
  { value: "JUST_ME", label: "Just me" },
  { value: "A_FEW_PEOPLE", label: "A few people" },
  { value: "WHOLE_OFFICE", label: "The whole office" },
];

const URGENCY: { value: Urgency; label: string }[] = [
  { value: "NUISANCE", label: "Yes, it is a nuisance" },
  { value: "PARTLY", label: "Partly" },
  { value: "WORK_STOPPED", label: "No, work has stopped" },
];

const MAX_SUBJECT = 250;

type FileItem =
  | { key: string; name: string; state: "uploading" }
  | { key: string; name: string; state: "ready"; upload: Upload }
  | { key: string; name: string; state: "failed"; message: string };

type Errors = { requestType?: string; subject?: string; description?: string; form?: string };

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Refused before upload; the server checks again (type from the bytes, size cap). */
function localProblem(file: File): string | null {
  if (file.size > MAX_FILE_BYTES) return "Too large - files can be up to 20 MB each.";
  if (/\.svgz?$/i.test(file.name)) return "SVG files cannot be uploaded.";
  return null;
}

/**
 * Customer portal New ticket form (raise only, follow by email):
 * after sending, the page shows only the reference - never status or SLA.
 */
export function NewTicketPage() {
  const navigate = useNavigate();
  const report = useReportError();
  const [types, setTypes] = useState<RequestType[] | null>(null);
  const [typesFailed, setTypesFailed] = useState(false);
  const [requestTypeId, setRequestTypeId] = useState("");
  const [subject, setSubject] = useState("");
  const [description, setDescription] = useState("");
  const [impact, setImpact] = useState<Impact>("JUST_ME");
  const [urgency, setUrgency] = useState<Urgency>("PARTLY");
  const [files, setFiles] = useState<FileItem[]>([]);
  const [errors, setErrors] = useState<Errors>({});
  const [busy, setBusy] = useState(false);
  const [reference, setReference] = useState<string | null>(null);
  const keyRef = useRef(newIdempotencyKey());
  const fileInput = useRef<HTMLInputElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const typesId = useId();

  useEffect(() => {
    const controller = new AbortController();
    fetchRequestTypes(controller.signal)
      .then((list) => {
        setTypes(list);
        const only = list.length === 1 ? list[0] : undefined;
        if (only) setRequestTypeId(String(only.id));
      })
      .catch(() => {
        if (!controller.signal.aborted) setTypesFailed(true);
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (reference) headingRef.current?.focus();
  }, [reference]);

  function onFiles(event: ChangeEvent<HTMLInputElement>) {
    const chosen = Array.from(event.target.files ?? []);
    event.target.value = "";
    for (const file of chosen) {
      const key = newIdempotencyKey();
      const problem = localProblem(file);
      if (problem) {
        setFiles((list) => [...list, { key, name: file.name, state: "failed", message: problem }]);
        continue;
      }
      setFiles((list) => [...list, { key, name: file.name, state: "uploading" }]);
      uploadFile(file)
        .then((upload) =>
          setFiles((list) =>
            list.map((f) => (f.key === key ? { key, name: file.name, state: "ready", upload } : f)),
          ),
        )
        .catch((e: unknown) => {
          if (e instanceof ApiError && e.status === 401) {
            navigate("/sign-in", { replace: true });
            return;
          }
          const message = e instanceof ApiError ? e.message : "The upload failed. Try again.";
          report(e, `${file.name} was not uploaded.`);
          setFiles((list) =>
            list.map((f) =>
              f.key === key ? { key, name: file.name, state: "failed", message } : f,
            ),
          );
        });
    }
  }

  function removeFile(key: string) {
    setFiles((list) => list.filter((f) => f.key !== key));
  }

  function validate(): Errors {
    const next: Errors = {};
    if (!requestTypeId) next.requestType = "Choose what you need help with.";
    if (!subject.trim()) next.subject = "Enter a subject.";
    else if (subject.trim().length > MAX_SUBJECT)
      next.subject = "Keep the subject under 250 characters.";
    if (!description.trim()) next.description = "Describe the problem.";
    return next;
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const found = validate();
    if (files.some((f) => f.state === "uploading")) {
      found.form = "Wait for the files to finish uploading.";
    }
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setBusy(true);
    try {
      const ref = await raiseTicket({
        requestTypeId: Number(requestTypeId),
        subject: subject.trim(),
        descriptionHtml: textToHtml(description),
        impact,
        urgency,
        attachmentIds: files.flatMap((f) => (f.state === "ready" ? [f.upload.id] : [])),
        idempotencyKey: keyRef.current,
      });
      setReference(ref);
    } catch (e) {
      // 401 already sends the user to sign-in (api/client.ts); nothing is kept in the URL.
      setErrors({
        form:
          e instanceof ApiError && e.status !== 401
            ? e.message
            : "We could not send your ticket. Check your connection and try again.",
      });
      report(e, "Your ticket was not sent.");
    } finally {
      setBusy(false);
    }
  }

  function raiseAnother() {
    keyRef.current = newIdempotencyKey();
    setSubject("");
    setDescription("");
    setImpact("JUST_ME");
    setUrgency("PARTLY");
    setFiles([]);
    setErrors({});
    setReference(null);
  }

  if (reference) {
    return (
      <div className={styles.portalWrap}>
        <section className={`${styles.success} ${portal.successPanel}`} aria-live="polite">
          <p className={styles.successLabel}>Sent</p>
          <h1 ref={headingRef} tabIndex={-1} className={portal.successHeading}>
            Ticket {reference} received
          </h1>
          <p>
            We have emailed you a copy. We will reply by email - keep [{reference}] in the subject
            if you write to us about it.
          </p>
          <Button className={styles.successButton} onClick={raiseAnother}>
            Raise another ticket
          </Button>
        </section>
      </div>
    );
  }

  return (
    <div className={styles.portalWrap}>
      <form className={styles.portalForm} onSubmit={onSubmit} noValidate aria-busy={busy}>
        <div>
          <h1>Raise a support ticket</h1>
          <p className={styles.lead}>
            We will reply by email. To add more information later, just reply to our email.
          </p>
        </div>

        <fieldset
          className={`${formStyles.fieldset} ${formStyles.boxed}`}
          aria-describedby={errors.requestType ? `${typesId}-error` : undefined}
          aria-busy={!types && !typesFailed}
        >
          <legend className={formStyles.legend}>What do you need help with?</legend>
          {errors.requestType && (
            <span id={`${typesId}-error`} className={formStyles.error}>
              {errors.requestType}
            </span>
          )}
          {typesFailed ? (
            <p role="alert" className={formStyles.error}>
              We could not load the request types. Reload the page to try again.
            </p>
          ) : null}
          {!types && !typesFailed ? <p className={formStyles.hint}>Loading...</p> : null}
          {(types ?? []).map((t) => {
            const nameId = `${typesId}-${t.id}-name`;
            const descId = `${typesId}-${t.id}-desc`;
            return (
              <label key={t.id} className={`${formStyles.choice} ${portal.typeChoice}`}>
                <input
                  type="radio"
                  name="requestType"
                  value={t.id}
                  checked={requestTypeId === String(t.id)}
                  onChange={(e) => setRequestTypeId(e.target.value)}
                  aria-labelledby={nameId}
                  aria-describedby={t.description ? descId : undefined}
                />
                <span className={portal.typeText}>
                  <span id={nameId}>{t.name}</span>
                  {t.description ? (
                    <span id={descId} className={formStyles.hint}>
                      {t.description}
                    </span>
                  ) : null}
                </span>
              </label>
            );
          })}
        </fieldset>

        <TextField
          label="Subject"
          placeholder="e.g. Outlook keeps asking for a password"
          value={subject}
          maxLength={MAX_SUBJECT}
          onChange={(e) => setSubject(e.target.value)}
          error={errors.subject}
        />
        <Textarea
          label="Describe the problem"
          rows={5}
          placeholder="What happened, since when, and on which computer?"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          error={errors.description}
        />
        <div className={styles.impactRow}>
          <RadioGroup
            boxed
            legend="Who is affected?"
            name="impact"
            options={IMPACT}
            value={impact}
            onChange={(v) => setImpact(v as Impact)}
          />
          <RadioGroup
            boxed
            legend="Can you still work?"
            name="urgency"
            options={URGENCY}
            value={urgency}
            onChange={(v) => setUrgency(v as Urgency)}
          />
        </div>

        <div className={styles.dropzone}>
          <input
            ref={fileInput}
            type="file"
            multiple
            className={portal.hiddenInput}
            onChange={onFiles}
            aria-label="Attach files"
            tabIndex={-1}
          />
          Add screenshots or files:{" "}
          <button
            type="button"
            className={styles.inlineLinkButton}
            onClick={() => fileInput.current?.click()}
          >
            choose files
          </button>
          <div className={styles.subtle}>Up to 20 MB each</div>
        </div>
        {files.length > 0 && (
          <ul className={portal.fileList} aria-label="Files">
            {files.map((f) => (
              <li key={f.key} className={portal.fileItem}>
                <span className={portal.fileName}>{f.name}</span>
                <span className={f.state === "failed" ? formStyles.error : styles.subtle}>
                  {f.state === "uploading" && "Uploading..."}
                  {f.state === "ready" && formatSize(f.upload.size)}
                  {f.state === "failed" && f.message}
                </span>
                <Button
                  className={portal.removeButton}
                  onClick={() => removeFile(f.key)}
                  aria-label={`Remove ${f.name}`}
                >
                  Remove
                </Button>
              </li>
            ))}
          </ul>
        )}

        {errors.form && (
          <p role="alert" className={formStyles.error}>
            {errors.form}
          </p>
        )}
        <Button type="submit" variant="primary" className={styles.alignStart} disabled={busy}>
          {busy ? "Sending..." : "Send ticket"}
        </Button>
      </form>

      <aside className={styles.portalAside} aria-label="After you send">
        <p className={styles.subtle}>
          You can only raise tickets here. Replies, updates and the resolved notice all arrive by
          email.
        </p>
      </aside>
    </div>
  );
}
