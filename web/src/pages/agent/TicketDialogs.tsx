import { useEffect, useState, type FormEvent } from "react";
import {
  assignFromTriage,
  changeOrganisation,
  getTriageOptions,
  type AgentOrganisation,
  type TicketDetail,
  type TriageOptions,
} from "../../api/agent";
import { Button, Checkbox, TextField } from "../../components/forms";
import { Modal } from "../../components/Modal";
import { errorText } from "../../format";
import formStyles from "../../components/forms.module.css";
import styles from "../pages.module.css";
import { useReportError } from "../../components/useReportError";

function OrganisationSelect({
  organisations,
  value,
  onChange,
  exclude,
}: {
  organisations: AgentOrganisation[];
  value: string;
  onChange: (v: string) => void;
  exclude?: number | null;
}) {
  return (
    <div className={formStyles.field}>
      <label htmlFor="dialog-organisation" className={formStyles.label}>
        Organisation
      </label>
      <select
        id="dialog-organisation"
        className={formStyles.control}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        required
      >
        <option value="">Choose an organisation</option>
        {organisations
          .filter((o) => o.id !== exclude)
          .map((o) => (
            <option key={o.id} value={o.id}>
              {o.name}
              {o.prefix ? ` (${o.prefix})` : ""}
            </option>
          ))}
      </select>
    </div>
  );
}

/**
 * Assign a triage ticket to an organisation with the two optional "remember" ticks.
 * The domain tick is offered only when the server says the sender's domain can be a verified
 * domain (never for public mail domains such as gmail.com); the server refuses it anyway.
 */
export function TriageAssignDialog({
  ticket,
  organisations,
  onClose,
  onDone,
}: {
  ticket: TicketDetail;
  organisations: AgentOrganisation[];
  onClose: () => void;
  onDone: (reference: string) => void;
}) {
  const [org, setOrg] = useState("");
  const [options, setOptions] = useState<TriageOptions | null>(null);
  const [saveContact, setSaveContact] = useState(false);
  const [addDomain, setAddDomain] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const report = useReportError();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    getTriageOptions(ticket.id, controller.signal).then(setOptions, (e: unknown) =>
      report(e, "The sender's details could not be loaded."),
    );
    return () => controller.abort();
  }, [ticket.id, report]);

  const orgName = organisations.find((o) => String(o.id) === org)?.name ?? "the organisation";

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!org) {
      setError("Choose an organisation.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const reference = await assignFromTriage(ticket.id, {
        organisationId: Number(org),
        saveContact: saveContact && !!options?.senderEmail,
        addDomain: addDomain && !!options?.domainOffered,
      });
      onDone(reference);
    } catch (e) {
      setError(errorText(e));
      report(e);
      setBusy(false);
    }
  }

  return (
    <Modal open title="Assign to an organisation" onClose={onClose}>
      <form className={styles.stack} onSubmit={submit} noValidate>
        <p>
          The ticket gets a reference from the organisation; {ticket.reference} keeps working as an
          alias.
        </p>
        <OrganisationSelect organisations={organisations} value={org} onChange={setOrg} />
        {options?.senderEmail && !options.contactExists && (
          <Checkbox
            label={`Save ${options.senderEmail} as a contact of ${orgName}`}
            checked={saveContact}
            onChange={(e) => setSaveContact(e.target.checked)}
          />
        )}
        {options?.domainOffered && options.senderDomain && (
          <Checkbox
            label={`Add ${options.senderDomain} as a verified domain of ${orgName}`}
            checked={addDomain}
            onChange={(e) => setAddDomain(e.target.checked)}
          />
        )}
        {options && options.senderDomain && !options.domainOffered && (
          <p className={styles.subtle}>
            {options.senderDomain} cannot be a verified domain (a public email domain, or already
            used). Save the sender as a contact instead.
          </p>
        )}
        {error && (
          <p className={styles.errorBox} role="alert">
            {error}
          </p>
        )}
        <div className={styles.filterActions}>
          <Button type="submit" variant="primary" disabled={busy}>
            Assign
          </Button>
          <Button onClick={onClose}>Cancel</Button>
        </div>
      </form>
    </Modal>
  );
}

/** Move a ticket to another organisation: new reference, old one an alias. */
export function ChangeOrganisationDialog({
  ticket,
  organisations,
  onClose,
  onDone,
}: {
  ticket: TicketDetail;
  organisations: AgentOrganisation[];
  onClose: () => void;
  onDone: (reference: string) => void;
}) {
  const [org, setOrg] = useState("");
  const [error, setError] = useState<string | null>(null);
  const report = useReportError();
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!org) {
      setError("Choose an organisation.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      onDone(await changeOrganisation(ticket.id, Number(org)));
    } catch (e) {
      setError(errorText(e));
      report(e);
      setBusy(false);
    }
  }

  return (
    <Modal open title="Change organisation" onClose={onClose}>
      <form className={styles.stack} onSubmit={submit} noValidate>
        <p>
          The ticket gets a new reference from the new organisation; {ticket.reference} keeps
          working as an alias. Contacts of the old organisation are removed from the participants,
          links to its tickets are removed, and the assignee is cleared if they cannot work the new
          organisation.
        </p>
        <OrganisationSelect
          organisations={organisations}
          value={org}
          onChange={setOrg}
          exclude={ticket.organisationId}
        />
        {error && (
          <p className={styles.errorBox} role="alert">
            {error}
          </p>
        )}
        <div className={styles.filterActions}>
          <Button type="submit" variant="primary" disabled={busy}>
            Move ticket
          </Button>
          <Button onClick={onClose}>Cancel</Button>
        </div>
      </form>
    </Modal>
  );
}

/** "Mark as duplicate of" and "Link related ticket": both take the other ticket's reference. */
export function ReferenceDialog({
  title,
  intro,
  submitLabel,
  onClose,
  onSubmit,
}: {
  title: string;
  intro: string;
  submitLabel: string;
  onClose: () => void;
  onSubmit: (reference: string) => Promise<void>;
}) {
  const [reference, setReference] = useState("");
  const [error, setError] = useState<string | null>(null);
  const report = useReportError();
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!reference.trim()) {
      setError("Enter the other ticket's reference.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onSubmit(reference.trim());
    } catch (e) {
      setError(errorText(e));
      report(e);
      setBusy(false);
    }
  }

  return (
    <Modal open title={title} onClose={onClose}>
      <form className={styles.stack} onSubmit={submit} noValidate>
        <p>{intro}</p>
        <TextField
          label="Other ticket's reference"
          value={reference}
          onChange={(e) => setReference(e.target.value)}
          placeholder="e.g. ACD-014"
          maxLength={40}
        />
        {error && (
          <p className={styles.errorBox} role="alert">
            {error}
          </p>
        )}
        <div className={styles.filterActions}>
          <Button type="submit" variant="primary" disabled={busy}>
            {submitLabel}
          </Button>
          <Button onClick={onClose}>Cancel</Button>
        </div>
      </form>
    </Modal>
  );
}
