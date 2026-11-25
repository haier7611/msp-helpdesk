import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { agentApi } from "./test/agentApi";
import { axeViolations, jsonResponse, renderAt } from "./test/helpers";

// "No silent failures": every user action that fails shows a pop-up error
// (showError); background refreshes fail quietly but visibly. Synthetic data only.

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

/** The pop-up errors currently on screen (the showError region). */
function popups(): HTMLElement[] {
  return within(screen.getByTestId("error-popups")).queryAllByRole("alert");
}

async function popupWith(text: string | RegExp): Promise<HTMLElement> {
  return within(screen.getByTestId("error-popups")).findByText(text, undefined, {
    timeout: 5000,
  });
}

const serverDown = () => jsonResponse({ error: "server_error", message: "NPE at line 42" }, 500);

describe("Notifications bell", () => {
  const unreadOne = () => jsonResponse({ unread: 1 });
  const notifications = () =>
    jsonResponse([
      {
        id: 11,
        text: "ACD-042 was assigned to you",
        reference: "ACD-042",
        ticketSubject: "Outlook keeps asking for a password",
        createdAt: "2026-11-18T02:00:00Z",
        read: false,
      },
    ]);

  it("Mark all as read that fails shows a pop-up (AUD-29)", async () => {
    const { api } = agentApi({
      "GET /api/v1/agent/notifications/unread-count": unreadOne,
      "GET /api/v1/agent/notifications": notifications,
      "POST /api/v1/agent/notifications/read-all": serverDown,
    });
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    fireEvent.click(
      await screen.findByRole("button", { name: "Notifications, 1 unread" }, { timeout: 5000 }),
    );
    fireEvent.click(await screen.findByRole("button", { name: "Mark all as read" }));
    const popup = await popupWith(
      "The notifications were not marked as read. Something went wrong on our side. Please try again.",
    );
    expect(popup).toBeInTheDocument();
    // The server's raw text is never shown for a 5xx.
    expect(screen.queryByText(/NPE at line 42/)).not.toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  });

  it("a failing background count refresh never pops up, however often it runs", async () => {
    const { api, calls } = agentApi({
      "GET /api/v1/agent/notifications/unread-count": serverDown,
    });
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    const bell = await screen.findByRole(
      "button",
      { name: "Notifications (couldn't refresh)" },
      { timeout: 5000 },
    );
    expect(bell).toHaveAttribute("title", "Couldn't refresh notifications");
    // Every page change refreshes the count again.
    const nav = screen.getByRole("navigation", { name: "Main" });
    fireEvent.click(within(nav).getByRole("link", { name: "Customers" }));
    await screen.findByRole("heading", { level: 1, name: "Customers" }, { timeout: 5000 });
    fireEvent.click(within(nav).getByRole("link", { name: "Queue" }));
    await waitFor(() =>
      expect(calls.filter((c) => c.path.endsWith("/unread-count")).length).toBeGreaterThan(2),
    );
    expect(popups()).toHaveLength(0);
  });

  it("the hint goes away once a refresh works again", async () => {
    let fail = true;
    const { api } = agentApi({
      "GET /api/v1/agent/notifications/unread-count": () =>
        fail ? serverDown() : jsonResponse({ unread: 2 }),
    });
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    await screen.findByRole(
      "button",
      { name: "Notifications (couldn't refresh)" },
      { timeout: 5000 },
    );
    fail = false;
    fireEvent.click(
      within(screen.getByRole("navigation", { name: "Main" })).getByRole("link", {
        name: "Customers",
      }),
    );
    expect(
      await screen.findByRole("button", { name: "Notifications, 2 unread" }, { timeout: 5000 }),
    ).toBeInTheDocument();
  });
});

describe("Ticket actions", () => {
  async function openTicket(handlers = {}) {
    const stub = agentApi(handlers);
    renderAt("/agent/tickets/ACD-042", undefined, { role: "AGENT", api: stub.api });
    await screen.findByRole("heading", { level: 1 }, { timeout: 5000 });
    return stub;
  }

  it("a refused status change pops up the permission message", async () => {
    await openTicket({
      "POST /api/v1/agent/tickets/42/status": () =>
        jsonResponse({ error: "forbidden", message: "You do not have access to this." }, 403),
    });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "RESOLVED" } });
    expect(
      await popupWith("That change was not saved. You don't have permission to do that."),
    ).toBeInTheDocument();
  });

  it("a priority change that never reaches the server says the connection was lost", async () => {
    const stub = agentApi();
    renderAt("/agent/tickets/ACD-042", undefined, {
      role: "AGENT",
      api: (path, init) => {
        if (path.endsWith("/priority")) throw new TypeError("Failed to fetch");
        return stub.api(path, init);
      },
    });
    await screen.findByRole("heading", { level: 1 }, { timeout: 5000 });
    fireEvent.change(screen.getByLabelText("Priority"), { target: { value: "P1" } });
    expect(
      await popupWith(
        "That change was not saved. Connection lost. Check your internet and try again.",
      ),
    ).toBeInTheDocument();
  });
});

describe("Admin saves", () => {
  it("a shared reply that fails to save keeps the inline error and pops up", async () => {
    const api = (path: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (path === "/api/v1/admin/saved-replies" && method === "GET") return jsonResponse([]);
      if (path === "/api/v1/admin/saved-replies" && method === "POST") {
        return jsonResponse({ error: "title_taken", message: "That title is already used." }, 409);
      }
      return undefined;
    };
    renderAt("/admin/saved-replies", undefined, { api });
    fireEvent.click(
      await screen.findByRole("button", { name: "Add shared reply" }, { timeout: 5000 }),
    );
    const dialog = await screen.findByRole("dialog", { name: "Add shared reply" });
    fireEvent.change(within(dialog).getByLabelText(/^Title/), { target: { value: "Printer" } });
    fireEvent.change(within(dialog).getByLabelText(/^Reply text/), {
      target: { value: "Try again" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await popupWith("That title is already used.")).toBeInTheDocument();
    expect(within(dialog).getByText("That title is already used.")).toBeInTheDocument();
  }, 20000);
});

describe("Portal", () => {
  const ACCOUNT = {
    name: "Kim Example",
    email: "kim@example.test",
    organisationName: "Acme Dental",
    microsoftSignInAvailable: false,
    microsoftLinked: false,
  };

  it("a rename refused as too many attempts pops up the wait message", async () => {
    const api = (path: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (path === "/api/v1/portal/account" && method === "GET") return jsonResponse(ACCOUNT);
      if (path === "/api/v1/portal/account" && method === "PATCH") {
        // A proxy's throttle page (no {error, message} body) gets the plain wait message.
        return new Response("<html>429 Too Many Requests</html>", { status: 429 });
      }
      return undefined;
    };
    renderAt("/portal/account", undefined, { api });
    fireEvent.change(await screen.findByLabelText("Name", undefined, { timeout: 5000 }), {
      target: { value: "Kim Tran" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save name" }));
    expect(await popupWith("Too many attempts - please wait a moment.")).toBeInTheDocument();
  });

  it("a sign-out that fails stays on the page and pops up", async () => {
    const api = (path: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (path === "/api/v1/portal/account" && method === "GET") return jsonResponse(ACCOUNT);
      if (path === "/api/v1/auth/sign-out") return serverDown();
      return undefined;
    };
    renderAt("/portal/account", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }, { timeout: 5000 }));
    expect(
      await popupWith(
        "We could not sign you out. Something went wrong on our side. Please try again.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 1, name: "Sign in" })).not.toBeInTheDocument();
  });
});
