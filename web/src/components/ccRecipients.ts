// The composer's CC editor - types and pure helpers (component in ReplyCc.tsx).

/** Server-side cap too: at most 10 people in CC on a ticket and on a reply. */
export const MAX_CC = 10;

/** One person in CC: a contact (name + userId) or a plain address. */
export type CcPerson = { email: string; name: string | null };

/** What the composer needs to offer the CC editor (agent ticket page). */
export type ReplyCcConfig = {
  /** Confirmed participants (not the reporter): the CC a reply gets today. */
  initial: CcPerson[];
  /** Participants from an email still waiting for an agent's confirmation. */
  pending: CcPerson[];
  organisationName: string;
  verifiedDomains: string[];
  /** Contacts of the ticket's organisation matching the text (server-scoped). */
  searchContacts: (q: string, signal: AbortSignal) => Promise<CcPerson[]>;
  /**
   * The server's early check of a typed address (reason code only). Optional: without it, or when
   * it fails, the editor decides from the verified domains and the server refuses at send time.
   */
  checkAddress?: (email: string) => Promise<CcCheckResult>;
};

/** POST /agent/tickets/{id}/cc-check: how the server would treat an address as a CC. */
export type CcCheckResult = "allowed" | "external" | "staff" | "not_allowed";

/** The editor's message for a refused address (spec: composer, CC on a reply). */
export function ccRefusal(email: string, result: "staff" | "not_allowed"): string {
  return result === "staff"
    ? `${email} is a staff account and can't be CC'd as a client.`
    : `You can't add ${email} to this ticket.`;
}

/** Server codes that refuse a reply because of its CC. */
export const CC_ERROR_CODES = ["cc_not_allowed", "external_cc_unconfirmed", "cc_limit"];

export type CcValue = {
  selected: CcPerson[];
  keep: boolean;
  /** Outside addresses the agent confirmed ("Add anyway"). */
  confirmed: string[];
};

export function initialCcValue(config: ReplyCcConfig): CcValue {
  return { selected: config.initial, keep: true, confirmed: [] };
}

/** Has the agent changed who gets this reply? (Only then is the choice sent.) */
export function ccChanged(config: ReplyCcConfig, value: CcValue): boolean {
  const before = config.initial.map((p) => p.email.toLowerCase()).sort();
  const after = value.selected.map((p) => p.email.toLowerCase()).sort();
  return before.length !== after.length || before.some((e, i) => e !== after[i]);
}

// Plain local@domain, the same shape the server accepts.
const PLAIN_ADDRESS =
  /^[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$/;

export function isPlainAddress(text: string): boolean {
  const v = text.trim().toLowerCase();
  return v.length <= 254 && PLAIN_ADDRESS.test(v);
}

export function ccLabel(p: CcPerson): string {
  return p.name || p.email;
}

/** "Sam Okoye, Office +2" style summary for the composer line. */
export function ccSummary(people: CcPerson[], shown = 2): string {
  if (people.length === 0) return "None";
  const names = people.slice(0, shown).map(ccLabel).join(", ");
  return people.length > shown ? `${names} +${people.length - shown}` : names;
}
