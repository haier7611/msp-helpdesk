import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { changeOrganisation, type AgentOrganisation, type TicketDetail } from "../../api/agent";
import { Button } from "../../components/forms";
import formStyles from "../../components/forms.module.css";
import { errorText } from "../../format";
import styles from "../pages.module.css";
import { useReportError } from "../../components/useReportError";

function orgLabel(o: { name: string; prefix: string | null }): string {
  return o.prefix ? `${o.name} (${o.prefix})` : o.name;
}

/**
 * The ticket's organisation as a dropdown in the Details card, styled like
 * Status and Priority. Choosing another organisation does not save: an inline confirmation with the
 * move's consequences appears and takes focus; Move calls the existing change-organisation API
 * (the server still checks access and refuses closed tickets), Cancel or Esc puts the old value
 * back. Only organisations the agent works on are listed (same source as the old dialog).
 */
export function OrganisationField({
  ticket,
  organisations,
  disabled,
  onMoved,
}: {
  ticket: TicketDetail;
  organisations: AgentOrganisation[];
  disabled?: boolean;
  onMoved: (reference: string) => void;
}) {
  const current = String(ticket.organisationId ?? "");
  const [pending, setPending] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const report = useReportError();
  const selectRef = useRef<HTMLSelectElement>(null);
  const confirmRef = useRef<HTMLDivElement>(null);
  const headingId = useId();
  const selectId = useId();

  const options = organisations.some((o) => String(o.id) === current)
    ? organisations
    : [
        {
          id: ticket.organisationId ?? 0,
          name: ticket.organisationName ?? "Current organisation",
          prefix: null,
          verifiedDomains: [],
        },
        ...organisations,
      ];
  const target = pending ? options.find((o) => String(o.id) === pending) : undefined;

  useEffect(() => {
    if (pending) confirmRef.current?.focus();
  }, [pending]);

  function choose(value: string) {
    setError(null);
    setPending(value === current ? null : value);
  }

  function cancel() {
    setPending(null);
    setError(null);
    selectRef.current?.focus();
  }

  async function move() {
    if (!pending) return;
    setBusy(true);
    setError(null);
    try {
      onMoved(await changeOrganisation(ticket.id, Number(pending)));
    } catch (e) {
      setError(errorText(e, "The ticket could not be moved. Please try again."));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape" && !busy) {
      event.preventDefault();
      cancel();
    }
  }

  return (
    <div className={formStyles.field}>
      <label htmlFor={selectId} className={formStyles.label}>
        Organisation
      </label>
      <select
        id={selectId}
        ref={selectRef}
        className={formStyles.control}
        value={pending ?? current}
        disabled={disabled || busy}
        onChange={(e) => choose(e.target.value)}
      >
        {options.map((o) => (
          <option key={o.id} value={String(o.id)}>
            {orgLabel(o)}
          </option>
        ))}
      </select>
      {pending && target && (
        <div
          ref={confirmRef}
          role="group"
          aria-labelledby={headingId}
          tabIndex={-1}
          className={styles.inlineConfirm}
          onKeyDown={onKeyDown}
        >
          <p id={headingId} className={styles.inlineConfirmTitle}>
            Move {ticket.reference} to {target.name}?
          </p>
          <p className={styles.inlineConfirmText}>
            The ticket gets a new reference from the new organisation; {ticket.reference} keeps
            working as an alias. Contacts of the old organisation are removed from the participants,
            links to its tickets are removed, and the assignee is cleared if they cannot work the
            new organisation. The SLA is worked out again on the new organisation&apos;s targets.
          </p>
          {error && (
            <p className={styles.errorBox} role="alert">
              {error}
            </p>
          )}
          <div className={styles.filterActions}>
            <Button variant="primary" disabled={busy} onClick={() => void move()}>
              Move
            </Button>
            <Button disabled={busy} onClick={cancel}>
              Cancel
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
