import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { textToHtml } from "../../api/portal";
import { axeViolations, jsonResponse, renderAt, type ApiStub } from "../../test/helpers";

const ACCOUNT = {
  name: "Kim Example",
  email: "kim@example.test",
  organisationName: "Acme Dental",
  microsoftSignInAvailable: false,
  microsoftLinked: false,
};

type Call = { method: string; path: string; body: unknown };

/** Portal API stubs that record every call. */
function portalApi(overrides: Partial<Record<string, (init?: RequestInit) => Response>> = {}) {
  const calls: Call[] = [];
  const api: ApiStub = (path, init) => {
    const method = init?.method ?? "GET";
    let body: unknown = undefined;
    if (typeof init?.body === "string") body = JSON.parse(init.body);
    calls.push({ method, path, body });
    const key = `${method} ${path}`;
    const override = overrides[key];
    if (override) return override(init);
    switch (key) {
      case "GET /api/v1/portal/request-types":
        return jsonResponse([
          { id: 2, name: "Network and internet", description: "Wi-Fi, VPN, internet down" },
          { id: 1, name: "Other or general question", description: "Anything else" },
        ]);
      case "GET /api/v1/portal/account":
        return jsonResponse(ACCOUNT);
      case "POST /api/v1/portal/tickets":
        return jsonResponse({ reference: "ACD-001" }, 201);
      case "PATCH /api/v1/portal/account":
        return jsonResponse({ ...ACCOUNT, name: (body as { name: string }).name });
      default:
        return undefined;
    }
  };
  return { api, calls };
}

async function fillForm() {
  const subject = await screen.findByLabelText("Subject");
  fireEvent.click(await screen.findByRole("radio", { name: "Other or general question" }));
  fireEvent.change(subject, { target: { value: "Printer jammed" } });
  fireEvent.change(screen.getByLabelText("Describe the problem"), {
    target: { value: "Paper stuck <again>\n\nSecond floor" },
  });
  fireEvent.click(screen.getByLabelText("No, work has stopped"));
  fireEvent.click(screen.getByLabelText("A few people"));
}

