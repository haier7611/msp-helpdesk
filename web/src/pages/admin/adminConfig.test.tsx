import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ADMIN_SECTIONS } from "../../navigation";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";

// Synthetic data only.
type Call = { path: string; method: string; body: unknown };
type Handler = (body: unknown, path: string) => Response;

/** Answers "METHOD /path" exactly, or "METHOD /path*" for any path starting with it. */
function recordingApi(handlers: Record<string, Handler>) {
  const calls: Call[] = [];
  const api = (path: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
    calls.push({ path, method, body });
    const exact = handlers[`${method} ${path}`];
    if (exact) return exact(body, path);
    const prefix = Object.keys(handlers).find(
      (k) => k.endsWith("*") && `${method} ${path}`.startsWith(k.slice(0, -1)),
    );
    return prefix ? handlers[prefix]?.(body, path) : undefined;
  };
  return { api, calls };
}

const stepUpOk = () => new Response(null, { status: 204 });
const needStepUp = () =>
  jsonResponse({ error: "step_up_required", message: "Please confirm it is you." }, 403);

async function confirmStepUp() {
  const dialog = await screen.findByRole("dialog", { name: "Confirm it is you" });
  fireEvent.change(within(dialog).getByLabelText(/6-digit code/i), { target: { value: "123456" } });
  fireEvent.click(within(dialog).getByRole("button", { name: /confirm/i }));
}

const LOGO = "data:image/png;base64,iVBORw0KGgo=";

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
    description: "Passwords, locked accounts",
    defaultPriority: "P3",
    portalVisible: true,
    archived: false,
    sortOrder: 10,
    defaultAssigneeUserId: null,
    system: false,
    ticketCount: 4,
  },
  {
    id: 2,
    name: "Alert",
    description: "",
    defaultPriority: "P2",
    portalVisible: false,
    archived: false,
    sortOrder: 20,
    defaultAssigneeUserId: null,
    system: true,
    ticketCount: 0,
  },
];

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("Admin navigation", () => {
  it("keeps the API keys entry hidden until WP-08 merges", () => {
    expect(ADMIN_SECTIONS.map((s) => s.label)).not.toContain("API keys");
    expect(ADMIN_SECTIONS.map((s) => s.label)).toContain("Audit log");
  });
});

