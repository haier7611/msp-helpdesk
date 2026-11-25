import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";
import { agentApi, triageDetail } from "../../test/agentApi";
import type { Mailbox } from "../../api/admin";
import { readFileSync } from "node:fs";
import { systemEntryText, systemKindLabel } from "../agent/systemEntryLabels";
import { defaultTrustedFor, TRUSTED_HINT, trustedAfterHostChange } from "./mailboxDefaults";

// Vitest does not process CSS (imports come back empty), so read the source files (cwd is web/).
const tokensCss = readFileSync("src/styles/tokens.css", "utf8");
const formsCss = readFileSync("src/components/forms.module.css", "utf8");

/** WP-19 (ADR-036): compact density, the reworked mailbox dialog and readable triage reasons. */

type Call = { path: string; method: string; body: unknown };

const MAILBOX: Mailbox = {
  id: 3,
  name: "Support",
  address: "support@helpdesk.example",
  enabled: true,
  imapHost: "imap.gmail.com",
  imapPort: 993,
  imapSecurity: "TLS",
  imapFolder: "INBOX",
  authType: "PASSWORD",
  username: "support@helpdesk.example",
  secretSet: true,
  oauthTokenUrl: null,
  oauthClientId: null,
  oauthScope: null,
  trustedAuthservIds: [],
  lastPollAt: null,
  lastSuccessAt: null,
  lastError: null,
  consecutiveFailures: 0,
  smtpHost: null,
  smtpPort: null,
  smtpSecurity: null,
  smtpSameSignIn: true,
  smtpUsername: null,
  smtpSecretSet: false,
  sending: false,
  smtpLastError: null,
};

function mailboxApi(rows: Mailbox[]) {
  const calls: Call[] = [];
  const api = (path: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
    calls.push({ path, method, body });
    if (method === "GET" && path === "/api/v1/admin/email/mailboxes") return jsonResponse(rows);
    if (method === "PUT" || method === "POST") return jsonResponse(rows[0] ?? MAILBOX);
    return undefined;
  };
  return { api, calls };
}

async function openMailboxes(rows: Mailbox[]) {
  const stub = mailboxApi(rows);
  renderAt("/admin/email", undefined, { api: stub.api });
  await screen.findByRole("button", { name: "Add mailbox" }, { timeout: 5000 });
  return stub;
}

describe("Trusted authserv-ids defaults (WP-19)", () => {
  it("knows Gmail and Microsoft 365, nothing else", () => {
    expect(defaultTrustedFor("imap.gmail.com")).toBe("mx.google.com");
    expect(defaultTrustedFor(" IMAP.GoogleMail.com ")).toBe("mx.google.com");
    expect(defaultTrustedFor("outlook.office365.com")).toBe("(none)");
    expect(defaultTrustedFor("imap.helpdesk.example")).toBeNull();
    expect(defaultTrustedFor("imap.gmail.com.evil.example")).toBeNull();
    expect(TRUSTED_HINT).toBe("For Gmail use mx.google.com; for Microsoft 365 use (none).");
  });

  it("fills an empty field, follows the server while it holds a default, never replaces typing", () => {
    expect(trustedAfterHostChange("", "imap.gmail.com", "")).toBe("mx.google.com");
    expect(trustedAfterHostChange("imap.gmail.com", "outlook.office365.com", "mx.google.com")).toBe(
      "(none)",
    );
    expect(trustedAfterHostChange("imap.gmail.com", "imap.gmail.comx", "mx.google.com")).toBe("");
    expect(
      trustedAfterHostChange("imap.gmail.com", "outlook.office365.com", "mx.own.example"),
    ).toBe("mx.own.example");
    expect(trustedAfterHostChange("", "imap.helpdesk.example", "")).toBe("");
  });

  it("pre-fills mx.google.com for a Gmail mailbox saved with an empty list", async () => {
    const { calls } = await openMailboxes([MAILBOX]);
    fireEvent.click(await screen.findByRole("button", { name: "Edit support@helpdesk.example" }));
    const dialog = await screen.findByRole("dialog", { name: "Edit mailbox" });
    expect(within(dialog).getByLabelText(/^Trusted authserv-ids/)).toHaveValue("mx.google.com");
    expect(
      within(dialog).getByText(new RegExp(TRUSTED_HINT.replace(/[().]/g, "\\$&"))),
    ).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save mailbox" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    const put = calls.find((c) => c.method === "PUT")?.body as Record<string, unknown>;
    expect(put.trustedAuthservIds).toEqual(["mx.google.com"]);
  }, 20000);

  it("fills (none) when a new mailbox's server is Microsoft 365", async () => {
    await openMailboxes([]);
    fireEvent.click(screen.getByRole("button", { name: "Add mailbox" }));
    const dialog = await screen.findByRole("dialog", { name: "Add mailbox" });
    const trusted = within(dialog).getByLabelText(/^Trusted authserv-ids/);
    expect(trusted).toHaveValue("");
    fireEvent.change(within(dialog).getByLabelText("IMAP server"), {
      target: { value: "outlook.office365.com" },
    });
    expect(trusted).toHaveValue("(none)");
    fireEvent.change(trusted, { target: { value: "mx.own.example" } });
    fireEvent.change(within(dialog).getByLabelText("IMAP server"), {
      target: { value: "imap.gmail.com" },
    });
    expect(trusted).toHaveValue("mx.own.example");
  }, 20000);
});

