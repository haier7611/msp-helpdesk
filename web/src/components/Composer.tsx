import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { MAX_UPLOAD_BYTES, type SavedReply, type Uploaded } from "../api/agent";
import { RichEditor, type RichEditorHandle, type SlashOption } from "./RichEditor";
import { ReplyCc } from "./ReplyCc";
import { ApiError } from "../api/client";
import {
  CC_ERROR_CODES,
  ccChanged,
  initialCcValue,
  type CcValue,
  type ReplyCcConfig,
} from "./ccRecipients";
import { useOptionalToast } from "./toastContext";
import { Tabs } from "./Tabs";
import styles from "./Composer.module.css";
import { errorMessage } from "./useReportError";

export type ComposerTab = "reply" | "note";

export type ComposerFile = { id: string; filename: string; size: number; image: boolean };

export type ComposerSend = {
  kind: ComposerTab;
  /** Editor HTML with inline images as attachment:<id> (the server sanitises it on save). */
  html: string;
  attachmentIds: string[];
  /** Set Pending on client in the same action (reply only). */
  thenPendingOnClient?: boolean;
  /** The CC the agent chose (reply only); absent = the ticket's participants. */
  cc?: { recipients: string[]; keepOnFutureReplies: boolean; confirmExternal: string[] };
};

export type ComposerProps = {
  /**
   * Keeps unsent drafts per user and ticket in sessionStorage (survives a sign-in round trip);
   * build it with draftKeyFor(). null keeps no draft.
   */
  draftKey: string | null;
  to: string;
  /** Plain CC text when the CC cannot be edited (e.g. an Email triage ticket). */
  cc?: string;
  /** Makes the CC editable ("Edit" next to To and CC). Never on the Internal note tab. */
  ccConfig?: ReplyCcConfig;
  /** e.g. "Your signature is added when the reply is emailed". */
  signatureHint?: string;
  /** The supporter's default composer tab (My settings - Preferences). */
  defaultTab?: ComposerTab;
  /** When set, the Reply to client tab says why replies are not possible (e.g. Closed). */
  replyDisabledReason?: string;
  savedReplies?: SavedReply[];
  renderSavedReply?: (id: number) => Promise<string>;
  upload?: (file: File) => Promise<Uploaded>;
  onSend: (send: ComposerSend) => Promise<void>;
};

type Draft = { html: string; files: ComposerFile[] };

const EMPTY: Draft = { html: "", files: [] };
const UPLOAD_PATH = /\/api\/v1\/agent\/attachments\/([0-9a-f-]{36})/gi;

function readDraft(key: string): Draft {
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return EMPTY;
    const parsed = JSON.parse(raw) as Partial<Draft>;
    return {
      html: typeof parsed.html === "string" ? parsed.html : "",
      files: Array.isArray(parsed.files) ? parsed.files : [],
    };
  } catch {
    return EMPTY;
  }
}

function writeDraft(key: string, draft: Draft) {
  try {
    if (!draft.html && draft.files.length === 0) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, JSON.stringify(draft));
  } catch {
    // Storage blocked (private mode): the draft just is not kept across reloads.
  }
}

/** Editor HTML -> stored form: our upload URLs become attachment:<id>. */
export function toStoredHtml(html: string): { html: string; inlineIds: string[] } {
  const inlineIds: string[] = [];
  const stored = html.replace(UPLOAD_PATH, (_m, id: string) => {
    inlineIds.push(id.toLowerCase());
    return `attachment:${id.toLowerCase()}`;
  });
  return { html: stored, inlineIds };
}

function hasContent(html: string): boolean {
  if (/<img\b/i.test(html)) return true;
  const div = document.createElement("div");
  div.innerHTML = html;
  return (div.textContent ?? "").trim().length > 0;
}

