import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ADMIN_PLACEHOLDERS } from "../../navigation";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";

// Admin - Customers. Synthetic customers only.
const sam = {
  id: 21,
  name: "Sam Example",
  email: "sam.example@acme-dental.example",
  organisationId: 7,
  organisationName: "Acme Dental",
  status: "ACTIVE",
  lastSignInAt: "2026-11-18T08:00:00Z",
  createdAt: "2026-10-01T08:00:00Z",
};
const robin = {
  id: 22,
  name: "Robin Example",
  email: "robin@beta-clinic.example",
  organisationId: 8,
  organisationName: "Beta Clinic",
  status: "INVITED",
  lastSignInAt: null,
  createdAt: "2026-11-01T08:00:00Z",
};
const samDetail = {
  ...sam,
  passwordSet: true,
  microsoftLinked: true,
  ticketsRaised: 3,
  invitationExpiresAt: null,
};
const robinDetail = {
  ...robin,
  passwordSet: false,
  microsoftLinked: false,
  ticketsRaised: 0,
  invitationExpiresAt: "2099-11-25T08:00:00Z",
};
const organisations = [
  {
    id: 7,
    name: "Acme Dental",
    prefix: "ACD",
    retiredPrefixes: [],
    verifiedDomains: [],
    timezone: "Asia/Ho_Chi_Minh",
    entraTenantId: null,
  },
  {
    id: 8,
    name: "Beta Clinic",
    prefix: "BCL",
    retiredPrefixes: [],
    verifiedDomains: [],
    timezone: "Asia/Ho_Chi_Minh",
    entraTenantId: null,
  },
];

type Call = { path: string; method: string; body: unknown };

function customersApi(handlers: Record<string, (body: unknown) => Response> = {}) {
  const calls: Call[] = [];
  const api = (path: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
    calls.push({ path, method, body });
    const handler = handlers[`${method} ${path}`];
    if (handler) return handler(body);
    if (method === "GET" && path.startsWith("/api/v1/admin/customers?")) {
      return jsonResponse({ items: [sam, robin], total: 2 });
    }
    if (method === "GET" && path === "/api/v1/admin/customers/21") return jsonResponse(samDetail);
    if (method === "GET" && path === "/api/v1/admin/customers/22") return jsonResponse(robinDetail);
    if (method === "GET" && path === "/api/v1/admin/organisations") {
      return jsonResponse(organisations);
    }
    return undefined;
  };
  return { api, calls };
}

const stepUpOnce = (ok: () => Response) => {
  let n = 0;
  return () => {
    n += 1;
    return n === 1
      ? jsonResponse({ error: "step_up_required", message: "Confirm it is you." }, 403)
      : ok();
  };
};