describe("Mailbox dialog layout (ADR-036)", () => {
  it("has Incoming and Outgoing sections, inline sign-in radios, info toggles and a closed Advanced", async () => {
    await openMailboxes([MAILBOX]);
    fireEvent.click(await screen.findByRole("button", { name: "Edit support@helpdesk.example" }));
    const dialog = await screen.findByRole("dialog", { name: "Edit mailbox" });
    const incoming = within(dialog).getByRole("group", { name: "Incoming (IMAP)" });
    within(dialog).getByRole("group", { name: "Outgoing (SMTP)" });
    const signIn = within(incoming).getByRole("group", { name: "Sign-in method" });
    expect(within(signIn).getAllByRole("radio")).toHaveLength(2);

    const info = within(incoming).getByRole("button", { name: "More about incoming email" });
    expect(info).toHaveAttribute("aria-expanded", "false");
    const detail = document.getElementById(info.getAttribute("aria-controls") ?? "");
    expect(detail).not.toBeVisible();
    fireEvent.click(info);
    expect(info).toHaveAttribute("aria-expanded", "true");
    expect(detail).toBeVisible();

    const advanced = within(incoming).getByText("Advanced").closest("details");
    expect(advanced).not.toHaveAttribute("open");
    expect(within(advanced as HTMLElement).getByLabelText("Folder")).toHaveValue("INBOX");
    expect(within(incoming).getByLabelText("Port").closest("div")?.className).toMatch(/sizeXs/);
    expect(await axeViolations()).toEqual([]);
  }, 20000);

  it("shows the last sending error next to the incoming error", async () => {
    await openMailboxes([
      { ...MAILBOX, lastError: "Login failed", smtpLastError: "SMTP sign-in failed" },
    ]);
    const table = screen.getByRole("table", { name: "Mailboxes" });
    expect((await within(table).findByText("SMTP sign-in failed")).parentElement).toHaveTextContent(
      "Sending: SMTP sign-in failed",
    );
    expect(within(table).getByText("Login failed").parentElement).toHaveTextContent(
      "Incoming: Login failed",
    );
  }, 20000);
});

describe("Readable system entries (WP-19)", () => {
  it("maps codes and triage reasons at display time", () => {
    expect(systemKindLabel("EMAIL_TRIAGE")).toBe("Email triage");
    expect(systemKindLabel("EMAIL_NOT_SENT")).toBe("Email not sent");
    expect(systemKindLabel("SOMETHING_NEW")).toBe("Something new");
    expect(systemEntryText("EMAIL_TRIAGE", "Email triage: Sender not authenticated")).toBe(
      "Couldn't verify the sender (DMARC). If this is your own mailbox provider, add it under Trusted authserv-ids (Admin - Email - Mailboxes - Advanced).",
    );
    expect(
      systemEntryText("EMAIL_TRIAGE", "Email triage: Not a participant of the ticket"),
    ).toMatch(/isn't the reporter, a CC on the ticket/);
    expect(systemEntryText("EMAIL_TRIAGE", "Email triage: Reference not found")).toMatch(
      /doesn't match any ticket/,
    );
    expect(
      systemEntryText("EMAIL_TRIAGE", "Email triage: Reference belongs to another organisation"),
    ).toMatch(/belongs to a different organisation/);
    expect(systemEntryText("EMAIL_TRIAGE", "Email triage: Something else")).toBe("Something else");
    expect(systemEntryText("EMAIL_AUTO_REPLY", "Automatic email from a@b.example: Away")).toBe(
      "Automatic email from a@b.example: Away",
    );
  });

  it("shows the human label on the ticket page, never the raw code", async () => {
    const stub = agentApi({
      "GET /api/v1/agent/tickets/7": () =>
        jsonResponse({
          ...triageDetail,
          systemEntries: [
            {
              id: 1,
              kind: "EMAIL_TRIAGE",
              text: "Email triage: Sender not authenticated",
              createdAt: "2026-11-18T02:05:00Z",
            },
          ],
        }),
    });
    renderAt("/agent/tickets/TRI-007", undefined, { role: "AGENT", api: stub.api });
    const entry = await screen.findByRole("article", { name: "System entry" }, { timeout: 5000 });
    expect(entry).toHaveTextContent("Email triage -");
    expect(entry).toHaveTextContent("Couldn't verify the sender (DMARC).");
    expect(entry).not.toHaveTextContent("EMAIL_TRIAGE");
  }, 20000);
});

describe("Compact density CSS (ADR-036)", () => {
  it("uses compact controls on fine pointers and 48 px targets on coarse pointers", () => {
    expect(tokensCss).toMatch(/--touch-target:\s*48px/);
    expect(tokensCss).toMatch(/--control-size:\s*38px/);
    const coarse = /@media \(pointer: coarse\)\s*{([\s\S]*?)\n}/.exec(tokensCss)?.[1] ?? "";
    expect(coarse).toMatch(/--control-size:\s*var\(--touch-target\)/);
    expect(coarse).toMatch(/--choice-size:\s*var\(--touch-target\)/);
    // Nothing on fine pointers below WCAG 2.2 AA target size (24 px).
    for (const m of tokensCss.matchAll(/--(control|choice|info)-size:\s*(\d+)px/g)) {
      expect(Number(m[2])).toBeGreaterThanOrEqual(24);
    }
    expect(formsCss).toMatch(/min-height:\s*var\(--control-size\)/);
    expect(formsCss).toMatch(/\.sizeXs\s*{/);
  });
});