describe("portal New ticket (WP-06)", () => {
  it("lists the request types in server order with their helper text (ADR-027)", async () => {
    const { api } = portalApi();
    renderAt("/portal/new-ticket", undefined, { api });
    const group = await screen.findByRole("group", { name: "What do you need help with?" });
    await within(group).findByRole("radio", { name: "Network and internet" });
    const radios = within(group).getAllByRole("radio");
    expect(radios.map((r) => r.closest("label")?.textContent)).toEqual([
      "Network and internetWi-Fi, VPN, internet down",
      "Other or general questionAnything else",
    ]);
    expect(
      within(group).getByRole("radio", { name: "Network and internet" }),
    ).toHaveAccessibleDescription("Wi-Fi, VPN, internet down");
    expect(radios.every((r) => !(r as HTMLInputElement).checked)).toBe(true);
  });

  it("shows field errors and sends nothing when the form is empty", async () => {
    const { api, calls } = portalApi();
    renderAt("/portal/new-ticket", undefined, { api });
    await screen.findByRole("heading", { level: 1, name: "Raise a support ticket" });
    await screen.findByRole("radio", { name: "Other or general question" });
    fireEvent.click(screen.getByRole("button", { name: "Send ticket" }));
    expect(await screen.findByText("Enter a subject.")).toBeInTheDocument();
    expect(screen.getByText("Describe the problem.")).toBeInTheDocument();
    expect(screen.getByText("Choose what you need help with.")).toBeInTheDocument();
    expect(screen.getByLabelText("Subject")).toHaveAttribute("aria-invalid", "true");
    expect(calls.some((c) => c.method === "POST")).toBe(false);
  });

  it("raises the ticket with only form fields and shows the reference", async () => {
    const { api, calls } = portalApi();
    renderAt("/portal/new-ticket", undefined, { api });
    await fillForm();
    fireEvent.click(screen.getByRole("button", { name: "Send ticket" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "Ticket ACD-001 received" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/We will reply by email/)).toBeInTheDocument();
    const post = calls.find((c) => c.method === "POST" && c.path === "/api/v1/portal/tickets");
    const body = post?.body as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(
      [
        "attachmentIds",
        "descriptionHtml",
        "idempotencyKey",
        "impact",
        "requestTypeId",
        "subject",
        "urgency",
      ].sort(),
    );
    expect(body).toMatchObject({
      requestTypeId: 1,
      subject: "Printer jammed",
      descriptionHtml: "<p>Paper stuck &lt;again&gt;</p><p>Second floor</p>",
      impact: "A_FEW_PEOPLE",
      urgency: "WORK_STOPPED",
      attachmentIds: [],
    });
    expect(typeof body.idempotencyKey).toBe("string");
    // Nothing about status, priority or SLA on the success page.
    expect(document.body.textContent ?? "").not.toMatch(/priority|status|SLA|due/i);
  });

  it("raise another starts a fresh form with a new submission key", async () => {
    const { api, calls } = portalApi();
    renderAt("/portal/new-ticket", undefined, { api });
    await fillForm();
    fireEvent.click(screen.getByRole("button", { name: "Send ticket" }));
    fireEvent.click(await screen.findByRole("button", { name: "Raise another ticket" }));
    expect(await screen.findByLabelText("Subject")).toHaveValue("");
    await fillForm();
    fireEvent.click(screen.getByRole("button", { name: "Send ticket" }));
    await screen.findByRole("heading", { name: "Ticket ACD-001 received" });
    const keys = calls
      .filter((c) => c.method === "POST")
      .map((c) => (c.body as { idempotencyKey: string }).idempotencyKey);
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toEqual(keys[1]);
  });

  it("a double click sends once", async () => {
    const { api, calls } = portalApi();
    const slow: ApiStub = (path, init) => {
      if (path === "/api/v1/portal/tickets") {
        calls.push({ method: "POST", path, body: undefined });
        return undefined; // falls through to the never-answering default below
      }
      return api(path, init);
    };
    const view = renderAt("/portal/new-ticket", () => new Promise<never>(() => undefined), {
      api: slow,
    });
    await fillForm();
    const send = screen.getByRole("button", { name: "Send ticket" });
    fireEvent.click(send);
    fireEvent.click(send);
    await screen.findByRole("button", { name: "Sending..." });
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(1);
    view.unmount();
  });

  it("shows the server's message when sending fails", async () => {
    const { api } = portalApi({
      "POST /api/v1/portal/tickets": () =>
        jsonResponse({ error: "invalid_request", message: "Choose a request type." }, 400),
    });
    renderAt("/portal/new-ticket", undefined, { api });
    await fillForm();
    fireEvent.click(screen.getByRole("button", { name: "Send ticket" }));
    expect((await screen.findAllByRole("alert"))[0]).toHaveTextContent("Choose a request type.");
    expect(screen.getByLabelText("Subject")).toHaveValue("Printer jammed");
  });

  it("refuses an SVG and a file over 20 MB before uploading", async () => {
    const { api, calls } = portalApi();
    renderAt("/portal/new-ticket", undefined, { api });
    const input = await screen.findByLabelText("Attach files");
    const svg = new File(["<svg/>"], "logo.svg", { type: "image/svg+xml" });
    const big = new File(["x"], "big.zip");
    Object.defineProperty(big, "size", { value: 21 * 1024 * 1024 });
    fireEvent.change(input, { target: { files: [svg, big] } });
    const list = await screen.findByRole("list", { name: "Files" });
    expect(within(list).getByText("SVG files cannot be uploaded.")).toBeInTheDocument();
    expect(within(list).getByText(/Too large/)).toBeInTheDocument();
    expect(calls.some((c) => c.path === "/api/v1/portal/attachments")).toBe(false);
    fireEvent.click(within(list).getByRole("button", { name: "Remove logo.svg" }));
    expect(within(list).queryByText("logo.svg")).not.toBeInTheDocument();
  });

  it("uploads a file and sends its id with the ticket", async () => {
    const { api, calls } = portalApi({
      "POST /api/v1/portal/attachments": () =>
        jsonResponse(
          { id: "8a6f6a52-0d1c-4b8e-9d33-5d3b8f2a1c11", filename: "a.png", size: 2048 },
          201,
        ),
    });
    renderAt("/portal/new-ticket", undefined, { api });
    await fillForm();
    fireEvent.change(screen.getByLabelText("Attach files"), {
      target: { files: [new File(["png"], "a.png", { type: "image/png" })] },
    });
    expect(await screen.findByText("2 KB")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Send ticket" }));
    await screen.findByRole("heading", { name: "Ticket ACD-001 received" });
    const post = calls.find((c) => c.path === "/api/v1/portal/tickets");
    expect((post?.body as { attachmentIds: string[] }).attachmentIds).toEqual([
      "8a6f6a52-0d1c-4b8e-9d33-5d3b8f2a1c11",
    ]);
  });

  it("the form and the success page have no axe violations", async () => {
    const { api } = portalApi();
    renderAt("/portal/new-ticket", undefined, { api });
    await fillForm();
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: "Send ticket" }));
    await screen.findByRole("heading", { name: "Ticket ACD-001 received" });
    expect(await axeViolations()).toEqual([]);
  });

  it("the form with field errors has no axe violations", async () => {
    const { api } = portalApi();
    renderAt("/portal/new-ticket", undefined, { api });
    await screen.findByLabelText("Subject");
    fireEvent.click(screen.getByRole("button", { name: "Send ticket" }));
    await screen.findByText("Enter a subject.");
    expect(await axeViolations()).toEqual([]);
  });
});

