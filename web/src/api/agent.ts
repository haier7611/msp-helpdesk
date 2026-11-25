import type { TicketStatus as StatusLabel } from "../mocks/types";
import { apiFetch, apiFetchWithHeaders } from "./client";

/**
 * Agent API (/api/v1/agent; documented in api/src/main/resources/openapi/
 * tickets.yaml). Agents and admins only - the server enforces the role gate and organisation scope
 * on every call; another organisation's ticket answers 404 like a missing one.
 */

export type Priority = "P1" | "P2" | "P3" | "P4";

export type TicketStatus =
  "NEW" | "OPEN" | "PENDING_ON_CLIENT" | "PENDING_ON_VENDOR" | "RESOLVED" | "CLOSED";

export const STATUS_LABELS: Record<TicketStatus, StatusLabel> = {
  NEW: "New",
  OPEN: "Open",
  PENDING_ON_CLIENT: "Pending on client",
  PENDING_ON_VENDOR: "Pending on vendor",
  RESOLVED: "Resolved",
  CLOSED: "Closed",
};

export const PRIORITY_LABELS: Record<Priority, string> = {
  P1: "P1 Critical",
  P2: "P2 High",
  P3: "P3 Medium",
  P4: "P4 Low",
};

export const IMPACT_LABELS: Record<string, string> = {
  JUST_ME: "Just me",
  A_FEW_PEOPLE: "A few people",
  WHOLE_OFFICE: "Whole office",
};

export const URGENCY_LABELS: Record<string, string> = {
  NUISANCE: "Nuisance",
  PARTLY: "Partly",
  WORK_STOPPED: "Work stopped",
};

/** SLA state, only present when the queue row has SLA data; otherwise the badge is hidden. */
export type SlaState = "breached" | "risk" | "ok" | "paused";

export type TicketSummary = {
  id: number;
  reference: string;
  organisationId: number | null;
  organisationName: string | null;
  subject: string;
  status: TicketStatus;
  priority: Priority;
  source: string;
  assigneeUserId: number | null;
  assigneeName: string | null;
  reporterName: string | null;
  reporterEmail: string | null;
  requestTypeName: string | null;
  createdAt: string;
  updatedAt: string;
  sla?: { state: SlaState; text?: string };
  /** The queue's First response column, worked out by the server; absent = no SLA yet. */
  firstResponseTimer?: FirstResponseTimer;
};

/** Where a queue row's first response stands (server-computed at `asOf`). */
export type FirstResponseTimer = {
  state: SlaPanelState;
  /** Business minutes left, rounded up; null when unknown. */
  remainingMinutes: number | null;
  breachedByMinutes: number | null;
  dueAt: string | null;
  /** One day on the priority's clock (540 business hours, 1440 for 24/7); 0 when unknown. */
  businessDayMinutes: number;
  asOf: string;
};

export type Person = { userId: number | null; name: string | null; email: string | null };

export type Attachment = {
  id: string;
  filename: string;
  size: number;
  contentType: string;
  image: boolean;
  inline: boolean;
  internal: boolean;
};

export type CommentEntry = {
  id: number;
  authorKind: string;
  author: Person | null;
  bodyHtml: string;
  receivedByEmail: boolean;
  createdAt: string;
  attachments: Attachment[];
  /** An email's files are still being processed ("Processing attachments..."). */
  attachmentsProcessing: boolean;
};

export type SystemEntry = { id: number; kind: string; text: string; createdAt: string };

/** An assignee change, shown as a one-line entry in the timeline (agent-only). */
export type AssignmentEntry = {
  id: number;
  fromUserId: number | null;
  toUserId: number | null;
  actorUserId: number | null;
  reason: string | null;
  text: string;
  createdAt: string;
};

/**
 * Any ticket change (status, priority, request type, assignment, organisation, duplicate,
 * link, follow-up, participant, reporter), shown as a one-line entry in the timeline (agent-only).
 * `text` is the server's ready wording, with names resolved for the viewer.
 */
export type TimelineEntry = {
  id: number;
  type: string;
  actorKind: string;
  actorUserId: number | null;
  text: string;
  createdAt: string;
};

