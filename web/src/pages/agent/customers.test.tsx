import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";
import { agentApi } from "../../test/agentApi";
import { textToHtml } from "./AgentNewTicketPage";

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

function type(label: string | RegExp, value: string, root: HTMLElement = document.body) {
  fireEvent.change(within(root).getByLabelText(label), { target: { value } });
}

const newContact = {
  id: 51,
  name: "Lee Park",
  email: "lee@acmedental.example",
  organisationId: 1,
  organisationName: "Acme Dental",
  status: "INVITED",
};

describe("Agent new ticket", () => {
  it("creates a ticket for an existing contact, assigned to me", async () => {
    const { api, calls } = agentApi({
      "POST /api/v1/agent/tickets": () => jsonResponse({ id: 44, reference: "ACD-044" }, 201),
    });
    renderAt("/agent/tickets/new", undefined, { role: "AGENT", api });
    expect(
      await screen.findByRole("heading", { level: 1, name: "New ticket" }, { timeout: 5000 }),
    ).toBeInTheDocument();
    await screen.findByRole("option", { name: "Acme Dental" });
    type("Organisation", "1");
    await screen.findByRole("option", { name: "Maria Gomez (maria@acmedental.example)" });
    type("Contact", "30");
    // Alert is for monitoring tools only.
    expect(screen.queryByRole("option", { name: /^Alert/ })).toBeNull();
    type("Subject", "Phone call: printer jam");
    type("Description", "Paper stuck.\n\nTried twice.");
    fireEvent.click(screen.getByRole("button", { name: "Create ticket" }));
    await waitFor(() =>
      expect(
        calls.find((c) => c.method === "POST" && c.path === "/api/v1/agent/tickets")?.body,
      ).toEqual({
        organisationId: 1,
        reporterUserId: 30,
        requestTypeId: 1,
        subject: "Phone call: printer jam",
        descriptionHtml: "<p>Paper stuck.</p><p>Tried twice.</p>",
        attachmentIds: [],
        assigneeUserId: 1,
      }),
    );
  });

  it("creates an inline new contact (with invitation) first, then the ticket", async () => {
    const { api, calls } = agentApi({
      "POST /api/v1/agent/contacts": () => jsonResponse(newContact, 201),
      "POST /api/v1/agent/tickets": () => jsonResponse({ id: 45, reference: "ACD-045" }, 201),
    });
    renderAt("/agent/tickets/new", undefined, { role: "AGENT", api });
    await screen.findByRole("option", { name: "Acme Dental" }, { timeout: 5000 });
    type("Organisation", "1");
    fireEvent.click(await screen.findByRole("button", { name: "New contact" }));
    type("Contact name", "Lee Park");
    type("Contact email", "lee@acmedental.example");
    fireEvent.click(screen.getByLabelText(/Send a portal invitation/));
    fireEvent.click(screen.getByLabelText("Assign to me"));
    type("Subject", "Walk-in");
    fireEvent.click(screen.getByRole("button", { name: "Create ticket" }));
    await waitFor(() =>
      expect(calls.some((c) => c.method === "POST" && c.path === "/api/v1/agent/tickets")).toBe(
        true,
      ),
    );
    expect(
      calls.find((c) => c.path === "/api/v1/agent/contacts" && c.method === "POST")?.body,
    ).toEqual({
      organisationId: 1,
      name: "Lee Park",
      email: "lee@acmedental.example",
      invite: true,
    });
    const ticket = calls.find((c) => c.method === "POST" && c.path === "/api/v1/agent/tickets");
    expect(ticket?.body).toMatchObject({ reporterUserId: 51, organisationId: 1 });
    expect(ticket?.body).not.toHaveProperty("assigneeUserId");
  });

  it("asks for a contact before creating", async () => {
    const { api } = agentApi();
    renderAt("/agent/tickets/new", undefined, { role: "AGENT", api });
    await screen.findByRole("option", { name: "Acme Dental" }, { timeout: 5000 });
    type("Organisation", "1");
    type("Subject", "x");
    fireEvent.click(screen.getByRole("button", { name: "Create ticket" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Choose a contact, or add a new one.",
    );
  });

  it("textToHtml escapes HTML", () => {
    expect(textToHtml("<b>x</b>\nline")).toBe("<p>&#60;b&#62;x&#60;/b&#62;<br>line</p>");
  });

  it("has no axe violations", async () => {
    const { api } = agentApi();
    renderAt("/agent/tickets/new", undefined, { role: "AGENT", api });
    await screen.findByRole("option", { name: "Acme Dental" }, { timeout: 5000 });
    type("Organisation", "1");
    fireEvent.click(await screen.findByRole("button", { name: "New contact" }));
    expect(await axeViolations()).toEqual([]);
  });
});

describe("Customers", () => {
  it("lists contacts of my organisations and searches", async () => {
    const { api, calls } = agentApi();
    renderAt("/agent/customers", undefined, { role: "AGENT", api });
    const table = await screen.findByRole("table", { name: "Contacts" }, { timeout: 5000 });
    expect(await within(table).findByText("Maria Gomez")).toBeInTheDocument();
    expect(within(table).getByText("Not signed up")).toBeInTheDocument();
    type("Search", "sam");
    await screen.findByRole("option", { name: "Beta Clinic" });
    type("Organisation", "1");
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() =>
      expect(calls.map((c) => c.path)).toContain(
        "/api/v1/agent/contacts?q=sam&organisationId=1&page=0&limit=50",
      ),
    );
  });

  it("creates a contact and invites one that has not signed up", async () => {
    const { api, calls } = agentApi({
      "POST /api/v1/agent/contacts": () => jsonResponse(newContact, 201),
      "POST /api/v1/agent/contacts/32/invitation": () =>
        jsonResponse({ expiresAt: "2026-11-25T00:00:00Z" }, 202),
    });
    renderAt("/agent/customers", undefined, { role: "AGENT", api });
    fireEvent.click(
      await screen.findByRole("button", { name: "Invite Sam Ho" }, { timeout: 5000 }),
    );
    await waitFor(() =>
      expect(calls.some((c) => c.path === "/api/v1/agent/contacts/32/invitation")).toBe(true),
    );
    expect(
      await screen.findByText("Invitation sent to sam@acmedental.example."),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "New contact" }));
    const dialog = screen.getByRole("dialog", { name: "New contact" });
    await within(dialog).findByRole("option", { name: "Acme Dental" });
    type("Organisation", "1", dialog);
    type("Name", "Lee Park", dialog);
    type("Email", "lee@acmedental.example", dialog);
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(within(dialog).getByRole("button", { name: "Create contact" }));
    await waitFor(() =>
      expect(
        calls.find((c) => c.method === "POST" && c.path === "/api/v1/agent/contacts")?.body,
      ).toEqual({
        organisationId: 1,
        name: "Lee Park",
        email: "lee@acmedental.example",
        invite: false,
      }),
    );
  });

  it("shows the server's message when the email already has an account", async () => {
    const { api } = agentApi({
      "POST /api/v1/agent/contacts": () =>
        jsonResponse(
          { error: "contact_exists", message: "Someone with this email already has an account." },
          409,
        ),
    });
    renderAt("/agent/customers", undefined, { role: "AGENT", api });
    fireEvent.click(await screen.findByRole("button", { name: "New contact" }, { timeout: 5000 }));
    const dialog = screen.getByRole("dialog", { name: "New contact" });
    await within(dialog).findByRole("option", { name: "Acme Dental" });
    type("Organisation", "1", dialog);
    type("Name", "Maria", dialog);
    type("Email", "maria@acmedental.example", dialog);
    fireEvent.click(within(dialog).getByRole("button", { name: "Create contact" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("already has an account");
  });

  it("has no axe violations", async () => {
    const { api } = agentApi();
    renderAt("/agent/customers", undefined, { role: "AGENT", api });
    await screen.findByText("Maria Gomez", undefined, { timeout: 5000 });
    expect(await axeViolations()).toEqual([]);
  });
});

describe("Agent organisations", () => {
  it("lists my organisations read-only, linking to their queue", async () => {
    const { api } = agentApi();
    renderAt("/agent/organisations", undefined, { role: "AGENT", api });
    const link = await screen.findByRole("link", { name: "Acme Dental" }, { timeout: 5000 });
    expect(link).toHaveAttribute("href", "/agent/queue?org=1");
    expect(screen.queryByRole("button", { name: /New organisation/ })).toBeNull();
    expect(await axeViolations()).toEqual([]);
  });
});
