import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router";
import {
  addInternalNote,
  addPublicReply,
  checkReplyCc,
  createFollowUp,
  defaultComposerTab,
  getTicket,
  IMPACT_LABELS,
  linkRelated,
  listAgentOrganisations,
  listContacts,
  listRequestTypes,
  listSavedReplies,
  listSupporters,
  markDuplicate,
  PRIORITY_LABELS,
  renderSavedReply,
  resolveReference,
  setAssignee,
  setPriority,
  setRequestType,
  setStatus,
  STATUS_LABELS,
  uploadAttachment,
  URGENCY_LABELS,
  type AgentOrganisation,
  type TimelineEntry,
  type CommentEntry,
  type ComposerTabPreference,
  type Priority,
  type RequestType,
  type SavedReply,
  type Supporter,
  type SystemEntry,
  type TicketDetail,
  type TicketStatus,
} from "../../api/agent";
import { fetchMe, type Role } from "../../api/auth";
import { clearDraftsOfOtherUsers, draftKeyFor } from "../../draftStorage";
import { ApiError } from "../../api/client";
import { PriorityBadge, SlaBadge, StatusBadge } from "../../components/Badge";
import { Composer, type ComposerSend } from "../../components/Composer";
import type { CcPerson, ReplyCcConfig } from "../../components/ccRecipients";
import { Identifier } from "../../components/DataTable";
import {
  TimelineLine,
  AttachmentList,
  InternalNoteCard,
  MessageCard,
  SystemEntryCard,
} from "../../components/Conversation";
import { Button } from "../../components/forms";
import { SafeHtml } from "../../components/SafeHtml";
import { useToast } from "../../components/toastContext";
import { Card } from "../../components/ui";
import { errorText, formatDateTime } from "../../format";
import formStyles from "../../components/forms.module.css";
import styles from "../pages.module.css";
import { supporterLabel } from "./queueFilter";
import { OrganisationField } from "./OrganisationField";
import { SlaPanel } from "./SlaPanel";
import { ReferenceDialog, TriageAssignDialog } from "./TicketDialogs";
import { CloseTicketDialog, ReopenTicketDialog } from "./CloseDialogs";
import { useStepUp } from "../../components/useStepUp";
import { systemEntryText, systemKindLabel } from "./systemEntryLabels";
import { useReportError } from "../../components/useReportError";

type Load =
  | { kind: "loading" }
  | { kind: "missing" }
  | { kind: "error"; message: string }
  | { kind: "ready"; ticket: TicketDetail };

type Entry =
  | { type: "public"; at: string; c: CommentEntry }
  | { type: "note"; at: string; c: CommentEntry }
  | { type: "system"; at: string; e: SystemEntry }
  | { type: "change"; at: string; a: TimelineEntry };

type Dialog = "triage" | "duplicate" | "link" | "close" | "reopen" | null;

function personName(p: { name: string | null; email: string | null } | null, fallback: string) {
  if (!p) return fallback;
  return p.name ?? p.email ?? fallback;
}

function conversation(t: TicketDetail): Entry[] {
  const entries: Entry[] = [
    ...t.publicComments.map((c) => ({ type: "public" as const, at: c.createdAt, c })),
    ...t.internalNotes.map((c) => ({ type: "note" as const, at: c.createdAt, c })),
    ...t.systemEntries.map((e) => ({ type: "system" as const, at: e.createdAt, e })),
    ...t.timelineEntries.map((a) => ({ type: "change" as const, at: a.createdAt, a })),
  ];
  // By time, not by string: the server's timestamps differ in fractional digits. At the same
  // instant the "Ticket created" line comes before the description; otherwise the order is kept.
  const time = (iso: string) => Date.parse(iso) || 0;
  const created = (e: Entry) => (e.type === "change" && e.a.type === "CREATED" ? 0 : 1);
  return entries.sort((a, b) => time(a.at) - time(b.at) || created(a) - created(b));
}

/** The attachment list, or nothing when every file is shown inline in the body. */
/** How often the ticket is re-read while an email's files are being processed. */
const PROCESSING_REFRESH_MS = 5000;

