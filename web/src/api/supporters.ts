import { apiFetch } from "./client";

/**
 * Admin - Manage supporters (/api/v1/admin/supporters). The server enforces admin-only,
 * step-up on role change, and the "yourself" / "last active admin" guards; the reasons it returns
 * are shown next to the disabled buttons.
 */

export type SupporterRole = "AGENT" | "ADMIN";
export type SupporterStatus = "INVITED" | "ACTIVE" | "DEACTIVATED";

export type Supporter = {
  id: number;
  email: string;
  name: string;
  jobTitle: string;
  role: SupporterRole;
  status: SupporterStatus;
  allOrganisations: boolean;
  organisationIds: number[];
  mfaEnrolled: boolean;
  lastSignInAt: string | null;
  openTickets: number;
  outOfOffice: boolean;
  outOfOfficeUntil: string | null;
  invitationExpiresAt: string | null;
  self: boolean;
  roleChangeBlockedReason: string | null;
  deactivateBlockedReason: string | null;
  /** End of a two-step exemption that applies now, and the admin's reason. */
  mfaExemptUntil: string | null;
  mfaExemptReason: string | null;
};

export type MfaExemption = { reason: string; endsAt: string; grantedAt: string };

export type InviteResult = { userId: number; expiresAt: string };

export type DeactivationPlan = {
  supporterId: number;
  openTickets: number;
  blockedReason: string | null;
  reassignTo: Supporter[];
};

export type ActivityEvent = {
  id: number;
  occurredAt: string;
  type: string;
  actorUserId: number | null;
  actorName: string | null;
  targetUserId: number | null;
  targetName: string | null;
  clientIp: string | null;
  details: Record<string, string | number | boolean>;
};

type Obj = Record<string, unknown>;

function bad(): never {
  throw new Error("API answered with an unexpected shape");
}

function obj(value: unknown): Obj {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Obj)
    : bad();
}

function str(v: unknown): string {
  return typeof v === "string" ? v : bad();
}

function optStr(v: unknown): string | null {
  return typeof v === "string" ? v : null;
}

function num(v: unknown): number {
  return typeof v === "number" ? v : bad();
}

function optNum(v: unknown): number | null {
  return typeof v === "number" ? v : null;
}

function list<T>(value: unknown, item: (v: unknown) => T): T[] {
  return Array.isArray(value) ? value.map(item) : bad();
}

function role(v: unknown): SupporterRole {
  return v === "AGENT" || v === "ADMIN" ? v : bad();
}

function status(v: unknown): SupporterStatus {
  return v === "INVITED" || v === "ACTIVE" || v === "DEACTIVATED" ? v : bad();
}

export function parseSupporter(value: unknown): Supporter {
  const v = obj(value);
  return {
    id: num(v.id),
    email: str(v.email),
    name: str(v.name),
    jobTitle: optStr(v.jobTitle) ?? "",
    role: role(v.role),
    status: status(v.status),
    allOrganisations: v.allOrganisations === true,
    organisationIds: list(v.organisationIds ?? [], num),
    mfaEnrolled: v.mfaEnrolled === true,
    lastSignInAt: optStr(v.lastSignInAt),
    openTickets: optNum(v.openTickets) ?? 0,
    outOfOffice: v.outOfOffice === true,
    outOfOfficeUntil: optStr(v.outOfOfficeUntil),
    invitationExpiresAt: optStr(v.invitationExpiresAt),
    self: v.self === true,
    roleChangeBlockedReason: optStr(v.roleChangeBlockedReason),
    deactivateBlockedReason: optStr(v.deactivateBlockedReason),
    mfaExemptUntil: optStr(v.mfaExemptUntil),
    mfaExemptReason: optStr(v.mfaExemptReason),
  };
}

function parseExemption(value: unknown): MfaExemption {
  const v = obj(value);
  return { reason: str(v.reason), endsAt: str(v.endsAt), grantedAt: str(v.grantedAt) };
}

function parseInvite(value: unknown): InviteResult {
  const v = obj(value);
  return { userId: num(v.userId), expiresAt: str(v.expiresAt) };
}

function parsePlan(value: unknown): DeactivationPlan {
  const v = obj(value);
  return {
    supporterId: num(v.supporterId),
    openTickets: num(v.openTickets),
    blockedReason: optStr(v.blockedReason),
    reassignTo: list(v.reassignTo, parseSupporter),
  };
}

