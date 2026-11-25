import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { browserNavigation } from "../../api/microsoft";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";

// Synthetic ids only.
const CLIENT = "0b3c9b4e-1f00-4d6a-9c11-5e2f7a8d9e01";
const TENANT = "11111111-aaaa-4bbb-8ccc-000000000001";

function settingsPage(overrides: Record<string, unknown> = {}) {
  return {
    settings: {
      enabled: false,
      clientId: CLIENT,
      mspTenantId: TENANT,
      secretSet: true,
      secretExpiresOn: "2027-06-30",
      tested: false,
      testedAt: null,
      secretExpiry: { state: "OK", expiresOn: "2027-06-30", daysLeft: 200 },
      ...overrides,
    },
    redirectUri: "https://support.example.test/api/v1/auth/microsoft/callback",
    organisations: [{ id: 7, name: "Acme Dental", tenantId: null }],
  };
}

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

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("Sign in with Microsoft", () => {
  it("shows the button only when switched on and sends the browser to Microsoft", async () => {
    const go = vi.spyOn(browserNavigation, "go").mockImplementation(() => undefined);
    const { api } = recordingApi({
      "GET /api/v1/auth/microsoft": () => jsonResponse({ enabled: true }),
      "POST /api/v1/auth/microsoft/sign-in": () =>
        jsonResponse({ authorizationUrl: "https://login.microsoftonline.com/organizations/x" }),
    });
    renderAt("/sign-in", undefined, { signedOut: true, api });
    fireEvent.click(
      await screen.findByRole("button", { name: "Sign in with Microsoft" }, { timeout: 5000 }),
    );
    await waitFor(() =>
      expect(go).toHaveBeenCalledWith("https://login.microsoftonline.com/organizations/x"),
    );
  });

  it("explains a Microsoft account that is not linked", async () => {
    renderAt("/sign-in?microsoft=not_linked", undefined, { signedOut: true });
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Sign in with your password, then link Microsoft in your settings.",
    );
    expect(screen.queryByRole("button", { name: "Sign in with Microsoft" })).toBeNull();
  });
});

describe("Admin - Sign-in - Microsoft", () => {
  it("shows the saved details but never a secret, and keeps the switch off until tested", async () => {
    const { api } = recordingApi({
      "GET /api/v1/admin/sign-in/microsoft": () => jsonResponse(settingsPage()),
    });
    renderAt("/admin/sign-in", undefined, { api });
    expect(
      await screen.findByRole(
        "heading",
        { level: 1, name: "Microsoft sign-in" },
        { timeout: 5000 },
      ),
    ).toBeInTheDocument();
    expect(await screen.findByDisplayValue(CLIENT)).toBeInTheDocument();
    expect(screen.getByLabelText("Client secret")).toHaveValue("");
    expect(screen.getByText(/A secret is saved. It is never shown again/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Switch on" })).toBeDisabled();
    expect(
      screen.getByDisplayValue("https://support.example.test/api/v1/auth/microsoft/callback"),
    ).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  });

  it("saves through step-up and sends no secret when the field is empty", async () => {
    let attempts = 0;
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/sign-in/microsoft": () => jsonResponse(settingsPage()),
      "PUT /api/v1/admin/sign-in/microsoft": () =>
        ++attempts === 1
          ? jsonResponse({ error: "step_up_required", message: "Please confirm it is you." }, 403)
          : jsonResponse(settingsPage({ secretExpiresOn: "2027-12-31" })),
      "POST /api/v1/auth/step-up": () => new Response(null, { status: 204 }),
    });
    renderAt("/admin/sign-in", undefined, { api });
    const expires = await screen.findByLabelText(/^Client secret expires on/, undefined, {
      timeout: 5000,
    });
    fireEvent.change(expires, { target: { value: "2027-12-31" } });
    fireEvent.click(screen.getByRole("button", { name: "Save details" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/code/i), { target: { value: "123456" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /confirm/i }));
    await waitFor(() => expect(attempts).toBe(2));
    expect(calls.filter((c) => c.method === "PUT").map((c) => c.body)).toEqual([
      { clientId: CLIENT, clientSecret: null, secretExpiresOn: "2027-12-31", mspTenantId: TENANT },
      { clientId: CLIENT, clientSecret: null, secretExpiresOn: "2027-12-31", mspTenantId: TENANT },
    ]);
  });

  it("starts Test sign-in and reports a failure code in plain words", async () => {
    const go = vi.spyOn(browserNavigation, "go").mockImplementation(() => undefined);
    const { api } = recordingApi({
      "GET /api/v1/admin/sign-in/microsoft": () => jsonResponse(settingsPage()),
      "POST /api/v1/admin/sign-in/microsoft/test": () =>
        jsonResponse({ authorizationUrl: `https://login.microsoftonline.com/${TENANT}/x` }),
    });
    renderAt("/admin/sign-in?test=failed&code=AADSTS7000215", undefined, { api });
    expect(await screen.findByText(/Microsoft answered AADSTS7000215/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "Test sign-in" }));
    await waitFor(() =>
      expect(go).toHaveBeenCalledWith(`https://login.microsoftonline.com/${TENANT}/x`),
    );
  });

  it("warns admins when the client secret expires soon", async () => {
    const { api } = recordingApi({
      "GET /api/v1/admin/sign-in/microsoft": () =>
        jsonResponse(
          settingsPage({ secretExpiry: { state: "SOON", expiresOn: "2026-12-01", daysLeft: 12 } }),
        ),
    });
    renderAt("/admin/organisations", undefined, { api });
    expect(
      await screen.findByText(/client secret expires in 12 days/, undefined, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add a new secret" })).toHaveAttribute(
      "href",
      "/admin/sign-in",
    );
  });
});

describe("My settings - Microsoft account", () => {
  it("links through step-up and Microsoft, and unlinks", async () => {
    const go = vi.spyOn(browserNavigation, "go").mockImplementation(() => undefined);
    const { api } = recordingApi({
      "GET /api/v1/auth/microsoft/link": () =>
        jsonResponse({ available: true, linked: false, tenantId: null, linkedAt: null }),
      "POST /api/v1/auth/microsoft/link": () =>
        jsonResponse({ authorizationUrl: `https://login.microsoftonline.com/${TENANT}/x` }),
      "GET /api/v1/auth/mfa": () =>
        jsonResponse({ enrolled: true, enrolledAt: null, recoveryCodesLeft: 10 }),
      "GET /api/v1/auth/trusted-devices": () => jsonResponse([]),
      "GET /api/v1/auth/sessions": () => jsonResponse([]),
    });
    renderAt("/agent/settings/security", undefined, { role: "AGENT", api });
    fireEvent.click(
      await screen.findByRole("button", { name: "Link Microsoft account" }, { timeout: 5000 }),
    );
    await waitFor(() =>
      expect(go).toHaveBeenCalledWith(`https://login.microsoftonline.com/${TENANT}/x`),
    );
  });

  it("explains a wrong tenant after coming back from Microsoft", async () => {
    const { api } = recordingApi({
      "GET /api/v1/auth/microsoft/link": () =>
        jsonResponse({ available: true, linked: false, tenantId: null, linkedAt: null }),
      "GET /api/v1/auth/trusted-devices": () => jsonResponse([]),
      "GET /api/v1/auth/sessions": () => jsonResponse([]),
    });
    renderAt("/agent/settings/security?microsoft=wrong_tenant", undefined, { role: "AGENT", api });
    expect(
      await screen.findByText(/belongs to an organisation that is not allowed here/, undefined, {
        timeout: 5000,
      }),
    ).toBeInTheDocument();
  });
});