export type TicketRef = { ticketId: number; reference: string };

/** The CC an agent chose in the composer (the server re-checks every rule). */
export type ReplyCcChoice = {
  recipients: string[];
  keepOnFutureReplies: boolean;
  confirmExternal: string[];
};

/**
 * Why a ticket was closed. The first five can be chosen in the close dialog; DUPLICATE
 * goes through "Mark as duplicate"; AUTO_CLOSED_NO_REPLY is the automatic Resolved -> Closed job.
 */
export type CloseReason =
  | "DUPLICATE"
  | "SPAM"
  | "RESOLVED_NO_FOLLOW_UP"
  | "CLIENT_CANCELLED"
  | "NO_RESPONSE"
  | "OTHER"
  | "AUTO_CLOSED_NO_REPLY";

/** The close dialog's choices, in order (labels match the server's timeline wording). */
export const CLOSE_REASON_CHOICES: readonly { value: CloseReason; label: string }[] = [
  { value: "DUPLICATE", label: "Duplicate" },
  { value: "SPAM", label: "Spam / junk" },
  { value: "RESOLVED_NO_FOLLOW_UP", label: "Resolved - no follow-up needed" },
  { value: "CLIENT_CANCELLED", label: "Client cancelled / no longer needed" },
  { value: "NO_RESPONSE", label: "No response from client" },
  { value: "OTHER", label: "Other" },
];

/** Note limits for Other and for an admin's reopen reason (the server checks them too). */
export const CLOSE_NOTE_MIN = 3;
export const CLOSE_NOTE_MAX = 500;

export type TicketDetail = {
  id: number;
  reference: string;
  aliases: string[];
  organisationId: number | null;
  organisationName: string | null;
  subject: string;
  status: TicketStatus;
  statusLabel: string;
  /** Agent-only: the reason of the current close, null when not closed. */
  closeReason: string | null;
  closeReasonLabel: string | null;
  closeNote: string | null;
  allowedStatuses: TicketStatus[];
  priority: Priority;
  impact: string | null;
  urgency: string | null;
  source: string;
  requestType: { id: number; name: string; defaultPriority: Priority };
  reporter: Person | null;
  assignee: Person | null;
  duplicateOf: TicketRef | null;
  followUpOf: TicketRef | null;
  links: { ticketId: number; reference: string; kind: string }[];
  participants: {
    userId: number | null;
    email: string | null;
    confirmed: boolean;
    /** A contact's current name; null for an address-only participant. */
    name: string | null;
  }[];
  publicComments: CommentEntry[];
  internalNotes: CommentEntry[];
  systemEntries: SystemEntry[];
  assignmentEntries: AssignmentEntry[];
  timelineEntries: TimelineEntry[];
  createdAt: string;
  updatedAt: string;
  /** Same chip as the queue row; null when the ticket has no SLA (e.g. Email triage). */
  sla: { state: SlaState; text?: string } | null;
  /** Live SLA panel (agent-only); null for Email triage. */
  slaPanel: SlaPanelData | null;
};

/** Where one SLA target stands, as the server worked it out at `asOf`. */
export type SlaPanelState =
  "RUNNING" | "PAUSED" | "STOPPED_OUTSIDE_HOURS" | "MET" | "BREACHED" | "NOT_APPLICABLE";

export type SlaPanelTarget = {
  state: SlaPanelState;
  targetMinutes: number;
  dueAt: string | null;
  warnAt: string | null;
  /** Rounded up; RUNNING, PAUSED and STOPPED_OUTSIDE_HOURS only. */
  remainingBusinessMinutes: number | null;
  usedMinutes: number;
  metAt: string | null;
  breachedByMinutes: number | null;
  nextClockStart: string | null;
  /** RUNNING on business hours: when today's hours end (the page re-syncs then). */
  clockStopsAt: string | null;
  pausedReason: "CLIENT" | "VENDOR" | null;
};

