/**
 * Readable labels for hidden system entries on the ticket page. The api stores a code
 * (`kind`, e.g. EMAIL_TRIAGE) and a short text (e.g. "Email triage: Sender not authenticated");
 * both stay unchanged in the database and the API, and are mapped here at display time only.
 */

const KIND_LABELS: Record<string, string> = {
  EMAIL_TRIAGE: "Email triage",
  EMAIL_AUTO_REPLY: "Automatic email",
  EMAIL_FOLDED: "Email folded in",
  EMAIL_HEADERS_TRUNCATED: "Email headers cut",
  EMAIL_ATTACHMENTS: "Email attachments",
  EMAIL_NOT_SENT: "Email not sent",
  AUTO_ASSIGN_SKIPPED: "Auto-assignment",
};

/** A readable label for a system entry code; unknown codes become "Sentence case words". */
export function systemKindLabel(kind: string): string {
  const known = KIND_LABELS[kind];
  if (known) return known;
  const words = kind.toLowerCase().replace(/_+/g, " ").trim();
  return words === "" ? "System" : words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * Clearer wording for the triage reasons (api InboundRouter.TriageReason labels), keyed by the
 * stored label in lower case.
 */
const TRIAGE_REASONS: Record<string, string> = {
  "sender not authenticated":
    "Couldn't verify the sender (DMARC). If this is your own mailbox provider, add it under Trusted authserv-ids (Admin - Email - Mailboxes - Advanced).",
  "not a participant of the ticket":
    "The sender isn't the reporter, a CC on the ticket or an agent with access to it.",
  "reference not found": "The ticket reference in the subject doesn't match any ticket.",
  "reference belongs to another organisation":
    "The ticket reference in the subject belongs to a different organisation than the sender.",
  "unknown sender":
    "The sender isn't a known contact and their domain isn't verified for any organisation.",
  "email could not be read": "The email couldn't be read. The original is kept for 90 days.",
  "email could not be processed":
    "Something went wrong while processing this email. The original is kept for 90 days.",
};

const TRIAGE_PREFIX = "email triage:";

/** The text to show for a system entry: triage reasons get the clearer wording above. */
export function systemEntryText(kind: string, text: string): string {
  if (kind !== "EMAIL_TRIAGE") return text;
  let reason = text.trim();
  if (reason.toLowerCase().startsWith(TRIAGE_PREFIX)) {
    reason = reason.slice(TRIAGE_PREFIX.length).trim();
  }
  return TRIAGE_REASONS[reason.toLowerCase()] ?? reason;
}
