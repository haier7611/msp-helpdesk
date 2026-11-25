import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { ToastProvider } from "../../components/Toast";
import { AppRoutes } from "../../routes";
import { axeViolations, jsonResponse } from "../../test/helpers";

type Call = { path: string; method: string; body: unknown };
type Handler = (call: Call, count: number) => Response;

/** Stubs fetch by "METHOD path"; records every call. */
function stubApi(handlers: Record<string, Handler>) {
  const calls: Call[] = [];
  const counts = new Map<string, number>();
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const path = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
    const call = { path, method, body };
    calls.push(call);
    if (path === "/api/v1/auth/csrf") {
      document.cookie = "XSRF-TOKEN=csrf-test-token; path=/";
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    const key = `${method} ${path}`;
    const handler = handlers[key];
    if (handler) {
      const n = (counts.get(key) ?? 0) + 1;
      counts.set(key, n);
      return Promise.resolve(handler(call, n));
    }
    // WP-03's route guards ask /auth/me before showing the agent area: a fully signed-in agent.
    if (key === "GET /api/v1/auth/me") {
      return Promise.resolve(jsonResponse(full));
    }
    return Promise.resolve(
      jsonResponse({ name: "Ticket System", version: "0.1.0", gitSha: "abc1234" }),
    );
  });
  vi.stubGlobal("fetch", fetchMock);
  return { calls };
}

function renderPath(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        <AppRoutes />
      </ToastProvider>
    </MemoryRouter>,
  );
}

function type(label: string | RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

const agent = { id: 3, email: "alex@example.test", name: "Alex", role: "AGENT" };
const full = { ...agent, authLevel: "FULL" };
const stepUpRequired = () =>
  jsonResponse(
    { error: "step_up_required", message: "Please confirm it is you with a code." },
    403,
  );

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.unstubAllGlobals();
});

