/**
 * Typed API client skeleton. Every call goes to the same origin (Caddy proxies /api), sends the
 * session cookie, and - for anything that changes state - echoes the CSRF token Spring Security
 * puts in a readable cookie back as the X-XSRF-TOKEN header (cookie-to-header pattern). Behind
 * HTTPS the cookie is __Host-XSRF-TOKEN (a sibling subdomain cannot plant it); plain-http local
 * runs use XSRF-TOKEN. A 401 from any call hands over to the unauthorised handler, which
 * the app wires to /sign-in.
 */

export const CSRF_COOKIE = "XSRF-TOKEN";
export const SECURE_CSRF_COOKIE = "__Host-XSRF-TOKEN";
export const CSRF_HEADER = "X-XSRF-TOKEN";
const CSRF_ENDPOINT = "/api/v1/auth/csrf";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * A failed API call. `message` is always safe to show the user: a plain British-English sentence
 * chosen by status (see friendlyMessage), using the server's own text only when the body had the
 * known {"error", "message"} shape. Status 0 means the request never reached the server.
 */
export class ApiError extends Error {
  readonly status: number;
  /** The server's machine code, e.g. "invalid_link" or "weak_password", when it sent one. */
  readonly code?: string;

  constructor(status: number, message: string, code?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export const MESSAGES = {
  forbidden: "You don't have permission to do that.",
  notFound: "That item no longer exists or you can't see it.",
  conflict: "That changed while you were working on it. Refresh and try again.",
  tooLarge: "That file is too large.",
  invalid: "Some of the details are missing or not valid.",
  tooMany: "Too many attempts - please wait a moment.",
  server: "Something went wrong on our side. Please try again.",
  network: "Connection lost. Check your internet and try again.",
  generic: "Something went wrong. Please try again.",
} as const;

/**
 * The message shown for a non-2xx answer. `serverMessage` is only passed when the body had the
 * known {"error", "message"} shape (the server's own curated text); anything else (a proxy page,
 * a framework error, a stack trace) is never shown. 403 and 404 keep the server's text only for a
 * specific refusal code (e.g. "step_up_required", "mfa_not_enrolled"); the plain "forbidden" and
 * "not_found" codes get the generic sentence.
 */
export function friendlyMessage(status: number, serverMessage?: string, code?: string): string {
  if (status === 0) return MESSAGES.network;
  if (status >= 500) return MESSAGES.server;
  // The server's throttle text says how long to wait ("Please wait 4 seconds"), so it wins.
  if (status === 429) return serverMessage ?? MESSAGES.tooMany;
  if (status === 403) {
    return serverMessage && code && code !== "forbidden" ? serverMessage : MESSAGES.forbidden;
  }
  if (status === 404) {
    return serverMessage && code && code !== "not_found" ? serverMessage : MESSAGES.notFound;
  }
  if (status === 409) return serverMessage ?? MESSAGES.conflict;
  if (status === 413) return serverMessage ?? MESSAGES.tooLarge;
  if (status === 400 || status === 422) return serverMessage ?? MESSAGES.invalid;
  return serverMessage ?? MESSAGES.generic;
}

/** True for an aborted request (a page left, a newer request started): never an error to show. */
export function isAbort(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    (error as { name?: unknown }).name === "AbortError"
  );
}

/** The CSRF token from whichever cookie the server uses. */
export function csrfToken(cookies: string = document.cookie): string | undefined {
  return readCookie(SECURE_CSRF_COOKIE, cookies) ?? readCookie(CSRF_COOKIE, cookies);
}

/**
 * Reads {"error", "message"} from an error response. The message is kept only when both are
 * strings (the server's own error shape), so a framework or proxy body is never shown.
 */
async function errorBody(response: Response): Promise<{ code?: string; message?: string }> {
  try {
    const body: unknown = await response.json();
    if (typeof body === "object" && body !== null) {
      const b = body as Record<string, unknown>;
      const code = typeof b.error === "string" ? b.error : undefined;
      const message =
        code && typeof b.message === "string" && b.message.trim() ? b.message : undefined;
      return { code, message };
    }
  } catch {
    // not JSON
  }
  return {};
}

/**
 * For the few calls that use fetch directly (raw uploads, CSV downloads): the ApiError for a
 * non-2xx answer, with the same friendly message and 401 hand-over as apiFetch.
 */
export async function errorFromResponse(response: Response): Promise<ApiError> {
  const { code, message } = await errorBody(response);
  if (response.status === 401) {
    if (code !== "invalid_credentials") unauthorisedHandler?.();
    return new ApiError(401, message ?? "Please sign in again.", code);
  }
  return new ApiError(response.status, friendlyMessage(response.status, message, code), code);
}

/** fetch, but a request that never reached the server throws ApiError(0, network message). */
export async function fetchOrNetworkError(input: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(input, init);
  } catch (error) {
    if (isAbort(error)) throw error;
    throw new ApiError(0, MESSAGES.network, "network");
  }
}

