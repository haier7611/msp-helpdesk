import { fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { jsonResponse, renderAt } from "./test/helpers";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("role guards and landing (UX only - the server enforces the gates)", () => {
  it.each([
    ["CUSTOMER", "Raise a support ticket"],
    ["AGENT", "My tickets"],
    ["ADMIN", "My tickets"],
  ] as const)("/ sends a %s to their landing page", async (role, heading) => {
    renderAt("/", undefined, { role });
    expect(await screen.findByRole("heading", { level: 1, name: heading })).toBeInTheDocument();
  });

  it.each(["/agent/queue", "/admin/organisations"])(
    "a customer opening %s lands on New ticket",
    async (path) => {
      renderAt(path, undefined, { role: "CUSTOMER" });
      expect(
        await screen.findByRole("heading", { level: 1, name: "Raise a support ticket" }),
      ).toBeInTheDocument();
    },
  );

  it("an agent opening /admin lands on the queue", async () => {
    renderAt("/admin/supporters", undefined, { role: "AGENT" });
    expect(
      await screen.findByRole("heading", { level: 1, name: "My tickets" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 1, name: "Agents" })).not.toBeInTheDocument();
  });

  it("supporters opening /portal land on the queue", async () => {
    renderAt("/portal/new-ticket", undefined, { role: "ADMIN" });
    expect(
      await screen.findByRole("heading", { level: 1, name: "My tickets" }),
    ).toBeInTheDocument();
  });

  it("an admin reaches the admin area", async () => {
    renderAt("/admin/supporters", undefined, { role: "ADMIN" });
    expect(await screen.findByRole("heading", { level: 1, name: "Agents" })).toBeInTheDocument();
  });

  it("not signed in: the guard's 401 goes to /sign-in", async () => {
    renderAt("/admin/organisations", undefined, { signedOut: true });
    expect(await screen.findByRole("heading", { level: 1, name: "Sign in" })).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { level: 1, name: "Organisations" }),
    ).not.toBeInTheDocument();
  });
});

describe("half-signed-in sessions (two-step owed) are not signed in to the app", () => {
  const CODE = "Two-step sign-in";
  const SET_UP = "Set up two-step sign-in";

  /** No app shell: no top bar (header), no navigation, no user name. */
  function expectNoShell() {
    expect(screen.queryByRole("banner")).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
    expect(screen.queryByText("Alex Tran")).not.toBeInTheDocument();
  }

  it.each([
    ["/agent/queue", "ADMIN"],
    ["/agent/tickets/ACME-1", "AGENT"],
    ["/admin", "ADMIN"],
    ["/admin/supporters", "ADMIN"],
    ["/", "AGENT"],
    ["/portal/new-ticket", "AGENT"],
  ] as const)("code owed: %s goes to the code step", async (path, role) => {
    renderAt(path, undefined, { role, authLevel: "MFA_REQUIRED" });
    expect(await screen.findByRole("heading", { level: 1, name: CODE })).toBeInTheDocument();
    expectNoShell();
    expect(screen.getByRole("button", { name: "Cancel and sign out" })).toBeInTheDocument();
  });

  it.each(["/agent/queue", "/admin", "/"])(
    "set-up owed: %s goes to the set-up step",
    async (path) => {
      renderAt(path, undefined, { role: "AGENT", authLevel: "MFA_ENROLMENT_REQUIRED" });
      expect(await screen.findByRole("heading", { level: 1, name: SET_UP })).toBeInTheDocument();
      expectNoShell();
      expect(screen.getByRole("button", { name: "Cancel and sign out" })).toBeInTheDocument();
    },
  );

  it("fully signed in: the same routes open normally", async () => {
    renderAt("/agent/queue", undefined, { role: "AGENT", authLevel: "FULL" });
    expect(
      await screen.findByRole("heading", { level: 1, name: "My tickets" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("banner")).toBeInTheDocument();
  });

  it("/sign-in stays reachable with a half-signed-in session", async () => {
    renderAt("/sign-in", undefined, { role: "AGENT", authLevel: "MFA_REQUIRED" });
    expect(await screen.findByRole("heading", { level: 1, name: "Sign in" })).toBeInTheDocument();
  });

  it.each([
    ["/mfa/verify", "MFA_REQUIRED"],
    ["/mfa/enrol", "MFA_ENROLMENT_REQUIRED"],
  ] as const)("Cancel and sign out on %s calls the real sign-out", async (path, authLevel) => {
    const { fetchMock } = renderAt(path, undefined, {
      role: "AGENT",
      authLevel,
      api: (url) =>
        url === "/api/v1/auth/sign-out" ? new Response(null, { status: 204 }) : undefined,
    });
    fireEvent.click(await screen.findByRole("button", { name: "Cancel and sign out" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Sign in" })).toBeInTheDocument();
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([url, init]) => url === "/api/v1/auth/sign-out" && init?.method === "POST",
        ),
      ).toBe(true),
    );
  });

  it("set-up opened from My settings has no Cancel and sign out", async () => {
    renderAt("/mfa/enrol?from=settings", () => jsonResponse({}), { role: "AGENT" });
    expect(await screen.findByRole("heading", { level: 1, name: SET_UP })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel and sign out" })).not.toBeInTheDocument();
  });
});
