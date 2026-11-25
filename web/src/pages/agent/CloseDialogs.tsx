import { useState, type FormEvent } from "react";
import { addBlockRule } from "../../api/admin";
import {
  addInternalNote,
  CLOSE_NOTE_MAX,
  CLOSE_NOTE_MIN,
  CLOSE_REASON_CHOICES,
  closeTicket,
  reopenTicket,
  type CloseReason,
  type TicketDetail,
} from "../../api/agent";
import { Button, Checkbox, RadioGroup, Textarea } from "../../components/forms";
import { Modal } from "../../components/Modal";
import { useReportError } from "../../components/useReportError";
import { StepUpCancelled } from "../../components/useStepUp";
import { errorText } from "../../format";
import styles from "../pages.module.css";

/** Plain text as one HTML paragraph per line (the server sanitises it again). */
function textToHtml(text: string): string {
  const escape = (s: string) =>
    s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => `<p>${escape(line)}</p>`)
    .join("");
}

function noteLengthOk(text: string): boolean {
  return text.length >= CLOSE_NOTE_MIN && text.length <= CLOSE_NOTE_MAX;
}

const NOTE_LENGTH_ERROR = `Say why (${CLOSE_NOTE_MIN} to ${CLOSE_NOTE_MAX} characters).`;

/**
 * Closing a ticket needs a reason. Duplicate switches to the "Mark as duplicate" picker;
 * Other needs a note (3-500 characters); Spam offers "Block this sender" to admins (the block list
 * is admin-only) and a hint to everyone else. The optional internal note is added after the close.
 * Cancel leaves the status as it was. The server checks every rule again.
 */
export function CloseTicketDialog({
  ticket,
  canBlock,
  onCancel,
  onDuplicate,
  onClosed,
}: {
  ticket: TicketDetail;
  canBlock: boolean;
  onCancel: () => void;
  onDuplicate: () => void;
  onClosed: (message: string) => void;
}) {
  const [reason, setReason] = useState<CloseReason | "">("");
  const [note, setNote] = useState("");
  const [internalNote, setInternalNote] = useState("");
  const [block, setBlock] = useState(false);
  const [noteError, setNoteError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const report = useReportError();
  const sender = ticket.reporter?.email ?? null;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!reason) {
      setError("Choose a reason for closing the ticket.");
      return;
    }
    if (reason === "DUPLICATE") {
      onDuplicate();
      return;
    }
    const trimmed = note.trim();
    if (reason === "OTHER" && !noteLengthOk(trimmed)) {
      setNoteError(NOTE_LENGTH_ERROR);
      return;
    }
    setBusy(true);
    setError(null);
    setNoteError(null);
    try {
      await closeTicket(ticket.id, reason, reason === "OTHER" ? trimmed : undefined);
    } catch (e) {
      setError(errorText(e));
      report(e, "The ticket was not closed.");
      setBusy(false);
      return;
    }
    // The ticket is closed; the extras below report their own failure without undoing it.
    if (internalNote.trim()) {
      try {
        await addInternalNote(ticket.id, textToHtml(internalNote), []);
      } catch (e) {
        report(e, "The ticket was closed, but the internal note was not added.");
      }
    }
    let blocked = false;
    if (reason === "SPAM" && block && canBlock && sender) {
      try {
        await addBlockRule("ADDRESS", sender, false);
        blocked = true;
      } catch (e) {
        report(e, "The ticket was closed, but the sender was not blocked.");
      }
    }
    onClosed(blocked ? `Ticket closed and ${sender} blocked.` : "Ticket closed.");
  }

  return (
    <Modal open title="Close ticket" onClose={onCancel}>
      <form className={styles.stack} onSubmit={submit} noValidate>
        <RadioGroup
          legend="Reason for closing"
          name="close-reason"
          options={CLOSE_REASON_CHOICES}
          value={reason}
          onChange={(v) => {
            setReason(v as CloseReason);
            setError(null);
          }}
        />
        {reason === "DUPLICATE" && (
          <p className={styles.subtle}>
            You choose the other ticket next, so the two tickets are linked.
          </p>
        )}
        {reason === "OTHER" && (
          <Textarea
            label="Why are you closing it?"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            required
            maxLength={CLOSE_NOTE_MAX}
            rows={2}
            error={noteError ?? undefined}
            fieldSize="full"
          />
        )}
        {reason === "SPAM" &&
          sender &&
          (canBlock ? (
            <Checkbox
              label={`Block this sender (${sender})`}
              checked={block}
              onChange={(e) => setBlock(e.target.checked)}
            />
          ) : (
            <p className={styles.subtle}>
              To stop more mail from {sender}, ask an admin to add it to the block list.
            </p>
          ))}
        {reason !== "DUPLICATE" && (
          <Textarea
            label="Internal note (optional)"
            hint="Only agents see it."
            value={internalNote}
            onChange={(e) => setInternalNote(e.target.value)}
            rows={2}
            fieldSize="full"
          />
        )}
        <p className={styles.warningText}>
          Agents can&apos;t reopen a closed ticket. Only an admin can.
        </p>
        {error && (
          <p className={styles.errorBox} role="alert">
            {error}
          </p>
        )}
        <div className={styles.filterActions}>
          <Button type="submit" variant="primary" disabled={busy}>
            {reason === "DUPLICATE" ? "Choose the other ticket" : "Close ticket"}
          </Button>
          <Button onClick={onCancel}>Cancel</Button>
        </div>
      </form>
    </Modal>
  );
}

/**
 * An admin reopens a closed ticket with a reason (3-500 characters). The server needs a
 * recent step-up; `runStepUp` (useStepUp's `run`) asks for the code and retries once.
 */
export function ReopenTicketDialog({
  ticket,
  runStepUp,
  onCancel,
  onReopened,
}: {
  ticket: TicketDetail;
  runStepUp: <T>(action: () => Promise<T>) => Promise<T>;
  onCancel: () => void;
  onReopened: () => void;
}) {
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const report = useReportError();

  async function submit(event: FormEvent) {
    event.preventDefault();
    const trimmed = reason.trim();
    if (!noteLengthOk(trimmed)) {
      setReasonError(NOTE_LENGTH_ERROR);
      return;
    }
    setBusy(true);
    setError(null);
    setReasonError(null);
    try {
      await runStepUp(() => reopenTicket(ticket.id, trimmed));
      onReopened();
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) setError(errorText(e));
      report(e, "The ticket was not reopened.");
      setBusy(false);
    }
  }

  return (
    <Modal open title="Reopen ticket" onClose={onCancel}>
      <form className={styles.stack} onSubmit={submit} noValidate>
        <p>
          {ticket.reference} goes back to Open and can be changed again. The SLA resolution clock
          carries on from where it stopped. Agents see the reason on the timeline.
        </p>
        <Textarea
          label="Reason for reopening"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          required
          maxLength={CLOSE_NOTE_MAX}
          rows={2}
          error={reasonError ?? undefined}
          fieldSize="full"
        />
        {error && (
          <p className={styles.errorBox} role="alert">
            {error}
          </p>
        )}
        <div className={styles.filterActions}>
          <Button type="submit" variant="primary" disabled={busy}>
            Reopen ticket
          </Button>
          <Button onClick={onCancel}>Cancel</Button>
        </div>
      </form>
    </Modal>
  );
}
