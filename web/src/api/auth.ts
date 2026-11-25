import { clearAllDrafts } from "../draftStorage";
import { apiFetch } from "./client";

/**
 * The sign-in API (/api/v1/auth). Link tokens travel in request bodies; the pages read
 * them from the URL fragment (#token=...), which browsers never send to a server.
 */

export type Role = "CUSTOMER" | "AGENT" | "ADMIN";

export type Me = {
  id: number;
  email: string;
  name: string;
  role: Role;
  authLevel: string | null;
  /** Set only for an engineer whose two-step exemption applies now: ISO end time. */
  mfaExemptUntil?: string | null;
};

export type PendingInvitation = {
  email: string;
  name: string;
};

function isRole(value: unknown): value is Role {
  return value === "CUSTOMER" || value === "AGENT" || value === "ADMIN";
}

export function parseMe(value: unknown): Me {
  if (typeof value === "object" && value !== null) {
    const v = value as Record<string, unknown>;
    if (
      typeof v.id === "number" &&
      typeof v.email === "string" &&
      typeof v.name === "string" &&
      isRole(v.role)
    ) {
      return {
        id: v.id,
        email: v.email,
        name: v.name,
        role: v.role,
        authLevel: typeof v.authLevel === "string" ? v.authLevel : null,
        mfaExemptUntil: typeof v.mfaExemptUntil === "string" ? v.mfaExemptUntil : null,
      };
    }
  }
  throw new Error("API answered with an unexpected shape");
}

function parsePending(value: unknown): PendingInvitation {
  if (typeof value === "object" && value !== null) {
    const v = value as Record<string, unknown>;
    if (typeof v.email === "string" && typeof v.name === "string") {
      return { email: v.email, name: v.name };
    }
  }
  throw new Error("API answered with an unexpected shape");
}

const ignore = () => undefined;

/** Where each role lands after signing in. */
export function homeFor(role: Role): string {
  return role === "CUSTOMER" ? "/portal/new-ticket" : "/agent/queue";
}

export function signIn(email: string, password: string): Promise<Me> {
  return apiFetch("/api/v1/auth/sign-in", parseMe, {
    method: "POST",
    body: { email, password },
  });
}

export function signOut(): Promise<void> {
  // Unsent drafts are wiped even if the call fails: the person meant to leave.
  clearAllDrafts();
  return apiFetch("/api/v1/auth/sign-out", ignore, { method: "POST", body: {} });
}

export function fetchMe(signal?: AbortSignal): Promise<Me> {
  return apiFetch("/api/v1/auth/me", parseMe, { signal });
}

export function requestPasswordReset(email: string): Promise<void> {
  return apiFetch("/api/v1/auth/password-reset/request", ignore, {
    method: "POST",
    body: { email },
  });
}

export function confirmPasswordReset(token: string, password: string): Promise<void> {
  return apiFetch("/api/v1/auth/password-reset/confirm", ignore, {
    method: "POST",
    body: { token, password },
  });
}

export function requestMagicLink(email: string): Promise<void> {
  return apiFetch("/api/v1/auth/magic-link/request", ignore, {
    method: "POST",
    body: { email },
  });
}

export function consumeMagicLink(token: string): Promise<Me> {
  return apiFetch("/api/v1/auth/magic-link/consume", parseMe, {
    method: "POST",
    body: { token },
  });
}

export function inspectInvitation(token: string): Promise<PendingInvitation> {
  return apiFetch("/api/v1/auth/invitations/inspect", parsePending, {
    method: "POST",
    body: { token },
  });
}

/** No invitation code - the single-use link plus a password is enough. */
export function acceptInvitation(token: string, password: string): Promise<void> {
  return apiFetch("/api/v1/auth/invitations/accept", ignore, {
    method: "POST",
    body: { token, password },
  });
}

export function fetchSetupAvailable(signal?: AbortSignal): Promise<boolean> {
  return apiFetch(
    "/api/v1/auth/setup",
    (b) => typeof b === "object" && b !== null && (b as { available?: unknown }).available === true,
    { signal },
  );
}

export function completeSetup(
  token: string,
  email: string,
  name: string,
  password: string,
): Promise<void> {
  return apiFetch("/api/v1/auth/setup", ignore, {
    method: "POST",
    body: { token, email, name, password },
  });
}

/** The token from a "#token=..." URL fragment, or null. Pure: does not change the URL. */
export function tokenFromFragment(hash: string): string | null {
  const match = /(?:^#|&)token=([A-Za-z0-9_-]+)/.exec(hash);
  return match?.[1] ?? null;
}

/** Client-side check matching the server's minimum (the server also checks the breached list). */
export const MIN_PASSWORD_LENGTH = 12;
