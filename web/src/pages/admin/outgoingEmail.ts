import type { ImapSecurity, Mailbox, MailboxInput } from "../../api/admin";

/** The outgoing (SMTP) part of the mailbox form. Ports are strings while editing. */
export type OutgoingDraft = {
  smtpHost: string;
  smtpPort: string;
  smtpSecurity: ImapSecurity;
  smtpSameSignIn: boolean;
  smtpUsername: string;
  smtpSecret: string;
  smtpSecretSet: boolean;
};

export const EMPTY_OUTGOING: OutgoingDraft = {
  smtpHost: "",
  smtpPort: "",
  smtpSecurity: "TLS",
  smtpSameSignIn: true,
  smtpUsername: "",
  smtpSecret: "",
  smtpSecretSet: false,
};

export function outgoingDraft(m: Mailbox): OutgoingDraft {
  return {
    smtpHost: m.smtpHost ?? "",
    smtpPort: m.smtpPort === null ? "" : String(m.smtpPort),
    smtpSecurity: m.smtpSecurity ?? "TLS",
    smtpSameSignIn: m.smtpSameSignIn,
    smtpUsername: m.smtpUsername ?? "",
    smtpSecret: "",
    smtpSecretSet: m.smtpSecretSet,
  };
}

type OutgoingInput = Pick<
  MailboxInput,
  "smtpHost" | "smtpPort" | "smtpSecurity" | "smtpSameSignIn" | "smtpUsername" | "smtpSecret"
>;

/**
 * The outgoing fields to send, or an error message to show. An empty server sends no outgoing
 * settings at all: ticket and account mail then keep using the server's mail settings.
 */
export function outgoingInput(d: OutgoingDraft): OutgoingInput | string {
  const host = d.smtpHost.trim();
  if (host === "") {
    return {
      smtpHost: null,
      smtpPort: null,
      smtpSecurity: null,
      smtpSameSignIn: true,
      smtpUsername: null,
      smtpSecret: null,
    };
  }
  const port = d.smtpPort.trim() === "" ? null : Number(d.smtpPort);
  if (port !== null && (!Number.isInteger(port) || port < 1 || port > 65535)) {
    return "Enter an SMTP port from 1 to 65535.";
  }
  if (!d.smtpSameSignIn) {
    if (d.smtpUsername.trim() === "") return "Enter the SMTP user name.";
    if (d.smtpSecret === "" && !d.smtpSecretSet) return "Enter the SMTP password.";
  }
  return {
    smtpHost: host,
    smtpPort: port,
    smtpSecurity: d.smtpSecurity,
    smtpSameSignIn: d.smtpSameSignIn,
    smtpUsername: d.smtpSameSignIn ? null : d.smtpUsername.trim(),
    smtpSecret: d.smtpSameSignIn || d.smtpSecret === "" ? null : d.smtpSecret,
  };
}
