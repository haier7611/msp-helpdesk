import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ADMIN_SECTIONS } from "../../navigation";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";

// Synthetic data only.
type Call = { path: string; method: string; body: unknown };
type Handler = (body: unknown, path: string) => Response;

function recordingApi(handlers: Record<string, Handler>) {
  const calls: Call[] = [];
  const api = (path: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
    calls.push({ path, method, body });
    const exact = handlers[`${method} ${path}`];
    return exact ? exact(body, path) : undefined;
  };
  return { api, calls };
}

const BASE = "/api/v1/admin/sla-automation";

const ORGS = [
  {
    id: 7,
    name: "Acme Dental",
    prefix: "ACD",
    retiredPrefixes: [],
    verifiedDomains: ["acme-dental.example"],
    timezone: "Asia/Ho_Chi_Minh",
    entraTenantId: null,
  },
];

const TYPES = [
  {
    id: 1,
    name: "Account and access",
    description: "",
    defaultPriority: "P3",
    portalVisible: true,
    archived: false,
    sortOrder: 10,
    defaultAssigneeUserId: null,
    system: false,
    ticketCount: 4,
  },
];

const AGENTS = [
  {
    id: 31,
    email: "alex@msp.example",
    name: "Alex Tran",
    role: "AGENT",
    status: "ACTIVE",
    allOrganisations: true,
    organisationIds: [],
    mfaEnrolled: true,
    lastSignInAt: null,
    createdAt: "2026-11-01T00:00:00Z",
  },
];

const RULE = {
  id: 5,
  name: "Escalate breaches",
  enabled: true,
  position: 1,
  trigger: "FIRST_RESPONSE_BREACHED",
  conditions: {
    includeTriage: true,
    organisationIds: [7],
    priorities: ["P1", "P2"],
    requestTypeIds: [],
  },
  actions: [
    { type: "NOTIFY_AGENTS", agentIds: [31], agentId: null, text: null },
    { type: "RAISE_PRIORITY", agentIds: [], agentId: null, text: null },
  ],
  createdAt: "2026-11-20T00:00:00Z",
  updatedAt: "2026-11-20T00:00:00Z",
};
const SECOND = {
  ...RULE,
  id: 6,
  name: "Note when met",
  enabled: false,
  position: 2,
  trigger: "RESOLUTION_MET",
  conditions: { includeTriage: false, organisationIds: [], priorities: [], requestTypeIds: [] },
  actions: [{ type: "ADD_NOTE", agentIds: [], agentId: null, text: "Met in time" }],
};

function baseHandlers(rules: unknown[]): Record<string, Handler> {
  return {
    [`GET ${BASE}/rules`]: () => jsonResponse(rules),
    "GET /api/v1/admin/organisations": () => jsonResponse(ORGS),
    "GET /api/v1/admin/request-types": () => jsonResponse(TYPES),
    "GET /api/v1/admin/supporters?status=ACTIVE": () => jsonResponse(AGENTS),
  };
}

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.unstubAllGlobals();
});