function sizeText(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function escapeAttr(text: string): string {
  return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

/**
 * Composer (spec C12): "Reply to client" and "Internal note" tabs with different colours, opening
 * on the supporter's default tab. No confirmation dialog; a note can never be turned into a reply.
 * Rich text, pasted or uploaded images, attachments, and saved replies inserted with "/". A send
 * that fails keeps the draft and shows the error; drafts are kept per ticket and tab in
 * sessionStorage, so a 401 round trip through sign-in does not lose them.
 */
export function Composer({
  draftKey,
  to,
  cc,
  ccConfig,
  signatureHint,
  defaultTab = "note",
  replyDisabledReason,
  savedReplies = [],
  renderSavedReply,
  upload,
  onSend,
}: ComposerProps) {
  const [tab, setTab] = useState<ComposerTab>(defaultTab);
  const [drafts, setDrafts] = useState<Record<ComposerTab, Draft>>(() => ({
    reply: draftKey ? readDraft(`${draftKey}:reply`) : EMPTY,
    note: draftKey ? readDraft(`${draftKey}:note`) : EMPTY,
  }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toast = useOptionalToast();
  const [status, setStatus] = useState<string | null>(null);
  const [editorKey, setEditorKey] = useState(0);
  const [ccValue, setCcValue] = useState<CcValue | null>(() =>
    ccConfig ? initialCcValue(ccConfig) : null,
  );
  // The participants changed (a reload after a send, or someone else): start from them again.
  const ccInitialKey = ccConfig
    ? ccConfig.initial.map((p) => p.email.toLowerCase()).join(",")
    : null;
  const [ccKey, setCcKey] = useState(ccInitialKey);
  if (ccKey !== ccInitialKey) {
    setCcKey(ccInitialKey);
    setCcValue(ccConfig ? initialCcValue(ccConfig) : null);
  }
  const editor = useRef<RichEditorHandle>(null);
  const imageInput = useRef<HTMLInputElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!draftKey) return;
    writeDraft(`${draftKey}:reply`, drafts.reply);
    writeDraft(`${draftKey}:note`, drafts.note);
  }, [draftKey, drafts]);

  function update(kind: ComposerTab, change: Partial<Draft>) {
    setDrafts((d) => ({ ...d, [kind]: { ...d[kind], ...change } }));
  }

  async function uploadAll(files: File[], inline: boolean) {
    if (!upload) return;
    setError(null);
    const kind = tab;
    for (const file of files) {
      if (file.size > MAX_UPLOAD_BYTES) {
        setError(`${file.name} is larger than 20 MB, so it cannot be attached.`);
        continue;
      }
      try {
        setStatus(`Uploading ${file.name}...`);
        const u = await upload(file);
        if (inline && u.image) {
          editor.current?.insertHtml(
            `<img src="/api/v1/agent/attachments/${u.id}" alt="${escapeAttr(u.filename)}">`,
          );
        } else {
          setDrafts((d) => ({
            ...d,
            [kind]: {
              ...d[kind],
              files: [
                ...d[kind].files,
                { id: u.id, filename: u.filename, size: u.size, image: u.image },
              ],
            },
          }));
        }
        setStatus(`${u.filename} added.`);
      } catch (e) {
        setStatus(null);
        const message = `Could not upload ${file.name}: ${errorMessage(e)}`;
        setError(message);
        toast?.showError(message);
      }
    }
  }

  function onFiles(event: ChangeEvent<HTMLInputElement>, inline: boolean) {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    void uploadAll(files, inline);
  }

  async function onSlashPick(option: SlashOption) {
    if (!renderSavedReply) return;
    setError(null);
    try {
      const html = await renderSavedReply(option.id);
      editor.current?.insertHtml(html);
      setStatus(`Saved reply "${option.title}" inserted.`);
    } catch (e) {
      const message = `Could not insert the saved reply: ${errorMessage(e)}`;
      setError(message);
      toast?.showError(message);
    }
  }

  async function send(kind: ComposerTab, thenPendingOnClient = false) {
    const draft = drafts[kind];
    if (!hasContent(draft.html) && draft.files.length === 0) {
      setError(kind === "reply" ? "Write a reply first." : "Write a note first.");
      return;
    }
    const { html, inlineIds } = toStoredHtml(draft.html);
    const ids = Array.from(new Set([...draft.files.map((f) => f.id), ...inlineIds]));
    const ccChoice =
      kind === "reply" && ccConfig && ccValue && ccChanged(ccConfig, ccValue)
        ? {
            recipients: ccValue.selected.map((p) => p.email),
            keepOnFutureReplies: ccValue.keep,
            confirmExternal: ccValue.confirmed,
          }
        : undefined;
    setBusy(true);
    setError(null);
    try {
      await onSend({
        kind,
        html,
        attachmentIds: ids,
        ...(thenPendingOnClient ? { thenPendingOnClient: true } : {}),
        ...(ccChoice ? { cc: ccChoice } : {}),
      });
      if (kind === "reply" && ccConfig) setCcValue(initialCcValue(ccConfig));
      update(kind, EMPTY);
      setEditorKey((k) => k + 1);
      setStatus(kind === "reply" ? "Reply sent." : "Note added.");
    } catch (e) {
      // Keep the draft; tell the user what went wrong.
      if (kind === "reply" && e instanceof ApiError && e.code && CC_ERROR_CODES.includes(e.code)) {
        // A CC refusal: the server's message names the address. The draft and the CC
        // choice stay, so the agent can fix CC and send again.
        const message = `The reply was not sent: ${e.message} Change CC and send again - your text is still here.`;
        setError(message);
        toast?.showError(message);
        return;
      }
      const message = `${kind === "reply" ? "The reply was not sent" : "The note was not saved"}: ${errorMessage(
        e,
      )} Your text is still here.`;
      setError(message);
      toast?.showError(message);
    } finally {
      setBusy(false);
    }
  }

  const slashOptions = savedReplies.map((r) => ({ id: r.id, title: r.title, shared: r.shared }));

  function toolbar() {
    const exec = (command: string) => {
      if (typeof document.execCommand === "function") document.execCommand(command);
      editor.current?.focus();
    };
    return (
      <div className={styles.toolbar} role="toolbar" aria-label="Formatting">
        <button
          type="button"
          className={styles.tool}
          aria-label="Bold"
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => exec("bold")}
        >
          <strong aria-hidden="true">B</strong>
        </button>
        <button
          type="button"
          className={styles.tool}
          aria-label="Italic"
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => exec("italic")}
        >
          <em aria-hidden="true">I</em>
        </button>
        <button
          type="button"
          className={styles.tool}
          aria-label="Bulleted list"
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => exec("insertUnorderedList")}
        >
          <span aria-hidden="true">&#8226;</span>
        </button>
        <button
          type="button"
          className={styles.tool}
          aria-label="Insert image"
          disabled={!upload}
          onClick={() => imageInput.current?.click()}
        >
          <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none">
            <rect x="3" y="4" width="18" height="16" rx="2" stroke="currentColor" strokeWidth="2" />
            <circle cx="9" cy="10" r="2" stroke="currentColor" strokeWidth="2" />
            <path d="M21 17l-5-5-9 8" stroke="currentColor" strokeWidth="2" />
          </svg>
        </button>
        <button
          type="button"
          className={styles.tool}
          aria-label="Attach file"
          disabled={!upload}
          onClick={() => fileInput.current?.click()}
        >
          <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path
              d="M21 11l-9 9a5 5 0 01-7-7l9-9a3.5 3.5 0 015 5l-9 9a2 2 0 01-3-3l8-8"
              stroke="currentColor"
              strokeWidth="2"
            />
          </svg>
        </button>
        <span className={styles.toolHint}>Type / for saved replies</span>
      </div>
    );
  }

  function editorFor(kind: ComposerTab) {
    return (
      <RichEditor
        key={`${kind}-${editorKey}`}
        ref={editor}
        label={kind === "reply" ? "Reply text" : "Internal note text"}
        initialHtml={drafts[kind].html}
        onChange={(html) => {
          update(kind, { html });
          // A "Write a reply first." style message is stale once the agent types.
          setError(null);
        }}
        slashOptions={slashOptions}
        onSlashPick={(o) => void onSlashPick(o)}
        onPasteFiles={(files) => void uploadAll(files, true)}
        className={kind === "note" ? styles.noteEditor : undefined}
      />
    );
  }

  function fileList(kind: ComposerTab) {
    const files = drafts[kind].files;
    if (files.length === 0) return null;
    return (
      <ul className={styles.files} aria-label="Attachments">
        {files.map((f) => (
          <li key={f.id} className={styles.file}>
            <span>
              {f.filename} ({sizeText(f.size)})
            </span>
            <button
              type="button"
              className={styles.linkButton}
              aria-label={`Remove ${f.filename}`}
              onClick={() => update(kind, { files: files.filter((x) => x.id !== f.id) })}
            >
              Remove
            </button>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <section className={styles.composer} aria-label="Write a reply">
      <input
        ref={imageInput}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp"
        hidden
        aria-hidden="true"
        tabIndex={-1}
        data-testid="image-input"
        onChange={(e) => onFiles(e, true)}
      />
      <input
        ref={fileInput}
        type="file"
        multiple
        hidden
        aria-hidden="true"
        tabIndex={-1}
        data-testid="file-input"
        onChange={(e) => onFiles(e, false)}
      />
      <Tabs
        label="Reply type"
        variant="filled"
        selectedId={tab}
        onSelect={(id) => {
          setTab(id as ComposerTab);
          setError(null);
        }}
        items={[
          {
            id: "reply",
            label: "Reply to client",
            content: replyDisabledReason ? (
              <div className={styles.panel}>
                <p className={styles.hint}>{replyDisabledReason}</p>
              </div>
            ) : (
              <div className={styles.panel}>
                {ccConfig && ccValue ? (
                  <ReplyCc
                    to={to}
                    config={ccConfig}
                    value={ccValue}
                    onChange={setCcValue}
                    disabled={busy}
                  />
                ) : (
                  <div className={styles.recipients}>
                    <span>
                      <strong>To:</strong> {to || "No contact on this ticket"}
                    </span>
                    {cc && (
                      <span>
                        <strong>CC:</strong> {cc}
                      </span>
                    )}
                  </div>
                )}
                {toolbar()}
                {editorFor("reply")}
                {fileList("reply")}
                <div className={styles.actions}>
                  {signatureHint && <span className={styles.hint}>{signatureHint}</span>}
                  <div className={styles.split} role="group" aria-label="Send">
                    <button
                      type="button"
                      className={`${styles.send} ${styles.splitMain}`}
                      disabled={busy || !to}
                      onClick={() => void send("reply")}
                    >
                      Send to client
                    </button>
                    <button
                      type="button"
                      className={`${styles.send} ${styles.splitSecond}`}
                      disabled={busy || !to}
                      onClick={() => void send("reply", true)}
                    >
                      Send and set Pending on client
                    </button>
                  </div>
                </div>
              </div>
            ),
          },
          {
            id: "note",
            label: "Internal note",
            className: styles.noteTab,
            selectedClassName: styles.noteTabSelected,
            content: (
              <div className={`${styles.panel} ${styles.notePanel}`}>
                <p className={styles.noteBanner}>
                  <span className={styles.internalLabel}>INTERNAL</span>
                  Only agents see this note. It is never emailed to the client.
                </p>
                {toolbar()}
                {editorFor("note")}
                {fileList("note")}
                <div className={styles.actions}>
                  <button
                    type="button"
                    className={`${styles.send} ${styles.addNote}`}
                    disabled={busy}
                    onClick={() => void send("note")}
                  >
                    Add note
                  </button>
                </div>
              </div>
            ),
          },
        ]}
      />
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <p className="visually-hidden" role="status">
        {status}
      </p>
    </section>
  );
}