function parseActivity(value: unknown): ActivityEvent {
  const v = obj(value);
  const details: Record<string, string | number | boolean> = {};
  const d = v.details && typeof v.details === "object" ? (v.details as Obj) : {};
  for (const [key, val] of Object.entries(d)) {
    if (typeof val === "string" || typeof val === "number" || typeof val === "boolean") {
      details[key] = val;
    }
  }
  return {
    id: num(v.id),
    occurredAt: str(v.occurredAt),
    type: str(v.type),
    actorUserId: optNum(v.actorUserId),
    actorName: optStr(v.actorName),
    targetUserId: optNum(v.targetUserId),
    targetName: optStr(v.targetName),
    clientIp: optStr(v.clientIp),
    details,
  };
}

const ignore = () => undefined;
const BASE = "/api/v1/admin/supporters";

export function listSupporters(
  filters: { role?: SupporterRole | ""; status?: SupporterStatus | "" },
  signal?: AbortSignal,
): Promise<Supporter[]> {
  const params = new URLSearchParams();
  if (filters.role) params.set("role", filters.role);
  if (filters.status) params.set("status", filters.status);
  const query = params.toString();
  return apiFetch(query ? `${BASE}?${query}` : BASE, (b) => list(b, parseSupporter), { signal });
}

export function inviteSupporter(body: {
  email: string;
  name: string;
  role: SupporterRole;
  allOrganisations: boolean;
  organisationIds: number[];
}): Promise<InviteResult> {
  return apiFetch(`${BASE}/invitations`, parseInvite, { method: "POST", body });
}

export function resendInvitation(id: number): Promise<InviteResult> {
  return apiFetch(`${BASE}/${id}/invitation/resend`, parseInvite, { method: "POST", body: {} });
}

export function revokeInvitation(id: number): Promise<void> {
  return apiFetch(`${BASE}/${id}/invitation/revoke`, ignore, { method: "POST", body: {} });
}

export function updateSupporter(id: number, name: string, jobTitle: string): Promise<Supporter> {
  return apiFetch(`${BASE}/${id}`, parseSupporter, { method: "PUT", body: { name, jobTitle } });
}

export function changeRole(id: number, newRole: SupporterRole): Promise<Supporter> {
  return apiFetch(`${BASE}/${id}/role`, parseSupporter, {
    method: "PUT",
    body: { role: newRole },
  });
}

export function setOrganisationAccess(
  id: number,
  allOrganisations: boolean,
  organisationIds: number[],
): Promise<void> {
  return apiFetch(`/api/v1/admin/users/${id}/supporter-access`, ignore, {
    method: "PUT",
    body: { allOrganisations, organisationIds },
  });
}

export function fetchDeactivationPlan(id: number, signal?: AbortSignal): Promise<DeactivationPlan> {
  return apiFetch(`${BASE}/${id}/deactivation`, parsePlan, { signal });
}

export function deactivateSupporter(id: number, reassignTo: number | null): Promise<Supporter> {
  return apiFetch(`${BASE}/${id}/deactivate`, parseSupporter, {
    method: "POST",
    body: { reassignTo },
  });
}

export function reactivateSupporter(id: number): Promise<Supporter> {
  return apiFetch(`${BASE}/${id}/reactivate`, parseSupporter, { method: "POST", body: {} });
}

export function resetSupporterMfa(id: number): Promise<void> {
  return apiFetch(`${BASE}/${id}/security/reset-mfa`, ignore, { method: "POST", body: {} });
}

export function signOutSupporterEverywhere(id: number): Promise<void> {
  return apiFetch(`${BASE}/${id}/security/sign-out-everywhere`, ignore, {
    method: "POST",
    body: {},
  });
}

/** Pause two-step sign-in for an engineer until `endsAt` (at most 30 days; step-up). */
export function grantMfaExemption(
  id: number,
  reason: string,
  endsAt: string,
): Promise<MfaExemption> {
  return apiFetch(`${BASE}/${id}/security/mfa-exemption`, parseExemption, {
    method: "PUT",
    body: { reason, endsAt },
  });
}

/** End the exemption now; the engineer is signed out everywhere (step-up). */
export function revokeMfaExemption(id: number): Promise<void> {
  return apiFetch(`${BASE}/${id}/security/mfa-exemption`, ignore, { method: "DELETE" });
}

export function sendPasswordReset(id: number): Promise<void> {
  return apiFetch(`${BASE}/${id}/security/password-reset`, ignore, { method: "POST", body: {} });
}

export function fetchActivity(id: number, signal?: AbortSignal): Promise<ActivityEvent[]> {
  return apiFetch(`${BASE}/${id}/activity`, (b) => list(b, parseActivity), { signal });
}
