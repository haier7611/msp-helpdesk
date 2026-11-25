import { apiFetch } from "./client";

/** Admin - Organisations (/api/v1/admin/organisations). The server enforces admin-only. */

export type Organisation = {
  id: number;
  name: string;
  prefix: string | null;
  retiredPrefixes: string[];
  verifiedDomains: string[];
  timezone: string;
  entraTenantId: string | null;
};

export type NewOrganisation = {
  name: string;
  prefix: string;
  verifiedDomains: string[];
  timezone: string;
  entraTenantId: string | null;
};

const BASE = "/api/v1/admin/organisations";

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((v) => typeof v === "string");
}

export function parseOrganisation(value: unknown): Organisation {
  if (typeof value === "object" && value !== null) {
    const v = value as Record<string, unknown>;
    if (
      typeof v.id === "number" &&
      typeof v.name === "string" &&
      (typeof v.prefix === "string" || v.prefix === null) &&
      isStringArray(v.retiredPrefixes) &&
      isStringArray(v.verifiedDomains) &&
      typeof v.timezone === "string"
    ) {
      return {
        id: v.id,
        name: v.name,
        prefix: v.prefix,
        retiredPrefixes: v.retiredPrefixes,
        verifiedDomains: v.verifiedDomains,
        timezone: v.timezone,
        entraTenantId: typeof v.entraTenantId === "string" ? v.entraTenantId : null,
      };
    }
  }
  throw new Error("API answered with an unexpected shape");
}

function parseList(value: unknown): Organisation[] {
  if (!Array.isArray(value)) throw new Error("API answered with an unexpected shape");
  return value.map(parseOrganisation);
}

export function listOrganisations(signal?: AbortSignal): Promise<Organisation[]> {
  return apiFetch(BASE, parseList, { signal });
}

export function createOrganisation(body: NewOrganisation): Promise<Organisation> {
  return apiFetch(BASE, parseOrganisation, { method: "POST", body });
}

export function changePrefix(id: number, prefix: string): Promise<Organisation> {
  return apiFetch(`${BASE}/${id}/prefix`, parseOrganisation, { method: "PUT", body: { prefix } });
}

export function addDomain(id: number, domain: string): Promise<Organisation> {
  return apiFetch(`${BASE}/${id}/domains`, parseOrganisation, {
    method: "POST",
    body: { domain },
  });
}

export function removeDomain(id: number, domain: string): Promise<Organisation> {
  return apiFetch(`${BASE}/${id}/domains/${encodeURIComponent(domain)}`, parseOrganisation, {
    method: "DELETE",
  });
}

/** "a.example, b.example" or one per line -> ["a.example", "b.example"]. */
export function splitDomains(text: string): string[] {
  return text
    .split(/[\s,;]+/)
    .map((d) => d.trim())
    .filter((d) => d.length > 0);
}

/**
 * The organisation's client email switches (Admin - Organisations - "Client emails").
 * Agent public replies and the agent-opened ticket email are never switchable, so they are not here.
 */
export type ClientEmailSwitches = {
  ticketReceived: boolean;
  resolvedNotice: boolean;
  closedNotice: boolean;
  pendingReminder: boolean;
};

export function parseClientEmailSwitches(value: unknown): ClientEmailSwitches {
  if (typeof value === "object" && value !== null) {
    const v = value as Record<string, unknown>;
    if (
      typeof v.ticketReceived === "boolean" &&
      typeof v.resolvedNotice === "boolean" &&
      typeof v.closedNotice === "boolean" &&
      typeof v.pendingReminder === "boolean"
    ) {
      return {
        ticketReceived: v.ticketReceived,
        resolvedNotice: v.resolvedNotice,
        closedNotice: v.closedNotice,
        pendingReminder: v.pendingReminder,
      };
    }
  }
  throw new Error("API answered with an unexpected shape");
}

export function getClientEmails(id: number, signal?: AbortSignal): Promise<ClientEmailSwitches> {
  return apiFetch(`${BASE}/${id}/client-emails`, parseClientEmailSwitches, { signal });
}

export function saveClientEmails(
  id: number,
  switches: ClientEmailSwitches,
): Promise<ClientEmailSwitches> {
  return apiFetch(`${BASE}/${id}/client-emails`, parseClientEmailSwitches, {
    method: "PUT",
    body: switches,
  });
}
