/**
 * The recommended trusted authserv-id for well-known IMAP servers. The api applies the same
 * default when the field is saved empty (MailboxService.defaultTrustedAuthservId), so these two
 * lists must stay in step.
 *
 * - Gmail / Google Workspace stamps `Authentication-Results: mx.google.com; ...`.
 * - Microsoft 365 (Exchange Online) writes no authserv-id at all ("spf=pass ...; dmarc=pass ..."),
 *   which our parser matches with the special value `(none)` (AuthenticationResultsParser
 *   .NO_AUTHSERV_ID). Matching is exact and case-insensitive, never a pattern.
 */
export const GMAIL_AUTHSERV_ID = "mx.google.com";
export const M365_AUTHSERV_ID = "(none)";

export const TRUSTED_HINT = `For Gmail use ${GMAIL_AUTHSERV_ID}; for Microsoft 365 use ${M365_AUTHSERV_ID}.`;

const GMAIL_HOSTS = new Set(["imap.gmail.com", "imap.googlemail.com"]);
const M365_HOSTS = new Set(["outlook.office365.com"]);

/** The recommended trusted authserv-id for an IMAP server, or null when we have none. */
export function defaultTrustedFor(imapHost: string): string | null {
  const host = imapHost.trim().toLowerCase().replace(/\.$/, "");
  if (GMAIL_HOSTS.has(host)) return GMAIL_AUTHSERV_ID;
  if (M365_HOSTS.has(host)) return M365_AUTHSERV_ID;
  return null;
}

/**
 * The trusted authserv-ids after the IMAP server changes from `previousHost` to `nextHost`: an
 * empty field gets the new server's default, and a field still holding the previous server's
 * default (so it was filled in for the admin) follows the server. Anything the admin typed stays.
 */
export function trustedAfterHostChange(
  previousHost: string,
  nextHost: string,
  trusted: string,
): string {
  const current = trusted.trim();
  const previousDefault = defaultTrustedFor(previousHost);
  if (current !== "" && current !== previousDefault) return trusted;
  return defaultTrustedFor(nextHost) ?? "";
}
