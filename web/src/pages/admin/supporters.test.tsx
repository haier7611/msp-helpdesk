import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { exemptionEndsAt } from "../../exemption";
import { formatShortDate, formatShortDateTime } from "../../format";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";

// Synthetic supporters only.
const base = {
  jobTitle: "",
  allOrganisations: true,
  organisationIds: [],
  mfaEnrolled: true,
  lastSignInAt: "2026-11-18T08:00:00Z",
  openTickets: 0,
  outOfOffice: false,
  outOfOfficeUntil: null,
  invitationExpiresAt: null,
  createdAt: "2026-11-01T08:00:00Z",
  self: false,
  roleChangeBlockedReason: null,
  deactivateBlockedReason: null,
  mfaExemptUntil: null,
  mfaExemptReason: null,
};

const me = {
  ...base,
  id: 1,
  name: "Alex Tran",
  email: "alex@example.test",
  role: "ADMIN",
  status: "ACTIVE",
  self: true,
  roleChangeBlockedReason: "You cannot change your own role.",
  deactivateBlockedReason: "You cannot deactivate yourself.",
};
const minh = {
  ...base,
  id: 2,
  name: "Minh Le",
  email: "minh@example.test",
  role: "AGENT",
  status: "ACTIVE",
  openTickets: 2,
};
const jamie = {
  ...base,
  id: 3,
  name: "Jamie Example",
  email: "jamie@example.test",
  role: "AGENT",
  status: "INVITED",
  mfaEnrolled: false,
  lastSignInAt: null,
  invitationExpiresAt: "2026-11-20T08:00:00Z",
};

type Call = { path: string; method: string; body: unknown };

function supportersApi(handlers: Record<string, (body: unknown) => Response> = {}) {
  const calls: Call[] = [];
  const api = (path: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
    calls.push({ path, method, body });
    const handler = handlers[`${method} ${path}`];
    if (handler) return handler(body);
    if (method === "GET" && path.startsWith("/api/v1/admin/supporters?")) {
      const params = new URLSearchParams(path.split("?")[1]);
      return jsonResponse(
        [me, minh, jamie].filter(
          (s) =>
            (!params.get("role") || s.role === params.get("role")) &&
            (!params.get("status") || s.status === params.get("status")),
        ),
      );
    }
    if (method === "GET" && path === "/api/v1/admin/supporters") {
      return jsonResponse([me, minh, jamie]);
    }
    if (method === "GET" && path === "/api/v1/admin/organisations") {
      return jsonResponse([
        {
          id: 7,
          name: "Acme Dental",
          prefix: "ACD",
          retiredPrefixes: [],
          verifiedDomains: [],
          timezone: "Asia/Ho_Chi_Minh",
          entraTenantId: null,
        },
      ]);
    }
    return undefined;
  };
  return { api, calls };
}

async function open(name: string) {
  fireEvent.click(await screen.findByRole("button", { name }, { timeout: 5000 }));
  return screen.getByRole("complementary", { name: "Agent detail" });
}

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.unstubAllGlobals();
});