type UnauthorisedHandler = () => void;

let unauthorisedHandler: UnauthorisedHandler | null = null;

/** Registers what happens on a 401 (the router sends the user to /sign-in). Returns an undo. */
export function setUnauthorisedHandler(handler: UnauthorisedHandler | null): () => void {
  unauthorisedHandler = handler;
  return () => {
    if (unauthorisedHandler === handler) {
      unauthorisedHandler = null;
    }
  };
}

/** Reads one cookie by name from document.cookie; undefined when it is not set. */
export function readCookie(name: string, cookies: string = document.cookie): string | undefined {
  for (const part of cookies.split(";")) {
    const index = part.indexOf("=");
    if (index < 0) {
      continue;
    }
    if (part.slice(0, index).trim() === name) {
      const raw = part.slice(index + 1).trim();
      try {
        return decodeURIComponent(raw);
      } catch {
        return raw;
      }
    }
  }
  return undefined;
}

export type ApiRequest = {
  method?: "GET" | "HEAD" | "OPTIONS" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
};

/**
 * Calls the API and returns the parsed JSON body, validated by `parse`.
 * Throws ApiError for any non-2xx answer (after calling the unauthorised handler on 401).
 */
export async function apiFetch<T>(
  path: string,
  parse: (body: unknown) => T,
  request: ApiRequest = {},
): Promise<T> {
  return (await apiFetchWithHeaders(path, parse, request)).body;
}

/**
 * Like apiFetch, but also returns the response headers (e.g. X-Total-Count on the queue). A
 * FormData body is sent as multipart (uploads) instead of JSON.
 */
export async function apiFetchWithHeaders<T>(
  path: string,
  parse: (body: unknown) => T,
  request: ApiRequest = {},
): Promise<{ body: T; headers: Headers }> {
  if (!path.startsWith("/api/")) {
    throw new Error("API paths must start with /api/");
  }
  const method = request.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json" };
  if (!SAFE_METHODS.has(method)) {
    let token = csrfToken();
    if (!token && path !== CSRF_ENDPOINT) {
      // First state-changing call of the visit: ask the server to issue the cookie.
      await fetch(CSRF_ENDPOINT, { credentials: "same-origin", signal: request.signal }).catch(
        () => undefined,
      );
      token = csrfToken();
    }
    if (token) {
      headers[CSRF_HEADER] = token;
    }
  }
  let body: string | FormData | undefined;
  if (request.body instanceof FormData) {
    body = request.body;
  } else if (request.body !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(request.body);
  }

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body,
      credentials: "same-origin",
      signal: request.signal,
    });
  } catch (error) {
    if (isAbort(error)) throw error;
    // The request never reached the server (offline, DNS, connection reset).
    throw new ApiError(0, MESSAGES.network, "network");
  }

  if (!response.ok) {
    // A failed sign-in is a 401 too; it is shown on the form, not turned into a redirect.
    throw await errorFromResponse(response);
  }
  if (response.status === 204 || response.status === 202) {
    const text = response.status === 202 ? await response.text().catch(() => "") : "";
    return {
      body: parse(text ? (JSON.parse(text) as unknown) : undefined),
      headers: response.headers,
    };
  }
  const json: unknown = await response.json();
  return { body: parse(json), headers: response.headers };
}
