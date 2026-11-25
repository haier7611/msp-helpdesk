import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";
import { agentApi, detail } from "../../test/agentApi";
import { formatDateTime } from "../../format";

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

const noContent = () => new Response(null, { status: 204 });

function typeInto(editor: HTMLElement, html: string) {
  editor.innerHTML = html;
  fireEvent.input(editor);
}

async function openTicket(ref = "ACD-042", handlers = {}) {
  const stub = agentApi(handlers);
  const view = renderAt(`/agent/tickets/${ref}`, undefined, { role: "AGENT", api: stub.api });
  await screen.findByRole("heading", { level: 1 }, { timeout: 5000 });
  return { ...stub, ...view };
}

describe("Agent ticket page", () => {
  it("shows the conversation: client reply via email, public reply, yellow INTERNAL note", async () => {
    await openTicket();
    expect(
      screen.getByRole("heading", { level: 1, name: "Outlook keeps asking for a password" }),
    ).toBeInTheDocument();
    const conversation = screen.getByRole("region", { name: "Conversation" });
    const articles = within(conversation).getAllByRole("article");
    expect(articles).toHaveLength(3);
    // Sorted by time: client, note, reply.
    expect(articles[0]).toHaveTextContent("Maria Gomez");
    expect(articles[0]).toHaveTextContent("via email");
    expect(articles[1]).toHaveAccessibleName("Internal note by Alex Tran");
    expect(within(articles[1] as HTMLElement).getByText("INTERNAL")).toBeInTheDocument();
    expect(articles[2]).toHaveTextContent("Reply to client");
    // Remote images are removed by DOMPurify; attachments get a thumbnail preview.
    expect(conversation.innerHTML).not.toContain("tracker.example");
    const preview = within(conversation).getByRole("img", { name: "Preview of error.png" });
    expect(preview).toHaveAttribute(
      "src",
      "/api/v1/agent/attachments/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/thumbnail",
    );
  });

  it("shows assignee changes as one-line timeline rows in time order, not cards (WP-16)", async () => {
    await openTicket();
    const conversation = screen.getByRole("region", { name: "Conversation" });
    // Still three cards: the assignment lines are not articles.
    expect(within(conversation).getAllByRole("article")).toHaveLength(3);
    const auto = within(conversation).getByText(
      "Assigned to Alex Tran automatically (default for Acme Dental)",
    );
    const moved = within(conversation).getByText(
      "Reassigned from Alex Tran to Sam Lee by Alex Tran",
    );
    const autoRow = auto.closest("p") as HTMLElement;
    const movedRow = moved.closest("p") as HTMLElement;
    // Interleaved by time: created line and client (02:00), auto line (02:10), note (02:20),
    // reply (02:30), move (02:32), priority (02:40).
    const rows = Array.from(conversation.children);
    expect(rows.map((r) => r.tagName)).toEqual([
      "P",
      "ARTICLE",
      "P",
      "ARTICLE",
      "ARTICLE",
      "P",
      "P",
    ]);
    expect(rows[2]).toBe(autoRow);
    expect(rows[5]).toBe(movedRow);
    // Date and time in a real time element, formatted for the viewer.
    const time = within(movedRow).getByText((_, el) => el?.tagName === "TIME");
    expect(time).toHaveAttribute("datetime", "2026-11-18T02:32:00Z");
    expect(time).toHaveTextContent(formatDateTime("2026-11-18T02:32:00Z"));
    expect(time.textContent).toMatch(/^\d{1,2} Nov 2026, \d{2}:\d{2}$/);
    // The icon is decorative only.
    expect(movedRow.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });

  it("shows every other ticket change as the same slim line, and no raw History list (WP-18)", async () => {
    await openTicket();
    const conversation = screen.getByRole("region", { name: "Conversation" });
    const rows = Array.from(conversation.children);
    // At the same instant as the client's description, "Ticket created" comes first.
    expect(rows[0]).toHaveTextContent("Ticket created from an email");
    const priority = within(conversation)
      .getByText("Priority changed from P3 Medium to P2 High by Sam Lee")
      .closest("p") as HTMLElement;
    expect(rows[rows.length - 1]).toBe(priority);
    const time = within(priority).getByText((_, el) => el?.tagName === "TIME");
    expect(time).toHaveAttribute("datetime", "2026-11-18T02:40:00Z");
    expect(time).toHaveTextContent(formatDateTime("2026-11-18T02:40:00Z"));
    expect(priority.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    // The side panel no longer lists raw history rows (ids, enum names).
    const details = screen.getByRole("complementary", { name: "Ticket details" });
    expect(within(details).queryByText(/^History/)).toBeNull();
    expect(details).not.toHaveTextContent("created (- -> EMAIL)");
    expect(await axeViolations()).toEqual([]);
  });

  it("shows references, participants, links and client impact in the side panel", async () => {
    await openTicket();
    const details = screen.getByRole("complementary", { name: "Ticket details" });
    expect(within(details).getByText("TRI-003")).toBeInTheDocument();
    // The address is an Identifier (a line break offered before the "@"), so match the whole item.
    expect(
      within(details).getByText(
        (_, el) =>
          el?.tagName === "LI" &&
          /^it@vendor\.example \(waiting for confirmation\)$/.test(el.textContent ?? ""),
      ),
    ).toBeInTheDocument();
    expect(within(details).getByRole("link", { name: "ACD-040" })).toBeInTheDocument();
    expect(within(details).getByText("Just me, Partly")).toBeInTheDocument();
  });

  it("opens the composer on Internal note when no preference exists, with To and CC on the reply tab", async () => {
    await openTicket();
    expect(await screen.findByRole("tab", { name: "Internal note" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.click(screen.getByRole("tab", { name: "Reply to client" }));
    const composer = screen.getByRole("region", { name: "Write a reply" });
    expect(
      within(composer).getByText("Maria Gomez <maria@acmedental.example>"),
    ).toBeInTheDocument();
    expect(within(composer).getByText("office@acmedental.example")).toBeInTheDocument();
    // An unconfirmed external CC gets nothing until confirmed (WP-07 rules).
    expect(within(composer).queryByText(/it@vendor.example/)).toBeNull();
  });

  it("uses the supporter's default tab from preferences", async () => {
    await openTicket("ACD-042", {
      "GET /api/v1/agent/me/preferences": () =>
        jsonResponse({ composerTab: "REPLY", queueView: "my-open" }),
    });
    expect(await screen.findByRole("tab", { name: "Reply to client" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  it("a note goes to internal-notes and a reply to public-replies with the status", async () => {
    const { calls } = await openTicket("ACD-042", {
      "POST /api/v1/agent/tickets/42/internal-notes": () => jsonResponse({ id: 6 }, 201),
      "POST /api/v1/agent/tickets/42/public-replies": () => jsonResponse({ id: 3 }, 201),
    });
    typeInto(await screen.findByRole("textbox", { name: "Internal note text" }), "<p>Note</p>");
    fireEvent.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() =>
      expect(calls.find((c) => c.path.endsWith("/internal-notes"))?.body).toEqual({
        bodyHtml: "<p>Note</p>",
        attachmentIds: [],
      }),
    );
    fireEvent.click(screen.getByRole("tab", { name: "Reply to client" }));
    typeInto(screen.getByRole("textbox", { name: "Reply text" }), "<p>Reply</p>");
    fireEvent.click(screen.getByRole("button", { name: "Send and set Pending on client" }));
    await waitFor(() =>
      expect(calls.find((c) => c.path.endsWith("/public-replies"))?.body).toEqual({
        bodyHtml: "<p>Reply</p>",
        attachmentIds: [],
        thenStatus: "PENDING_ON_CLIENT",
      }),
    );
    expect(calls.filter((c) => c.path.endsWith("/public-replies"))).toHaveLength(1);
  });

  it("a reply that fails to queue keeps the draft and shows the error", async () => {
    await openTicket("ACD-042", {
      "POST /api/v1/agent/tickets/42/public-replies": () =>
        jsonResponse({ error: "server_error", message: "Could not queue the email" }, 500),
    });
    fireEvent.click(await screen.findByRole("tab", { name: "Reply to client" }));
    typeInto(screen.getByRole("textbox", { name: "Reply text" }), "<p>Keep me</p>");
    fireEvent.click(screen.getByRole("button", { name: "Send to client" }));
    // A 5xx never shows the server's own text; the inline error and the pop-up both say so.
    expect(
      await screen.findAllByText(/The reply was not sent: Something went wrong on our side/),
    ).toHaveLength(2);
    expect(screen.queryByText(/Could not queue the email/)).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Reply text" }).innerHTML).toBe("<p>Keep me</p>");
  });

  it("a 401 on send goes to sign-in and the draft survives", async () => {
    await openTicket("ACD-042", {
      "POST /api/v1/agent/tickets/42/internal-notes": () =>
        jsonResponse({ error: "unauthenticated", message: "Please sign in." }, 401),
    });
    typeInto(await screen.findByRole("textbox", { name: "Internal note text" }), "<p>Draft</p>");
    fireEvent.click(screen.getByRole("button", { name: "Add note" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Sign in" })).toBeInTheDocument();
    expect(sessionStorage.getItem("ticket-draft:1:42:note")).toContain("<p>Draft</p>");
  });

  it('typing "/" inserts a saved reply rendered by the server for this ticket', async () => {
    const { calls } = await openTicket("ACD-042", {
      "POST /api/v1/agent/tickets/42/saved-replies/9/render": () =>
        jsonResponse({ bodyHtml: "<p>Hi Maria, ACD-042 is fixed.</p>" }),
    });
    fireEvent.click(await screen.findByRole("tab", { name: "Reply to client" }));
    const editor = screen.getByRole("textbox", { name: "Reply text" });
    typeInto(editor, "/pass");
    fireEvent.keyDown(editor, { key: "Enter" });
    await waitFor(() => expect(editor.innerHTML).toContain("Hi Maria, ACD-042 is fixed."));
    expect(calls.some((c) => c.path.endsWith("/saved-replies/9/render"))).toBe(true);
  });

  it("changes status, priority and assignee through the API", async () => {
    const { calls } = await openTicket("ACD-042", {
      "POST /api/v1/agent/tickets/42/status": () => jsonResponse({ status: "RESOLVED" }),
      "POST /api/v1/agent/tickets/42/priority": noContent,
      "POST /api/v1/agent/tickets/42/assignee": noContent,
    });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "RESOLVED" } });
    fireEvent.change(screen.getByLabelText("Priority"), { target: { value: "P1" } });
    await screen.findByRole("option", { name: "Minh Le (out of office)" });
    fireEvent.change(screen.getByLabelText("Assignee"), { target: { value: "" } });
    await waitFor(() => {
      expect(calls.find((c) => c.path.endsWith("/status"))?.body).toEqual({ status: "RESOLVED" });
      expect(calls.find((c) => c.path.endsWith("/priority"))?.body).toEqual({ priority: "P1" });
      expect(calls.find((c) => c.path.endsWith("/assignee"))?.body).toEqual({
        assigneeUserId: null,
      });
    });
  });

  it("marks as duplicate, and shows the server's refusal across organisations", async () => {
    const { calls } = await openTicket("ACD-042", {
      "POST /api/v1/agent/tickets/42/duplicate-of": (body: unknown) =>
        (body as { reference: string }).reference === "BCL-001"
          ? jsonResponse(
              {
                error: "invalid_request",
                message: "Duplicates must belong to the same organisation.",
              },
              400,
            )
          : jsonResponse({ id: 40 }),
    });
    fireEvent.click(screen.getByRole("button", { name: "Mark as duplicate of..." }));
    const dialog = screen.getByRole("dialog", { name: "Mark as duplicate" });
    fireEvent.change(within(dialog).getByLabelText("Other ticket's reference"), {
      target: { value: "BCL-001" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Mark as duplicate" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "Duplicates must belong to the same organisation.",
    );
    fireEvent.change(within(dialog).getByLabelText("Other ticket's reference"), {
      target: { value: "ACD-040" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Mark as duplicate" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "Mark as duplicate" })).not.toBeInTheDocument(),
    );
    expect(calls.filter((c) => c.path.endsWith("/duplicate-of")).map((c) => c.body)).toEqual([
      { reference: "BCL-001" },
      { reference: "ACD-040" },
    ]);
  });

  it("links a related ticket", async () => {
    const { calls } = await openTicket("ACD-042", {
      "POST /api/v1/agent/tickets/42/links": noContent,
    });
    fireEvent.click(screen.getByRole("button", { name: "Link related ticket" }));
    const dialog = screen.getByRole("dialog", { name: "Link related ticket" });
    fireEvent.change(within(dialog).getByLabelText("Other ticket's reference"), {
      target: { value: "acd-41" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Link tickets" }));
    await waitFor(() =>
      expect(calls.find((c) => c.path.endsWith("/links"))?.body).toEqual({ reference: "acd-41" }),
    );
  });

  it("changes organisation from the Details dropdown only after the inline Move confirmation", async () => {
    const { calls } = await openTicket("ACD-042", {
      "POST /api/v1/agent/tickets/42/organisation": () => jsonResponse({ reference: "BCL-005" }),
      "GET /api/v1/agent/tickets/by-reference/BCL-005": () =>
        jsonResponse({ ticketId: 42, reference: "BCL-005", matched: "BCL-005", alias: false }),
    });
    expect(screen.queryByRole("button", { name: "Change organisation" })).toBeNull();
    const select = await screen.findByRole("combobox", { name: "Organisation" });
    await waitFor(() => expect(select).toHaveValue("1"));
    fireEvent.change(select, { target: { value: "2" } });
    // Nothing is saved yet: the confirmation takes focus and keeps the dialog's warnings.
    const confirm = screen.getByRole("group", { name: /^Move ACD-042 to/ });
    expect(confirm).toHaveFocus();
    expect(confirm).toHaveTextContent("keeps working as an alias");
    expect(confirm).toHaveTextContent("assignee is cleared");
    expect(confirm).toHaveTextContent("SLA is worked out again");
    expect(calls.some((c) => c.path.endsWith("/organisation"))).toBe(false);
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(within(confirm).getByRole("button", { name: "Move" }));
    await waitFor(() =>
      expect(calls.find((c) => c.path.endsWith("/organisation"))?.body).toEqual({
        organisationId: 2,
      }),
    );
    await waitFor(() =>
      expect(calls.some((c) => c.path.endsWith("/by-reference/BCL-005"))).toBe(true),
    );
  });

  it("Cancel and Esc put the old organisation back without saving", async () => {
    const { calls } = await openTicket("ACD-042");
    const select = await screen.findByRole("combobox", { name: "Organisation" });
    await waitFor(() => expect(select).toHaveValue("1"));
    fireEvent.change(select, { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(select).toHaveValue("1");
    expect(select).toHaveFocus();
    expect(screen.queryByRole("group", { name: /^Move ACD-042/ })).toBeNull();

    fireEvent.change(select, { target: { value: "2" } });
    fireEvent.keyDown(screen.getByRole("group", { name: /^Move ACD-042/ }), { key: "Escape" });
    expect(select).toHaveValue("1");
    expect(screen.queryByRole("group", { name: /^Move ACD-042/ })).toBeNull();
    expect(calls.some((c) => c.path.endsWith("/organisation"))).toBe(false);
  });

  it("shows the server's refusal inside the confirmation and keeps the ticket", async () => {
    await openTicket("ACD-042", {
      "POST /api/v1/agent/tickets/42/organisation": () =>
        jsonResponse({ error: "forbidden", message: "You do not work on that organisation." }, 403),
    });
    const select = await screen.findByRole("combobox", { name: "Organisation" });
    await waitFor(() => expect(select).toHaveValue("1"));
    fireEvent.change(select, { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: "Move" }));
    const confirm = screen.getByRole("group", { name: /^Move ACD-042/ });
    // A plain 403 "forbidden" is worded by the API client (no silent failures, 2026-11-19).
    expect(await within(confirm).findByRole("alert")).toHaveTextContent(
      "You don't have permission to do that.",
    );
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "Outlook keeps asking for a password",
    );
  });

  it("shows the SLA chip next to the status, as in the queue (L-QA-002)", async () => {
    await openTicket("ACD-042", {
      "GET /api/v1/agent/tickets/42": () =>
        jsonResponse({
          ...detail,
          sla: {
            firstResponse: { state: "MET" },
            resolution: { state: "PAUSED" },
            warning: null,
          },
        }),
    });
    expect(screen.getByText("Paused")).toBeInTheDocument();
  });

  it("an internal note whose only image is inline has no empty attachment box (L-QA-003)", async () => {
    await openTicket("ACD-042", {
      "GET /api/v1/agent/tickets/42": () =>
        jsonResponse({
          ...detail,
          internalNotes: [
            {
              ...detail.internalNotes[0],
              attachments: [
                {
                  id: "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                  filename: "screen.png",
                  size: 10,
                  contentType: "image/png",
                  image: true,
                  inline: true,
                  internal: true,
                },
              ],
            },
          ],
        }),
    });
    const note = screen.getByRole("article", { name: "Internal note by Alex Tran" });
    expect(within(note).queryByRole("list", { name: "Attachments" })).toBeNull();
    expect(note.children).toHaveLength(2);
  });

  it("shows Processing attachments... on an email comment until its files are in, re-reading the ticket (ADR-039)", async () => {
    let calls = 0;
    const processing = {
      ...detail,
      publicComments: [
        {
          ...detail.publicComments[0],
          bodyHtml: "<p>Photo below</p><p>[image]</p>",
          attachments: [],
          attachmentsProcessing: true,
        },
      ],
    };
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      await openTicket("ACD-042", {
        "GET /api/v1/agent/tickets/42": () => {
          calls++;
          return jsonResponse(calls === 1 ? processing : detail);
        },
      });
      expect(screen.getByText("Processing attachments...")).toHaveAttribute("role", "status");
      expect(screen.queryByRole("list", { name: "Attachments" })).toBeNull();
      await vi.advanceTimersByTimeAsync(5000);
      await waitFor(() => expect(screen.queryByText("Processing attachments...")).toBeNull());
      expect(calls).toBe(2);
      expect(screen.getByRole("list", { name: "Attachments" })).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("a ticket the agent cannot see shows a not-found page (server 404)", async () => {
    agentApi();
    renderAt("/agent/tickets/ZZZ-001", undefined, { role: "AGENT", api: agentApi().api });
    expect(
      await screen.findByRole("heading", { level: 1, name: "Ticket ZZZ-001" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("We could not find that ticket.");
  });

  it("a closed ticket offers a follow-up and no public reply", async () => {
    await openTicket("ACD-042", {
      "GET /api/v1/agent/tickets/42": () =>
        jsonResponse({ ...detail, status: "CLOSED", statusLabel: "Closed", allowedStatuses: [] }),
    });
    expect(screen.getByRole("button", { name: "Create follow-up ticket" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Mark as duplicate of..." })).toBeNull();
    fireEvent.click(await screen.findByRole("tab", { name: "Reply to client" }));
    expect(screen.getByText(/This ticket is closed/)).toBeInTheDocument();
  });

  it("has no axe violations (both composer tabs)", async () => {
    await openTicket();
    await screen.findByRole("tab", { name: "Internal note" });
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByRole("tab", { name: "Reply to client" }));
    expect(await axeViolations()).toEqual([]);
  });
});

describe("Email triage assignment", () => {
  it("offers only the contact tick for a public-domain sender and sends the choice", async () => {
    const { calls } = await openTicket("TRI-007", {
      "GET /api/v1/agent/tickets/7/triage-options": () =>
        jsonResponse({
          senderEmail: "someone@gmail.com",
          senderDomain: "gmail.com",
          domainOffered: false,
          contactExists: false,
        }),
      "POST /api/v1/agent/tickets/7/triage-assignment": () =>
        jsonResponse({ reference: "ACD-043", contactId: 50 }),
    });
    fireEvent.click(screen.getByRole("button", { name: "Assign to organisation" }));
    const dialog = screen.getByRole("dialog", { name: "Assign to an organisation" });
    fireEvent.change(within(dialog).getByLabelText("Organisation"), { target: { value: "1" } });
    const contactTick = await within(dialog).findByRole("checkbox", {
      name: "Save someone@gmail.com as a contact of Acme Dental",
    });
    expect(within(dialog).queryByRole("checkbox", { name: /verified domain/ })).toBeNull();
    expect(within(dialog).getByText(/gmail.com cannot be a verified domain/)).toBeInTheDocument();
    fireEvent.click(contactTick);
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(within(dialog).getByRole("button", { name: "Assign" }));
    await waitFor(() =>
      expect(calls.find((c) => c.path.endsWith("/triage-assignment"))?.body).toEqual({
        organisationId: 1,
        saveContact: true,
        addDomain: false,
      }),
    );
  });

  it("offers the domain tick for a company domain", async () => {
    const { calls } = await openTicket("TRI-007", {
      "GET /api/v1/agent/tickets/7/triage-options": () =>
        jsonResponse({
          senderEmail: "new@acmedental.example",
          senderDomain: "acmedental.example",
          domainOffered: true,
          contactExists: false,
        }),
      "POST /api/v1/agent/tickets/7/triage-assignment": () =>
        jsonResponse({ error: "domain_taken", message: "That domain is already used." }, 409),
    });
    fireEvent.click(screen.getByRole("button", { name: "Assign to organisation" }));
    const dialog = screen.getByRole("dialog", { name: "Assign to an organisation" });
    fireEvent.change(within(dialog).getByLabelText("Organisation"), { target: { value: "1" } });
    fireEvent.click(
      await within(dialog).findByRole("checkbox", {
        name: "Add acmedental.example as a verified domain of Acme Dental",
      }),
    );
    fireEvent.click(within(dialog).getByRole("button", { name: "Assign" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("already used");
    expect(calls.find((c) => c.path.endsWith("/triage-assignment"))?.body).toEqual({
      organisationId: 1,
      saveContact: false,
      addDomain: true,
    });
  });
});
