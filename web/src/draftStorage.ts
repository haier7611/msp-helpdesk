/**
 * Unsent composer drafts live in sessionStorage under `ticket-draft:<userId>:<ticketId>:<tab>`.
 * The user id is part of the key so a second person signing in in the same tab never sees the
 * first person's text; every entry is wiped on sign-out. Storage can be blocked (private mode,
 * policy), so every access is wrapped and a failure just means no draft is kept.
 */

export const DRAFT_PREFIX = "ticket-draft:";

/** The key prefix for one user's drafts on one ticket (the composer appends `:reply` / `:note`). */
export function draftKeyFor(userId: number, ticketId: number | string): string {
  return `${DRAFT_PREFIX}${userId}:${ticketId}`;
}

function draftKeys(): string[] {
  const keys: string[] = [];
  for (let i = 0; i < sessionStorage.length; i++) {
    const key = sessionStorage.key(i);
    if (key?.startsWith(DRAFT_PREFIX)) keys.push(key);
  }
  return keys;
}

/** Removes every draft (sign-out). */
export function clearAllDrafts(): void {
  try {
    draftKeys().forEach((key) => sessionStorage.removeItem(key));
  } catch {
    // Storage blocked: nothing was stored either.
  }
}

/** Removes drafts that belong to anyone other than `userId` (another person signed in here). */
export function clearDraftsOfOtherUsers(userId: number): void {
  try {
    const own = `${DRAFT_PREFIX}${userId}:`;
    draftKeys()
      .filter((key) => !key.startsWith(own))
      .forEach((key) => sessionStorage.removeItem(key));
  } catch {
    // Storage blocked: nothing was stored either.
  }
}
