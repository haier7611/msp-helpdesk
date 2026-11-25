import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { agentApi, summaryRow } from "../test/agentApi";
import { axeViolations, jsonResponse, renderAt } from "../test/helpers";

// Synthetic people and tickets only.

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

const notifications = [
  {
    id: 11,
    kind: "ASSIGNED",
    detail: null,
    text: "ACD-042 was assigned to you",
    ticketId: 42,
    reference: "ACD-042",
    ticketSubject: "Outlook keeps asking for a password",
    createdAt: "2026-11-18T02:00:00Z",
    read: false,
  },
  {
    id: 10,
    kind: "CLIENT_REPLIED",
    detail: null,
    text: "Maria Gomez replied on ACD-041",
    ticketId: 41,
    reference: "ACD-041",
    ticketSubject: "Printer offline",
    createdAt: "2026-11-17T02:00:00Z",
    read: true,
  },
];

async function openAccountMenu(name = "Alex Tran") {
  const button = await screen.findByRole(
    "button",
    { name: `Account menu for ${name}` },
    { timeout: 5000 },
  );
  fireEvent.click(button);
  return button;
}

describe("Supporter top bar - sign out", () => {
  it("calls the sign-out API, wipes drafts and lands on /sign-in", async () => {
    const { api, calls } = agentApi({
      "POST /api/v1/auth/sign-out": () => new Response(null, { status: 204 }),
    });
    sessionStorage.setItem("ticket-draft:1:42:note", "half-written note");
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    await openAccountMenu();

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(
      await screen.findByRole("heading", { level: 1, name: "Sign in" }, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(
      calls.filter((c) => c.method === "POST" && c.path === "/api/v1/auth/sign-out"),
    ).toHaveLength(1);
    expect(sessionStorage.getItem("ticket-draft:1:42:note")).toBeNull();
    expect(screen.queryByRole("navigation", { name: "Main" })).not.toBeInTheDocument();
  });

  it("is a button, not a link straight to /sign-in", async () => {
    const { api } = agentApi();
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    await openAccountMenu();
    expect(screen.queryByRole("link", { name: "Sign out" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
  });

  it("stays put and says so when the sign-out call fails", async () => {
    const { api, calls } = agentApi({
      "POST /api/v1/auth/sign-out": () =>
        jsonResponse({ error: "unavailable", message: "Try again." }, 503),
    });
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    await openAccountMenu();

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(
      await screen.findByText("We could not sign you out. Check your connection and try again."),
    ).toBeInTheDocument();
    expect(calls.some((c) => c.path === "/api/v1/auth/sign-out")).toBe(true);
    expect(screen.getByRole("heading", { level: 1, name: "My tickets" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });
});

describe("Supporter top bar - who is signed in", () => {
  it("shows the signed-in supporter's name and initials, not demo data", async () => {
    const { api } = agentApi();
    renderAt("/agent/queue", undefined, { role: "AGENT", name: "Priya Shah", api });
    const button = await screen.findByRole(
      "button",
      { name: "Account menu for Priya Shah" },
      { timeout: 5000 },
    );
    expect(within(button).getByText("PS")).toBeInTheDocument();
    expect(screen.queryByText("AT")).not.toBeInTheDocument();
  });

  it("shows Admin only to admins", async () => {
    const { api } = agentApi();
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    const nav = await screen.findByRole("navigation", { name: "Main" }, { timeout: 5000 });
    await screen.findByRole("button", { name: "Account menu for Alex Tran" });
    expect(within(nav).queryByRole("link", { name: "Admin" })).not.toBeInTheDocument();
  });

  it("an admin sees the Admin link", async () => {
    const { api } = agentApi();
    renderAt("/agent/queue", undefined, { role: "ADMIN", api });
    const nav = await screen.findByRole("navigation", { name: "Main" }, { timeout: 5000 });
    expect(within(nav).getByRole("link", { name: "Admin" })).toHaveAttribute("href", "/admin");
  });
});

describe("Supporter top bar - notifications bell", () => {
  function notificationApi() {
    let unread = 1;
    return agentApi({
      "GET /api/v1/agent/notifications/unread-count": () => jsonResponse({ unread }),
      "GET /api/v1/agent/notifications": () => jsonResponse(notifications),
      "POST /api/v1/agent/notifications/11/read": () => {
        unread = 0;
        return new Response(null, { status: 204 });
      },
      "POST /api/v1/agent/notifications/read-all": () => {
        unread = 0;
        return jsonResponse({ marked: 1 });
      },
    });
  }

  it("shows the real unread count", async () => {
    const { api } = notificationApi();
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    expect(
      await screen.findByRole("button", { name: "Notifications, 1 unread" }, { timeout: 5000 }),
    ).toBeInTheDocument();
  });

  it("opens the list, closes on Escape with focus back on the bell", async () => {
    const { api } = notificationApi();
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    const bell = await screen.findByRole(
      "button",
      { name: "Notifications, 1 unread" },
      { timeout: 5000 },
    );
    expect(bell).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(bell);
    expect(bell).toHaveAttribute("aria-expanded", "true");
    const panel = await screen.findByRole("region", { name: "Notifications" });
    const first = await within(panel).findByRole("link", { name: /ACD-042 was assigned to you/ });
    expect(first).toHaveAttribute("href", "/agent/tickets/ACD-042");
    expect(first).toHaveTextContent("Unread:");
    expect(first).toHaveTextContent("ACD-042 - Outlook keeps asking for a password");
    expect(await axeViolations(panel)).toEqual([]);

    fireEvent.keyDown(first, { key: "Escape" });
    expect(screen.queryByRole("region", { name: "Notifications" })).not.toBeInTheDocument();
    expect(bell).toHaveFocus();
  });

  it("opening a notification marks it read and updates the count", async () => {
    const { api, calls } = notificationApi();
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    fireEvent.click(
      await screen.findByRole("button", { name: "Notifications, 1 unread" }, { timeout: 5000 }),
    );
    const panel = await screen.findByRole("region", { name: "Notifications" });
    fireEvent.click(await within(panel).findByRole("link", { name: /ACD-042 was assigned/ }));
    await waitFor(() =>
      expect(calls.some((c) => c.method === "POST" && c.path.endsWith("/11/read"))).toBe(true),
    );
    expect(
      await screen.findByRole("button", { name: "Notifications, 0 unread" }),
    ).toBeInTheDocument();
  });

  it("Mark all as read clears the count", async () => {
    const { api, calls } = notificationApi();
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    fireEvent.click(
      await screen.findByRole("button", { name: "Notifications, 1 unread" }, { timeout: 5000 }),
    );
    fireEvent.click(await screen.findByRole("button", { name: "Mark all as read" }));
    expect(
      await screen.findByRole("button", { name: "Notifications, 0 unread" }),
    ).toBeInTheDocument();
    expect(calls.some((c) => c.method === "POST" && c.path.endsWith("/read-all"))).toBe(true);
    expect(screen.queryByRole("button", { name: "Mark all as read" })).not.toBeInTheDocument();
  });
});

describe("Supporter top bar - search", () => {
  it("submitting searches every ticket in the queue", async () => {
    const { api, calls } = agentApi();
    renderAt("/agent/customers", undefined, { role: "AGENT", api });
    const box = await screen.findByRole("searchbox", { name: "Search tickets" }, { timeout: 5000 });
    fireEvent.change(box, { target: { value: "ACD-042" } });
    fireEvent.submit(box.closest("form") as HTMLFormElement);

    expect(
      await screen.findByRole("heading", { level: 1, name: "All open tickets" }, { timeout: 5000 }),
    ).toBeInTheDocument();
    await screen.findByRole("link", { name: summaryRow.reference });
    const ticketCall = calls.find((c) => c.path.startsWith("/api/v1/agent/tickets?"));
    expect(ticketCall?.path).toContain("q=ACD-042");
    expect(ticketCall?.path).toContain("includeClosed=true");
  });

  it("an empty search does nothing", async () => {
    const { api } = agentApi();
    renderAt("/agent/customers", undefined, { role: "AGENT", api });
    const box = await screen.findByRole("searchbox", { name: "Search tickets" }, { timeout: 5000 });
    fireEvent.submit(box.closest("form") as HTMLFormElement);
    expect(
      screen.queryByRole("heading", { level: 1, name: "All open tickets" }),
    ).not.toBeInTheDocument();
  });
});