describe("portal My account (WP-06)", () => {
  it("shows my details, hides Microsoft while it is off, and renames", async () => {
    const { api, calls } = portalApi();
    renderAt("/portal/account", undefined, { api });
    expect(await screen.findByText("kim@example.test", { selector: "dd" })).toBeInTheDocument();
    expect(screen.getByText("Acme Dental", { selector: "dd" })).toBeInTheDocument();
    expect(screen.queryByText("Microsoft account")).not.toBeInTheDocument();
    expect(screen.getByText(/Email me a sign-in link/)).toBeInTheDocument();
    const name = screen.getByLabelText("Name");
    fireEvent.change(name, { target: { value: "Kim Tran" } });
    fireEvent.click(screen.getByRole("button", { name: "Save name" }));
    await waitFor(() =>
      expect(calls.find((c) => c.method === "PATCH")?.body).toEqual({ name: "Kim Tran" }),
    );
  });

  it("shows the Microsoft section once sign-in with Microsoft is on", async () => {
    const { api } = portalApi({
      "GET /api/v1/portal/account": () =>
        jsonResponse({ ...ACCOUNT, microsoftSignInAvailable: true }),
    });
    renderAt("/portal/account", undefined, { api });
    expect(await screen.findByRole("heading", { name: "Microsoft account" })).toBeInTheDocument();
  });

  it("checks the new password locally before sending", async () => {
    const { api, calls } = portalApi();
    renderAt("/portal/account", undefined, { api });
    fireEvent.change(await screen.findByLabelText("New password"), {
      target: { value: "short" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Change password" }));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(calls.some((c) => c.path === "/api/v1/auth/password/change")).toBe(false);
  });

  it("has no axe violations", async () => {
    const { api } = portalApi();
    renderAt("/portal/account", undefined, { api });
    await screen.findByLabelText("Name");
    expect(await axeViolations()).toEqual([]);
  });
});

describe("textToHtml", () => {
  it("escapes markup and keeps paragraphs and line breaks", () => {
    expect(textToHtml(" <script>x</script>\nline 2\n\n& more ")).toBe(
      "<p>&lt;script&gt;x&lt;/script&gt;<br>line 2</p><p>&amp; more</p>",
    );
  });
});