export type SlaPanelData = {
  asOf: string;
  clock: "TWENTY_FOUR_SEVEN" | "BUSINESS_HOURS";
  timezone: string;
  priority: Priority;
  policyLabel: string;
  pendingOnVendorPauses: boolean;
  businessDayMinutes: number;
  firstResponse: SlaPanelTarget;
  resolution: SlaPanelTarget;
};

export type RequestType = { id: number; name: string; defaultPriority: Priority };
export type Supporter = {
  id: number;
  name: string;
  email: string;
  outOfOffice: boolean;
  outOfOfficeUntil?: string;
};
export type AgentOrganisation = {
  id: number;
  name: string;
  prefix: string | null;
  verifiedDomains: string[];
};
export type Contact = {
  id: number;
  name: string;
  email: string;
  organisationId: number;
  organisationName: string | null;
  status: string;
};
export type SavedReply = { id: number; title: string; bodyHtml: string; shared: boolean };
export type TriageOptions = {
  senderEmail: string | null;
  senderDomain: string | null;
  domainOffered: boolean;
  contactExists: boolean;
};
export type Uploaded = {
  id: string;
  filename: string;
  size: number;
  contentType: string;
  image: boolean;
};

/** Upload limit; the server refuses bigger files too. */
export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

const BASE = "/api/v1/agent";

function shapeError(): Error {
  return new Error("API answered with an unexpected shape");
}

function obj(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw shapeError();
  return value as Record<string, unknown>;
}

function list<T>(parse: (v: unknown) => T): (value: unknown) => T[] {
  return (value) => {
    if (!Array.isArray(value)) throw shapeError();
    return value.map(parse);
  };
}

function str(v: unknown): string | null {
  return typeof v === "string" ? v : null;
}

function num(v: unknown): number | null {
  return typeof v === "number" ? v : null;
}

function requireNum(v: unknown): number {
  if (typeof v !== "number") throw shapeError();
  return v;
}

function requireStr(v: unknown): string {
  if (typeof v !== "string") throw shapeError();
  return v;
}

const SLA_STATES = new Set(["breached", "risk", "ok", "paused"]);

export function parseSummary(value: unknown): TicketSummary {
  const v = obj(value);
  const summary: TicketSummary = {
    id: requireNum(v.id),
    reference: requireStr(v.reference),
    organisationId: num(v.organisationId),
    organisationName: str(v.organisationName),
    subject: requireStr(v.subject),
    status: requireStr(v.status) as TicketStatus,
    priority: requireStr(v.priority) as Priority,
    source: str(v.source) ?? "",
    assigneeUserId: num(v.assigneeUserId),
    assigneeName: str(v.assigneeName),
    reporterName: str(v.reporterName),
    reporterEmail: str(v.reporterEmail),
    requestTypeName: str(v.requestTypeName),
    createdAt: requireStr(v.createdAt),
    updatedAt: requireStr(v.updatedAt),
  };
  const sla = parseSlaChip(v);
  if (sla) summary.sla = sla;
  const timer = parseFirstResponseTimer(v.firstResponseTimer);
  if (timer) summary.firstResponseTimer = timer;
  return summary;
}

/** The queue's first-response timer; null when absent or not understood (the column then shows a dash). */
function parseFirstResponseTimer(value: unknown): FirstResponseTimer | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const t = value as Record<string, unknown>;
  if (typeof t.state !== "string" || !PANEL_STATES.has(t.state)) return null;
  return {
    state: t.state as SlaPanelState,
    remainingMinutes: num(t.remainingMinutes),
    breachedByMinutes: num(t.breachedByMinutes),
    dueAt: str(t.dueAt),
    businessDayMinutes: num(t.businessDayMinutes) ?? 0,
    asOf: str(t.asOf) ?? "",
  };
}

