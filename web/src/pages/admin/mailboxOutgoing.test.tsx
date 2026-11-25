import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";
import type { Mailbox } from "../../api/admin";
import { EMPTY_OUTGOING, outgoingInput } from "./outgoingEmail";

type Call = { path: string; method: string; body: unknown };

function recordingApi(handlers: Record<string, (body: unknown) => Response>) {
  const calls: Call[] = [];
  const api = (path: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
    calls.push({ path, method, body });
    return handlers[`${method} ${path}`]?.(body);
  };
  return { api, calls };
}

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

async function openEdit(mailbox: Mailbox) {
  const { api, calls } = recordingApi({
    "GET /api/v1/admin/email/mailboxes": () => jsonResponse([mailbox]),
    "PUT /api/v1/admin/email/mailboxes/3": () => jsonResponse(mailbox),
  });
  renderAt("/admin/email", undefined, { api });
  fireEvent.click(
    await screen.findByRole("button", { name: "Edit support@helpdesk.example" }, { timeout: 5000 }),
  );
  const dialog = await screen.findByRole("dialog", { name: "Edit mailbox" });
  return { dialog, calls };
}

function lastPut(calls: Call[]) {
  return calls.filter((c) => c.method === "PUT").at(-1)?.body as Record<string, unknown>;
}

describe("Admin - Email - Mailboxes - Outgoing email (ADR-034)", () => {
  it("is empty by default, explains the server fallback and sends no SMTP settings", async () => {
    const { dialog, calls } = await openEdit(MAILBOX);
    const section = within(dialog).getByRole("group", { name: "Outgoing (SMTP)" });
    expect(
      within(section).getByText(/Leave empty to keep using the server's mail settings\./),
    ).toBeInTheDocument();
    expect(within(section).getByLabelText(/^SMTP server/)).toHaveValue("");
    expect(within(section).queryByLabelText(/^SMTP port/)).not.toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);

    fireEvent.click(within(dialog).getByRole("button", { name: "Save mailbox" }));
    await waitFor(() => expect(lastPut(calls)).toBeDefined());
    const body = lastPut(calls);
    expect(body.smtpHost).toBeNull();
    expect(body.smtpSameSignIn).toBe(true);
    expect(body.smtpSecret).toBeNull();
  }, 20000);

  it("saves Gmail with the same sign-in, and a separate sign-in needs a password", async () => {
    const { dialog, calls } = await openEdit(MAILBOX);
    const section = within(dialog).getByRole("group", { name: "Outgoing (SMTP)" });
    fireEvent.change(within(section).getByLabelText(/^SMTP server/), {
      target: { value: "smtp.gmail.com" },
    });
    fireEvent.change(within(section).getByLabelText(/^SMTP port/), { target: { value: "465" } });
    const same = within(section).getByLabelText("Use the same sign-in as incoming");
    expect(same).toBeChecked();
    expect(await axeViolations()).toEqual([]);

    fireEvent.click(same);
    expect(within(section).getByLabelText(/^SMTP password/)).toHaveValue("");
    expect(within(section).getByText(/use an app password/)).toBeInTheDocument();
    fireEvent.change(within(section).getByLabelText(/^SMTP user name/), {
      target: { value: "sender@helpdesk.example" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save mailbox" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Enter the SMTP password.");
    expect(calls.some((c) => c.method === "PUT")).toBe(false);

    fireEvent.click(same);
    fireEvent.click(within(dialog).getByRole("button", { name: "Save mailbox" }));
    await waitFor(() => expect(lastPut(calls)).toBeDefined());
    expect(lastPut(calls)).toMatchObject({
      smtpHost: "smtp.gmail.com",
      smtpPort: 465,
      smtpSecurity: "TLS",
      smtpSameSignIn: true,
      smtpUsername: null,
      smtpSecret: null,
    });
  }, 20000);

  it("never shows a saved SMTP password and keeps it unless a new one is typed", async () => {
    const { dialog, calls } = await openEdit({
      ...MAILBOX,
      smtpHost: "smtp.gmail.com",
      smtpPort: 587,
      smtpSecurity: "STARTTLS",
      smtpSameSignIn: false,
      smtpUsername: "sender@helpdesk.example",
      smtpSecretSet: true,
      sending: true,
    });
    const section = within(dialog).getByRole("group", { name: "Outgoing (SMTP)" });
    expect(within(section).getByLabelText(/^SMTP password/)).toHaveValue("");
    expect(within(section).getByText(/Saved and encrypted/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save mailbox" }));
    await waitFor(() => expect(lastPut(calls)).toBeDefined());
    expect(lastPut(calls)).toMatchObject({
      smtpHost: "smtp.gmail.com",
      smtpPort: 587,
      smtpSecurity: "STARTTLS",
      smtpSameSignIn: false,
      smtpUsername: "sender@helpdesk.example",
      smtpSecret: null,
    });
  }, 20000);
});

describe("outgoingInput", () => {
  it("checks the port and sends nothing for an empty server", () => {
    expect(outgoingInput({ ...EMPTY_OUTGOING, smtpHost: "smtp.x.example", smtpPort: "0" })).toBe(
      "Enter an SMTP port from 1 to 65535.",
    );
    expect(outgoingInput({ ...EMPTY_OUTGOING, smtpPort: "25" })).toMatchObject({
      smtpHost: null,
      smtpPort: null,
    });
  });
});
