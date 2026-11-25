// Stored audit event names never change (they are stored); only their on-screen label does.
const WORDS: Record<string, string> = {
  api: "API",
  mfa: "MFA",
  sla: "SLA",
  qa: "QA",
  entra: "Entra",
  supporter: "agent",
  smtp: "SMTP",
};

/** "SIGN_IN_FAILED" -> "Sign in failed"; "MFA_RESET" -> "MFA reset"; "SUPPORTER_UPDATED" -> "Agent updated". */
export function auditEventLabel(type: string): string {
  const text = type
    .toLowerCase()
    .split("_")
    .filter(Boolean)
    .map((w) => WORDS[w] ?? w)
    .join(" ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}