describe("two-step sign-in", () => {
  it("sends a supporter who owes a code from sign-in to the code page", async () => {
    stubApi({
      "POST /api/v1/auth/sign-in": () => jsonResponse({ ...agent, authLevel: "MFA_REQUIRED" }),
    });
    renderPath("/sign-in");
    type("Email", "alex@example.test");
    type("Password", "Violet-Tractor-Sundial-42");
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "Two-step sign-in" }),
    ).toBeInTheDocument();
  });

  it("sends a supporter without TOTP to the set-up page", async () => {
    stubApi({
      "POST /api/v1/auth/sign-in": () =>
        jsonResponse({ ...agent, authLevel: "MFA_ENROLMENT_REQUIRED" }),
    });
    renderPath("/sign-in");
    type("Email", "alex@example.test");
    type("Password", "Violet-Tractor-Sundial-42");
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "Set up two-step sign-in" }),
    ).toBeInTheDocument();
  });

  it("verifies a code, can trust the device, and lands on the queue", async () => {
    const { calls } = stubApi({
      "POST /api/v1/auth/mfa/verify": () => jsonResponse({ me: full, recoveryCodesLeft: null }),
    });
    renderPath("/mfa/verify");
    await screen.findByRole("heading", { level: 1, name: "Two-step sign-in" });
    expect(await axeViolations()).toEqual([]);
    type("6-digit code", "123456");
    fireEvent.click(screen.getByLabelText("Trust this device for 30 days"));
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "My tickets" }),
    ).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v1/auth/mfa/verify")?.body).toEqual({
      code: "123456",
      trustDevice: true,
    });
  });

  it("shows the server's message for a wrong code", async () => {
    stubApi({
      "POST /api/v1/auth/mfa/verify": () =>
        jsonResponse({ error: "invalid_code", message: "That code is not right." }, 400),
    });
    renderPath("/mfa/verify");
    type("6-digit code", "000000");
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect((await screen.findAllByRole("alert"))[0]).toHaveTextContent("That code is not right.");
  });

  it("accepts a recovery code instead", async () => {
    const { calls } = stubApi({
      "POST /api/v1/auth/mfa/recovery": () => jsonResponse({ me: full, recoveryCodesLeft: 2 }),
    });
    renderPath("/mfa/verify");
    fireEvent.click(await screen.findByRole("button", { name: "Use a recovery code instead" }));
    type("Recovery code", "ABCD-EFGH-JKMN");
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "My tickets" }),
    ).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v1/auth/mfa/recovery")?.body).toEqual({
      recoveryCode: "ABCD-EFGH-JKMN",
      trustDevice: false,
    });
    expect(await screen.findByText(/2 recovery codes left/)).toBeInTheDocument();
  });

  it("offers 'Trust this device' on the recovery-code step too (WP-15)", async () => {
    const { calls } = stubApi({
      "POST /api/v1/auth/mfa/recovery": () => jsonResponse({ me: full, recoveryCodesLeft: 9 }),
    });
    renderPath("/mfa/verify");
    fireEvent.click(await screen.findByRole("button", { name: "Use a recovery code instead" }));
    type("Recovery code", "ABCD-EFGH-JKMN");
    fireEvent.click(screen.getByLabelText("Trust this device for 30 days"));
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    await screen.findByRole("heading", { level: 1, name: "My tickets" });
    expect(calls.find((c) => c.path === "/api/v1/auth/mfa/recovery")?.body).toEqual({
      recoveryCode: "ABCD-EFGH-JKMN",
      trustDevice: true,
    });
  });

  it("shows an exempt engineer the paused banner and asks them for their password at step-up", async () => {
    const exempt = { ...full, mfaExemptUntil: "2026-11-26T09:30:00Z" };
    const fresh = Array.from({ length: 10 }, (_, i) => `NEW0-0000-000${i}`);
    const { calls } = stubApi({
      "GET /api/v1/auth/me": () => jsonResponse(exempt),
      "GET /api/v1/auth/mfa": () =>
        jsonResponse({ enrolled: true, enrolledAt: "2026-11-01T08:00:00Z", recoveryCodesLeft: 8 }),
      "GET /api/v1/auth/sessions": () => jsonResponse([]),
      "GET /api/v1/auth/trusted-devices": () => jsonResponse([]),
      "POST /api/v1/auth/mfa/recovery-codes": (_call, n) =>
        n === 1 ? stepUpRequired() : jsonResponse({ recoveryCodes: fresh, me: null }),
      "POST /api/v1/auth/step-up": () => jsonResponse({ recoveryCodesLeft: null }),
    });
    renderPath("/agent/settings/security");
    expect(
      await screen.findByText(/^Two-step sign-in is paused for your account until 26 Nov 2026/),
    ).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "Make new recovery codes" }));
    const dialog = await screen.findByRole("dialog", { name: "Confirm it is you" });
    const password = await within(dialog).findByLabelText("Password");
    expect(within(dialog).queryByRole("button", { name: "Use a recovery code" })).toBeNull();
    expect(await axeViolations()).toEqual([]);
    fireEvent.change(password, { target: { value: "Violet-Tractor-Sundial-42" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await screen.findByRole("dialog", { name: "Your new recovery codes" });
    expect(calls.find((c) => c.path === "/api/v1/auth/step-up")?.body).toEqual({
      password: "Violet-Tractor-Sundial-42",
    });
  });

  it("switches the step-up to a code when the pause ended while the dialog was open", async () => {
    const exempt = { ...full, mfaExemptUntil: "2026-11-26T09:30:00Z" };
    stubApi({
      "GET /api/v1/auth/me": () => jsonResponse(exempt),
      "GET /api/v1/auth/mfa": () =>
        jsonResponse({ enrolled: true, enrolledAt: "2026-11-01T08:00:00Z", recoveryCodesLeft: 8 }),
      "GET /api/v1/auth/sessions": () => jsonResponse([]),
      "GET /api/v1/auth/trusted-devices": () => jsonResponse([]),
      "POST /api/v1/auth/mfa/recovery-codes": () => stepUpRequired(),
      "POST /api/v1/auth/step-up": () =>
        jsonResponse(
          {
            error: "password_step_up_not_allowed",
            message: "Confirm with a code from your authenticator app.",
          },
          403,
        ),
    });
    renderPath("/agent/settings/security");
    fireEvent.click(await screen.findByRole("button", { name: "Make new recovery codes" }));
    const dialog = await screen.findByRole("dialog", { name: "Confirm it is you" });
    fireEvent.change(await within(dialog).findByLabelText("Password"), {
      target: { value: "Violet-Tractor-Sundial-42" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
    expect(
      await within(dialog).findByText("Confirm with a code from your authenticator app."),
    ).toBeInTheDocument();
    expect(within(dialog).getByLabelText("6-digit code")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Use a recovery code" })).toBeInTheDocument();
  });

  it("shows no banner and asks for a code when two-step is not paused", async () => {
    stubApi({
      "GET /api/v1/auth/mfa": () =>
        jsonResponse({ enrolled: true, enrolledAt: "2026-11-01T08:00:00Z", recoveryCodesLeft: 8 }),
      "GET /api/v1/auth/sessions": () => jsonResponse([]),
      "GET /api/v1/auth/trusted-devices": () => jsonResponse([]),
      "POST /api/v1/auth/mfa/recovery-codes": () => stepUpRequired(),
    });
    renderPath("/agent/settings/security");
    fireEvent.click(await screen.findByRole("button", { name: "Make new recovery codes" }));
    const dialog = await screen.findByRole("dialog", { name: "Confirm it is you" });
    expect(within(dialog).getByLabelText("6-digit code")).toBeInTheDocument();
    expect(screen.queryByText(/Two-step sign-in is paused/)).toBeNull();
  });

  it("enrols: QR code, confirm, then the recovery codes once", async () => {
    const codes = Array.from({ length: 10 }, (_, i) => `AAAA-BBBB-CC${String(i).padStart(2, "0")}`);
    const { calls } = stubApi({
      "POST /api/v1/auth/mfa/enrolment": () =>
        jsonResponse({
          manualKey: "JBSWY3DPEHPK3PXP",
          otpauthUri: "otpauth://totp/x",
          qrSize: 29,
          qrPath: "M4 4h1v1h-1z",
        }),
      "POST /api/v1/auth/mfa/enrolment/confirm": () =>
        jsonResponse({ recoveryCodes: codes, me: full }),
    });
    renderPath("/mfa/enrol");
    fireEvent.click(await screen.findByRole("button", { name: "Show the QR code" }));
    expect(
      await screen.findByRole("img", { name: "QR code for your authenticator app" }),
    ).toBeInTheDocument();
    expect(screen.getByText("JBSWY3DPEHPK3PXP")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
    type("6-digit code", "654321");
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));

    const list = await screen.findByRole("list", { name: "Recovery codes" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(10);
    const proceed = screen.getByRole("button", { name: "Continue" });
    expect(proceed).toBeDisabled();
    fireEvent.click(screen.getByLabelText("I have saved my recovery codes"));
    fireEvent.click(proceed);
    expect(
      await screen.findByRole("heading", { level: 1, name: "My tickets" }),
    ).toBeInTheDocument();
    expect(calls.find((c) => c.path.endsWith("/confirm"))?.body).toEqual({ code: "654321" });
  });

  it("refuses a QR path that is not plain path data", async () => {
    stubApi({
      "POST /api/v1/auth/mfa/enrolment": () =>
        jsonResponse({
          manualKey: "X",
          otpauthUri: "otpauth://totp/x",
          qrSize: 29,
          qrPath: '"/><script>alert(1)</script>',
        }),
    });
    renderPath("/mfa/enrol");
    fireEvent.click(await screen.findByRole("button", { name: "Show the QR code" }));
    expect((await screen.findAllByRole("alert"))[0]).toBeInTheDocument();
    expect(screen.queryByRole("img", { name: /QR code/ })).not.toBeInTheDocument();
  });
});

describe("My settings - Security", () => {
  const sessions = [
    {
      id: "s-current",
      createdAt: "2026-11-18T08:00:00Z",
      lastActiveAt: "2026-11-18T09:00:00Z",
      ipAddress: "198.51.100.7",
      device: "Firefox on Windows",
      authLevel: "FULL",
      current: true,
    },
    {
      id: "s-other",
      createdAt: "2026-11-17T08:00:00Z",
      lastActiveAt: "2026-11-17T09:00:00Z",
      ipAddress: "203.0.113.4",
      device: "Safari on iOS",
      authLevel: "FULL",
      current: false,
    },
  ];
  const devices = [
    {
      id: 11,
      label: "Firefox on Windows",
      createdAt: "2026-11-01T08:00:00Z",
      lastUsedAt: "2026-11-18T08:00:00Z",
      trustedUntil: "2026-12-01T08:00:00Z",
    },
  ];
  const base = {
    "GET /api/v1/auth/mfa": () =>
      jsonResponse({ enrolled: true, enrolledAt: "2026-11-01T08:00:00Z", recoveryCodesLeft: 8 }),
    "GET /api/v1/auth/sessions": () => jsonResponse(sessions),
    "GET /api/v1/auth/trusted-devices": () => jsonResponse(devices),
  };

  it("lists sessions and devices and signs one session out", async () => {
    const { calls } = stubApi({
      ...base,
      "DELETE /api/v1/auth/sessions/s-other": () => new Response(null, { status: 204 }),
      "DELETE /api/v1/auth/trusted-devices/11": () => new Response(null, { status: 204 }),
    });
    renderPath("/agent/settings/security");
    expect(await screen.findByRole("heading", { level: 1, name: "Security" })).toBeInTheDocument();
    expect(
      await screen.findByText("8 of 10 recovery codes left.", { exact: false }),
    ).toBeInTheDocument();
    expect(await screen.findByText("Firefox on Windows (this browser)")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);

    fireEvent.click(screen.getByRole("button", { name: "Sign out Safari on iOS" }));
    await waitFor(() =>
      expect(calls.some((c) => c.method === "DELETE" && c.path.endsWith("/s-other"))).toBe(true),
    );
    fireEvent.click(screen.getByRole("button", { name: "Remove Firefox on Windows" }));
    await waitFor(() =>
      expect(
        calls.some((c) => c.method === "DELETE" && c.path.endsWith("/trusted-devices/11")),
      ).toBe(true),
    );
  });

  it("asks for a code when the server wants a step-up, then retries the action", async () => {
    const fresh = Array.from({ length: 10 }, (_, i) => `ZZZZ-YYYY-XX${String(i).padStart(2, "0")}`);
    const { calls } = stubApi({
      ...base,
      "POST /api/v1/auth/mfa/recovery-codes": (_call, n) =>
        n === 1 ? stepUpRequired() : jsonResponse({ recoveryCodes: fresh, me: null }),
      "POST /api/v1/auth/step-up": () => jsonResponse({ recoveryCodesLeft: null }),
    });
    renderPath("/agent/settings/security");
    fireEvent.click(await screen.findByRole("button", { name: "Make new recovery codes" }));
    const dialog = await screen.findByRole("dialog", { name: "Confirm it is you" });
    fireEvent.change(within(dialog).getByLabelText("6-digit code"), {
      target: { value: "246810" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));

    const codes = await screen.findByRole("dialog", { name: "Your new recovery codes" });
    expect(within(codes).getAllByRole("listitem")).toHaveLength(10);
    expect(calls.filter((c) => c.path === "/api/v1/auth/mfa/recovery-codes")).toHaveLength(2);
    expect(calls.find((c) => c.path === "/api/v1/auth/step-up")?.body).toEqual({ code: "246810" });
  });

  it("changes the password with the current one", async () => {
    const { calls } = stubApi({
      ...base,
      "POST /api/v1/auth/password/change": () => new Response(null, { status: 204 }),
    });
    renderPath("/agent/settings/security");
    await screen.findByRole("heading", { level: 1, name: "Security" });
    type("Current password", "Violet-Tractor-Sundial-42");
    type("New password", "Copper-Meadow-Lantern-58");
    type("Repeat new password", "Copper-Meadow-Lantern-58");
    fireEvent.click(screen.getByRole("button", { name: "Change password" }));
    await waitFor(() =>
      expect(calls.find((c) => c.path === "/api/v1/auth/password/change")?.body).toEqual({
        currentPassword: "Violet-Tractor-Sundial-42",
        newPassword: "Copper-Meadow-Lantern-58",
      }),
    );
  });
});