describe("Admin - Request types", () => {
  it("lists types, adds one, and shows a duplicate-name refusal", async () => {
    let posts = 0;
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/request-types": () => jsonResponse(TYPES),
      "GET /api/v1/admin/supporters?status=ACTIVE": () => jsonResponse([]),
      "POST /api/v1/admin/request-types": (body) =>
        ++posts === 1
          ? jsonResponse(
              {
                error: "duplicate_name",
                message: 'A request type called "Printers" already exists.',
              },
              409,
            )
          : jsonResponse({ ...TYPES[0], id: 9, name: "Printers", ...(body as object) }, 201),
    });
    renderAt("/admin/request-types", undefined, { api });
    expect(
      await screen.findByRole("heading", { level: 1, name: "Request types" }, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(await screen.findByText("Passwords, locked accounts")).toBeInTheDocument();
    expect(screen.getByText("Built in")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);

    fireEvent.click(screen.getByRole("button", { name: "Add request type" }));
    const dialog = await screen.findByRole("dialog", { name: "Add request type" });
    fireEvent.change(within(dialog).getByLabelText(/^Name/), { target: { value: "Printers" } });
    fireEvent.change(within(dialog).getByLabelText(/^Starting priority/), {
      target: { value: "P4" },
    });
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("already exists");
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({
      name: "Printers",
      description: "",
      defaultPriority: "P4",
      portalVisible: true,
      archived: false,
    });
  }, 20000);

  it("does not let the Alert type be renamed or shown in the portal", async () => {
    const { api } = recordingApi({
      "GET /api/v1/admin/request-types": () => jsonResponse(TYPES),
      "GET /api/v1/admin/supporters?status=ACTIVE": () => jsonResponse([]),
    });
    renderAt("/admin/request-types", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Edit Alert" }, { timeout: 5000 }));
    const dialog = await screen.findByRole("dialog", { name: "Edit request type" });
    expect(within(dialog).getByLabelText(/^Name/)).toBeDisabled();
    expect(within(dialog).getByLabelText("Clients can choose it in the portal")).toBeDisabled();
  }, 20000);
});

const TARGETS = {
  P1: {
    firstResponse: { amount: 30, unit: "MINUTES" },
    resolution: { amount: 4, unit: "HOURS" },
    clock: "TWENTY_FOUR_SEVEN",
  },
  P2: {
    firstResponse: { amount: 1, unit: "HOURS" },
    resolution: { amount: 8, unit: "HOURS" },
    clock: "BUSINESS_HOURS",
  },
  P3: {
    firstResponse: { amount: 4, unit: "HOURS" },
    resolution: { amount: 3, unit: "DAYS" },
    clock: "BUSINESS_HOURS",
  },
  P4: {
    firstResponse: { amount: 1, unit: "DAYS" },
    resolution: { amount: 10, unit: "DAYS" },
    clock: "BUSINESS_HOURS",
  },
};

const SLA = {
  organisationId: 7,
  timezone: "Asia/Ho_Chi_Minh",
  settings: {
    pendingReminderEnabled: true,
    pendingReminderBusinessDays: 3,
    autoResolveEnabled: true,
    autoResolveBusinessDays: 7,
    autoCloseEnabled: true,
    autoCloseCalendarDays: 5,
    apiAlertClientEmails: false,
    defaultAssigneeUserId: null,
  },
  businessHours: [1, 2, 3, 4, 5].map((d) => ({
    dayOfWeek: d,
    opens: "08:30:00",
    closes: "17:30:00",
  })),
  defaultHours: false,
  holidays: [{ date: "2026-12-25", name: "Christmas Day" }],
  policies: [{ requestTypeId: null, pendingOnVendorPauses: false, targets: TARGETS }],
};

describe("Admin - SLA policies", () => {
  it("shows the organisation's targets and the server's refusal of bad numbers", async () => {
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/organisations": () => jsonResponse(ORGS),
      "GET /api/v1/admin/request-types": () => jsonResponse(TYPES),
      "GET /api/v1/admin/supporters?status=ACTIVE": () => jsonResponse([]),
      "GET /api/v1/admin/organisations/7/sla": () => jsonResponse(SLA),
      "PUT /api/v1/admin/organisations/7/sla/policies/default": () =>
        jsonResponse(
          {
            error: "invalid_request",
            message: "P2: the resolution target cannot be shorter than the first response.",
          },
          400,
        ),
    });
    renderAt("/admin/sla", undefined, { api });
    const fr = await screen.findByLabelText(/^P2 first response$/, undefined, { timeout: 5000 });
    expect(fr).toHaveValue(1);
    // P1's clock is fixed: shown as text, not a disabled select (usability finding).
    expect(screen.queryByRole("combobox", { name: /^P1 clock/ })).toBeNull();
    expect(screen.getByRole("note", { name: "Clock 24/7 (always)" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "P2 clock" })).toHaveValue("BUSINESS_HOURS");
    // One row per target; the visible label does not repeat the priority (UX-D).
    expect(screen.getAllByText("First response")).toHaveLength(4);
    expect(screen.queryByText("P1 first response")).toBeNull();
    expect(screen.getByRole("group", { name: "P1 Critical" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "P3 resolution unit" })).toHaveValue("DAYS");
    fireEvent.change(screen.getByRole("combobox", { name: "P3 resolution unit" }), {
      target: { value: "HOURS" },
    });
    expect(screen.getByText("Christmas Day")).toBeInTheDocument();
    // Holiday dates use the app's date style, not 2026-12-25.
    expect(screen.getByText("25 Dec 2026")).toBeInTheDocument();
    expect(screen.getByLabelText(/^Monday opens/)).toHaveValue("08:30");
    expect(await axeViolations()).toEqual([]);

    fireEvent.change(fr, { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "Save default targets" }));
    expect(
      (await screen.findAllByText(/cannot be shorter than the first response/))[0],
    ).toBeInTheDocument();
    const put = calls.find((c) => c.method === "PUT");
    const sent = (put?.body as { targets: typeof TARGETS }).targets;
    expect(sent.P2.firstResponse.amount).toBe(20);
    expect(sent.P3.resolution).toEqual({ amount: 3, unit: "HOURS" });
    expect(sent.P1.clock).toBe("TWENTY_FOUR_SEVEN");
  }, 20000);
});

