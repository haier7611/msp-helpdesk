import { apiFetch, csrfToken, CSRF_HEADER, errorFromResponse, fetchOrNetworkError } from "./client";

/** My settings (/api/v1/agent/me): the signed-in supporter's own settings only. */

export type Profile = {
  email: string;
  displayName: string;
  jobTitle: string;
  phone: string;
  mobile: string;
  freeLine: string;
  hasPhoto: boolean;
};

export type ProfileFields = Pick<
  Profile,
  "displayName" | "jobTitle" | "phone" | "mobile" | "freeLine"
>;

/** The server's rendering of the full signature, HTML and plain text. */
export type RenderedSignature = {
  fullHtml: string;
  fullText: string;
};

export type ComposerTab = "REPLY" | "INTERNAL_NOTE";
export type Theme = "LIGHT" | "DARK" | "SYSTEM";
export type DateFormat = "DMY" | "MDY" | "YMD";

export type Preferences = {
  composerTab: ComposerTab;
  queueView: string;
  timezone: string;
  dateFormat: DateFormat;
  theme: Theme;
};

export type OutOfOffice = { enabled: boolean; from: string | null; until: string | null };

export type NotificationEvent =
  | "TICKET_ASSIGNED"
  | "CLIENT_REPLIED"
  | "MENTIONED"
  | "INTERNAL_NOTE_ADDED"
  | "SLA_WARNING"
  | "SLA_BREACHED";

export type NotificationChoice = { event: NotificationEvent; email: boolean; inApp: boolean };
export type Notifications = { events: NotificationChoice[]; dailyDigest: boolean };

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

function oneOf<T extends string>(v: unknown, allowed: readonly T[]): T {
  return typeof v === "string" && (allowed as readonly string[]).includes(v) ? (v as T) : bad();
}

export const NOTIFICATION_EVENTS: readonly NotificationEvent[] = [
  "TICKET_ASSIGNED",
  "CLIENT_REPLIED",
  "MENTIONED",
  "INTERNAL_NOTE_ADDED",
  "SLA_WARNING",
  "SLA_BREACHED",
];

export function parseProfile(value: unknown): Profile {
  const v = obj(value);
  return {
    email: str(v.email),
    displayName: str(v.displayName),
    jobTitle: str(v.jobTitle),
    phone: str(v.phone),
    mobile: str(v.mobile),
    freeLine: str(v.freeLine),
    hasPhoto: v.hasPhoto === true,
  };
}

export function parseRendered(value: unknown): RenderedSignature {
  const v = obj(value);
  return {
    fullHtml: str(v.fullHtml),
    fullText: str(v.fullText),
  };
}

export function parsePreferences(value: unknown): Preferences {
  const v = obj(value);
  return {
    composerTab: oneOf(v.composerTab, ["REPLY", "INTERNAL_NOTE"] as const),
    queueView: str(v.queueView),
    timezone: str(v.timezone),
    dateFormat: oneOf(v.dateFormat, ["DMY", "MDY", "YMD"] as const),
    theme: oneOf(v.theme, ["LIGHT", "DARK", "SYSTEM"] as const),
  };
}

function parseOutOfOffice(value: unknown): OutOfOffice {
  const v = obj(value);
  return { enabled: v.enabled === true, from: optStr(v.from), until: optStr(v.until) };
}

function parseNotifications(value: unknown): Notifications {
  const v = obj(value);
  if (!Array.isArray(v.events)) bad();
  return {
    events: v.events.map((e) => {
      const o = obj(e);
      return {
        event: oneOf(o.event, NOTIFICATION_EVENTS),
        email: o.email === true,
        inApp: o.inApp === true,
      };
    }),
    dailyDigest: v.dailyDigest === true,
  };
}

const ME = "/api/v1/agent/me";

export function fetchProfile(signal?: AbortSignal): Promise<Profile> {
  return apiFetch(`${ME}/profile`, parseProfile, { signal });
}

export function saveProfile(fields: ProfileFields): Promise<Profile> {
  return apiFetch(`${ME}/profile`, parseProfile, { method: "PUT", body: fields });
}

export function fetchSignature(signal?: AbortSignal): Promise<RenderedSignature> {
  return apiFetch(`${ME}/signature`, parseRendered, { signal });
}

export function previewSignature(
  fields: ProfileFields,
  signal?: AbortSignal,
): Promise<RenderedSignature> {
  return apiFetch(`${ME}/signature-preview`, parseRendered, {
    method: "POST",
    body: fields,
    signal,
  });
}

/** Same-origin URL of the caller's photo; the query defeats the browser cache after an upload. */
export function photoUrl(version: number): string {
  return `${ME}/photo?v=${version}`;
}

export const MAX_PHOTO_BYTES = 5 * 1024 * 1024;

/** Uploads a photo as the raw body (the server detects the type and re-encodes it). */
export async function uploadPhoto(file: Blob): Promise<void> {
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
  const response = await fetchOrNetworkError(`${ME}/photo`, {
    method: "PUT",
    headers,
    body: file,
    credentials: "same-origin",
  });
  if (!response.ok) throw await errorFromResponse(response);
}

export function deletePhoto(): Promise<void> {
  return apiFetch(`${ME}/photo`, () => undefined, { method: "DELETE" });
}

export function fetchPreferences(signal?: AbortSignal): Promise<Preferences> {
  return apiFetch(`${ME}/preferences`, parsePreferences, { signal });
}

export function savePreferences(body: Preferences): Promise<Preferences> {
  return apiFetch(`${ME}/preferences`, parsePreferences, { method: "PUT", body });
}

export function fetchOutOfOffice(signal?: AbortSignal): Promise<OutOfOffice> {
  return apiFetch(`${ME}/out-of-office`, parseOutOfOffice, { signal });
}

export function saveOutOfOffice(body: OutOfOffice): Promise<OutOfOffice> {
  return apiFetch(`${ME}/out-of-office`, parseOutOfOffice, { method: "PUT", body });
}

export function fetchNotifications(signal?: AbortSignal): Promise<Notifications> {
  return apiFetch(`${ME}/notifications`, parseNotifications, { signal });
}

export function saveNotifications(body: Notifications): Promise<Notifications> {
  return apiFetch(`${ME}/notifications`, parseNotifications, { method: "PUT", body });
}
