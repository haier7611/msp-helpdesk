import { apiFetch } from "./client";
import { parseMe, type Me } from "./auth";

/**
 * Two-step sign-in (TOTP), step-up, sessions and trusted devices. Codes travel only in
 * POST bodies. Every parser checks the shape so a surprise from the server fails loudly.
 */

export type Enrolment = {
  manualKey: string;
  otpauthUri: string;
  qrSize: number;
  qrPath: string;
};

export type MfaStatus = {
  enrolled: boolean;
  enrolledAt: string | null;
  recoveryCodesLeft: number;
};

export type SessionInfo = {
  id: string;
  createdAt: string;
  lastActiveAt: string;
  ipAddress: string | null;
  device: string | null;
  authLevel: string | null;
  current: boolean;
};

export type TrustedDevice = {
  id: number;
  label: string;
  createdAt: string;
  lastUsedAt: string;
  trustedUntil: string;
};

type Obj = Record<string, unknown>;

function obj(value: unknown): Obj {
  if (typeof value === "object" && value !== null && !Array.isArray(value)) {
    return value as Obj;
  }
  throw new Error("API answered with an unexpected shape");
}

function str(v: unknown): string {
  if (typeof v !== "string") throw new Error("API answered with an unexpected shape");
  return v;
}

function optStr(v: unknown): string | null {
  return typeof v === "string" ? v : null;
}

function list<T>(value: unknown, item: (v: unknown) => T): T[] {
  if (!Array.isArray(value)) throw new Error("API answered with an unexpected shape");
  return value.map(item);
}

const ignore = () => undefined;

export function parseEnrolment(value: unknown): Enrolment {
  const v = obj(value);
  if (typeof v.qrSize !== "number") throw new Error("API answered with an unexpected shape");
  const qrPath = str(v.qrPath);
  // Only SVG path commands made of digits and h/v/M/z - never markup.
  if (!/^[Mhvz0-9 -]*$/.test(qrPath)) throw new Error("API answered with an unexpected shape");
  return { manualKey: str(v.manualKey), otpauthUri: str(v.otpauthUri), qrSize: v.qrSize, qrPath };
}

function parseCodes(value: unknown): { recoveryCodes: string[]; me: Me | null } {
  const v = obj(value);
  return {
    recoveryCodes: list(v.recoveryCodes, str),
    me: v.me ? parseMe(v.me) : null,
  };
}

function parseSignedIn(value: unknown): { me: Me; recoveryCodesLeft: number | null } {
  const v = obj(value);
  return {
    me: parseMe(v.me),
    recoveryCodesLeft: typeof v.recoveryCodesLeft === "number" ? v.recoveryCodesLeft : null,
  };
}

export function parseStatus(value: unknown): MfaStatus {
  const v = obj(value);
  return {
    enrolled: v.enrolled === true,
    enrolledAt: optStr(v.enrolledAt),
    recoveryCodesLeft: typeof v.recoveryCodesLeft === "number" ? v.recoveryCodesLeft : 0,
  };
}

function parseSession(value: unknown): SessionInfo {
  const v = obj(value);
  return {
    id: str(v.id),
    createdAt: str(v.createdAt),
    lastActiveAt: str(v.lastActiveAt),
    ipAddress: optStr(v.ipAddress),
    device: optStr(v.device),
    authLevel: optStr(v.authLevel),
    current: v.current === true,
  };
}

function parseDevice(value: unknown): TrustedDevice {
  const v = obj(value);
  if (typeof v.id !== "number") throw new Error("API answered with an unexpected shape");
  return {
    id: v.id,
    label: str(v.label),
    createdAt: str(v.createdAt),
    lastUsedAt: str(v.lastUsedAt),
    trustedUntil: str(v.trustedUntil),
  };
}

export function startEnrolment(): Promise<Enrolment> {
  return apiFetch("/api/v1/auth/mfa/enrolment", parseEnrolment, { method: "POST", body: {} });
}

export function confirmEnrolment(code: string) {
  return apiFetch("/api/v1/auth/mfa/enrolment/confirm", parseCodes, {
    method: "POST",
    body: { code },
  });
}

export function verifyCode(code: string, trustDevice: boolean) {
  return apiFetch("/api/v1/auth/mfa/verify", parseSignedIn, {
    method: "POST",
    body: { code, trustDevice },
  });
}

/** A recovery code at sign-in; `trustDevice` trusts this browser for 30 days. */
export function verifyRecoveryCode(recoveryCode: string, trustDevice: boolean) {
  return apiFetch("/api/v1/auth/mfa/recovery", parseSignedIn, {
    method: "POST",
    body: { recoveryCode, trustDevice },
  });
}

/** Step-up: a code, a recovery code, or - only for an exempt engineer - the password. */
export function stepUp(input: {
  code?: string;
  recoveryCode?: string;
  password?: string;
}): Promise<void> {
  return apiFetch("/api/v1/auth/step-up", ignore, { method: "POST", body: input });
}

export function fetchMfaStatus(signal?: AbortSignal): Promise<MfaStatus> {
  return apiFetch("/api/v1/auth/mfa", parseStatus, { signal });
}

export function regenerateRecoveryCodes(): Promise<string[]> {
  return apiFetch("/api/v1/auth/mfa/recovery-codes", (b) => parseCodes(b).recoveryCodes, {
    method: "POST",
    body: {},
  });
}

export function fetchSessions(signal?: AbortSignal): Promise<SessionInfo[]> {
  return apiFetch("/api/v1/auth/sessions", (b) => list(b, parseSession), { signal });
}

export function signOutSession(id: string): Promise<void> {
  return apiFetch(`/api/v1/auth/sessions/${encodeURIComponent(id)}`, ignore, {
    method: "DELETE",
  });
}

export function signOutOtherSessions(): Promise<void> {
  return apiFetch("/api/v1/auth/sessions/sign-out-others", ignore, { method: "POST", body: {} });
}

export function fetchTrustedDevices(signal?: AbortSignal): Promise<TrustedDevice[]> {
  return apiFetch("/api/v1/auth/trusted-devices", (b) => list(b, parseDevice), { signal });
}

export function removeTrustedDevice(id: number): Promise<void> {
  return apiFetch(`/api/v1/auth/trusted-devices/${id}`, ignore, { method: "DELETE" });
}

export function removeAllTrustedDevices(): Promise<void> {
  return apiFetch("/api/v1/auth/trusted-devices", ignore, { method: "DELETE" });
}

export function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  return apiFetch("/api/v1/auth/password/change", ignore, {
    method: "POST",
    body: { currentPassword, newPassword },
  });
}

/** Where the sign-in page sends a supporter whose session still owes the MFA step. */
export function mfaPathFor(authLevel: string | null): string | null {
  if (authLevel === "MFA_REQUIRED") return "/mfa/verify";
  if (authLevel === "MFA_ENROLMENT_REQUIRED") return "/mfa/enrol";
  return null;
}
