import { apiFetch } from "./client";

/**
 * Microsoft (Entra) sign-in. The server answers a Microsoft URL
 * for every start (sign-in, link, Test sign-in); the page then sends the whole browser there, and
 * Microsoft comes back to the server's callback, which redirects to a page with a short result
 * word in the query string. The client secret is sent once and never comes back.
 */

export type LinkStatus = {
  available: boolean;
  linked: boolean;
  tenantId: string | null;
  linkedAt: string | null;
};

export type ExpiryState = "NONE" | "OK" | "SOON" | "EXPIRED";

export type SecretExpiry = {
  state: ExpiryState;
  expiresOn: string | null;
  daysLeft: number | null;
};

export type MicrosoftSettings = {
  enabled: boolean;
  clientId: string | null;
  mspTenantId: string | null;
  secretSet: boolean;
  secretExpiresOn: string | null;
  tested: boolean;
  testedAt: string | null;
  secretExpiry: SecretExpiry;
};

export type OrganisationTenant = { id: number; name: string; tenantId: string | null };

export type SettingsPage = {
  settings: MicrosoftSettings;
  redirectUri: string;
  organisations: OrganisationTenant[];
};

type Obj = Record<string, unknown>;

function obj(value: unknown): Obj {
  if (typeof value === "object" && value !== null && !Array.isArray(value)) return value as Obj;
  throw new Error("API answered with an unexpected shape");
}

function optStr(v: unknown): string | null {
  return typeof v === "string" ? v : null;
}

function str(v: unknown): string {
  if (typeof v !== "string") throw new Error("API answered with an unexpected shape");
  return v;
}

const EXPIRY_STATES: readonly ExpiryState[] = ["NONE", "OK", "SOON", "EXPIRED"];

function parseExpiry(value: unknown): SecretExpiry {
  const v = obj(value);
  const state = EXPIRY_STATES.find((s) => s === v.state) ?? "NONE";
  return {
    state,
    expiresOn: optStr(v.expiresOn),
    daysLeft: typeof v.daysLeft === "number" ? v.daysLeft : null,
  };
}

export function parseSettingsPage(value: unknown): SettingsPage {
  const v = obj(value);
  const s = obj(v.settings);
  if (!Array.isArray(v.organisations)) throw new Error("API answered with an unexpected shape");
  return {
    settings: {
      enabled: s.enabled === true,
      clientId: optStr(s.clientId),
      mspTenantId: optStr(s.mspTenantId),
      secretSet: s.secretSet === true,
      secretExpiresOn: optStr(s.secretExpiresOn),
      tested: s.tested === true,
      testedAt: optStr(s.testedAt),
      secretExpiry: parseExpiry(s.secretExpiry),
    },
    redirectUri: str(v.redirectUri),
    organisations: v.organisations.map(parseOrganisationTenant),
  };
}

function parseOrganisationTenant(value: unknown): OrganisationTenant {
  const o = obj(value);
  if (typeof o.id !== "number") throw new Error("API answered with an unexpected shape");
  return { id: o.id, name: str(o.name), tenantId: optStr(o.tenantId) };
}

function parseLinkStatus(value: unknown): LinkStatus {
  const v = obj(value);
  return {
    available: v.available === true,
    linked: v.linked === true,
    tenantId: optStr(v.tenantId),
    linkedAt: optStr(v.linkedAt),
  };
}

/** Only ever a Microsoft sign-in URL (https), never a script or a page of ours. */
function parseRedirect(value: unknown): string {
  const url = str(obj(value).authorizationUrl);
  if (!/^https?:\/\//.test(url)) throw new Error("API answered with an unexpected shape");
  return url;
}

const ignore = () => undefined;

/** Is "Sign in with Microsoft" switched on? Anything unexpected counts as off. */
export async function fetchMicrosoftEnabled(signal?: AbortSignal): Promise<boolean> {
  try {
    return await apiFetch("/api/v1/auth/microsoft", (b) => obj(b).enabled === true, { signal });
  } catch {
    return false;
  }
}

export function startMicrosoftSignIn(): Promise<string> {
  return apiFetch("/api/v1/auth/microsoft/sign-in", parseRedirect, { method: "POST", body: {} });
}

export function fetchLinkStatus(signal?: AbortSignal): Promise<LinkStatus> {
  return apiFetch("/api/v1/auth/microsoft/link", parseLinkStatus, { signal });
}

export function startMicrosoftLink(): Promise<string> {
  return apiFetch("/api/v1/auth/microsoft/link", parseRedirect, { method: "POST", body: {} });
}

export function unlinkMicrosoft(): Promise<void> {
  return apiFetch("/api/v1/auth/microsoft/link", ignore, { method: "DELETE" });
}

const ADMIN = "/api/v1/admin/sign-in/microsoft";

export function fetchMicrosoftSettings(signal?: AbortSignal): Promise<SettingsPage> {
  return apiFetch(ADMIN, parseSettingsPage, { signal });
}

export function saveMicrosoftSettings(input: {
  clientId: string;
  clientSecret: string | null;
  secretExpiresOn: string;
  mspTenantId: string;
}): Promise<SettingsPage> {
  return apiFetch(ADMIN, parseSettingsPage, { method: "PUT", body: input });
}

export function setMicrosoftEnabled(enabled: boolean): Promise<SettingsPage> {
  return apiFetch(`${ADMIN}/enabled`, parseSettingsPage, { method: "PUT", body: { enabled } });
}

export function startMicrosoftTest(): Promise<string> {
  return apiFetch(`${ADMIN}/test`, parseRedirect, { method: "POST", body: {} });
}

export function setOrganisationTenant(
  id: number,
  tenantId: string | null,
): Promise<OrganisationTenant> {
  return apiFetch(`${ADMIN}/organisations/${id}`, parseOrganisationTenant, {
    method: "PUT",
    body: { tenantId },
  });
}

/** Sends the whole browser to Microsoft (a separate function so tests can stub it). */
export const browserNavigation = {
  go(url: string) {
    window.location.assign(url);
  },
};

/** Plain words for the result codes the callback puts in the page address. */
export function microsoftResultText(code: string | null): string | null {
  switch (code) {
    case null:
    case "":
      return null;
    case "linked":
      return null;
    case "not_linked":
      return "This Microsoft account is not linked to an account here. Sign in with your password, then link Microsoft in your settings.";
    case "unavailable":
      return "Microsoft sign-in is unavailable at the moment. Use your password instead.";
    case "disabled":
      return "Microsoft sign-in is switched off. Use your password instead.";
    case "cancelled":
      return "Microsoft sign-in was cancelled.";
    case "throttled":
      return "Too many attempts. Please wait a minute and try again.";
    case "wrong_tenant":
      return "That Microsoft account belongs to an organisation that is not allowed here.";
    case "linked_elsewhere":
      return "That Microsoft account is already linked to another user.";
    case "already_linked":
      return "A Microsoft account is already linked. Unlink it first.";
    default:
      return "Microsoft sign-in did not work. Try again, or use your password.";
  }
}