const MAILBOX = {
  id: 3,
  name: "Support",
  address: "support@helpdesk.example",
  enabled: true,
  imapHost: "imap.helpdesk.example",
  imapPort: 993,
  imapSecurity: "TLS",
  imapFolder: "INBOX",
  authType: "PASSWORD",
  username: "support@helpdesk.example",
  secretSet: true,
  oauthTokenUrl: null,
  oauthClientId: null,
  oauthScope: null,
  trustedAuthservIds: [],
  lastPollAt: "2026-11-18T08:00:00Z",
  lastSuccessAt: "2026-11-18T08:00:00Z",
  lastError: null,
  consecutiveFailures: 0,
  nextPollAt: null,
};

describe("Admin - Email - Mailboxes", () => {
  it("never shows the secret, saves through step-up and reports test results", async () => {
    let puts = 0;
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/email/mailboxes": () => jsonResponse([MAILBOX]),
      "PUT /api/v1/admin/email/mailboxes/3": () =>
        ++puts === 1 ? needStepUp() : jsonResponse(MAILBOX),
      "POST /api/v1/auth/step-up": stepUpOk,
      "POST /api/v1/admin/email/mailboxes/3/test": () =>
        jsonResponse({
          loginOk: false,
          error: "LOGIN_FAILED",
          probeId: null,
          message: "Sign-in failed: check the user name and password or OAuth2 settings.",
        }),
    });
    renderAt("/admin/email", undefined, { api });
    expect(
      await screen.findByText("support@helpdesk.example", undefined, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);

    fireEvent.click(
      screen.getByRole("button", { name: "Test connection for support@helpdesk.example" }),
    );
    expect(await screen.findByText(/Sign-in failed: check the user name/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Edit support@helpdesk.example" }));
    const dialog = await screen.findByRole("dialog", { name: "Edit mailbox" });
    expect(
      within(dialog).getByLabelText(/^Password/, { selector: "input[type=password]" }),
    ).toHaveValue("");
    expect(within(dialog).getByText(/never shown again/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save mailbox" }));
    await confirmStepUp();
    await waitFor(() => expect(puts).toBe(2));
    const body = calls.find((c) => c.method === "PUT")?.body as Record<string, unknown>;
    expect(body.secret).toBeNull();
    expect(body.imapPort).toBe(993);
  }, 20000);
});

describe("Admin - Email - Mailboxes test result", () => {
  it("shows the test result as a status alert inside the Mailboxes card, with its follow-up", async () => {
    const { api } = recordingApi({
      "GET /api/v1/admin/email/mailboxes": () => jsonResponse([MAILBOX]),
      "POST /api/v1/admin/email/mailboxes/3/test": () =>
        jsonResponse({
          loginOk: true,
          error: null,
          probeId: 11,
          message:
            "Signed in. A test email was sent to the internal address; check back in a minute.",
        }),
    });
    renderAt("/admin/email", undefined, { api });
    fireEvent.click(
      await screen.findByRole(
        "button",
        { name: "Test connection for support@helpdesk.example" },
        { timeout: 5000 },
      ),
    );
    const message = await screen.findByText(/Signed in\. A test email was sent/, undefined, {
      timeout: 5000,
    });
    const alert = message.closest("[role=status]") as HTMLElement;
    expect(alert).not.toBeNull();
    expect(alert).toHaveTextContent(/^support@helpdesk\.example:/);
    // Inside the card, above the table - not floating beside it.
    const card = screen.getByRole("heading", { name: "Mailboxes" }).closest("section");
    expect(card).toContainElement(alert);
    expect(alert.className).toMatch(/notice/);
    expect(within(alert).getByRole("button", { name: "Check the test email" })).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  }, 20000);
});

describe("Admin - Email - Supporter email replies", () => {
  it("explains the risk and changes the setting only after step-up", async () => {
    let puts = 0;
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/email/settings": () => jsonResponse({ supporterReplies: "INTERNAL_ONLY" }),
      "PUT /api/v1/admin/email/settings": (b) => (++puts === 1 ? needStepUp() : jsonResponse(b)),
      "POST /api/v1/auth/step-up": stepUpOk,
    });
    renderAt("/admin/email?tab=replies", undefined, { api });
    expect(
      await screen.findByText(/email replies skip two-step sign-in/, undefined, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByLabelText(/^Notes and public replies/));
    fireEvent.click(screen.getByRole("button", { name: "Save setting" }));
    await confirmStepUp();
    await waitFor(() => expect(puts).toBe(2));
    expect(calls.filter((c) => c.method === "PUT").map((c) => c.body)).toEqual([
      { supporterReplies: "NOTES_AND_PUBLIC" },
      { supporterReplies: "NOTES_AND_PUBLIC" },
    ]);
  }, 20000);
});

const TEMPLATE = {
  companyName: "Example MSP",
  address: "1 Example Street, Example City",
  brandColour: "#2B5797",
  emailMode: "SUPPORTER",
  supportEmail: "support@helpdesk.example",
  logoAlt: "Example MSP logo",
  confidentialityLine: "",
  logoUploaded: false,
  logoDataUri: LOGO,
  logoWidth: 150,
  logoHeight: 40,
};

const RENDERED = {
  fullHtml:
    '<table role="presentation"><tr><td style="color:#2B5797;">Alex Tran</td></tr><tr><td>A: 2 Example Road</td></tr></table>',
  fullText: "Alex Tran\nA: 2 Example Road\n--\nExample MSP",
};

describe("Admin - Email - Company signature", () => {
  it("renders the server preview of unsaved values and saves through step-up", async () => {
    let puts = 0;
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/signature-template": () => jsonResponse(TEMPLATE),
      "POST /api/v1/admin/signature-template/preview": () => jsonResponse(RENDERED),
      "PUT /api/v1/admin/signature-template": (b) =>
        ++puts === 1 ? needStepUp() : jsonResponse({ ...TEMPLATE, ...(b as object) }),
      "POST /api/v1/auth/step-up": stepUpOk,
    });
    renderAt("/admin/email?tab=signature", undefined, { api });
    const address = await screen.findByLabelText(/^Address/, undefined, { timeout: 5000 });
    fireEvent.change(address, { target: { value: "2 Example Road" } });
    expect(
      await screen.findByRole("group", { name: "Signature" }, { timeout: 3000 }),
    ).toHaveTextContent("A: 2 Example Road");
    await waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.path === "/api/v1/admin/signature-template/preview" &&
            (c.body as { address: string }).address === "2 Example Road",
        ),
      ).toBe(true),
    );
    expect(screen.getByAltText("Placeholder logo")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);

    fireEvent.click(screen.getByRole("button", { name: "Save template" }));
    await confirmStepUp();
    await waitFor(() => expect(puts).toBe(2));
    expect((calls.find((c) => c.method === "PUT")?.body as { address: string }).address).toBe(
      "2 Example Road",
    );
  }, 20000);

  it("shows the server's message when a logo is refused", async () => {
    const { api } = recordingApi({
      "GET /api/v1/admin/signature-template": () => jsonResponse(TEMPLATE),
      "POST /api/v1/admin/signature-template/preview": () => jsonResponse(RENDERED),
      "PUT /api/v1/admin/signature-template/logo": () =>
        jsonResponse(
          {
            error: "invalid_logo",
            message: "This logo is too detailed to stay under 30 KB in every email.",
          },
          400,
        ),
    });
    renderAt("/admin/email?tab=signature", undefined, { api });
    const input = await screen.findByLabelText("Logo file", undefined, { timeout: 5000 });
    const file = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], "logo.png", {
      type: "image/png",
    });
    fireEvent.change(input, { target: { files: [file] } });
    expect((await screen.findAllByText(/too detailed to stay under 30 KB/))[0]).toBeInTheDocument();
  }, 20000);
});

