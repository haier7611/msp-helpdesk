import { useEffect, useState, type FormEvent } from "react";
import {
  createSharedReply,
  deleteSharedReply,
  listSharedReplies,
  updateSharedReply,
  type SharedReply,
} from "../../api/admin";
import { DataTable } from "../../components/DataTable";
import { Button, Textarea, TextField } from "../../components/forms";
import { Modal } from "../../components/Modal";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import styles from "./admin.module.css";
import { messageOf } from "./adminShared";
import { useReportError } from "../../components/useReportError";

const PLACEHOLDERS = [
  "{client_first_name}",
  "{client_name}",
  "{ticket_ref}",
  "{organisation_name}",
  "{agent_name}",
];

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/** Plain text to the server's rich text: blank lines make paragraphs, line breaks stay. */
function textToHtml(text: string): string {
  return text
    .trim()
    .split(/\n\s*\n/)
    .map((p) => `<p>${escapeHtml(p.trim()).replace(/\n/g, "<br>")}</p>`)
    .join("");
}

/** The stored (sanitised) HTML as plain text for editing - never inserted into the page. */
function htmlToText(html: string): string {
  const marked = html
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/p>\s*<p[^>]*>/gi, "\n\n")
    .replace(/<\/(li|div)>/gi, "\n");
  const doc = new DOMParser().parseFromString(marked, "text/html");
  return (doc.body.textContent ?? "").trim();
}

type Draft = { id: number | null; title: string; body: string };

/**
 * Admin - Saved replies: the shared replies every supporter can insert with
 * "/" in the composer. Supporters keep their personal replies private (My settings); this screen
 * never lists them.
 */
export function SavedRepliesPage() {
  const toast = useToast();
  const report = useReportError();
  const [rows, setRows] = useState<SharedReply[] | null>(null);
  const [loadError, setLoadError] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [formError, setFormError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listSharedReplies(controller.signal)
      .then(setRows)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setLoadError(messageOf(e));
      });
    return () => controller.abort();
  }, []);

  async function onSave(event: FormEvent) {
    event.preventDefault();
    if (!draft) return;
    setFormError("");
    if (!draft.title.trim() || !draft.body.trim()) {
      setFormError("Enter a title and the reply text.");
      return;
    }
    setBusy(true);
    try {
      const input = { title: draft.title.trim(), bodyHtml: textToHtml(draft.body) };
      const saved =
        draft.id === null
          ? await createSharedReply(input)
          : await updateSharedReply(draft.id, input);
      setRows((r) => {
        const others = (r ?? []).filter((x) => x.id !== saved.id);
        return [...others, saved].sort((a, b) => a.title.localeCompare(b.title));
      });
      setDraft(null);
      toast.show(`Saved "${saved.title}" for every agent.`, "success");
    } catch (e) {
      setFormError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  async function onDelete(reply: SharedReply) {
    setBusy(true);
    try {
      await deleteSharedReply(reply.id);
      setRows((r) => r?.filter((x) => x.id !== reply.id) ?? null);
      toast.show(`Deleted "${reply.title}".`, "success");
    } catch (e) {
      toast.showError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.page}>
      <PageHeader
        title="Saved replies"
        actions={
          <Button
            variant="primary"
            onClick={() => {
              setFormError("");
              setDraft({ id: null, title: "", body: "" });
            }}
          >
            Add shared reply
          </Button>
        }
      />
      <p className={styles.muted}>
        Shared replies appear in every agent&apos;s &quot;/&quot; picker in the composer. Personal
        replies stay private to their owner and are not shown here.
      </p>
      {loadError && (
        <p role="alert" className={styles.error}>
          {loadError}
        </p>
      )}
      <Card>
        <DataTable
          stackOnPhone
          caption="Shared saved replies"
          minWidth={640}
          loading={rows === null && !loadError}
          rows={rows ?? []}
          rowKey={(r) => String(r.id)}
          emptyMessage="No shared replies yet."
          columns={[
            { key: "title", header: "Title", rowHeader: true, render: (r) => r.title },
            {
              key: "text",
              header: "Text",
              render: (r) => {
                const text = htmlToText(r.bodyHtml);
                return text.length > 120 ? `${text.slice(0, 120)}...` : text;
              },
            },
            {
              key: "actions",
              header: <span className="visually-hidden">Actions</span>,
              actions: true,
              render: (r) => (
                <>
                  <Button
                    aria-label={`Edit ${r.title}`}
                    onClick={() => {
                      setFormError("");
                      setDraft({ id: r.id, title: r.title, body: htmlToText(r.bodyHtml) });
                    }}
                  >
                    Edit
                  </Button>
                  <Button
                    variant="danger"
                    aria-label={`Delete ${r.title}`}
                    disabled={busy}
                    onClick={() => void onDelete(r)}
                  >
                    Delete
                  </Button>
                </>
              ),
            },
          ]}
        />
      </Card>
      <Modal
        open={draft !== null}
        title={draft?.id === null ? "Add shared reply" : "Edit shared reply"}
        onClose={() => setDraft(null)}
      >
        {draft && (
          <form className={styles.form} onSubmit={onSave} noValidate>
            <TextField
              label="Title"
              maxLength={120}
              value={draft.title}
              onChange={(e) => setDraft({ ...draft, title: e.target.value })}
            />
            <Textarea
              label="Reply text"
              rows={8}
              hint={`Plain text; a blank line starts a new paragraph. Placeholders: ${PLACEHOLDERS.join(" ")}`}
              value={draft.body}
              onChange={(e) => setDraft({ ...draft, body: e.target.value })}
            />
            {formError && (
              <p role="alert" className={styles.error}>
                {formError}
              </p>
            )}
            <div className={styles.actions}>
              <Button type="submit" variant="primary" disabled={busy}>
                Save
              </Button>
              <Button onClick={() => setDraft(null)}>Cancel</Button>
            </div>
          </form>
        )}
      </Modal>
    </div>
  );
}
