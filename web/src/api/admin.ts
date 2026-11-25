import {
  apiFetch,
  csrfToken,
  ApiError,
  CSRF_HEADER,
  errorFromResponse,
  fetchOrNetworkError,
} from "./client";
import type { RenderedSignature } from "./settings";

/**
 * Admin configuration API (email and SLA). Every endpoint is
 * admin-only on the server; the sensitive ones answer 403 `step_up_required`, which the pages
 * handle with useStepUp(). Secrets are write-only: the server never sends them back.
 */

type Obj = Record<string, unknown>;

function bad(): never {
  throw new Error("API answered with an unexpected shape");
}

function obj(value: unknown): Obj {
  if (typeof value === "object" && value !== null && !Array.isArray(value)) return value as Obj;
  return bad();
}

function arr(value: unknown): unknown[] {
  return Array.isArray(value) ? value : bad();
}

function str(value: unknown): string {
  return typeof value === "string" ? value : bad();
}

function optStr(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function num(value: unknown): number {
  return typeof value === "number" ? value : bad();
}

function optNum(value: unknown): number | null {
  return typeof value === "number" ? value : null;
}

const ignore = () => undefined;

export type Priority = "P1" | "P2" | "P3" | "P4";
export const PRIORITIES: readonly Priority[] = ["P1", "P2", "P3", "P4"];

function priority(value: unknown): Priority {
  return PRIORITIES.find((p) => p === value) ?? bad();
}

// Request types -----------------------------------------------------------------------------------

export type AdminRequestType = {
  id: number;
  name: string;
  description: string;
  defaultPriority: Priority;
  portalVisible: boolean;
  archived: boolean;
  sortOrder: number;
  defaultAssigneeUserId: number | null;
  system: boolean;
  ticketCount: number;
};

export type RequestTypeInput = {
  name: string;
  description: string;
  defaultPriority: Priority;
  portalVisible: boolean;
  archived: boolean;
};

function parseRequestType(value: unknown): AdminRequestType {
  const v = obj(value);
  return {
    id: num(v.id),
    name: str(v.name),
    description: optStr(v.description) ?? "",
    defaultPriority: priority(v.defaultPriority),
    portalVisible: v.portalVisible === true,
    archived: v.archived === true,
    sortOrder: optNum(v.sortOrder) ?? 0,
    defaultAssigneeUserId: optNum(v.defaultAssigneeUserId),
    system: v.system === true,
    ticketCount: optNum(v.ticketCount) ?? 0,
  };
}

const TYPES = "/api/v1/admin/request-types";

export function listRequestTypes(signal?: AbortSignal): Promise<AdminRequestType[]> {
  return apiFetch(TYPES, (b) => arr(b).map(parseRequestType), { signal });
}

export function createRequestType(input: RequestTypeInput): Promise<AdminRequestType> {
  return apiFetch(TYPES, parseRequestType, { method: "POST", body: input });
}

export function updateRequestType(id: number, input: RequestTypeInput): Promise<AdminRequestType> {
  return apiFetch(`${TYPES}/${id}`, parseRequestType, { method: "PUT", body: input });
}

export function reorderRequestTypes(ids: number[]): Promise<AdminRequestType[]> {
  return apiFetch(`${TYPES}/order`, (b) => arr(b).map(parseRequestType), {
    method: "PUT",
    body: { ids },
  });
}

/** The request type's default assignee (null clears it). */
export function setRequestTypeAssignee(id: number, userId: number | null): Promise<void> {
  return apiFetch(`${TYPES}/${id}/default-assignee`, ignore, {
    method: "PUT",
    body: { userId },
  });
}

// SLA ---------------------------------------------------------------------------------

export type TargetUnit = "MINUTES" | "HOURS" | "DAYS";
export type ClockType = "TWENTY_FOUR_SEVEN" | "BUSINESS_HOURS";
export type Target = { amount: number; unit: TargetUnit };
export type PriorityTargets = { firstResponse: Target; resolution: Target; clock: ClockType };
export type Policy = {
  requestTypeId: number | null;
  pendingOnVendorPauses: boolean;
  targets: Record<Priority, PriorityTargets>;
};
export type SlaTimers = {
  pendingReminderEnabled: boolean;
  pendingReminderBusinessDays: number;
  autoResolveEnabled: boolean;
  autoResolveBusinessDays: number;
  autoCloseEnabled: boolean;
  autoCloseCalendarDays: number;
  apiAlertClientEmails: boolean;
  defaultAssigneeUserId: number | null;
};
export type HoursRow = { dayOfWeek: number; opens: string; closes: string };
export type Holiday = { date: string; name: string };
export type OrganisationSla = {
  organisationId: number;
  timezone: string;
  settings: SlaTimers;
  businessHours: HoursRow[];
  defaultHours: boolean;
  holidays: Holiday[];
  policies: Policy[];
};

const UNITS: readonly TargetUnit[] = ["MINUTES", "HOURS", "DAYS"];

function parseTarget(value: unknown): Target {
  const v = obj(value);
  return { amount: num(v.amount), unit: UNITS.find((u) => u === v.unit) ?? bad() };
}

function parsePolicy(value: unknown): Policy {
  const v = obj(value);
  const t = obj(v.targets);
  const targets = {} as Record<Priority, PriorityTargets>;
  for (const p of PRIORITIES) {
    const row = obj(t[p]);
    targets[p] = {
      firstResponse: parseTarget(row.firstResponse),
      resolution: parseTarget(row.resolution),
      clock: row.clock === "TWENTY_FOUR_SEVEN" ? "TWENTY_FOUR_SEVEN" : "BUSINESS_HOURS",
    };
  }
  return {
    requestTypeId: optNum(v.requestTypeId),
    pendingOnVendorPauses: v.pendingOnVendorPauses === true,
    targets,
  };
}

/** "08:30:00" or "08:30" -> "08:30". */
function hhmm(value: unknown): string {
  return str(value).slice(0, 5);
}

export function parseOrganisationSla(value: unknown): OrganisationSla {
  const v = obj(value);
  const s = obj(v.settings);
  return {
    organisationId: num(v.organisationId),
    timezone: str(v.timezone),
    settings: {
      pendingReminderEnabled: s.pendingReminderEnabled === true,
      pendingReminderBusinessDays: num(s.pendingReminderBusinessDays),
      autoResolveEnabled: s.autoResolveEnabled === true,
      autoResolveBusinessDays: num(s.autoResolveBusinessDays),
      autoCloseEnabled: s.autoCloseEnabled === true,
      autoCloseCalendarDays: num(s.autoCloseCalendarDays),
      apiAlertClientEmails: s.apiAlertClientEmails === true,
      defaultAssigneeUserId: optNum(s.defaultAssigneeUserId),
    },
    businessHours: arr(v.businessHours).map((h) => {
      const r = obj(h);
      return { dayOfWeek: num(r.dayOfWeek), opens: hhmm(r.opens), closes: hhmm(r.closes) };
    }),
    defaultHours: v.defaultHours === true,
    holidays: arr(v.holidays).map((h) => {
      const r = obj(h);
      return { date: str(r.date), name: str(r.name) };
    }),
    policies: arr(v.policies).map(parsePolicy),
  };
}

const orgSla = (id: number) => `/api/v1/admin/organisations/${id}/sla`;

export function fetchOrganisationSla(id: number, signal?: AbortSignal): Promise<OrganisationSla> {
  return apiFetch(orgSla(id), parseOrganisationSla, { signal });
}

export function saveSlaPolicy(
  organisationId: number,
  requestTypeId: number | null,
  pendingOnVendorPauses: boolean,
  targets: Record<Priority, PriorityTargets>,
): Promise<OrganisationSla> {
  const path =
    requestTypeId === null
      ? `${orgSla(organisationId)}/policies/default`
      : `${orgSla(organisationId)}/policies/request-types/${requestTypeId}`;
  return apiFetch(path, parseOrganisationSla, {
    method: "PUT",
    body: { pendingOnVendorPauses, targets },
  });
}

export function deleteSlaOverride(
  organisationId: number,
  requestTypeId: number,
): Promise<OrganisationSla> {
  return apiFetch(
    `${orgSla(organisationId)}/policies/request-types/${requestTypeId}`,
    parseOrganisationSla,
    { method: "DELETE" },
  );
}

export function saveBusinessHours(id: number, rows: HoursRow[]): Promise<OrganisationSla> {
  return apiFetch(`${orgSla(id)}/business-hours`, parseOrganisationSla, {
    method: "PUT",
    body: rows,
  });
}

export function saveHolidays(id: number, rows: Holiday[]): Promise<OrganisationSla> {
  return apiFetch(`${orgSla(id)}/holidays`, parseOrganisationSla, { method: "PUT", body: rows });
}

/**
 * The Pending reminder on/off switch is not sent: it is edited in Admin - Organisations -
 * "Client emails", and the server keeps the stored value when it is left out.
 */
export function saveTimers(
  id: number,
  timers: Omit<SlaTimers, "defaultAssigneeUserId" | "pendingReminderEnabled">,
): Promise<OrganisationSla> {
  return apiFetch(`${orgSla(id)}/timers`, parseOrganisationSla, { method: "PUT", body: timers });
}

export function saveOrganisationAssignee(
  id: number,
  userId: number | null,
): Promise<OrganisationSla> {
  return apiFetch(`${orgSla(id)}/default-assignee`, parseOrganisationSla, {
    method: "PUT",
    body: { userId },
  });
}

// Email: mailboxes, reply setting, block list, sender rules, public domains ------------------------

export type ImapSecurity = "TLS" | "STARTTLS" | "NONE";
export type AuthType = "PASSWORD" | "OAUTH2";

export type Mailbox = {
  id: number;
  name: string;
  address: string;
  enabled: boolean;
  imapHost: string;
  imapPort: number;
  imapSecurity: ImapSecurity;
  imapFolder: string;
  authType: AuthType;
  username: string;
  secretSet: boolean;
  oauthTokenUrl: string | null;
  oauthClientId: string | null;
  oauthScope: string | null;
  trustedAuthservIds: string[];
  lastPollAt: string | null;
  lastSuccessAt: string | null;
  lastError: string | null;
  consecutiveFailures: number;
  /** Outgoing: null = all mail uses the server's mail settings. */
  smtpHost: string | null;
  smtpPort: number | null;
  smtpSecurity: ImapSecurity | null;
  /** Sign in to SMTP with the incoming user name and password (the default). */
  smtpSameSignIn: boolean;
  smtpUsername: string | null;
  /** A separate SMTP password is stored (never the value). */
  smtpSecretSet: boolean;
  /** This mailbox sends ticket mail now. */
  sending: boolean;
  /** Last sending error: a short category, never server text. */
  smtpLastError: string | null;
};

export type MailboxInput = {
  name: string;
  address: string;
  enabled: boolean;
  imapHost: string;
  imapPort: number | null;
  imapSecurity: ImapSecurity;
  imapFolder: string;
  authType: AuthType;
  username: string;
  /** null keeps the stored secret (write-only). */
  secret: string | null;
  oauthTenantId: string | null;
  oauthTokenUrl: string | null;
  oauthClientId: string | null;
  oauthScope: string | null;
  trustedAuthservIds: string[];
  /** Outgoing; null = keep using the server's mail settings. */
  smtpHost: string | null;
  smtpPort: number | null;
  smtpSecurity: ImapSecurity | null;
  smtpSameSignIn: boolean;
  smtpUsername: string | null;
  /** null keeps the stored SMTP password (write-only). */
  smtpSecret: string | null;
};

function parseMailbox(value: unknown): Mailbox {
  const v = obj(value);
  return {
    id: num(v.id),
    name: str(v.name),
    address: str(v.address),
    enabled: v.enabled === true,
    imapHost: str(v.imapHost),
    imapPort: num(v.imapPort),
    imapSecurity:
      v.imapSecurity === "STARTTLS" ? "STARTTLS" : v.imapSecurity === "NONE" ? "NONE" : "TLS",
    imapFolder: optStr(v.imapFolder) ?? "INBOX",
    authType: v.authType === "OAUTH2" ? "OAUTH2" : "PASSWORD",
    username: str(v.username),
    secretSet: v.secretSet === true,
    oauthTokenUrl: optStr(v.oauthTokenUrl),
    oauthClientId: optStr(v.oauthClientId),
    oauthScope: optStr(v.oauthScope),
    trustedAuthservIds: Array.isArray(v.trustedAuthservIds)
      ? v.trustedAuthservIds.filter((s): s is string => typeof s === "string")
      : [],
    lastPollAt: optStr(v.lastPollAt),
    lastSuccessAt: optStr(v.lastSuccessAt),
    lastError: optStr(v.lastError),
    consecutiveFailures: optNum(v.consecutiveFailures) ?? 0,
    smtpHost: optStr(v.smtpHost),
    smtpPort: optNum(v.smtpPort),
    smtpSecurity:
      v.smtpSecurity === "TLS" || v.smtpSecurity === "STARTTLS" || v.smtpSecurity === "NONE"
        ? v.smtpSecurity
        : null,
    smtpSameSignIn: v.smtpSameSignIn !== false,
    smtpUsername: optStr(v.smtpUsername),
    smtpSecretSet: v.smtpSecretSet === true,
    sending: v.sending === true,
    smtpLastError: optStr(v.smtpLastError),
  };
}

const EMAIL = "/api/v1/admin/email";

export function listMailboxes(signal?: AbortSignal): Promise<Mailbox[]> {
  return apiFetch(`${EMAIL}/mailboxes`, (b) => arr(b).map(parseMailbox), { signal });
}

export function createMailbox(input: MailboxInput): Promise<Mailbox> {
  return apiFetch(`${EMAIL}/mailboxes`, parseMailbox, { method: "POST", body: input });
}

export function updateMailbox(id: number, input: MailboxInput): Promise<Mailbox> {
  return apiFetch(`${EMAIL}/mailboxes/${id}`, parseMailbox, { method: "PUT", body: input });
}

export function deleteMailbox(id: number): Promise<void> {
  return apiFetch(`${EMAIL}/mailboxes/${id}`, ignore, { method: "DELETE" });
}

export type TestResult = {
  loginOk: boolean;
  error: string | null;
  probeId: number | null;
  message: string;
};

export function testMailbox(id: number): Promise<TestResult> {
  return apiFetch(
    `${EMAIL}/mailboxes/${id}/test`,
    (b) => {
      const v = obj(b);
      return {
        loginOk: v.loginOk === true,
        error: optStr(v.error),
        probeId: optNum(v.probeId),
        message: str(v.message),
      };
    },
    { method: "POST", body: {} },
  );
}

export type ProbeStatus = { arrived: boolean; expired: boolean; message: string };

export function probeStatus(id: number, probeId: number): Promise<ProbeStatus> {
  return apiFetch(`${EMAIL}/mailboxes/${id}/test/${probeId}`, (b) => {
    const v = obj(b);
    return { arrived: v.arrived === true, expired: v.expired === true, message: str(v.message) };
  });
}

export type ReplyMode = "OFF" | "INTERNAL_ONLY" | "NOTES_AND_PUBLIC";

function parseReplyMode(b: unknown): ReplyMode {
  const m = obj(b).supporterReplies;
  return m === "OFF" || m === "NOTES_AND_PUBLIC" ? m : "INTERNAL_ONLY";
}

export function fetchReplyMode(signal?: AbortSignal): Promise<ReplyMode> {
  return apiFetch(`${EMAIL}/settings`, parseReplyMode, { signal });
}

export function saveReplyMode(mode: ReplyMode): Promise<ReplyMode> {
  return apiFetch(`${EMAIL}/settings`, parseReplyMode, {
    method: "PUT",
    body: { supporterReplies: mode },
  });
}

export type RuleKind = "ADDRESS" | "DOMAIN";
export type BlockRule = { id: number; kind: RuleKind; value: string; createdAt: string };
export type BlockedMail = {
  id: number;
  fromAddress: string;
  subject: string;
  receivedAt: string;
  ruleKind: string;
  rule: string;
  restoredAt: string | null;
  ticketId: number | null;
};
export type SenderRule = {
  id: number;
  kind: RuleKind;
  value: string;
  organisationId: number;
  requestTypeId: number;
  createdAt: string;
};

function kind(value: unknown): RuleKind {
  return value === "DOMAIN" ? "DOMAIN" : "ADDRESS";
}

function parseBlockRule(value: unknown): BlockRule {
  const v = obj(value);
  return { id: num(v.id), kind: kind(v.kind), value: str(v.value), createdAt: str(v.createdAt) };
}

export function listBlockRules(signal?: AbortSignal): Promise<BlockRule[]> {
  return apiFetch(`${EMAIL}/block-rules`, (b) => arr(b).map(parseBlockRule), { signal });
}

/** Adds a block rule; a 409 `confirmation_required` carries the warning to show. */
export function addBlockRule(k: RuleKind, value: string, confirm: boolean): Promise<BlockRule> {
  return apiFetch(`${EMAIL}/block-rules`, parseBlockRule, {
    method: "POST",
    body: { kind: k, value, confirm },
  });
}

export function removeBlockRule(id: number): Promise<void> {
  return apiFetch(`${EMAIL}/block-rules/${id}`, ignore, { method: "DELETE" });
}

export function listBlockedMail(signal?: AbortSignal): Promise<BlockedMail[]> {
  return apiFetch(
    `${EMAIL}/blocked-mail`,
    (b) =>
      arr(b).map((x) => {
        const v = obj(x);
        return {
          id: num(v.id),
          fromAddress: optStr(v.fromAddress) ?? "",
          subject: optStr(v.subject) ?? "",
          receivedAt: str(v.receivedAt),
          ruleKind: optStr(v.ruleKind) ?? "",
          rule: optStr(v.rule) ?? "",
          restoredAt: optStr(v.restoredAt),
          ticketId: optNum(v.ticketId),
        };
      }),
    { signal },
  );
}

export function restoreBlockedMail(id: number): Promise<number> {
  return apiFetch(`${EMAIL}/blocked-mail/${id}/restore`, (b) => num(obj(b).ticketId), {
    method: "POST",
    body: {},
  });
}

function parseSenderRule(value: unknown): SenderRule {
  const v = obj(value);
  return {
    id: num(v.id),
    kind: kind(v.kind),
    value: str(v.value),
    organisationId: num(v.organisationId),
    requestTypeId: num(v.requestTypeId),
    createdAt: str(v.createdAt),
  };
}

export function listSenderRules(signal?: AbortSignal): Promise<SenderRule[]> {
  return apiFetch(`${EMAIL}/sender-rules`, (b) => arr(b).map(parseSenderRule), { signal });
}

export function addSenderRule(input: {
  kind: RuleKind;
  value: string;
  organisationId: number;
  requestTypeId: number;
}): Promise<SenderRule> {
  return apiFetch(`${EMAIL}/sender-rules`, parseSenderRule, { method: "POST", body: input });
}

export function removeSenderRule(id: number): Promise<void> {
  return apiFetch(`${EMAIL}/sender-rules/${id}`, ignore, { method: "DELETE" });
}

export function listPublicDomains(signal?: AbortSignal): Promise<string[]> {
  return apiFetch(`${EMAIL}/public-domains`, (b) => arr(b).map(str), { signal });
}

// Company signature template and branding --------------------------------------------------------

export type EmailMode = "SUPPORTER" | "SUPPORT_MAILBOX";

export type TemplateInput = {
  companyName: string;
  address: string;
  brandColour: string;
  emailMode: EmailMode;
  supportEmail: string;
  logoAlt: string;
  confidentialityLine: string;
};

export type LogoInfo = {
  logoUploaded: boolean;
  /** data:image/png;base64,... only (checked when parsed). */
  logoDataUri: string;
  logoWidth: number;
  logoHeight: number;
};

export type SignatureTemplate = TemplateInput & LogoInfo;

function parseLogo(v: Obj): LogoInfo {
  const uri = str(v.logoDataUri);
  if (!uri.startsWith("data:image/png;base64,")) bad();
  return {
    logoUploaded: v.logoUploaded === true,
    logoDataUri: uri,
    logoWidth: num(v.logoWidth),
    logoHeight: num(v.logoHeight),
  };
}

function parseTemplate(value: unknown): SignatureTemplate {
  const v = obj(value);
  return {
    companyName: str(v.companyName),
    address: str(v.address),
    brandColour: str(v.brandColour),
    emailMode: v.emailMode === "SUPPORT_MAILBOX" ? "SUPPORT_MAILBOX" : "SUPPORTER",
    supportEmail: str(v.supportEmail),
    logoAlt: str(v.logoAlt),
    confidentialityLine: optStr(v.confidentialityLine) ?? "",
    ...parseLogo(v),
  };
}

function parseRendered(value: unknown): RenderedSignature {
  const v = obj(value);
  return {
    fullHtml: str(v.fullHtml),
    fullText: str(v.fullText),
  };
}

const TEMPLATE = "/api/v1/admin/signature-template";

export function fetchTemplate(signal?: AbortSignal): Promise<SignatureTemplate> {
  return apiFetch(TEMPLATE, parseTemplate, { signal });
}

export function saveTemplate(input: TemplateInput): Promise<SignatureTemplate> {
  return apiFetch(TEMPLATE, parseTemplate, { method: "PUT", body: input });
}

export function previewTemplate(
  input: TemplateInput,
  signal?: AbortSignal,
): Promise<RenderedSignature> {
  return apiFetch(`${TEMPLATE}/preview`, parseRendered, { method: "POST", body: input, signal });
}

export const MAX_LOGO_BYTES = 2 * 1024 * 1024;

/** Uploads the logo as the raw body (the server detects the type and re-encodes it). */
export async function uploadLogo(file: Blob): Promise<SignatureTemplate> {
  if (file.size > MAX_LOGO_BYTES) {
    throw new ApiError(413, "The logo file can be at most 2 MB.", "too_large");
  }
  let token = csrfToken();
  if (!token) {
    await fetch("/api/v1/auth/csrf", { credentials: "same-origin" }).catch(() => undefined);
    token = csrfToken();
  }
  const headers: Record<string, string> = {
    Accept: "application/json",
    "Content-Type": "application/octet-stream",
  };
  if (token) headers[CSRF_HEADER] = token;
  const response = await fetchOrNetworkError(`${TEMPLATE}/logo`, {
    method: "PUT",
    headers,
    body: file,
    credentials: "same-origin",
  });
  if (!response.ok) throw await errorFromResponse(response);
  return parseTemplate(await response.json());
}

export function removeLogo(): Promise<SignatureTemplate> {
  return apiFetch(`${TEMPLATE}/logo`, parseTemplate, { method: "DELETE" });
}

export type Branding = LogoInfo & {
  supportName: string;
  supportNameCustom: boolean;
  defaultSupportName: string;
  brandColour: string;
};

function parseBranding(value: unknown): Branding {
  const v = obj(value);
  return {
    supportName: str(v.supportName),
    supportNameCustom: v.supportNameCustom === true,
    defaultSupportName: str(v.defaultSupportName),
    brandColour: str(v.brandColour),
    ...parseLogo(v),
  };
}

export function fetchBranding(signal?: AbortSignal): Promise<Branding> {
  return apiFetch("/api/v1/admin/branding", parseBranding, { signal });
}

export function saveBranding(supportName: string, brandColour: string): Promise<Branding> {
  return apiFetch("/api/v1/admin/branding", parseBranding, {
    method: "PUT",
    body: { supportName, brandColour },
  });
}

// Shared saved replies -----------------------------------------

export type SharedReply = { id: number; title: string; bodyHtml: string };

function parseSharedReply(value: unknown): SharedReply {
  const v = obj(value);
  return { id: num(v.id), title: str(v.title), bodyHtml: str(v.bodyHtml) };
}

const REPLIES = "/api/v1/admin/saved-replies";

export function listSharedReplies(signal?: AbortSignal): Promise<SharedReply[]> {
  return apiFetch(REPLIES, (b) => arr(b).map(parseSharedReply), { signal });
}

export function createSharedReply(input: {
  title: string;
  bodyHtml: string;
}): Promise<SharedReply> {
  return apiFetch(REPLIES, parseSharedReply, { method: "POST", body: input });
}

export function updateSharedReply(
  id: number,
  input: { title: string; bodyHtml: string },
): Promise<SharedReply> {
  return apiFetch(`${REPLIES}/${id}`, parseSharedReply, { method: "PUT", body: input });
}

export function deleteSharedReply(id: number): Promise<void> {
  return apiFetch(`${REPLIES}/${id}`, ignore, { method: "DELETE" });
}

// Audit log -----------------------------------------------------------------------------------

export type AuditEvent = {
  id: number;
  occurredAt: string;
  type: string;
  actorName: string | null;
  actorEmail: string | null;
  targetName: string | null;
  targetEmail: string | null;
  clientIp: string | null;
  /** Allow-listed detail fields, already flattened to text (never rendered as HTML). */
  details: [string, string][];
};

export type AuditPage = {
  events: AuditEvent[];
  nextBefore: number | null;
  from: string;
  to: string;
  types: string[];
};

export type AuditFilters = {
  from: string;
  to: string;
  actor: string;
  type: string;
  organisationId: string;
};

function detailText(value: unknown): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return "";
}