describe("Admin - Manage supporters", () => {
  it("lists supporters from the API and filters by role and status", async () => {
    const { api, calls } = supportersApi();
    renderAt("/admin/supporters", undefined, { api });
    expect(await screen.findByRole("button", { name: "Minh Le" }, { timeout: 5000 })).toBeVisible();
    expect(screen.getByText("2 active, 1 invited")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Role"), { target: { value: "ADMIN" } });
    await waitFor(() => expect(screen.queryByRole("button", { name: "Minh Le" })).toBeNull());
    expect(calls.some((c) => c.path === "/api/v1/admin/supporters?role=ADMIN")).toBe(true);

    fireEvent.change(screen.getByLabelText("Role"), { target: { value: "" } });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "INVITED" } });
    expect(await screen.findByRole("button", { name: "Jamie Example" })).toBeVisible();
    await waitFor(() => expect(screen.queryByRole("button", { name: "Minh Le" })).toBeNull());
  });

  it("disables demote and deactivate for yourself, with the server's reason", async () => {
    const { api } = supportersApi();
    renderAt("/admin/supporters", undefined, { api });
    const panel = await open("Alex Tran");
    const deactivate = within(panel).getByRole("button", { name: "Deactivate" });
    expect(deactivate).toBeDisabled();
    expect(within(panel).getByText("You cannot deactivate yourself.")).toBeInTheDocument();
    fireEvent.click(within(panel).getByRole("tab", { name: "Access" }));
    expect(within(panel).getByRole("button", { name: "Change role" })).toBeDisabled();
    expect(within(panel).getByText("You cannot change your own role.")).toBeInTheDocument();
  });

  it("shows the four side panel tabs and the activity as plain text", async () => {
    const { api } = supportersApi({
      "GET /api/v1/admin/supporters/2/activity": () =>
        jsonResponse([
          {
            id: 9,
            occurredAt: "2026-11-18T08:00:00Z",
            type: "NEW_DEVICE_SIGN_IN",
            actorUserId: 2,
            actorName: "Minh Le",
            targetUserId: 2,
            targetName: "Minh Le",
            clientIp: "203.0.113.9",
            details: { device: "<img src=x onerror=alert(1)>" },
          },
        ]),
    });
    renderAt("/admin/supporters", undefined, { api });
    const panel = await open("Minh Le");
    expect(
      within(panel)
        .getAllByRole("tab")
        .map((t) => t.textContent),
    ).toEqual(["Overview", "Access", "Security", "Activity"]);
    fireEvent.click(within(panel).getByRole("tab", { name: "Activity" }));
    const activity = await within(panel).findByRole("list", { name: "Activity" });
    expect(activity).toHaveTextContent("New device sign in (device: <img src=x onerror=alert(1)>)");
    // Attacker-chosen text stays text: no element was created from it.
    expect(activity.querySelector("img")).toBeNull();
  });

  it("changes the role through step-up: asks for a code, then retries", async () => {
    let attempts = 0;
    const { api, calls } = supportersApi({
      "PUT /api/v1/admin/supporters/2/role": () => {
        attempts += 1;
        return attempts === 1
          ? jsonResponse({ error: "step_up_required", message: "Confirm it is you." }, 403)
          : jsonResponse({ ...minh, role: "ADMIN" });
      },
      "POST /api/v1/auth/step-up": () => new Response(null, { status: 204 }),
    });
    renderAt("/admin/supporters", undefined, { api });
    const panel = await open("Minh Le");
    fireEvent.click(within(panel).getByRole("tab", { name: "Access" }));
    fireEvent.click(within(panel).getByRole("radio", { name: /Admin/ }));
    fireEvent.click(within(panel).getByRole("button", { name: "Change role" }));
    const dialog = await screen.findByRole("dialog", { name: "Confirm it is you" });
    fireEvent.change(within(dialog).getByLabelText("6-digit code"), {
      target: { value: "123456" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(attempts).toBe(2));
    expect(calls.filter((c) => c.path === "/api/v1/admin/supporters/2/role")[1]?.body).toEqual({
      role: "ADMIN",
    });
  });

  it("invites a supporter with chosen organisations and shows no code (ADR-046)", async () => {
    const { api, calls } = supportersApi({
      "POST /api/v1/admin/supporters/invitations": () =>
        jsonResponse({ userId: 4, expiresAt: "2026-11-20T08:00:00Z" }, 201),
    });
    renderAt("/admin/supporters", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Invite agent" }, { timeout: 5000 }));
    const dialog = screen.getByRole("dialog", { name: "Invite agent" });
    fireEvent.change(within(dialog).getByLabelText("Email"), {
      target: { value: "robin@example.test" },
    });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Robin Example" } });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: "All organisations" }));
    fireEvent.click(await within(dialog).findByRole("checkbox", { name: "Acme Dental (ACD)" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Send invitation" }));

    const sent = await screen.findByRole("dialog", { name: "Invitation sent" });
    expect(within(sent).getByText(/emailed a link to robin@example.test/)).toBeInTheDocument();
    expect(within(sent).queryByText(/read this code/i)).toBeNull();
    expect(within(sent).queryByText(/\b\d{6}\b/)).toBeNull();
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({
      email: "robin@example.test",
      name: "Robin Example",
      role: "AGENT",
      allOrganisations: false,
      organisationIds: [7],
    });
    fireEvent.click(within(sent).getByRole("button", { name: "Done" }));
    expect(screen.queryByRole("dialog", { name: "Invitation sent" })).toBeNull();
  });

  it("invites an admin through step-up: asks for a code, then retries", async () => {
    let attempts = 0;
    const { api, calls } = supportersApi({
      "POST /api/v1/admin/supporters/invitations": () => {
        attempts += 1;
        return attempts === 1
          ? jsonResponse({ error: "step_up_required", message: "Confirm it is you." }, 403)
          : jsonResponse({ userId: 5, expiresAt: "2026-11-20T08:00:00Z" }, 201);
      },
      "POST /api/v1/auth/step-up": () => new Response(null, { status: 204 }),
    });
    renderAt("/admin/supporters", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Invite agent" }, { timeout: 5000 }));
    const invite = screen.getByRole("dialog", { name: "Invite agent" });
    fireEvent.change(within(invite).getByLabelText("Email"), {
      target: { value: "sam@example.test" },
    });
    fireEvent.change(within(invite).getByLabelText("Name"), { target: { value: "Sam Example" } });
    fireEvent.click(within(invite).getByRole("radio", { name: /Admin/ }));
    fireEvent.click(within(invite).getByRole("button", { name: "Send invitation" }));
    const stepUp = await screen.findByRole("dialog", { name: "Confirm it is you" });
    fireEvent.change(within(stepUp).getByLabelText("6-digit code"), {
      target: { value: "123456" },
    });
    fireEvent.click(within(stepUp).getByRole("button", { name: "Confirm" }));
    const sent = await screen.findByRole("dialog", { name: "Invitation sent" });
    expect(within(sent).getByText(/emailed a link to sam@example.test/)).toBeInTheDocument();
    expect(attempts).toBe(2);
    expect(
      calls.filter((c) => c.path === "/api/v1/admin/supporters/invitations")[1]?.body,
    ).toMatchObject({ email: "sam@example.test", role: "ADMIN", allOrganisations: true });
  });

  it("refuses an invite without organisations before calling the API", async () => {
    const { api, calls } = supportersApi();
    renderAt("/admin/supporters", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Invite agent" }, { timeout: 5000 }));
    const dialog = screen.getByRole("dialog", { name: "Invite agent" });
    fireEvent.change(within(dialog).getByLabelText("Email"), {
      target: { value: "r@example.test" },
    });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "R" } });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: "All organisations" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Send invitation" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("at least one organisation");
    expect(calls.some((c) => c.method === "POST")).toBe(false);
  });

  it("resends and revokes a pending invitation from the Security tab", async () => {
    const { api, calls } = supportersApi({
      "POST /api/v1/admin/supporters/3/invitation/resend": () =>
        jsonResponse({ userId: 3, expiresAt: "2026-11-20T08:00:00Z" }),
      "POST /api/v1/admin/supporters/3/invitation/revoke": () =>
        new Response(null, { status: 204 }),
    });
    renderAt("/admin/supporters", undefined, { api });
    const panel = await open("Jamie Example");
    fireEvent.click(within(panel).getByRole("tab", { name: "Security" }));
    fireEvent.click(within(panel).getByRole("button", { name: "Resend invitation" }));
    expect(await screen.findByRole("dialog", { name: "Invitation sent" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    fireEvent.click(within(panel).getByRole("button", { name: "Revoke invitation" }));
    await waitFor(() =>
      expect(calls.some((c) => c.path.endsWith("/3/invitation/revoke"))).toBe(true),
    );
  });

  it("deactivates in two steps with the reassignment choice", async () => {
    const { api, calls } = supportersApi({
      "GET /api/v1/admin/supporters/2/deactivation": () =>
        jsonResponse({ supporterId: 2, openTickets: 2, blockedReason: null, reassignTo: [me] }),
      "POST /api/v1/admin/supporters/2/deactivate": () =>
        jsonResponse({ ...minh, status: "DEACTIVATED" }),
    });
    renderAt("/admin/supporters", undefined, { api });
    const panel = await open("Minh Le");
    fireEvent.click(
      within(panel).getByRole("button", { name: "Deactivate and reassign 2 tickets" }),
    );
    const step1 = await screen.findByRole("dialog", { name: "Deactivate Minh Le - step 1 of 2" });
    expect(await within(step1).findByText("They have 2 open tickets.")).toBeInTheDocument();
    fireEvent.change(within(step1).getByLabelText("Reassign their open tickets to"), {
      target: { value: "1" },
    });
    fireEvent.click(within(step1).getByRole("button", { name: "Next" }));
    const step2 = screen.getByRole("dialog", { name: "Deactivate Minh Le - step 2 of 2" });
    expect(step2).toHaveTextContent("moving to: Alex Tran");
    fireEvent.click(within(step2).getByRole("button", { name: "Deactivate" }));
    await waitFor(() =>
      expect(calls.find((c) => c.path.endsWith("/2/deactivate"))?.body).toEqual({ reassignTo: 1 }),
    );
  });

  it("the deactivate wizard blocks Next when the server says so", async () => {
    const { api } = supportersApi({
      "GET /api/v1/admin/supporters/2/deactivation": () =>
        jsonResponse({
          supporterId: 2,
          openTickets: 0,
          blockedReason: "The last active admin cannot be deactivated.",
          reassignTo: [],
        }),
    });
    renderAt("/admin/supporters", undefined, { api });
    const panel = await open("Minh Le");
    fireEvent.click(within(panel).getByRole("button", { name: /Deactivate/ }));
    const dialog = await screen.findByRole("dialog");
    expect(
      await within(dialog).findByText("The last active admin cannot be deactivated."),
    ).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("pauses two-step sign-in for an engineer through step-up, with reason and end date", async () => {
    let attempts = 0;
    const { api, calls } = supportersApi({
      "PUT /api/v1/admin/supporters/2/security/mfa-exemption": () => {
        attempts += 1;
        return attempts === 1
          ? jsonResponse({ error: "step_up_required", message: "Confirm it is you." }, 403)
          : jsonResponse({
              reason: "Phone lost",
              endsAt: "2026-11-26T09:30:00Z",
              grantedAt: "2026-11-19T09:30:00Z",
            });
      },
      "POST /api/v1/auth/step-up": () => new Response(null, { status: 204 }),
    });
    renderAt("/admin/supporters", undefined, { api });
    const panel = await open("Minh Le");
    fireEvent.click(within(panel).getByRole("tab", { name: "Security" }));
    const pause = within(panel).getByRole("button", { name: "Pause two-step sign-in" });
    // Reason required before anything is sent.
    fireEvent.click(pause);
    expect(await within(panel).findByRole("alert")).toHaveTextContent("Give a reason.");
    expect(calls.some((c) => c.method === "PUT")).toBe(false);
    // End date: 7 days by default, at most 30.
    const end = within(panel).getByLabelText(/End date/) as HTMLInputElement;
    expect(end.value).not.toBe("");
    expect(end.max).not.toBe("");
    expect(within(panel).getByLabelText(/Reason/)).toHaveAttribute("maxlength", "200");
    fireEvent.change(within(panel).getByLabelText(/Reason/), {
      target: { value: "Phone lost" },
    });
    fireEvent.click(pause);
    const dialog = await screen.findByRole("dialog", { name: "Confirm it is you" });
    fireEvent.change(await within(dialog).findByLabelText("6-digit code"), {
      target: { value: "123456" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(attempts).toBe(2));
    const sent = calls.filter((c) => c.method === "PUT")[1]?.body as {
      reason: string;
      endsAt: string;
    };
    expect(sent.reason).toBe("Phone lost");
    const days = (Date.parse(sent.endsAt) - Date.now()) / (24 * 60 * 60 * 1000);
    expect(days).toBeGreaterThan(6.9);
    expect(days).toBeLessThanOrEqual(7.01);
  });

  it("shows 'Exempt until' in the list and the panel, and ends the exemption", async () => {
    const exempt = {
      ...minh,
      mfaExemptUntil: "2026-11-26T09:30:00Z",
      mfaExemptReason: "Phone lost",
    };
    const { api, calls } = supportersApi({
      "GET /api/v1/admin/supporters": () => jsonResponse([me, exempt, jamie]),
      "DELETE /api/v1/admin/supporters/2/security/mfa-exemption": () =>
        new Response(null, { status: 204 }),
    });
    renderAt("/admin/supporters", undefined, { api });
    // The list shows a compact badge; the full end date and time is its accessible text and tooltip.
    const badge = await screen.findByText(/^Paused to 26 Nov/, {}, { timeout: 5000 });
    expect(badge).toBeVisible();
    const full = screen.getByText(/^Two-step sign-in paused until 26 Nov 2026, \d\d:\d\d$/);
    expect(full).toHaveClass("visually-hidden");
    expect(full.closest("[title]")).toHaveAttribute("title", full.textContent);
    expect(screen.queryByText(/^Exempt until/)).toBeNull();
    const panel = await open("Minh Le");
    fireEvent.click(within(panel).getByRole("tab", { name: "Security" }));
    expect(within(panel).getByText(/^Two-step sign-in: Exempt until 26 Nov 2026/)).toBeVisible();
    expect(within(panel).getByText("Reason: Phone lost")).toBeVisible();
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(within(panel).getByRole("button", { name: "End exemption now" }));
    await waitFor(() =>
      expect(calls.some((c) => c.method === "DELETE" && c.path.endsWith("/mfa-exemption"))).toBe(
        true,
      ),
    );
  });

  it("never offers the pause for an admin", async () => {
    const other = { ...me, id: 4, name: "Robin Admin", email: "robin@example.test", self: false };
    const { api } = supportersApi({
      "GET /api/v1/admin/supporters": () => jsonResponse([me, other, minh]),
    });
    renderAt("/admin/supporters", undefined, { api });
    const panel = await open("Robin Admin");
    fireEvent.click(within(panel).getByRole("tab", { name: "Security" }));
    expect(within(panel).queryByRole("button", { name: "Pause two-step sign-in" })).toBeNull();
    expect(within(panel).getByText("Admins always use two-step sign-in.")).toBeVisible();
  });

  it("caps the end time a minute short of 30 days and refuses a past day", () => {
    const now = new Date(2026, 10, 19, 10, 0, 0);
    const in30 = exemptionEndsAt("2026-12-19", now);
    expect(in30).not.toBeNull();
    expect(Date.parse(in30 ?? "") - now.getTime()).toBe(30 * 24 * 60 * 60 * 1000 - 60 * 1000);
    expect(exemptionEndsAt("2026-11-26", now)).toBe(new Date(2026, 10, 26, 10, 0, 0).toISOString());
    expect(exemptionEndsAt("2026-11-19", now)).toBeNull();
    expect(exemptionEndsAt("", now)).toBeNull();
  });

  it("formats the last sign-in compactly: year only when not this year", () => {
    const now = new Date(2026, 10, 19, 10, 0, 0);
    expect(formatShortDateTime(new Date(2026, 10, 19, 12, 51).toISOString(), now)).toBe(
      "19 Nov, 12:51",
    );
    expect(formatShortDateTime(new Date(2025, 2, 4, 9, 5).toISOString(), now)).toBe(
      "4 Mar 2025, 09:05",
    );
    expect(formatShortDate(new Date(2026, 10, 26, 12, 52).toISOString(), now)).toBe("26 Nov");
    expect(formatShortDateTime(null, now)).toBe("");
    expect(formatShortDateTime("not a date", now)).toBe("");
  });

  it("keeps the table tidy: one-line header and date, the email under the name, phone cards", async () => {
    const { api } = supportersApi();
    renderAt("/admin/supporters", undefined, { api });
    const name = await screen.findByRole("button", { name: "Minh Le" }, { timeout: 5000 });
    // The email is a sibling below the name button, not inside it.
    expect(name.parentElement).toContainElement(screen.getByText("minh@example.test"));
    expect(name).not.toHaveTextContent("minh@example.test");
    const header = screen.getByRole("columnheader", { name: "Last sign-in" });
    expect(within(header).getByText("Last sign-in").className).toMatch(/noWrap/);
    // Each date is one compact line, with the full date as a tooltip.
    const times = [...document.querySelectorAll("time[datetime='2026-11-18T08:00:00Z']")];
    expect(times).toHaveLength(2);
    const [first] = times;
    expect(first?.textContent).toMatch(/^18 Nov( 2026)?, \d\d:\d\d$/);
    expect(first?.getAttribute("title")).toMatch(/^18 Nov 2026, \d\d:\d\d$/);
    expect(screen.getByRole("region", { name: "Agents" }).className).toMatch(/stack/);
    expect(await axeViolations()).toEqual([]);
  });

  it("has no axe violations with the panel and the wizard open", async () => {
    const { api } = supportersApi({
      "GET /api/v1/admin/supporters/2/deactivation": () =>
        jsonResponse({ supporterId: 2, openTickets: 2, blockedReason: null, reassignTo: [me] }),
    });
    renderAt("/admin/supporters", undefined, { api });
    const panel = await open("Minh Le");
    for (const tab of ["Overview", "Access", "Security"]) {
      fireEvent.click(within(panel).getByRole("tab", { name: tab }));
      expect(await axeViolations()).toEqual([]);
    }
    fireEvent.click(within(panel).getByRole("button", { name: /Deactivate/ }));
    await screen.findByText("They have 2 open tickets.");
    expect(await axeViolations()).toEqual([]);
  }, 20000);
});