/** The SLA chip of a ticket (queue row or ticket page), or null when it has no SLA. */
function parseSlaChip(v: Record<string, unknown>): { state: SlaState; text?: string } | null {
  const sla = v.sla && typeof v.sla === "object" ? obj(v.sla) : null;
  if (sla) {
    const first = sla.firstResponse ? obj(sla.firstResponse) : {};
    const resolution = sla.resolution ? obj(sla.resolution) : {};
    const warning = str(sla.warning);
    let state: SlaState = "ok";
    if (warning === "BREACHED" || first.state === "BREACHED" || resolution.state === "BREACHED") {
      state = "breached";
    } else if (warning === "AT_RISK") {
      state = "risk";
    } else if (resolution.state === "PAUSED" || first.state === "PAUSED") {
      state = "paused";
    }
    return { state };
  }
  if (typeof v.slaState === "string" && SLA_STATES.has(v.slaState.toLowerCase())) {
    return { state: v.slaState.toLowerCase() as SlaState, text: str(v.slaText) ?? undefined };
  }
  return null;
}

function parsePerson(value: unknown): Person | null {
  if (value === null || value === undefined) return null;
  const v = obj(value);
  return { userId: num(v.userId), name: str(v.name), email: str(v.email) };
}

function parseAttachment(value: unknown): Attachment {
  const v = obj(value);
  return {
    id: requireStr(v.id),
    filename: requireStr(v.filename),
    size: num(v.size) ?? 0,
    contentType: str(v.contentType) ?? "",
    image: v.image === true,
    inline: v.inline === true,
    internal: v.internal === true,
  };
}

function parseComment(value: unknown): CommentEntry {
  const v = obj(value);
  return {
    id: requireNum(v.id),
    authorKind: str(v.authorKind) ?? "",
    author: parsePerson(v.author),
    bodyHtml: str(v.bodyHtml) ?? "",
    receivedByEmail: v.receivedByEmail === true,
    createdAt: requireStr(v.createdAt),
    attachments: Array.isArray(v.attachments) ? v.attachments.map(parseAttachment) : [],
    attachmentsProcessing: v.attachmentsProcessing === true,
  };
}

function parseRef(value: unknown): TicketRef | null {
  if (value === null || value === undefined) return null;
  const v = obj(value);
  return { ticketId: requireNum(v.ticketId), reference: requireStr(v.reference) };
}