describe("Admin - SLA automation", () => {
  it("is in the admin navigation", () => {
    expect(ADMIN_SECTIONS.map((s) => s.to)).toContain("/admin/sla-automation");
  });

  it("lists rules with their trigger, conditions and actions, and has no axe violations", async () => {
    const { api } = recordingApi(baseHandlers([RULE, SECOND]));
    renderAt("/admin/sla-automation", undefined, { api });
    expect(
      await screen.findByRole("heading", { level: 1, name: "SLA automation" }, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(await screen.findByText("Escalate breaches")).toBeInTheDocument();
    expect(screen.getByText("First response breached")).toBeInTheDocument();
    expect(
      await screen.findByText("Email triage, Acme Dental; P1, P2; any request type"),
    ).toBeInTheDocument();
    expect(await screen.findByText("Notify Alex Tran; Raise the priority")).toBeInTheDocument();
    expect(screen.getByLabelText("Escalate breaches enabled")).toBeChecked();
    expect(screen.getByLabelText("Note when met enabled")).not.toBeChecked();
    expect(screen.getByRole("button", { name: "Move Escalate breaches up" })).toBeDisabled();
    expect(await axeViolations()).toEqual([]);
  }, 20000);

  it("turns a rule off, reorders and deletes only after the confirmation in the row", async () => {
    const { api, calls } = recordingApi({
      ...baseHandlers([RULE, SECOND]),
      [`PUT ${BASE}/rules/5/enabled`]: () => jsonResponse({ ...RULE, enabled: false }),
      [`PUT ${BASE}/order`]: () => jsonResponse([SECOND, RULE]),
      [`DELETE ${BASE}/rules/6`]: () => new Response(null, { status: 204 }),
    });
    renderAt("/admin/sla-automation", undefined, { api });
    fireEvent.click(
      await screen.findByLabelText("Escalate breaches enabled", {}, { timeout: 5000 }),
    );
    await waitFor(() =>
      expect(calls.find((c) => c.path === `${BASE}/rules/5/enabled`)?.body).toEqual({
        enabled: false,
      }),
    );
    await waitFor(() =>
      expect(screen.getByLabelText("Escalate breaches enabled")).not.toBeChecked(),
    );

    fireEvent.click(screen.getByRole("button", { name: "Move Note when met up" }));
    await waitFor(() =>
      expect(calls.find((c) => c.path === `${BASE}/order`)?.body).toEqual({ ids: [6, 5] }),
    );

    fireEvent.click(await screen.findByRole("button", { name: "Delete Note when met" }));
    expect(calls.some((c) => c.method === "DELETE")).toBe(false);
    const confirm = screen.getByRole("group", { name: "Confirm deleting Note when met" });
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(within(confirm).getByRole("button", { name: "Yes, delete" }));
    await waitFor(() => expect(screen.queryByText("Note when met")).not.toBeInTheDocument());
    expect(calls.filter((c) => c.method === "DELETE").map((c) => c.path)).toEqual([
      `${BASE}/rules/6`,
    ]);
  }, 20000);

  it("adds a rule with conditions and actions, and pops up the server's refusal", async () => {
    let posts = 0;
    const { api, calls } = recordingApi({
      ...baseHandlers([]),
      [`POST ${BASE}/rules`]: (body) =>
        ++posts === 1
          ? jsonResponse(
              { error: "invalid_request", message: "One of the chosen agents is deactivated." },
              400,
            )
          : jsonResponse({ ...RULE, id: 9, ...(body as object) }, 201),
    });
    renderAt("/admin/sla-automation", undefined, { api });
    expect(await screen.findByText("No rules yet.", {}, { timeout: 5000 })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Add rule" }));
    const dialog = await screen.findByRole("dialog", { name: "Add rule" });

    // Client-side check first: a name is needed.
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Enter a name.");

    fireEvent.change(within(dialog).getByLabelText(/^Name/), { target: { value: "Assign P1s" } });
    fireEvent.change(within(dialog).getByLabelText(/^When/), {
      target: { value: "RESOLUTION_AT_RISK" },
    });
    fireEvent.click(await within(dialog).findByLabelText("Email triage (no organisation yet)"));
    fireEvent.click(await within(dialog).findByLabelText("Acme Dental"));
    fireEvent.click(within(dialog).getByLabelText("P1 Critical"));
    fireEvent.click(await within(dialog).findByLabelText("Account and access"));
    fireEvent.change(within(dialog).getByLabelText(/^Action 1/), { target: { value: "ASSIGN" } });
    fireEvent.change(await within(dialog).findByLabelText(/^Agent for action 1/), {
      target: { value: "31" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Add action" }));
    fireEvent.change(within(dialog).getByLabelText(/^Action 2/), { target: { value: "ADD_NOTE" } });
    fireEvent.change(within(dialog).getByLabelText(/^Note text for action 2/), {
      target: { value: "Assigned by automation" },
    });
    expect(await axeViolations()).toEqual([]);

    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("deactivated");
    expect(await screen.findByText(/The rule was not saved/)).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({
      name: "Assign P1s",
      enabled: true,
      trigger: "RESOLUTION_AT_RISK",
      conditions: {
        includeTriage: true,
        organisationIds: [7],
        priorities: ["P1"],
        requestTypeIds: [1],
      },
      actions: [
        { type: "ASSIGN", agentIds: [], agentId: 31, text: null },
        { type: "ADD_NOTE", agentIds: [], agentId: null, text: "Assigned by automation" },
      ],
    });

    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "Add rule" })).not.toBeInTheDocument(),
    );
  }, 20000);

  it("edits an existing rule with its values filled in", async () => {
    const { api, calls } = recordingApi({
      ...baseHandlers([SECOND]),
      [`PUT ${BASE}/rules/6`]: (body) => jsonResponse({ ...SECOND, ...(body as object) }),
    });
    renderAt("/admin/sla-automation", undefined, { api });
    fireEvent.click(
      await screen.findByRole("button", { name: "Edit Note when met" }, { timeout: 5000 }),
    );
    const dialog = await screen.findByRole("dialog", { name: "Edit rule" });
    expect(within(dialog).getByLabelText(/^Name/)).toHaveValue("Note when met");
    expect(within(dialog).getByLabelText(/^Note text for action 1/)).toHaveValue("Met in time");
    expect(within(dialog).getByLabelText("Rule is on")).not.toBeChecked();
    fireEvent.change(within(dialog).getByLabelText(/^Note text for action 1/), {
      target: { value: "Resolved within the target" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(calls.find((c) => c.method === "PUT")?.body).toMatchObject({
        name: "Note when met",
        enabled: false,
        actions: [
          { type: "ADD_NOTE", agentIds: [], agentId: null, text: "Resolved within the target" },
        ],
      }),
    );
  }, 20000);

  it("shows a load failure instead of an empty list", async () => {
    const { api } = recordingApi({
      ...baseHandlers([]),
      [`GET ${BASE}/rules`]: () =>
        jsonResponse({ error: "forbidden", message: "You do not have access to this." }, 403),
    });
    renderAt("/admin/sla-automation", undefined, { api });
    expect(await screen.findByRole("alert", {}, { timeout: 5000 })).toHaveTextContent(
      /access|permission/i,
    );
  }, 20000);
});
