import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";
import { agentApi, detail } from "../../test/agentApi";

// ADR-041: the close-reason dialog and the admin reopen dialog on the agent ticket page.

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

const noContent = () => new Response(null, { status: 204 });

const resolved = {
  ...detail,
  status: "RESOLVED",
  statusLabel: "Resolved",
  allowedStatuses: ["OPEN", "CLOSED"],
};

const closed = {
  ...detail,
  status: "CLOSED",
  statusLabel: "Closed",
  allowedStatuses: [],
  closeReason: "OTHER",
  closeReasonLabel: "Other",
  closeNote: "Moved to the new contract",
};

async function openTicket(
  role: "AGENT" | "ADMIN",
  ticket: object,
  handlers: Record<string, (body: unknown, path: string) => Response | undefined> = {},
) {
  const stub = agentApi({
    "GET /api/v1/agent/tickets/42": () => jsonResponse(ticket),
    ...handlers,
  });
  renderAt("/agent/tickets/ACD-042", undefined, { role, api: stub.api });
  await screen.findByRole("heading", { level: 1 }, { timeout: 5000 });
  return stub;
}

function chooseClosed() {
  fireEvent.change(screen.getByLabelText("Status"), { target: { value: "CLOSED" } });
  return screen.getByRole("dialog", { name: "Close ticket" });
}