function attachmentsOf(c: CommentEntry): ReactNode {
  const list = c.attachments.some((f) => !f.inline) ? (
    <AttachmentList files={c.attachments} />
  ) : undefined;
  if (!c.attachmentsProcessing) return list;
  return (
    <>
      <p className={styles.subtle} role="status">
        Processing attachments...
      </p>
      {list}
    </>
  );
}

function publicMeta(c: CommentEntry): string {
  const when = formatDateTime(c.createdAt);
  if (c.authorKind === "SUPPORTER") {
    return `Reply to client${c.receivedByEmail ? " (by email)" : ""} - ${when}`;
  }
  if (c.authorKind === "API_KEY") return `Alert via API - ${when}`;
  return `${c.receivedByEmail ? "via email" : "Client"} - ${when}`;
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  );
}

function SidebarSelect({
  id,
  label,
  value,
  options,
  disabled,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  options: [string, string][];
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  return (
    <div className={formStyles.field}>
      <label htmlFor={id} className={formStyles.label}>
        {label}
      </label>
      <select
        id={id}
        className={formStyles.control}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
      >
        {options.map(([v, text]) => (
          <option key={v} value={v}>
            {text}
          </option>
        ))}
      </select>
    </div>
  );
}

export function TicketPage() {
  const { ref = "" } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const report = useReportError();
  const [load, setLoad] = useState<Load>({ kind: "loading" });
  const [loadedRef, setLoadedRef] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const [tabPref, setTabPref] = useState<ComposerTabPreference | null>(null);
  const [savedReplies, setSavedReplies] = useState<SavedReply[]>([]);
  const [requestTypes, setRequestTypes] = useState<RequestType[]>([]);
  const [organisations, setOrganisations] = useState<AgentOrganisation[]>([]);
  const [supporters, setSupporters] = useState<Supporter[]>([]);
  const [dialog, setDialog] = useState<Dialog>(null);
  // undefined while loading, null when /auth/me failed (then no draft is kept).
  const [userId, setUserId] = useState<number | null | undefined>(undefined);
  const [role, setRole] = useState<Role | null>(null);
  const stepUp = useStepUp();

  useEffect(() => {
    const controller = new AbortController();
    fetchMe(controller.signal).then(
      (me) => {
        clearDraftsOfOtherUsers(me.id);
        setUserId(me.id);
        setRole(me.role);
      },
      () => {
        if (!controller.signal.aborted) setUserId(null);
      },
    );
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    defaultComposerTab(controller.signal).then(setTabPref, () => setTabPref("note"));
    listSavedReplies(controller.signal).then(setSavedReplies, (e: unknown) =>
      report(e, "Saved replies could not be loaded."),
    );
    listRequestTypes(controller.signal).then(setRequestTypes, (e: unknown) =>
      report(e, "Request types could not be loaded."),
    );
    listAgentOrganisations(controller.signal).then(setOrganisations, (e: unknown) =>
      report(e, "Organisations could not be loaded."),
    );
    return () => controller.abort();
  }, [report]);

  useEffect(() => {
    const controller = new AbortController();
    resolveReference(ref, controller.signal)
      .then((r) => getTicket(r.ticketId, controller.signal))
      .then(
        (ticket) => {
          setLoad({ kind: "ready", ticket });
          setLoadedRef(ref);
        },
        (e: unknown) => {
          if (controller.signal.aborted) return;
          setLoadedRef(ref);
          setLoad(
            e instanceof ApiError && e.status === 404
              ? { kind: "missing" }
              : { kind: "error", message: errorText(e, "The ticket could not be loaded.") },
          );
        },
      );
    return () => controller.abort();
  }, [ref, version]);

  const ticket = load.kind === "ready" && loadedRef === ref ? load.ticket : null;
  const ticketOrg = ticket ? ticket.organisationId : undefined;

  useEffect(() => {
    if (ticketOrg === undefined) return;
    const controller = new AbortController();
    listSupporters(ticketOrg, controller.signal).then(setSupporters, (e: unknown) =>
      report(e, "Agents could not be loaded."),
    );
    return () => controller.abort();
  }, [ticketOrg, report]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);

  // An email's files arrive a few seconds after its comment; re-read until they are in.
  const processing =
    ticket !== null &&
    [...ticket.publicComments, ...ticket.internalNotes].some((c) => c.attachmentsProcessing);
  useEffect(() => {
    if (!processing) return;
    const timer = setTimeout(reload, PROCESSING_REFRESH_MS);
    return () => clearTimeout(timer);
  }, [processing, version, reload]);

  async function act(action: () => Promise<unknown>, done: string) {
    try {
      await action();
      toast.show(done);
    } catch (e) {
      report(e, "That change was not saved.");
    }
    reload();
  }

  const back = (
    <Link to="/agent/queue" className={styles.backLink}>
      &lt; Back to the queue
    </Link>
  );

  if (loadedRef !== ref || load.kind === "loading") {
    return (
      <div className={styles.column}>
        {back}
        <p role="status">Loading ticket {ref}...</p>
      </div>
    );
  }
  if (load.kind === "missing" || load.kind === "error" || !ticket) {
    return (
      <div className={styles.column}>
        {back}
        <h1>Ticket {ref}</h1>
        <Card>
          <p role="alert">
            {load.kind === "error"
              ? load.message
              : "We could not find that ticket. It may not exist, or it belongs to an organisation you do not work on."}
          </p>
        </Card>
      </div>
    );
  }

  const t = ticket;
  const closed = t.status === "CLOSED";
  const isAdmin = role === "ADMIN";
  const triage = t.organisationId === null;
  const reporterEmail = t.reporter?.email ?? null;
  const to = reporterEmail
    ? t.reporter?.name
      ? `${t.reporter.name} <${reporterEmail}>`
      : reporterEmail
    : "";
  const cc = t.participants
    .filter((p) => p.confirmed && p.email && p.email.toLowerCase() !== reporterEmail?.toLowerCase())
    .map((p) => p.email)
    .join(", ");
  const unconfirmed = t.participants.filter((p) => !p.confirmed);
  const ccPerson = (p: TicketDetail["participants"][number]): CcPerson | null =>
    p.email && p.email.toLowerCase() !== reporterEmail?.toLowerCase()
      ? { email: p.email.toLowerCase(), name: p.name }
      : null;
  const ticketOrganisationId = t.organisationId;
  // CC is editable on a client ticket (not in Email triage, not when closed).
  const ccConfig: ReplyCcConfig | undefined =
    ticketOrganisationId === null || closed
      ? undefined
      : {
          initial: t.participants
            .filter((p) => p.confirmed)
            .map(ccPerson)
            .filter((p): p is CcPerson => p !== null),
          pending: unconfirmed.map(ccPerson).filter((p): p is CcPerson => p !== null),
          organisationName: t.organisationName ?? "this organisation",
          verifiedDomains:
            organisations.find((o) => o.id === ticketOrganisationId)?.verifiedDomains ?? [],
          searchContacts: (q, signal) =>
            listContacts({ q, organisationId: ticketOrganisationId }, signal).then((r) =>
              r.items
                .filter((c) => c.email.toLowerCase() !== reporterEmail?.toLowerCase())
                .map((c) => ({ email: c.email.toLowerCase(), name: c.name })),
            ),
          checkAddress: (email) => checkReplyCc(t.id, email),
        };
  const assigneeOptions: [string, string][] = [
    ["", "Unassigned"],
    ...supporters.map((s) => [String(s.id), supporterLabel(s, null)] as [string, string]),
  ];
  if (t.assignee?.userId && !supporters.some((s) => s.id === t.assignee?.userId)) {
    assigneeOptions.push([String(t.assignee.userId), personName(t.assignee, "Agent")]);
  }

  async function onSend(send: ComposerSend) {
    if (send.kind === "note") {
      await addInternalNote(t.id, send.html, send.attachmentIds);
      toast.show("Internal note added.");
    } else {
      await addPublicReply(
        t.id,
        send.html,
        send.attachmentIds,
        send.thenPendingOnClient ? "PENDING_ON_CLIENT" : undefined,
        send.cc,
      );
      toast.show(
        send.thenPendingOnClient
          ? "Reply sent and the ticket set to Pending on client."
          : "Reply sent to the client.",
      );
    }
    reload();
  }

  function moved(reference: string) {
    setDialog(null);
    toast.show(`Ticket moved - new reference ${reference}.`);
    navigate(`/agent/tickets/${encodeURIComponent(reference)}`);
  }

  return (
    <div className={styles.column}>
      {back}
      <div className={styles.split}>
        <div className={styles.column}>
          <div className={styles.ticketHead}>
            <div className={styles.ticketTitle}>
              <div className={styles.ticketMeta}>
                <span className={styles.ticketRef}>{t.reference}</span>
                <PriorityBadge priority={t.priority} long />
                <StatusBadge status={STATUS_LABELS[t.status]} />
                {t.sla && <SlaBadge state={t.sla.state} text={t.sla.text} />}
                <span className={styles.subtle}>Created {formatDateTime(t.createdAt)}</span>
              </div>
              <h1>{t.subject}</h1>
            </div>
            {t.slaPanel && (
              <SlaPanel
                key={t.slaPanel.asOf}
                ticketId={t.id}
                initial={t.slaPanel}
                className={styles.ticketSla}
              />
            )}
          </div>

          <section aria-label="Conversation" className={styles.conversation}>
            {conversation(t).map((entry) => {
              if (entry.type === "change") {
                return (
                  <TimelineLine
                    key={`h${entry.a.id}`}
                    text={entry.a.text}
                    at={entry.a.createdAt}
                    type={entry.a.type}
                  />
                );
              }
              if (entry.type === "system") {
                return (
                  <SystemEntryCard
                    key={`s${entry.e.id}`}
                    meta={`${systemKindLabel(entry.e.kind)} - ${formatDateTime(entry.e.createdAt)}`}
                  >
                    {systemEntryText(entry.e.kind, entry.e.text)}
                  </SystemEntryCard>
                );
              }
              const c = entry.c;
              if (entry.type === "note") {
                return (
                  <InternalNoteCard
                    key={`n${c.id}`}
                    author={personName(c.author, c.authorKind === "SYSTEM" ? "System" : "Agent")}
                    meta={`Internal note${c.receivedByEmail ? " (by email)" : ""} - ${formatDateTime(c.createdAt)}`}
                    attachment={attachmentsOf(c)}
                  >
                    <SafeHtml html={c.bodyHtml} />
                  </InternalNoteCard>
                );
              }
              return (
                <MessageCard
                  key={`p${c.id}`}
                  kind={c.authorKind === "SUPPORTER" ? "reply" : "client"}
                  author={personName(
                    c.author,
                    c.authorKind === "API_KEY" ? "Monitoring" : "Client",
                  )}
                  meta={publicMeta(c)}
                  attachment={attachmentsOf(c)}
                >
                  <SafeHtml html={c.bodyHtml} />
                </MessageCard>
              );
            })}
          </section>

          {tabPref === null || userId === undefined ? (
            <p role="status">Loading composer...</p>
          ) : (
            <Composer
              draftKey={userId === null ? null : draftKeyFor(userId, t.id)}
              to={to}
              cc={cc}
              ccConfig={ccConfig}
              defaultTab={tabPref}
              signatureHint="Your signature is added when the reply is emailed."
              replyDisabledReason={
                closed
                  ? "This ticket is closed, so it cannot get public replies. Add an internal note, or create a follow-up ticket."
                  : undefined
              }
              savedReplies={savedReplies}
              renderSavedReply={(id) => renderSavedReply(t.id, id)}
              upload={uploadAttachment}
              onSend={onSend}
            />
          )}
        </div>

        <aside className={styles.details} aria-label="Ticket details">
          <Card title="Details">
            <SidebarSelect
              id="ticket-status"
              label="Status"
              value={t.status}
              disabled={t.allowedStatuses.length === 0}
              options={[t.status, ...t.allowedStatuses].map(
                (s) => [s, STATUS_LABELS[s as TicketStatus]] as [string, string],
              )}
              onChange={(v) => {
                // Closed needs a reason, so it opens the close dialog; the select stays
                // on the current status until the ticket is really closed.
                if (v === "CLOSED") {
                  setDialog("close");
                  return;
                }
                void act(
                  () => setStatus(t.id, v as TicketStatus),
                  `Status set to ${STATUS_LABELS[v as TicketStatus]}.`,
                );
              }}
            />
            <SidebarSelect
              id="ticket-priority"
              label="Priority"
              value={t.priority}
              disabled={closed}
              options={Object.entries(PRIORITY_LABELS) as [string, string][]}
              onChange={(v) =>
                void act(
                  () => setPriority(t.id, v as Priority),
                  `Priority set to ${PRIORITY_LABELS[v as Priority]}.`,
                )
              }
            />
            <SidebarSelect
              id="ticket-assignee"
              label="Assignee"
              value={t.assignee?.userId ? String(t.assignee.userId) : ""}
              disabled={closed}
              options={assigneeOptions}
              onChange={(v) =>
                void act(
                  () => setAssignee(t.id, v ? Number(v) : null),
                  v ? "Assignee changed." : "Ticket unassigned.",
                )
              }
            />
            <SidebarSelect
              id="ticket-request-type"
              label="Request type"
              value={String(t.requestType.id)}
              disabled={closed}
              options={(requestTypes.some((r) => r.id === t.requestType.id)
                ? requestTypes
                : [t.requestType, ...requestTypes]
              ).map((r) => [String(r.id), r.name] as [string, string])}
              onChange={(v) =>
                void act(() => setRequestType(t.id, Number(v)), "Request type changed.")
              }
            />
            {!triage && (
              <OrganisationField
                key={`${t.id}-${t.organisationId}`}
                ticket={t}
                organisations={organisations}
                disabled={closed}
                onMoved={moved}
              />
            )}
            <dl className={styles.facts}>
              {triage && <Field label="Organisation">Email triage</Field>}
              <Field label="Reporter">{personName(t.reporter, "Unknown sender")}</Field>
              {t.reporter?.email && t.reporter.name && (
                <Field label="Reporter email">
                  <Identifier value={t.reporter.email} />
                </Field>
              )}
              <Field label="Client impact">
                {[
                  t.impact ? IMPACT_LABELS[t.impact] : null,
                  t.urgency ? URGENCY_LABELS[t.urgency] : null,
                ]
                  .filter(Boolean)
                  .join(", ") || "Not given"}
              </Field>
              <Field label="Source">{t.source.toLowerCase()}</Field>
              {closed && t.closeReasonLabel && (
                <Field label="Close reason">
                  {t.closeReasonLabel}
                  {t.closeNote ? ` - ${t.closeNote}` : ""}
                </Field>
              )}
            </dl>
            {!closed && triage && (
              <Button
                variant="primary"
                className={styles.actionButton}
                onClick={() => setDialog("triage")}
              >
                Assign to organisation
              </Button>
            )}
          </Card>

          <Card title="References">
            <ul className={styles.refList} aria-label="References">
              <li>
                <strong>{t.reference}</strong> (primary)
              </li>
              {t.aliases.map((a) => (
                <li key={a}>{a}</li>
              ))}
            </ul>
          </Card>

          <Card title="Participants">
            {t.participants.length === 0 ? (
              <p className={styles.subtle}>Only the reporter.</p>
            ) : (
              <ul className={styles.refList}>
                {t.participants.map((p) => (
                  <li key={`${p.userId ?? ""}${p.email ?? ""}`}>
                    {p.name && p.email ? (
                      <>
                        {p.name} (<Identifier value={p.email} />)
                      </>
                    ) : p.email ? (
                      <Identifier value={p.email} />
                    ) : (
                      (p.name ?? `User ${p.userId}`)
                    )}
                    {!p.confirmed && " (waiting for confirmation)"}
                  </li>
                ))}
              </ul>
            )}
            {unconfirmed.length > 0 && (
              <p className={styles.subtle}>
                Unconfirmed participants get no email until an agent confirms them.
              </p>
            )}
          </Card>

          <Card title="Linked tickets">
            {t.duplicateOf || t.followUpOf || t.links.length > 0 ? (
              <ul className={styles.refList}>
                {t.duplicateOf && (
                  <li>
                    Duplicate of{" "}
                    <Link to={`/agent/tickets/${encodeURIComponent(t.duplicateOf.reference)}`}>
                      {t.duplicateOf.reference}
                    </Link>
                  </li>
                )}
                {t.followUpOf && (
                  <li>
                    Follow-up to{" "}
                    <Link to={`/agent/tickets/${encodeURIComponent(t.followUpOf.reference)}`}>
                      {t.followUpOf.reference}
                    </Link>
                  </li>
                )}
                {t.links.map((l) => (
                  <li key={`${l.kind}${l.ticketId}`}>
                    {l.kind.toLowerCase().replace(/_/g, " ")}{" "}
                    <Link to={`/agent/tickets/${encodeURIComponent(l.reference)}`}>
                      {l.reference}
                    </Link>
                  </li>
                ))}
              </ul>
            ) : (
              <p className={styles.subtle}>None.</p>
            )}
          </Card>

          <Card title="Actions">
            {!closed && !triage && (
              <>
                <Button className={styles.actionButton} onClick={() => setDialog("duplicate")}>
                  Mark as duplicate of...
                </Button>
                <Button className={styles.actionButton} onClick={() => setDialog("link")}>
                  Link related ticket
                </Button>
              </>
            )}
            {closed && isAdmin && !t.duplicateOf && (
              <Button className={styles.actionButton} onClick={() => setDialog("reopen")}>
                Reopen ticket
              </Button>
            )}
            {closed && (
              <Button
                className={styles.actionButton}
                onClick={() =>
                  void createFollowUp(t.id).then(
                    (r) => {
                      toast.show(`Follow-up ${r.reference} created.`);
                      navigate(`/agent/tickets/${encodeURIComponent(r.reference)}`);
                    },
                    (e: unknown) => report(e, "The follow-up ticket was not created."),
                  )
                }
              >
                Create follow-up ticket
              </Button>
            )}
            {triage && !closed && (
              <p className={styles.subtle}>Assign the ticket to an organisation first.</p>
            )}
          </Card>
          {/* No separate History list - every change is a line in the timeline. */}
        </aside>
      </div>

      {dialog === "triage" && (
        <TriageAssignDialog
          ticket={t}
          organisations={organisations}
          onClose={() => setDialog(null)}
          onDone={moved}
        />
      )}
      {dialog === "duplicate" && (
        <ReferenceDialog
          title="Mark as duplicate"
          intro={`${t.reference} will be closed without an email to the client, linked to the other ticket, and its reference will lead to the other ticket from now on. Both must belong to the same organisation.`}
          submitLabel="Mark as duplicate"
          onClose={() => setDialog(null)}
          onSubmit={async (reference) => {
            await markDuplicate(t.id, reference);
            setDialog(null);
            toast.show(`${t.reference} marked as a duplicate of ${reference}.`);
            reload();
          }}
        />
      )}
      {dialog === "close" && (
        <CloseTicketDialog
          ticket={t}
          canBlock={isAdmin}
          onCancel={() => setDialog(null)}
          onDuplicate={() => setDialog("duplicate")}
          onClosed={(message) => {
            setDialog(null);
            toast.show(message);
            reload();
          }}
        />
      )}
      {dialog === "reopen" && (
        <ReopenTicketDialog
          ticket={t}
          runStepUp={stepUp.run}
          onCancel={() => setDialog(null)}
          onReopened={() => {
            setDialog(null);
            toast.show(`${t.reference} reopened.`);
            reload();
          }}
        />
      )}
      {stepUp.dialog}
      {dialog === "link" && (
        <ReferenceDialog
          title="Link related ticket"
          intro="Links the two tickets both ways without closing either. Both must belong to the same organisation."
          submitLabel="Link tickets"
          onClose={() => setDialog(null)}
          onSubmit={async (reference) => {
            await linkRelated(t.id, reference);
            setDialog(null);
            toast.show(`Linked to ${reference}.`);
            reload();
          }}
        />
      )}
    </div>
  );
}