function parseAuditPage(value: unknown): AuditPage {
  const v = obj(value);
  return {
    events: arr(v.events).map((x) => {
      const e = obj(x);
      const d = typeof e.details === "object" && e.details !== null ? (e.details as Obj) : {};
      return {
        id: num(e.id),
        occurredAt: str(e.occurredAt),
        type: str(e.type),
        actorName: optStr(e.actorName),
        actorEmail: optStr(e.actorEmail),
        targetName: optStr(e.targetName),
        targetEmail: optStr(e.targetEmail),
        clientIp: optStr(e.clientIp),
        details: Object.entries(d).map(([k, val]): [string, string] => [k, detailText(val)]),
      };
    }),
    nextBefore: optNum(v.nextBefore),
    from: str(v.from),
    to: str(v.to),
    types: arr(v.types).map(str),
  };
}

/** The query string for the viewer and the CSV link (empty filters are left out). */
export function auditQuery(f: AuditFilters, before?: number | null): string {
  const params = new URLSearchParams();
  if (f.from) params.set("from", f.from);
  if (f.to) params.set("to", f.to);
  if (f.actor.trim()) params.set("actor", f.actor.trim());
  if (f.type) params.set("type", f.type);
  if (f.organisationId) params.set("organisationId", f.organisationId);
  if (before != null) params.set("before", String(before));
  const q = params.toString();
  return q ? `?${q}` : "";
}

export function fetchAuditPage(
  f: AuditFilters,
  before: number | null,
  signal?: AbortSignal,
): Promise<AuditPage> {
  return apiFetch(`/api/v1/admin/audit${auditQuery(f, before)}`, parseAuditPage, { signal });
}

/**
 * Fetches the CSV export (the server audit-logs it). Fetched rather than linked so a refusal (for
 * example more than 10,000 rows) shows as a message instead of a JSON page.
 */
export async function downloadAuditCsv(f: AuditFilters): Promise<Blob> {
  const response = await fetchOrNetworkError(`/api/v1/admin/audit/csv${auditQuery(f)}`, {
    credentials: "same-origin",
    headers: { Accept: "text/csv, application/json" },
  });
  if (!response.ok) throw await errorFromResponse(response);
  return response.blob();
}
