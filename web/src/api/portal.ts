import {
  ApiError,
  apiFetch,
  CSRF_HEADER,
  csrfToken,
  errorFromResponse,
  fetchOrNetworkError,
} from "./client";

/**
 * The customer portal API (/api/v1/portal): request types, uploads, raise a ticket
 * and the customer's own account. Nothing here reads a ticket - the portal is raise-only.
 */

/** In display order from the server; `description` is short helper text, or null. */
export type RequestType = { id: number; name: string; description: string | null };

export type Impact = "JUST_ME" | "A_FEW_PEOPLE" | "WHOLE_OFFICE";
export type Urgency = "NUISANCE" | "PARTLY" | "WORK_STOPPED";

export type Upload = { id: string; filename: string; size: number };

export type NewTicket = {
  requestTypeId: number;
  subject: string;
  descriptionHtml: string;
  impact: Impact;
  urgency: Urgency;
  attachmentIds: string[];
  idempotencyKey: string;
};

export type Account = {
  name: string;
  email: string;
  organisationName: string;
  microsoftSignInAvailable: boolean;
  microsoftLinked: boolean;
};

/** Same limit as the server. */
export const MAX_FILE_BYTES = 20 * 1024 * 1024;

type Obj = Record<string, unknown>;

function obj(value: unknown): Obj {
  if (typeof value === "object" && value !== null && !Array.isArray(value)) return value as Obj;
  throw new Error("API answered with an unexpected shape");
}

function str(v: unknown): string {
  if (typeof v !== "string") throw new Error("API answered with an unexpected shape");
  return v;
}

function num(v: unknown): number {
  if (typeof v !== "number") throw new Error("API answered with an unexpected shape");
  return v;
}

export function parseRequestTypes(value: unknown): RequestType[] {
  if (!Array.isArray(value)) throw new Error("API answered with an unexpected shape");
  return value.map((v) => {
    const o = obj(v);
    const description = o.description == null ? null : str(o.description);
    return { id: num(o.id), name: str(o.name), description };
  });
}

export function parseUpload(value: unknown): Upload {
  const o = obj(value);
  return { id: str(o.id), filename: str(o.filename), size: num(o.size) };
}

export function parseAccount(value: unknown): Account {
  const o = obj(value);
  return {
    name: str(o.name),
    email: str(o.email),
    organisationName: str(o.organisationName),
    microsoftSignInAvailable: o.microsoftSignInAvailable === true,
    microsoftLinked: o.microsoftLinked === true,
  };
}

export function fetchRequestTypes(signal?: AbortSignal): Promise<RequestType[]> {
  return apiFetch("/api/v1/portal/request-types", parseRequestTypes, { signal });
}

export function raiseTicket(ticket: NewTicket): Promise<string> {
  return apiFetch("/api/v1/portal/tickets", (b) => str(obj(b).reference), {
    method: "POST",
    body: ticket,
  });
}

export function fetchAccount(signal?: AbortSignal): Promise<Account> {
  return apiFetch("/api/v1/portal/account", parseAccount, { signal });
}

export function renameAccount(name: string): Promise<Account> {
  return apiFetch("/api/v1/portal/account", parseAccount, { method: "PATCH", body: { name } });
}

/**
 * Uploads one file for the New ticket form (multipart, so not through apiFetch's JSON body). Same
 * CSRF header and error shape as every other call.
 */
export async function uploadFile(file: File): Promise<Upload> {
  let token = csrfToken();
  if (!token) {
    await fetch("/api/v1/auth/csrf", { credentials: "same-origin" }).catch(() => undefined);
    token = csrfToken();
  }
  const form = new FormData();
  form.append("file", file);
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) headers[CSRF_HEADER] = token;
  const response = await fetchOrNetworkError("/api/v1/portal/attachments", {
    method: "POST",
    headers,
    body: form,
    credentials: "same-origin",
  });
  if (!response.ok) {
    const error = await errorFromResponse(response);
    if (response.status === 413) {
      throw new ApiError(413, "That file is too large (up to 20 MB each).", error.code);
    }
    throw error;
  }
  return parseUpload(await response.json());
}

/** Plain text from the form as the minimal HTML the server stores (escaped, one <p> per paragraph). */
export function textToHtml(text: string): string {
  const escape = (s: string) =>
    s
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  return text
    .trim()
    .split(/\n{2,}/)
    .map((para) => `<p>${escape(para).replace(/\n/g, "<br>")}</p>`)
    .join("");
}

/** A random key per form, so a double submit cannot raise two tickets. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
