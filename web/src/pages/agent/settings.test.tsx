import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";
import { RENDERED } from "../../test/signatureFixture";
import { applyTheme, setTheme, storedTheme, THEME_STORAGE_KEY } from "../../theme";

const profile = {
  email: "alex@example.test",
  displayName: "Alex Tran",
  jobTitle: "Service Desk Engineer",
  phone: "+00 000 000 000",
  mobile: "",
  freeLine: "",
  hasPhoto: false,
};

const prefs = {
  composerTab: "INTERNAL_NOTE",
  queueView: "my-open",
  timezone: "Asia/Ho_Chi_Minh",
  dateFormat: "DMY",
  theme: "SYSTEM",
};

type Call = { path: string; method: string; body: unknown };

function settingsApi(handlers: Record<string, (body: unknown) => Response> = {}) {
  const calls: Call[] = [];
  const api = (path: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : init?.body;
    calls.push({ path, method, body });
    const handler = handlers[`${method} ${path}`];
    if (handler) return handler(body);
    if (method === "GET" && path === "/api/v1/agent/me/profile") return jsonResponse(profile);
    if (method === "GET" && path === "/api/v1/agent/me/signature") return jsonResponse(RENDERED);
    if (method === "GET" && path === "/api/v1/agent/me/preferences") return jsonResponse(prefs);
    return undefined;
  };
  return { api, calls };
}

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  document.documentElement.removeAttribute("data-theme");
  try {
    window.localStorage.clear();
  } catch {
    // ignore
  }
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("My settings - Profile and signature", () => {
  it("loads the profile and the server's preview, then re-renders it as you type", async () => {
    const { api, calls } = settingsApi({
      "POST /api/v1/agent/me/signature-preview": (body) =>
        jsonResponse({
          ...RENDERED,
          fullText: `${(body as { displayName: string }).displayName}\n--\nExample MSP`,
        }),
    });
    renderAt("/agent/settings/profile", undefined, { api, role: "AGENT" });
    const name = await screen.findByLabelText(/^Display name/, {}, { timeout: 5000 });
    expect(name).toHaveValue("Alex Tran");
    expect(screen.getByRole("group", { name: "Signature" })).toHaveTextContent(
      "alex.tran@helpdesk.example",
    );

    fireEvent.change(name, { target: { value: "<b>Robin</b>" } });
    await waitFor(
      () => expect(calls.some((c) => c.path === "/api/v1/agent/me/signature-preview")).toBe(true),
      { timeout: 3000 },
    );
    expect(calls.find((c) => c.method === "POST")?.body).toMatchObject({
      displayName: "<b>Robin</b>",
    });
    fireEvent.click(screen.getByRole("button", { name: "Plain text" }));
    expect(await screen.findByText(/<b>Robin<\/b>/)).toBeInTheDocument();
    // Nothing was saved by previewing.
    expect(calls.some((c) => c.method === "PUT")).toBe(false);
  });

  it("shows the server's validation message for the preview", async () => {
    const { api } = settingsApi({
      "POST /api/v1/agent/me/signature-preview": () =>
        jsonResponse({ error: "invalid_request", message: "Phone can only contain digits." }, 400),
    });
    renderAt("/agent/settings/profile", undefined, { api, role: "AGENT" });
    fireEvent.change(await screen.findByLabelText("Phone", {}, { timeout: 5000 }), {
      target: { value: "call me" },
    });
    expect(await screen.findByRole("alert", {}, { timeout: 3000 })).toHaveTextContent(
      "Phone can only contain digits.",
    );
  });

  it("saves the fields", async () => {
    const { api, calls } = settingsApi({
      "PUT /api/v1/agent/me/profile": (body) => jsonResponse({ ...profile, ...(body as object) }),
    });
    renderAt("/agent/settings/profile", undefined, { api, role: "AGENT" });
    fireEvent.change(await screen.findByLabelText("Job title", {}, { timeout: 5000 }), {
      target: { value: "Field Engineer" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({
      displayName: "Alex Tran",
      jobTitle: "Field Engineer",
      phone: "+00 000 000 000",
      mobile: "",
      freeLine: "",
    });
    expect(await screen.findByText("Profile saved.")).toBeInTheDocument();
  });

  it("uploads a photo as the raw body and refuses files over 5 MB in the browser", async () => {
    const { api, calls } = settingsApi({
      "PUT /api/v1/agent/me/photo": () => new Response(null, { status: 204 }),
    });
    renderAt("/agent/settings/profile", undefined, { api, role: "AGENT" });
    const input = (await screen.findByLabelText(
      "Change photo",
      {},
      { timeout: 5000 },
    )) as HTMLInputElement;
    const big = new File([new Uint8Array(5 * 1024 * 1024 + 1)], "big.jpg", { type: "image/jpeg" });
    fireEvent.change(input, { target: { files: [big] } });
    expect(await screen.findByText("The picture can be at most 5 MB.")).toBeInTheDocument();
    expect(calls.some((c) => c.path === "/api/v1/agent/me/photo")).toBe(false);

    const small = new File([new Uint8Array([0xff, 0xd8, 0xff])], "me.jpg", { type: "image/jpeg" });
    fireEvent.change(input, { target: { files: [small] } });
    expect(await screen.findByRole("img", { name: "Your profile photo" })).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v1/agent/me/photo")?.method).toBe("PUT");
  });

  it("has no axe violations", async () => {
    const { api } = settingsApi();
    renderAt("/agent/settings/profile", undefined, { api, role: "AGENT" });
    await screen.findByLabelText(/^Display name/, {}, { timeout: 5000 });
    expect(await axeViolations()).toEqual([]);
  });
});

describe("My settings - Preferences, Notifications, Out of office", () => {
  it("saves preferences and applies the theme", async () => {
    const { api, calls } = settingsApi({
      "PUT /api/v1/agent/me/preferences": (body) => jsonResponse(body),
    });
    renderAt("/agent/settings/preferences", undefined, { api, role: "AGENT" });
    expect(
      await screen.findByRole("radio", { name: "Internal note" }, { timeout: 5000 }),
    ).toBeChecked();
    fireEvent.click(screen.getByRole("radio", { name: "Dark" }));
    fireEvent.change(screen.getByLabelText(/^Time zone/), { target: { value: "Europe/London" } });
    fireEvent.click(screen.getByRole("button", { name: "Save preferences" }));
    await waitFor(() => expect(document.documentElement.getAttribute("data-theme")).toBe("dark"));
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({
      ...prefs,
      theme: "DARK",
      timezone: "Europe/London",
    });
    expect(storedTheme()).toBe("DARK");
    expect(await axeViolations()).toEqual([]);
  });

  it("stores notification choices per event and the daily digest", async () => {
    const events = [
      "TICKET_ASSIGNED",
      "CLIENT_REPLIED",
      "MENTIONED",
      "INTERNAL_NOTE_ADDED",
      "SLA_WARNING",
      "SLA_BREACHED",
    ].map((event) => ({ event, email: true, inApp: true }));
    const { api, calls } = settingsApi({
      "GET /api/v1/agent/me/notifications": () => jsonResponse({ events, dailyDigest: false }),
      "PUT /api/v1/agent/me/notifications": (body) => jsonResponse(body),
    });
    renderAt("/agent/settings/notifications", undefined, { api, role: "AGENT" });
    const table = await screen.findByRole("table", {}, { timeout: 5000 });
    fireEvent.click(within(table).getByRole("checkbox", { name: "SLA about to breach - email" }));
    fireEvent.click(screen.getByRole("checkbox", { name: /Daily digest/ }));
    fireEvent.click(screen.getByRole("button", { name: "Save notifications" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    const body = calls.find((c) => c.method === "PUT")?.body as {
      events: { event: string; email: boolean }[];
      dailyDigest: boolean;
    };
    expect(body.dailyDigest).toBe(true);
    expect(body.events.find((e) => e.event === "SLA_WARNING")?.email).toBe(false);
    expect(await axeViolations()).toEqual([]);
  });

  it("turns out of office on with dates", async () => {
    const { api, calls } = settingsApi({
      "GET /api/v1/agent/me/out-of-office": () =>
        jsonResponse({ enabled: false, from: null, until: null }),
      "PUT /api/v1/agent/me/out-of-office": (body) => jsonResponse(body),
    });
    renderAt("/agent/settings/out-of-office", undefined, { api, role: "AGENT" });
    fireEvent.click(
      await screen.findByRole("checkbox", { name: "I am out of office" }, { timeout: 5000 }),
    );
    fireEvent.change(screen.getByLabelText("From (optional)"), { target: { value: "2026-11-20" } });
    fireEvent.change(screen.getByLabelText("Until (optional)"), {
      target: { value: "2026-11-24" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(calls.find((c) => c.method === "PUT")?.body).toEqual({
        enabled: true,
        from: "2026-11-20",
        until: "2026-11-24",
      }),
    );
    expect(await axeViolations()).toEqual([]);
  });
});

describe("theme", () => {
  it("applies light, dark and system on <html>", () => {
    applyTheme("DARK");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
    applyTheme("LIGHT");
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
    applyTheme("SYSTEM");
    expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
  });

  it("still works when localStorage is blocked", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    expect(() => setTheme("DARK")).not.toThrow();
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
    expect(storedTheme()).toBeNull();
  });

  it("remembers the choice in this browser", () => {
    setTheme("LIGHT");
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("LIGHT");
  });
});
