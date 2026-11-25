import { apiFetch } from "./client";

/** Longest support name the server accepts (Admin - Branding, MAIL_SUPPORT_NAME). */
const MAX_LENGTH = 100;

export function parseDisplayName(value: unknown): string {
  if (typeof value === "object" && value !== null) {
    const name = (value as Record<string, unknown>).name;
    if (typeof name === "string" && name.trim() && name.length <= MAX_LENGTH) {
      return name.trim();
    }
  }
  throw new Error("API answered with an unexpected shape");
}

/** GET /api/v1/display-name - public, returns only the configured support name. */
export function fetchDisplayName(signal?: AbortSignal): Promise<string> {
  return apiFetch("/api/v1/display-name", parseDisplayName, { signal });
}