describe("Close ticket dialog (ADR-041)", () => {
  it("opens from the status dropdown with the reasons and the warning, and has no axe violations", async () => {
    const { calls } = await openTicket("AGENT", resolved);
    const dialog = chooseClosed();
    const group = within(dialog).getByRole("group", { name: "Reason for closing" });
    expect(
      within(group)
        .getAllByRole("radio")
        .map((r) => (r.closest("label") as HTMLElement).textContent),
    ).toEqual([
      "Duplicate",
      "Spam / junk",
      "Resolved - no follow-up needed",
      "Client cancelled / no longer needed",
      "No response from client",
      "Other",
    ]);
    expect(dialog).toHaveTextContent("Agents can't reopen a closed ticket. Only an admin can.");
    expect(within(dialog).getByLabelText(/Internal note \(optional\)/)).toBeInTheDocument();
    // Nothing is sent just by choosing Closed.
    expect(calls.some((c) => c.path.endsWith("/status"))).toBe(false);
    expect(await axeViolations()).toEqual([]);
  });

  it("Cancel keeps the previous status and sends nothing", async () => {
    const { calls } = await openTicket("AGENT", resolved);
    const dialog = chooseClosed();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog", { name: "Close ticket" })).toBeNull();
    expect(screen.getByLabelText("Status")).toHaveValue("RESOLVED");
    expect(calls.some((c) => c.method === "POST")).toBe(false);
  });

  it("needs a reason, and Other needs a note of 3 to 500 characters", async () => {
    const { calls } = await openTicket("AGENT", resolved, {
      "POST /api/v1/agent/tickets/42/status": () => jsonResponse({ status: "CLOSED" }),
    });
    const dialog = chooseClosed();
    fireEvent.click(within(dialog).getByRole("button", { name: "Close ticket" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent(
      "Choose a reason for closing the ticket.",
    );
    fireEvent.click(within(dialog).getByRole("radio", { name: "Other" }));
    const note = within(dialog).getByLabelText(/Why are you closing it\?/);
    expect(note).toBeRequired();
    fireEvent.change(note, { target: { value: "ab" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Close ticket" }));
    expect(await within(dialog).findByText(/3 to 500 characters/)).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith("/status"))).toBe(false);

    fireEvent.change(note, { target: { value: "  Moved to the new contract " } });
    fireEvent.change(within(dialog).getByLabelText(/Internal note \(optional\)/), {
      target: { value: "Checked with <Sam>" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Close ticket" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "Close ticket" })).not.toBeInTheDocument(),
    );
    expect(calls.find((c) => c.path.endsWith("/status"))?.body).toEqual({
      status: "CLOSED",
      closeReason: "OTHER",
      closeNote: "Moved to the new contract",
    });
    expect(calls.find((c) => c.path.endsWith("/internal-notes"))?.body).toEqual({
      bodyHtml: "<p>Checked with &lt;Sam&gt;</p>",
      attachmentIds: [],
    });
  });

  it("Duplicate switches to the mark-as-duplicate picker without closing", async () => {
    const { calls } = await openTicket("AGENT", resolved);
    const dialog = chooseClosed();
    fireEvent.click(within(dialog).getByRole("radio", { name: "Duplicate" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Choose the other ticket" }));
    expect(await screen.findByRole("dialog", { name: "Mark as duplicate" })).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Close ticket" })).toBeNull();
    expect(calls.some((c) => c.path.endsWith("/status"))).toBe(false);
  });

  it("Spam offers an agent only a hint to ask an admin", async () => {
    await openTicket("AGENT", resolved);
    const dialog = chooseClosed();
    fireEvent.click(within(dialog).getByRole("radio", { name: "Spam / junk" }));
    expect(within(dialog).queryByRole("checkbox", { name: /Block this sender/ })).toBeNull();
    expect(dialog).toHaveTextContent("ask an admin to add it to the block list");
  });

  it("Spam lets an admin block the sender after closing", async () => {
    const { calls } = await openTicket("ADMIN", resolved, {
      "POST /api/v1/agent/tickets/42/status": () => jsonResponse({ status: "CLOSED" }),
      "POST /api/v1/admin/email/block-rules": () =>
        jsonResponse(
          { id: 5, kind: "ADDRESS", value: "maria@acmedental.example", createdAt: "x" },
          201,
        ),
    });
    const dialog = chooseClosed();
    fireEvent.click(within(dialog).getByRole("radio", { name: "Spam / junk" }));
    fireEvent.click(
      within(dialog).getByRole("checkbox", {
        name: "Block this sender (maria@acmedental.example)",
      }),
    );
    fireEvent.click(within(dialog).getByRole("button", { name: "Close ticket" }));
    expect(
      await screen.findByText("Ticket closed and maria@acmedental.example blocked."),
    ).toBeInTheDocument();
    const posts = calls.filter((c) => c.method === "POST").map((c) => c.path);
    expect(posts).toEqual(["/api/v1/agent/tickets/42/status", "/api/v1/admin/email/block-rules"]);
    expect(calls.find((c) => c.path.endsWith("/block-rules"))?.body).toEqual({
      kind: "ADDRESS",
      value: "maria@acmedental.example",
      confirm: false,
    });
  });

  it("shows the server's refusal in the dialog and as a pop-up", async () => {
    await openTicket("AGENT", resolved, {
      "POST /api/v1/agent/tickets/42/status": () =>
        jsonResponse(
          { error: "close_reason_required", message: "Choose a reason for closing the ticket." },
          400,
        ),
    });
    const dialog = chooseClosed();
    fireEvent.click(within(dialog).getByRole("radio", { name: "No response from client" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Close ticket" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "Choose a reason for closing the ticket.",
    );
    expect(screen.getByTestId("error-popups")).toHaveTextContent("The ticket was not closed.");
  });
});

describe("Reopen ticket (ADR-041)", () => {
  it("shows the close reason; agents get no Reopen button", async () => {
    await openTicket("AGENT", closed);
    const details = screen.getByRole("complementary", { name: "Ticket details" });
    expect(details).toHaveTextContent("Close reasonOther - Moved to the new contract");
    expect(screen.queryByRole("button", { name: "Reopen ticket" })).toBeNull();
  });

  it("an admin reopens with a reason after a step-up, and the dialog has no axe violations", async () => {
    let n = 0;
    const { calls } = await openTicket("ADMIN", closed, {
      "POST /api/v1/admin/tickets/42/reopen": () => {
        n += 1;
        return n === 1
          ? jsonResponse({ error: "step_up_required", message: "Confirm it is you." }, 403)
          : jsonResponse({ status: "OPEN" });
      },
      "POST /api/v1/auth/step-up": noContent,
    });
    fireEvent.click(screen.getByRole("button", { name: "Reopen ticket" }));
    const dialog = screen.getByRole("dialog", { name: "Reopen ticket" });
    expect(await axeViolations()).toEqual([]);
    const reason = within(dialog).getByLabelText(/Reason for reopening/);
    expect(reason).toBeRequired();
    fireEvent.click(within(dialog).getByRole("button", { name: "Reopen ticket" }));
    expect(await within(dialog).findByText(/3 to 500 characters/)).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith("/reopen"))).toBe(false);

    fireEvent.change(reason, { target: { value: "Client came back" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Reopen ticket" }));
    const stepUp = await screen.findByRole("dialog", { name: "Confirm it is you" });
    fireEvent.change(within(stepUp).getByLabelText("6-digit code"), {
      target: { value: "123456" },
    });
    fireEvent.click(within(stepUp).getByRole("button", { name: "Confirm" }));
    expect(await screen.findByText("ACD-042 reopened.")).toBeInTheDocument();
    expect(calls.filter((c) => c.path.endsWith("/reopen")).map((c) => c.body)).toEqual([
      { reason: "Client came back" },
      { reason: "Client came back" },
    ]);
  });

  it("a merged duplicate offers no Reopen button", async () => {
    await openTicket("ADMIN", {
      ...closed,
      duplicateOf: { ticketId: 40, reference: "ACD-040" },
    });
    expect(screen.queryByRole("button", { name: "Reopen ticket" })).toBeNull();
  });
});