async function confirmStepUp() {
  const dialog = await screen.findByRole("dialog", { name: "Confirm it is you" });
  fireEvent.change(within(dialog).getByLabelText("6-digit code"), { target: { value: "123456" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
}

async function openPanel(name: string) {
  fireEvent.click(await screen.findByRole("button", { name }, { timeout: 5000 }));
  const panel = screen.getByRole("complementary", { name: "Customer detail" });
  await within(panel).findByText("Sign-in methods");
  return panel;
}

function popups(): HTMLElement {
  return screen.getByTestId("error-popups");
}

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.unstubAllGlobals();
});

describe("Admin - Customers", () => {
  it("is a real screen now, not a placeholder", () => {
    expect(ADMIN_PLACEHOLDERS.some((p) => p.path === "customers")).toBe(false);
  });

  it("lists customers of every organisation and sends the filters and sort to the server", async () => {
    const { api, calls } = customersApi();
    renderAt("/admin/customers", undefined, { api });
    expect(
      await screen.findByRole("button", { name: "Sam Example" }, { timeout: 5000 }),
    ).toBeVisible();
    expect(screen.getByText("2 customers")).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getByText("Beta Clinic")).toBeInTheDocument();
    expect(within(table).getByText("Invited")).toBeInTheDocument();
    expect(calls.some((c) => c.path === "/api/v1/admin/customers?limit=50")).toBe(true);

    await screen.findByRole("option", { name: "Beta Clinic" });
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "robin" } });
    fireEvent.change(screen.getByLabelText("Organisation"), { target: { value: "8" } });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "INVITED" } });
    fireEvent.change(screen.getByLabelText("Sort by"), { target: { value: "lastSignIn" } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.path ===
            "/api/v1/admin/customers?q=robin&organisationId=8&status=INVITED&sort=lastSignIn&limit=50",
        ),
      ).toBe(true),
    );
    expect(await axeViolations()).toEqual([]);
  });

  it("shows the detail panel: sign-in methods, last sign-in, tickets with a queue link", async () => {
    const { api } = customersApi();
    renderAt("/admin/customers", undefined, { api });
    const panel = await openPanel("Sam Example");
    expect(within(panel).getByText("Password set, Microsoft linked")).toBeInTheDocument();
    expect(within(panel).getByText("Accepted - they have signed up")).toBeInTheDocument();
    const link = within(panel).getByRole("link", { name: "Search the queue for their email" });
    expect(link.getAttribute("href")).toContain("q=sam.example%40acme-dental.example");
    // Active customers get a reset link, not an invitation.
    expect(within(panel).getByRole("button", { name: "Send password reset link" })).toBeVisible();
    expect(within(panel).queryByRole("button", { name: /invitation/ })).toBeNull();
    expect(await axeViolations()).toEqual([]);
  });

  it("resends an invitation to an invited customer", async () => {
    const { api, calls } = customersApi({
      "POST /api/v1/admin/customers/22/invitation": () =>
        jsonResponse({ expiresAt: "2099-11-27T08:00:00Z" }, 202),
    });
    renderAt("/admin/customers", undefined, { api });
    const panel = await openPanel("Robin Example");
    expect(within(panel).getByText(/Sent - link valid until/)).toBeInTheDocument();
    fireEvent.click(within(panel).getByRole("button", { name: "Resend invitation" }));
    expect(await screen.findByText("Invitation sent. The link is valid for 7 days.")).toBeVisible();
    expect(calls.some((c) => c.method === "POST" && c.path.endsWith("/22/invitation"))).toBe(true);
    expect(within(panel).queryByRole("button", { name: "Send password reset link" })).toBeNull();
  });

  it("signs a customer out everywhere through step-up", async () => {
    const { api, calls } = customersApi({
      "POST /api/v1/admin/customers/21/sign-out-everywhere": stepUpOnce(
        () => new Response(null, { status: 204 }),
      ),
      "POST /api/v1/auth/step-up": () => new Response(null, { status: 204 }),
    });
    renderAt("/admin/customers", undefined, { api });
    const panel = await openPanel("Sam Example");
    fireEvent.click(within(panel).getByRole("button", { name: "Sign out everywhere" }));
    await confirmStepUp();
    expect(await screen.findByText("Signed out everywhere.")).toBeVisible();
    expect(calls.filter((c) => c.path.endsWith("/21/sign-out-everywhere"))).toHaveLength(2);
  });

  it("deactivates after a confirmation and can reactivate", async () => {
    const deactivated = { ...samDetail, status: "DEACTIVATED" };
    let state = samDetail;
    const { api, calls } = customersApi({
      "GET /api/v1/admin/customers/21": () => jsonResponse(state),
      "POST /api/v1/admin/customers/21/deactivate": () => {
        state = deactivated;
        return jsonResponse(deactivated);
      },
      "POST /api/v1/admin/customers/21/reactivate": () => {
        state = samDetail;
        return jsonResponse(samDetail);
      },
    });
    renderAt("/admin/customers", undefined, { api });
    const panel = await openPanel("Sam Example");
    fireEvent.click(within(panel).getByRole("button", { name: "Deactivate" }));
    const dialog = screen.getByRole("dialog", { name: "Deactivate Sam Example?" });
    expect(within(dialog).getByText(/Their tickets stay as they are/)).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(within(dialog).getByRole("button", { name: "Deactivate" }));
    expect(
      await screen.findByText("Sam Example is deactivated and signed out everywhere."),
    ).toBeVisible();
    fireEvent.click(await within(panel).findByRole("button", { name: "Reactivate" }));
    expect(await screen.findByText("Sam Example is active again.")).toBeVisible();
    expect(calls.filter((c) => c.method === "POST").map((c) => c.path)).toEqual([
      "/api/v1/admin/customers/21/deactivate",
      "/api/v1/admin/customers/21/reactivate",
    ]);
  });

  it("moves a customer to another organisation through step-up, explaining what happens", async () => {
    const { api, calls } = customersApi({
      "PUT /api/v1/admin/customers/21/organisation": stepUpOnce(() =>
        jsonResponse({
          customer: { ...samDetail, organisationId: 8, organisationName: "Beta Clinic" },
          participantTicketsRemoved: 2,
        }),
      ),
      "POST /api/v1/auth/step-up": () => new Response(null, { status: 204 }),
    });
    renderAt("/admin/customers", undefined, { api });
    const panel = await openPanel("Sam Example");
    fireEvent.click(within(panel).getByRole("button", { name: "Move to another organisation" }));
    const dialog = screen.getByRole("dialog", { name: "Move Sam Example to another organisation" });
    expect(
      within(dialog).getByText(/Tickets they raised stay with Acme Dental/),
    ).toBeInTheDocument();
    // Only other organisations are offered.
    expect(within(dialog).queryByRole("option", { name: "Acme Dental" })).toBeNull();
    expect(within(dialog).getByRole("button", { name: "Move" })).toBeDisabled();
    expect(await axeViolations()).toEqual([]);
    fireEvent.change(within(dialog).getByLabelText("Move to"), { target: { value: "8" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Move" }));
    await confirmStepUp();
    expect(
      await screen.findByText(
        "Sam Example moved to Beta Clinic. Removed as a participant from 2 tickets.",
      ),
    ).toBeVisible();
    const puts = calls.filter((c) => c.method === "PUT");
    expect(puts).toHaveLength(2);
    expect(puts[1]?.body).toEqual({ organisationId: 8 });
  });

  it("erases only after the email is typed, with an irreversible warning and step-up", async () => {
    const { api, calls } = customersApi({
      "POST /api/v1/admin/users/21/erase": stepUpOnce(() => new Response(null, { status: 204 })),
      "POST /api/v1/auth/step-up": () => new Response(null, { status: 204 }),
    });
    renderAt("/admin/customers", undefined, { api });
    const panel = await openPanel("Sam Example");
    fireEvent.click(within(panel).getByRole("button", { name: "Erase personal data" }));
    const dialog = screen.getByRole("dialog", { name: "Erase Sam Example's personal data" });
    expect(within(dialog).getByText("This cannot be undone.")).toBeInTheDocument();
    const erase = within(dialog).getByRole("button", { name: "Erase permanently" });
    const field = within(dialog).getByLabelText("Type sam.example@acme-dental.example to confirm");
    fireEvent.change(field, { target: { value: "sam.example@acme-dental" } });
    expect(erase).toBeDisabled();
    expect(await axeViolations()).toEqual([]);
    fireEvent.change(field, { target: { value: " SAM.example@acme-dental.example " } });
    expect(erase).toBeEnabled();
    fireEvent.click(erase);
    await confirmStepUp();
    expect(await screen.findByText("Personal data erased.")).toBeVisible();
    expect(calls.filter((c) => c.path === "/api/v1/admin/users/21/erase")).toHaveLength(2);
    await waitFor(() =>
      expect(screen.queryByRole("complementary", { name: "Customer detail" })).toBeNull(),
    );
  });

  it("shows a server refusal as a pop-up error (no silent failures)", async () => {
    const { api } = customersApi({
      "POST /api/v1/admin/customers/21/password-reset": () =>
        jsonResponse(
          { error: "not_active", message: "Only active customers can reset their password." },
          409,
        ),
    });
    renderAt("/admin/customers", undefined, { api });
    const panel = await openPanel("Sam Example");
    fireEvent.click(within(panel).getByRole("button", { name: "Send password reset link" }));
    expect(
      await within(popups()).findByText(
        "The password reset link was not sent. Only active customers can reset their password.",
      ),
    ).toBeInTheDocument();
  });

  it("shows the list error when the server refuses (for example a non-admin session)", async () => {
    const { api } = customersApi({
      "GET /api/v1/admin/customers?limit=50": () =>
        jsonResponse({ error: "forbidden", message: "You do not have access to this." }, 403),
    });
    renderAt("/admin/customers", undefined, { api });
    expect(
      await screen.findByText("You don't have permission to do that.", undefined, {
        timeout: 5000,
      }),
    ).toBeVisible();
  });
});
