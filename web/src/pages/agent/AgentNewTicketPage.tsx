import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import {
  createContact,
  createTicket,
  listAgentOrganisations,
  listContacts,
  listRequestTypes,
  PRIORITY_LABELS,
  type AgentOrganisation,
  type Contact,
  type Priority,
  type RequestType,
} from "../../api/agent";
import { fetchMe } from "../../api/auth";
import { Button, Checkbox, Textarea, TextField } from "../../components/forms";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import { errorText } from "../../format";
import formStyles from "../../components/forms.module.css";
import styles from "../pages.module.css";
import { useReportError } from "../../components/useReportError";

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

/** Plain text -> paragraphs (the server sanitises again on save). */
export function textToHtml(text: string): string {
  return text
    .split(/\n{2,}/)
    .map((p) => p.trim())
    .filter(Boolean)
    .map((p) => `<p>${escapeHtml(p).replace(/\n/g, "<br>")}</p>`)
    .join("");
}

/**
 * Agent "New ticket" (spec flow 4, F2): for phone calls and walk-ins. Pick the organisation and a
 * contact, or add a new contact inline (optionally inviting them to the portal). Source = agent.
 */
export function AgentNewTicketPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [me, setMe] = useState<{ id: number } | null>(null);
  const [orgs, setOrgs] = useState<AgentOrganisation[]>([]);
  const [types, setTypes] = useState<RequestType[]>([]);
  const [org, setOrg] = useState("");
  const [contacts, setContacts] = useState<{ org: string; items: Contact[] } | null>(null);
  const [contactId, setContactId] = useState("");
  const [newContact, setNewContact] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const report = useReportError();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listAgentOrganisations(controller.signal).then(setOrgs, (e: unknown) =>
      report(e, "Organisations could not be loaded."),
    );
    listRequestTypes(controller.signal).then(setTypes, (e: unknown) =>
      report(e, "Request types could not be loaded."),
    );
    fetchMe(controller.signal).then(setMe, () => undefined);
    return () => controller.abort();
  }, [report]);

  useEffect(() => {
    if (!org) return;
    const controller = new AbortController();
    listContacts({ organisationId: Number(org) }, controller.signal).then(
      (r) => setContacts({ org, items: r.items }),
      () => setContacts({ org, items: [] }),
    );
    return () => controller.abort();
  }, [org]);

  const orgContacts = contacts && contacts.org === org ? contacts.items : [];
  const portalTypes = types.filter((t) => t.name !== "Alert");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const subject = String(data.get("subject") ?? "").trim();
    const description = String(data.get("description") ?? "");
    const requestTypeId = Number(data.get("requestType") ?? 0);
    const priority = String(data.get("priority") ?? "");
    if (!org) return setError("Choose an organisation.");
    if (!newContact && !contactId) return setError("Choose a contact, or add a new one.");
    if (!requestTypeId) return setError("Choose a request type.");
    if (!subject) return setError("Enter a subject.");
    setBusy(true);
    setError(null);
    try {
      let reporter = Number(contactId);
      if (newContact) {
        const c = await createContact({
          organisationId: Number(org),
          name: String(data.get("contactName") ?? "").trim(),
          email: String(data.get("contactEmail") ?? "").trim(),
          invite: data.get("invite") === "on",
        });
        reporter = c.id;
        // Keep the new contact selected if the ticket step fails.
        setContacts({ org, items: [...orgContacts, c] });
        setContactId(String(c.id));
        setNewContact(false);
      }
      const created = await createTicket({
        organisationId: Number(org),
        reporterUserId: reporter,
        requestTypeId,
        ...(priority ? { priority: priority as Priority } : {}),
        subject,
        descriptionHtml: textToHtml(description),
        attachmentIds: [],
        ...(data.get("assignToMe") === "on" && me?.id ? { assigneeUserId: me.id } : {}),
      });
      toast.show(`Ticket ${created.reference} created.`);
      navigate(`/agent/tickets/${encodeURIComponent(created.reference)}`);
    } catch (e) {
      setError(errorText(e));
      report(e);
      setBusy(false);
    }
  }

  return (
    <div className={styles.columnNarrow}>
      <Link to="/agent/queue" className={styles.backLink}>
        &lt; Back to the queue
      </Link>
      <PageHeader title="New ticket" />
      <Card>
        <form className={styles.stack} onSubmit={submit} noValidate>
          <div className={formStyles.field}>
            <label htmlFor="new-organisation" className={formStyles.label}>
              Organisation
            </label>
            <select
              id="new-organisation"
              className={formStyles.control}
              value={org}
              onChange={(e) => {
                setOrg(e.target.value);
                setContactId("");
              }}
            >
              <option value="">Choose an organisation</option>
              {orgs.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.name}
                </option>
              ))}
            </select>
          </div>

          {org && !newContact && (
            <div className={styles.inlineRow}>
              <div className={formStyles.field}>
                <label htmlFor="new-contact" className={formStyles.label}>
                  Contact
                </label>
                <select
                  id="new-contact"
                  className={formStyles.control}
                  value={contactId}
                  onChange={(e) => setContactId(e.target.value)}
                >
                  <option value="">Choose a contact</option>
                  {orgContacts.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} ({c.email})
                    </option>
                  ))}
                </select>
              </div>
              <Button onClick={() => setNewContact(true)}>New contact</Button>
            </div>
          )}

          {org && newContact && (
            <fieldset className={formStyles.fieldset}>
              <legend className={formStyles.legend}>New contact</legend>
              <TextField label="Contact name" name="contactName" maxLength={200} required />
              <TextField
                label="Contact email"
                name="contactEmail"
                type="email"
                fieldSize="md"
                maxLength={320}
                required
              />
              <Checkbox label="Send a portal invitation (link valid 7 days)" name="invite" />
              <Button onClick={() => setNewContact(false)}>Pick an existing contact instead</Button>
            </fieldset>
          )}

          <div className={formStyles.field}>
            <label htmlFor="new-request-type" className={formStyles.label}>
              Request type
            </label>
            <select id="new-request-type" name="requestType" className={formStyles.control}>
              {portalTypes.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name} (starts at {t.defaultPriority})
                </option>
              ))}
            </select>
          </div>
          <div className={formStyles.field}>
            <label htmlFor="new-priority" className={formStyles.label}>
              Priority
            </label>
            <select id="new-priority" name="priority" className={formStyles.control}>
              <option value="">From the request type</option>
              {Object.entries(PRIORITY_LABELS).map(([p, label]) => (
                <option key={p} value={p}>
                  {label}
                </option>
              ))}
            </select>
          </div>
          <TextField label="Subject" name="subject" maxLength={200} required />
          <Textarea label="Description" name="description" rows={6} />
          <Checkbox label="Assign to me" name="assignToMe" defaultChecked />
          {error && (
            <p className={styles.errorBox} role="alert">
              {error}
            </p>
          )}
          <div className={styles.filterActions}>
            <Button type="submit" variant="primary" disabled={busy}>
              Create ticket
            </Button>
          </div>
        </form>
      </Card>
    </div>
  );
}