describe("Admin - Email - Sender rules and public domains", () => {
  it("adds a routing rule (there is no ignore option)", async () => {
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/email/sender-rules": () => jsonResponse([]),
      "GET /api/v1/admin/organisations": () => jsonResponse(ORGS),
      "GET /api/v1/admin/request-types": () => jsonResponse(TYPES),
      "POST /api/v1/admin/email/sender-rules": (b) =>
        jsonResponse({ id: 1, createdAt: "2026-11-18T08:00:00Z", ...(b as object) }, 201),
    });
    renderAt("/admin/email?tab=sender-rules", undefined, { api });
    expect(
      await screen.findByText(/no way to ignore mail/, undefined, { timeout: 5000 }),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/^Sender address/), {
      target: { value: "alerts@monitor.example" },
    });
    await screen.findByRole("option", { name: "Acme Dental" });
    fireEvent.change(screen.getByLabelText(/^Organisation/), { target: { value: "7" } });
    fireEvent.change(screen.getByLabelText(/^Request type/), { target: { value: "2" } });
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: "Add rule" }));
    expect(await screen.findByText("alerts@monitor.example")).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({
      kind: "ADDRESS",
      value: "alerts@monitor.example",
      organisationId: 7,
      requestTypeId: 2,
    });
  }, 20000);

  it("shows the public domain list read-only", async () => {
    const { api } = recordingApi({
      "GET /api/v1/admin/email/public-domains": () =>
        jsonResponse(["gmail.com", "outlook.com", "yahoo.com"]),
    });
    renderAt("/admin/email?tab=public-domains", undefined, { api });
    expect(await screen.findByText("gmail.com", undefined, { timeout: 5000 })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/^Filter/), { target: { value: "out" } });
    expect(screen.queryByText("gmail.com")).toBeNull();
    expect(screen.getByText("1 of 3 domains")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  }, 20000);
});