function arr(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

const PANEL_STATES = new Set<string>([
  "RUNNING",
  "PAUSED",
  "STOPPED_OUTSIDE_HOURS",
  "MET",
  "BREACHED",
  "NOT_APPLICABLE",
]);

function parsePanelTarget(value: unknown): SlaPanelTarget {
  const v = obj(value);
  const state = requireStr(v.state);
  if (!PANEL_STATES.has(state)) throw shapeError();
  const reason = str(v.pausedReason);
  return {
    state: state as SlaPanelState,
    targetMinutes: requireNum(v.targetMinutes),
    dueAt: str(v.dueAt),
    warnAt: str(v.warnAt),
    remainingBusinessMinutes: num(v.remainingBusinessMinutes),
    usedMinutes: num(v.usedMinutes) ?? 0,
    metAt: str(v.metAt),
    breachedByMinutes: num(v.breachedByMinutes),
    nextClockStart: str(v.nextClockStart),
    clockStopsAt: str(v.clockStopsAt),
    pausedReason: reason === "CLIENT" || reason === "VENDOR" ? reason : null,
  };
}

/** The SLA panel; null when absent (triage). */
export function parseSlaPanel(value: unknown): SlaPanelData | null {
  if (value === null || value === undefined) return null;
  const v = obj(value);
  return {
    asOf: requireStr(v.asOf),
    clock: v.clock === "TWENTY_FOUR_SEVEN" ? "TWENTY_FOUR_SEVEN" : "BUSINESS_HOURS",
    timezone: str(v.timezone) ?? "",
    priority: requireStr(v.priority) as Priority,
    policyLabel: str(v.policyLabel) ?? "",
    pendingOnVendorPauses: v.pendingOnVendorPauses === true,
    businessDayMinutes: num(v.businessDayMinutes) ?? 1440,
    firstResponse: parsePanelTarget(v.firstResponse),
    resolution: parsePanelTarget(v.resolution),
  };
}

export function parseDetail(value: unknown): TicketDetail {
  const v = obj(value);
  const type = obj(v.requestType);
  return {
    sla: parseSlaChip(v),
    slaPanel: parseSlaPanel(v.slaPanel),
    id: requireNum(v.id),
    reference: requireStr(v.reference),
    aliases: arr(v.aliases).filter((a): a is string => typeof a === "string"),
    organisationId: num(v.organisationId),
    organisationName: str(v.organisationName),
    subject: requireStr(v.subject),
    status: requireStr(v.status) as TicketStatus,
    statusLabel: str(v.statusLabel) ?? "",
    closeReason: str(v.closeReason),
    closeReasonLabel: str(v.closeReasonLabel),
    closeNote: str(v.closeNote),
    allowedStatuses: arr(v.allowedStatuses).filter((s): s is TicketStatus => typeof s === "string"),
    priority: requireStr(v.priority) as Priority,
    impact: str(v.impact),
    urgency: str(v.urgency),
    source: str(v.source) ?? "",
    requestType: {
      id: requireNum(type.id),
      name: requireStr(type.name),
      defaultPriority: requireStr(type.defaultPriority) as Priority,
    },
    reporter: parsePerson(v.reporter),
    assignee: parsePerson(v.assignee),
    duplicateOf: parseRef(v.duplicateOf),
    followUpOf: parseRef(v.followUpOf),
    links: arr(v.links).map((l) => {
      const o = obj(l);
      return {
        ticketId: requireNum(o.ticketId),
        reference: requireStr(o.reference),
        kind: str(o.kind) ?? "",
      };
    }),
    participants: arr(v.participants).map((p) => {
      const o = obj(p);
      return {
        userId: num(o.userId),
        email: str(o.email),
        confirmed: o.confirmed === true,
        name: str(o.name),
      };
    }),
    publicComments: arr(v.publicComments).map(parseComment),
    internalNotes: arr(v.internalNotes).map(parseComment),
    systemEntries: arr(v.systemEntries).map((e) => {
      const o = obj(e);
      return {
        id: requireNum(o.id),
        kind: str(o.kind) ?? "",
        text: str(o.text) ?? "",
        createdAt: requireStr(o.createdAt),
      };
    }),
    assignmentEntries: arr(v.assignmentEntries).map((a) => {
      const o = obj(a);
      return {
        id: requireNum(o.id),
        fromUserId: num(o.fromUserId),
        toUserId: num(o.toUserId),
        actorUserId: num(o.actorUserId),
        reason: str(o.reason),
        text: str(o.text) ?? "",
        createdAt: requireStr(o.createdAt),
      };
    }),
    timelineEntries: arr(v.timelineEntries).map((e) => {
      const o = obj(e);
      return {
        id: requireNum(o.id),
        type: str(o.type) ?? "",
        actorKind: str(o.actorKind) ?? "",
        actorUserId: num(o.actorUserId),
        text: str(o.text) ?? "",
        createdAt: requireStr(o.createdAt),
      };
    }),
    createdAt: requireStr(v.createdAt),
    updatedAt: requireStr(v.updatedAt),
  };
}

function parseContact(value: unknown): Contact {
  const v = obj(value);
  return {
    id: requireNum(v.id),
    name: requireStr(v.name),
    email: requireStr(v.email),
    organisationId: requireNum(v.organisationId),
    organisationName: str(v.organisationName),
    status: str(v.status) ?? "",
  };
}

function ignore(): void {
  return undefined;
}

// --- Queue --------------------------------------------------------------------------------------

/** Queue filters; they live in the URL so a view can be bookmarked and shared. */
export type QueueFilter = {
  status?: string;
  organisationId?: string;
  triage?: boolean;
  excludeTriage?: boolean;
  assignee?: string;
  priority?: string;
  q?: string;
  sort?: string;
  includeClosed?: boolean;
};

export const PAGE_SIZE = 50;

export function filterParams(filter: QueueFilter): URLSearchParams {
  const p = new URLSearchParams();
  if (filter.status) p.set("status", filter.status);
  if (filter.organisationId) p.set("organisationId", filter.organisationId);
  if (filter.triage) p.set("triage", "true");
  if (filter.excludeTriage) p.set("excludeTriage", "true");
  if (filter.assignee) p.set("assignee", filter.assignee);
  if (filter.priority) p.set("priority", filter.priority);
  if (filter.q) p.set("q", filter.q);
  if (filter.sort) p.set("sort", filter.sort);
  if (filter.includeClosed) p.set("includeClosed", "true");
  return p;
}

export async function listTickets(
  filter: QueueFilter,
  page: number,
  signal?: AbortSignal,
): Promise<{ rows: TicketSummary[]; total: number }> {
  const p = filterParams(filter);
  p.set("page", String(page));
  p.set("limit", String(PAGE_SIZE));
  const { body, headers } = await apiFetchWithHeaders(
    `${BASE}/tickets?${p.toString()}`,
    list(parseSummary),
    { signal },
  );
  const total = Number(headers.get("X-Total-Count"));
  return { rows: body, total: Number.isFinite(total) && total >= 0 ? total : body.length };
}

/** The CSV export URL for the same filter (a plain download; the server audit-logs it). */
export function exportUrl(filter: QueueFilter): string {
  const p = filterParams(filter).toString();
  return `${BASE}/tickets/export${p ? `?${p}` : ""}`;
}

// --- Ticket -------------------------------------------------------------------------------------

export function resolveReference(reference: string, signal?: AbortSignal): Promise<TicketRef> {
  return apiFetch(
    `${BASE}/tickets/by-reference/${encodeURIComponent(reference)}`,
    (v) => {
      const o = obj(v);
      return { ticketId: requireNum(o.ticketId), reference: requireStr(o.reference) };
    },
    { signal },
  );
}

export function getTicket(id: number, signal?: AbortSignal): Promise<TicketDetail> {
  return apiFetch(`${BASE}/tickets/${id}`, parseDetail, { signal });
}

/** The live SLA panel alone, for the ticket page's periodic re-sync. */
export function getSlaPanel(id: number, signal?: AbortSignal): Promise<SlaPanelData | null> {
  return apiFetch(`${BASE}/tickets/${id}/sla`, parseSlaPanel, { signal });
}

export type NewTicket = {
  organisationId: number;
  reporterUserId: number;
  requestTypeId: number;
  priority?: Priority;
  subject: string;
  descriptionHtml: string;
  attachmentIds: string[];
  assigneeUserId?: number;
};

export function createTicket(body: NewTicket): Promise<TicketRef> {
  return apiFetch(
    `${BASE}/tickets`,
    (v) => {
      const o = obj(v);
      return { ticketId: requireNum(o.id), reference: requireStr(o.reference) };
    },
    { method: "POST", body },
  );
}

export function setStatus(id: number, status: TicketStatus): Promise<void> {
  return apiFetch(`${BASE}/tickets/${id}/status`, ignore, { method: "POST", body: { status } });
}

/** Closes a ticket with a reason (and the note for Other). */
export function closeTicket(
  id: number,
  closeReason: CloseReason,
  closeNote?: string,
): Promise<void> {
  return apiFetch(`${BASE}/tickets/${id}/status`, ignore, {
    method: "POST",
    body: { status: "CLOSED", closeReason, closeNote: closeNote ?? null },
  });
}

/** An admin reopens a closed ticket (step-up; the caller wraps it in useStepUp). */
export function reopenTicket(id: number, reason: string): Promise<void> {
  return apiFetch(`/api/v1/admin/tickets/${id}/reopen`, ignore, {
    method: "POST",
    body: { reason },
  });
}

export function setPriority(id: number, priority: Priority): Promise<void> {
  return apiFetch(`${BASE}/tickets/${id}/priority`, ignore, {
    method: "POST",
    body: { priority },
  });
}

export function setRequestType(id: number, requestTypeId: number): Promise<void> {
  return apiFetch(`${BASE}/tickets/${id}/request-type`, ignore, {
    method: "POST",
    body: { requestTypeId },
  });
}

export function setAssignee(id: number, assigneeUserId: number | null): Promise<void> {
  return apiFetch(`${BASE}/tickets/${id}/assignee`, ignore, {
    method: "POST",
    body: { assigneeUserId },
  });
}

export function changeOrganisation(id: number, organisationId: number): Promise<string> {
  return apiFetch(`${BASE}/tickets/${id}/organisation`, (v) => requireStr(obj(v).reference), {
    method: "POST",
    body: { organisationId },
  });
}

export function addPublicReply(
  id: number,
  bodyHtml: string,
  attachmentIds: string[],
  thenStatus?: TicketStatus,
  cc?: ReplyCcChoice,
): Promise<void> {
  return apiFetch(`${BASE}/tickets/${id}/public-replies`, ignore, {
    method: "POST",
    body: { bodyHtml, attachmentIds, thenStatus: thenStatus ?? null, ...(cc ? { cc } : {}) },
  });
}

/** How the server would treat `email` as a CC on this ticket (code only). */
export function checkReplyCc(
  id: number,
  email: string,
): Promise<"allowed" | "external" | "staff" | "not_allowed"> {
  return apiFetch(
    `${BASE}/tickets/${id}/cc-check`,
    (v) => {
      const r = obj(v).result;
      if (r === "allowed" || r === "external" || r === "staff" || r === "not_allowed") return r;
      throw new Error("Unexpected CC check answer.");
    },
    { method: "POST", body: { email } },
  );
}

export function addInternalNote(
  id: number,
  bodyHtml: string,
  attachmentIds: string[],
): Promise<void> {
  return apiFetch(`${BASE}/tickets/${id}/internal-notes`, ignore, {
    method: "POST",
    body: { bodyHtml, attachmentIds },
  });
}

export function markDuplicate(id: number, reference: string): Promise<void> {
  return apiFetch(`${BASE}/tickets/${id}/duplicate-of`, ignore, {
    method: "POST",
    body: { reference },
  });
}

export function linkRelated(id: number, reference: string): Promise<void> {
  return apiFetch(`${BASE}/tickets/${id}/links`, ignore, { method: "POST", body: { reference } });
}

export function createFollowUp(id: number): Promise<TicketRef> {
  return apiFetch(
    `${BASE}/tickets/${id}/follow-ups`,
    (v) => {
      const o = obj(v);
      return { ticketId: requireNum(o.id), reference: requireStr(o.reference) };
    },
    { method: "POST" },
  );
}

export function getTriageOptions(id: number, signal?: AbortSignal): Promise<TriageOptions> {
  return apiFetch(
    `${BASE}/tickets/${id}/triage-options`,
    (v) => {
      const o = obj(v);
      return {
        senderEmail: str(o.senderEmail),
        senderDomain: str(o.senderDomain),
        domainOffered: o.domainOffered === true,
        contactExists: o.contactExists === true,
      };
    },
    { signal },
  );
}

export function assignFromTriage(
  id: number,
  body: { organisationId: number; saveContact: boolean; addDomain: boolean },
): Promise<string> {
  return apiFetch(`${BASE}/tickets/${id}/triage-assignment`, (v) => requireStr(obj(v).reference), {
    method: "POST",
    body,
  });
}

export function uploadAttachment(file: File): Promise<Uploaded> {
  const form = new FormData();
  form.append("file", file);
  return apiFetch(
    `${BASE}/attachments`,
    (v) => {
      const o = obj(v);
      return {
        id: requireStr(o.id),
        filename: requireStr(o.filename),
        size: num(o.size) ?? 0,
        contentType: str(o.contentType) ?? "",
        image: o.image === true,
      };
    },
    { method: "POST", body: form },
  );
}

// --- Saved replies ------------------------------------------------------------------------------

export function listSavedReplies(signal?: AbortSignal): Promise<SavedReply[]> {
  return apiFetch(
    `${BASE}/saved-replies`,
    list((v) => {
      const o = obj(v);
      return {
        id: requireNum(o.id),
        title: requireStr(o.title),
        bodyHtml: str(o.bodyHtml) ?? "",
        shared: o.shared === true,
      };
    }),
    { signal },
  );
}

/** The saved reply with its placeholders filled in for this ticket (values HTML-escaped). */
export function renderSavedReply(ticketId: number, replyId: number): Promise<string> {
  return apiFetch(
    `${BASE}/tickets/${ticketId}/saved-replies/${replyId}/render`,
    (v) => requireStr(obj(v).bodyHtml),
    { method: "POST" },
  );
}

// --- People and organisations ------------------------------------------------------------------

export function listRequestTypes(signal?: AbortSignal): Promise<RequestType[]> {
  return apiFetch(
    `${BASE}/request-types`,
    list((v) => {
      const o = obj(v);
      return {
        id: requireNum(o.id),
        name: requireStr(o.name),
        defaultPriority: requireStr(o.defaultPriority) as Priority,
      };
    }),
    { signal },
  );
}

export function listSupporters(
  organisationId: number | null,
  signal?: AbortSignal,
): Promise<Supporter[]> {
  const q = organisationId === null ? "" : `?organisationId=${organisationId}`;
  return apiFetch(
    `${BASE}/supporters${q}`,
    list((v) => {
      const o = obj(v);
      return {
        id: requireNum(o.id),
        name: requireStr(o.name),
        email: str(o.email) ?? "",
        outOfOffice: o.outOfOffice === true,
        outOfOfficeUntil: str(o.outOfOfficeUntil) ?? undefined,
      };
    }),
    { signal },
  );
}

export function listAgentOrganisations(signal?: AbortSignal): Promise<AgentOrganisation[]> {
  return apiFetch(
    `${BASE}/organisations`,
    list((v) => {
      const o = obj(v);
      return {
        id: requireNum(o.id),
        name: requireStr(o.name),
        prefix: str(o.prefix),
        verifiedDomains: arr(o.verifiedDomains).filter((d): d is string => typeof d === "string"),
      };
    }),
    { signal },
  );
}

export function listContacts(
  filter: { q?: string; organisationId?: number | null; page?: number },
  signal?: AbortSignal,
): Promise<{ items: Contact[]; total: number }> {
  const p = new URLSearchParams();
  if (filter.q) p.set("q", filter.q);
  if (filter.organisationId) p.set("organisationId", String(filter.organisationId));
  p.set("page", String(filter.page ?? 0));
  p.set("limit", String(PAGE_SIZE));
  return apiFetch(
    `${BASE}/contacts?${p.toString()}`,
    (v) => {
      const o = obj(v);
      return { items: arr(o.items).map(parseContact), total: num(o.total) ?? 0 };
    },
    { signal },
  );
}

export function createContact(body: {
  organisationId: number;
  name: string;
  email: string;
  invite: boolean;
}): Promise<Contact> {
  return apiFetch(`${BASE}/contacts`, parseContact, { method: "POST", body });
}

export function inviteContact(id: number): Promise<void> {
  return apiFetch(`${BASE}/contacts/${id}/invitation`, ignore, { method: "POST" });
}

// --- Preferences -----------------------------------------------------------------------

export type ComposerTabPreference = "reply" | "note";

/** Preference keys -> queue views. */
const QUEUE_VIEW_KEYS: Record<string, string> = {
  "my-open": "mine",
  unassigned: "unassigned",
  "all-open": "all",
  triage: "triage",
};

export type AgentPreferences = { composerTab: ComposerTabPreference; queueView: string };

/**
 * The supporter's composer tab and default queue view from My settings - Preferences
 * (`GET /api/v1/agent/me/preferences`). If the call fails, the composer opens on Internal
 * note (the safe default: nothing reaches the client by accident) and the queue on My tickets.
 */
export async function getPreferences(signal?: AbortSignal): Promise<AgentPreferences> {
  try {
    return await apiFetch(
      `${BASE}/me/preferences`,
      (v) => {
        const o = obj(v);
        const tab = (str(o.composerTab) ?? str(o.defaultComposerTab) ?? "").toLowerCase();
        return {
          composerTab: tab.startsWith("reply") ? "reply" : "note",
          queueView: QUEUE_VIEW_KEYS[str(o.queueView) ?? ""] ?? "mine",
        };
      },
      { signal },
    );
  } catch {
    return { composerTab: "note", queueView: "mine" };
  }
}

export async function defaultComposerTab(signal?: AbortSignal): Promise<ComposerTabPreference> {
  return (await getPreferences(signal)).composerTab;
}