describe("Admin - Block list", () => {
  it("warns about a client's domain, then blocks after confirm and step-up; restores mail", async () => {
    let posts = 0;
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/email/block-rules": () => jsonResponse([]),
      "GET /api/v1/admin/email/blocked-mail": () =>
        jsonResponse([
          {
            id: 5,
            fromAddress: "promo@spam.example",
            subject: "Win a prize",
            receivedAt: "2026-11-17T08:00:00Z",
            ruleKind: "DOMAIN",
            rule: "spam.example",
            restoredAt: null,
            ticketId: null,
          },
        ]),
      "POST /api/v1/admin/email/block-rules": () => {
        posts++;
        if (posts === 1)
          return jsonResponse(
            {
              error: "confirmation_required",
              message:
                "acme-dental.example is a verified domain of Acme Dental. Confirm to block it anyway.",
            },
            409,
          );
        if (posts === 2) return needStepUp();
        return jsonResponse(
          {
            id: 1,
            kind: "DOMAIN",
            value: "acme-dental.example",
            createdAt: "2026-11-18T08:00:00Z",
          },
          201,
        );
      },
      "POST /api/v1/auth/step-up": stepUpOk,
      "POST /api/v1/admin/email/blocked-mail/5/restore": () => jsonResponse({ ticketId: 42 }),
    });
    renderAt("/admin/block-list", undefined, { api });
    expect(
      await screen.findByText("Win a prize", undefined, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);

    fireEvent.change(screen.getByLabelText(/^Block$/), { target: { value: "DOMAIN" } });
    fireEvent.change(screen.getByLabelText(/^Domain/), {
      target: { value: "acme-dental.example" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Block" }));
    expect(await screen.findByText(/verified domain of Acme Dental/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Block anyway" }));
    await confirmStepUp();
    await waitFor(() => expect(posts).toBe(3));
    expect(
      calls.filter((c) => c.method === "POST" && c.path.endsWith("block-rules")).map((c) => c.body),
    ).toEqual([
      { kind: "DOMAIN", value: "acme-dental.example", confirm: false },
      { kind: "DOMAIN", value: "acme-dental.example", confirm: true },
      { kind: "DOMAIN", value: "acme-dental.example", confirm: true },
    ]);

    fireEvent.click(
      screen.getByRole("button", { name: "Restore the email from promo@spam.example as a ticket" }),
    );
    expect(await screen.findByText("Restored as #42")).toBeInTheDocument();
  }, 20000);
});

describe("Admin - Branding", () => {
  it("saves the support name through step-up", async () => {
    let puts = 0;
    const branding = {
      supportName: "Ticket System",
      supportNameCustom: false,
      defaultSupportName: "Ticket System",
      brandColour: "#2B5797",
      logoUploaded: false,
      logoDataUri: LOGO,
      logoWidth: 150,
      logoHeight: 40,
    };
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/branding": () => jsonResponse(branding),
      "PUT /api/v1/admin/branding": (b) =>
        ++puts === 1
          ? needStepUp()
          : jsonResponse({
              ...branding,
              supportName: "Example MSP Support",
              supportNameCustom: true,
              ...(b as object),
            }),
      "POST /api/v1/auth/step-up": stepUpOk,
    });
    renderAt("/admin/branding", undefined, { api });
    const name = await screen.findByLabelText(/^Support name/, undefined, { timeout: 5000 });
    expect(await axeViolations()).toEqual([]);
    fireEvent.change(name, { target: { value: "Example MSP Support" } });
    fireEvent.click(screen.getByRole("button", { name: "Save branding" }));
    await confirmStepUp();
    await waitFor(() => expect(puts).toBe(2));
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({
      supportName: "Example MSP Support",
      brandColour: "#2B5797",
    });
  }, 20000);
});

describe("Admin - Saved replies", () => {
  it("lists shared replies as text and adds one as paragraphs", async () => {
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/saved-replies": () =>
        jsonResponse([
          {
            id: 1,
            title: "Password reset",
            bodyHtml: "<p>Hi {client_first_name}</p>",
            shared: true,
          },
        ]),
      "POST /api/v1/admin/saved-replies": (b) =>
        jsonResponse({ id: 2, shared: true, ...(b as object) }, 201),
    });
    renderAt("/admin/saved-replies", undefined, { api });
    expect(
      await screen.findByText("Hi {client_first_name}", undefined, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: "Add shared reply" }));
    const dialog = await screen.findByRole("dialog", { name: "Add shared reply" });
    fireEvent.change(within(dialog).getByLabelText(/^Title/), { target: { value: "Printer" } });
    fireEvent.change(within(dialog).getByLabelText(/^Reply text/), {
      target: { value: "Hello <team>\n\nTry again" },
    });
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("rowheader", { name: "Printer" })).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({
      title: "Printer",
      bodyHtml: "<p>Hello &lt;team&gt;</p><p>Try again</p>",
    });
  }, 20000);
});

describe("Admin - Audit log", () => {
  const PAGE = {
    events: [
      {
        id: 11,
        occurredAt: "2026-11-18T08:00:00Z",
        type: "SIGN_IN_FAILED",
        actorUserId: null,
        actorName: null,
        actorEmail: null,
        targetUserId: null,
        targetName: null,
        targetEmail: null,
        clientIp: "198.51.100.9",
        details: { email: "<img src=x onerror=alert(1)>", organisation: 7 },
      },
    ],
    nextBefore: null,
    from: "2026-10-20",
    to: "2026-11-18",
    types: ["AUDIT_LOG_EXPORTED", "SIGN_IN_FAILED"],
  };

  it("shows details as plain text, filters by event and reports a refused export", async () => {
    const { api, calls } = recordingApi({
      "GET /api/v1/admin/organisations": () => jsonResponse(ORGS),
      "GET /api/v1/admin/audit/csv*": () =>
        jsonResponse(
          { error: "too_many_rows", message: "More than 10000 events match. Narrow the dates." },
          400,
        ),
      "GET /api/v1/admin/audit*": () => jsonResponse(PAGE),
    });
    const { container } = renderAt("/admin/audit-log", undefined, { api });
    expect(
      await screen.findByRole("rowheader", { name: "Sign in failed" }, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(screen.getByText(/email: <img src=x onerror=alert\(1\)>/)).toBeInTheDocument();
    expect(container.querySelector("img[src='x']")).toBeNull();
    expect(await screen.findByText("organisation: Acme Dental")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);

    fireEvent.change(screen.getByLabelText(/^Event/), { target: { value: "SIGN_IN_FAILED" } });
    fireEvent.change(screen.getByLabelText(/^Actor email contains/), { target: { value: "alex" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.path.startsWith("/api/v1/admin/audit?") &&
            c.path.includes("type=SIGN_IN_FAILED") &&
            c.path.includes("actor=alex"),
        ),
      ).toBe(true),
    );

    fireEvent.click(screen.getByRole("button", { name: "Export CSV" }));
    expect(await screen.findByText(/More than 10000 events match/)).toBeInTheDocument();
  }, 20000);
});
