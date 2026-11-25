import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { splitDomains } from "../../api/organisations";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";

const acme = {
  id: 1,
  name: "Acme Dental",
  prefix: "ACD",
  retiredPrefixes: [],
  verifiedDomains: ["acme-dental.example"],
  timezone: "Asia/Ho_Chi_Minh",
  entraTenantId: null,
  status: "ACTIVE",
};

const allOn = {
  ticketReceived: true,
  resolvedNotice: true,
  closedNotice: true,
  pendingReminder: true,
};

type Call = { path: string; method: string; body: unknown };

/** Admin organisations API stub with a list, recording each call. */
function organisationsApi(handlers: Record<string, (body: unknown) => Response> = {}) {
  const calls: Call[] = [];
  const api = (path: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
    calls.push({ path, method, body });
    const handler = handlers[`${method} ${path}`];
    if (handler) return handler(body);
    if (method === "GET" && path === "/api/v1/admin/organisations") return jsonResponse([acme]);
    if (method === "GET" && path === "/api/v1/admin/organisations/1/client-emails")
      return jsonResponse(allOn);
    return undefined;
  };
  return { api, calls };
}

function type(label: string | RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.unstubAllGlobals();
});

describe("Admin - Organisations", () => {
  it("lists organisations from the API with prefix and domains", async () => {
    const { api } = organisationsApi();
    renderAt("/admin/organisations", undefined, { api });
    // First render in this file pays the lazy-load cost; 1 s flaked on the 2-CPU CI container.
    expect(
      await screen.findByRole("button", { name: "Acme Dental" }, { timeout: 5000 }),
    ).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Organisations" });
    expect(within(table).getByText("ACD")).toBeInTheDocument();
    expect(within(table).getByText("acme-dental.example")).toBeInTheDocument();
    expect(screen.getByText("1 organisation")).toBeInTheDocument();
  });

  it("creates an organisation and shows it", async () => {
    const created = { ...acme, id: 2, name: "Beta Clinic", prefix: "BCL", verifiedDomains: [] };
    const { api, calls } = organisationsApi({
      "POST /api/v1/admin/organisations": () => jsonResponse(created, 201),
    });
    renderAt("/admin/organisations", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "New organisation" }));
    const dialog = screen.getByRole("dialog", { name: "New organisation" });
    type("Name", "Beta Clinic");
    type(/^Ticket prefix/, "bcl");
    type(/^Verified domains/, "beta-clinic.example, mail.beta-clinic.example");
    fireEvent.click(within(dialog).getByRole("button", { name: "Create organisation" }));

    expect(await screen.findByRole("button", { name: "Beta Clinic" })).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({
      name: "Beta Clinic",
      prefix: "bcl",
      verifiedDomains: ["beta-clinic.example", "mail.beta-clinic.example"],
      timezone: "Asia/Ho_Chi_Minh",
      entraTenantId: null,
    });
  });

  it("shows the server's message when the prefix is taken", async () => {
    const { api } = organisationsApi({
      "POST /api/v1/admin/organisations": () =>
        jsonResponse(
          {
            error: "prefix_taken",
            message: "ACD is already used, or was used before, by another organisation.",
          },
          409,
        ),
    });
    renderAt("/admin/organisations", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "New organisation" }));
    type("Name", "Copy Dental");
    type(/^Ticket prefix/, "ACD");
    fireEvent.click(screen.getByRole("button", { name: "Create organisation" }));
    expect((await screen.findAllByRole("alert"))[0]).toHaveTextContent("ACD is already used");
  });

  it("changes the prefix and adds a domain from the side panel", async () => {
    const renamed = { ...acme, prefix: "ADG", retiredPrefixes: ["ACD"] };
    const withDomain = { ...renamed, verifiedDomains: ["acme-dental.example", "acme.example"] };
    const { api, calls } = organisationsApi({
      "PUT /api/v1/admin/organisations/1/prefix": () => jsonResponse(renamed),
      "POST /api/v1/admin/organisations/1/domains": () => jsonResponse(withDomain),
    });
    renderAt("/admin/organisations", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Acme Dental" }));
    const panel = screen.getByRole("complementary", { name: "Organisation detail" });
    fireEvent.change(within(panel).getByLabelText(/^Ticket prefix/), { target: { value: "ADG" } });
    fireEvent.click(within(panel).getByRole("button", { name: "Change prefix" }));
    expect(await within(panel).findByText("Earlier prefixes: ACD")).toBeInTheDocument();

    fireEvent.change(within(panel).getByLabelText(/^Add a domain/), {
      target: { value: "acme.example" },
    });
    fireEvent.click(within(panel).getByRole("button", { name: "Add domain" }));
    expect(await within(panel).findByText("acme.example")).toBeInTheDocument();
    expect(calls.filter((c) => c.method !== "GET").map((c) => c.body)).toEqual([
      { prefix: "ADG" },
      { domain: "acme.example" },
    ]);
  });

  it("shows why a public domain cannot be added", async () => {
    const { api } = organisationsApi({
      "POST /api/v1/admin/organisations/1/domains": () =>
        jsonResponse(
          {
            error: "public_domain",
            message: "gmail.com is a public email domain, so it cannot be a verified domain.",
          },
          400,
        ),
    });
    renderAt("/admin/organisations", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Acme Dental" }));
    const panel = screen.getByRole("complementary", { name: "Organisation detail" });
    fireEvent.change(within(panel).getByLabelText(/^Add a domain/), {
      target: { value: "gmail.com" },
    });
    fireEvent.click(within(panel).getByRole("button", { name: "Add domain" }));
    await waitFor(() =>
      expect(within(panel).getByLabelText(/^Add a domain/)).toHaveAttribute("aria-invalid", "true"),
    );
    expect(within(panel).getByText(/public email domain/)).toBeInTheDocument();
  });

  it("has no axe violations with the panel open", async () => {
    const { api } = organisationsApi();
    renderAt("/admin/organisations", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Acme Dental" }));
    expect(await screen.findByRole("checkbox", { name: "Ticket received" })).toBeChecked();
    expect(await axeViolations()).toEqual([]);
  });

  it("shows the four client email switches, all on by default", async () => {
    const { api } = organisationsApi();
    renderAt("/admin/organisations", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Acme Dental" }));
    const panel = screen.getByRole("complementary", { name: "Organisation detail" });
    const section = await within(panel).findByRole("region", { name: "Client emails" });
    for (const name of [
      "Ticket received",
      "Resolved notice",
      "Closed notice",
      "Pending reminder",
    ]) {
      const box = await within(section).findByRole("checkbox", { name });
      expect(box).toBeChecked();
      expect(box).toHaveAccessibleDescription(/./);
    }
    expect(within(section).getByText(/Agent replies and tickets an agent opens/)).toBeVisible();
  });

  it("turns one client email off and saves all four switches", async () => {
    const { api, calls } = organisationsApi({
      "PUT /api/v1/admin/organisations/1/client-emails": (body) => jsonResponse(body),
    });
    renderAt("/admin/organisations", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Acme Dental" }));
    const box = await screen.findByRole("checkbox", { name: "Resolved notice" });
    fireEvent.click(box);
    await waitFor(() => expect(box).not.toBeChecked());
    expect(await screen.findByText("Resolved notice turned off for Acme Dental.")).toBeVisible();
    expect(calls.filter((c) => c.method === "PUT")).toEqual([
      {
        path: "/api/v1/admin/organisations/1/client-emails",
        method: "PUT",
        body: { ...allOn, resolvedNotice: false },
      },
    ]);
    expect(screen.getByRole("checkbox", { name: "Ticket received" })).toBeChecked();
    expect(await axeViolations()).toEqual([]);
  });

  it("shows a pop-up and keeps the switch when saving fails", async () => {
    const { api } = organisationsApi({
      "PUT /api/v1/admin/organisations/1/client-emails": () =>
        jsonResponse({ error: "server_error", message: "NPE at line 42" }, 500),
    });
    renderAt("/admin/organisations", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Acme Dental" }));
    const box = await screen.findByRole("checkbox", { name: "Closed notice" });
    fireEvent.click(box);
    const popups = screen.getByTestId("error-popups");
    expect(
      await within(popups).findByText(/^The client email setting was not changed\./),
    ).toBeInTheDocument();
    expect(box).toBeChecked();
    expect(box).toBeEnabled();
    expect(screen.queryByText(/NPE at line 42/)).not.toBeInTheDocument();
  });

  it("says so when the client email switches cannot be loaded", async () => {
    const { api } = organisationsApi({
      "GET /api/v1/admin/organisations/1/client-emails": () =>
        jsonResponse({ error: "forbidden", message: "You do not have access to this." }, 403),
    });
    renderAt("/admin/organisations", undefined, { api });
    fireEvent.click(await screen.findByRole("button", { name: "Acme Dental" }));
    expect(
      await screen.findByText(/^The client email settings could not be loaded\./),
    ).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Ticket received" })).not.toBeInTheDocument();
  });

  it("splits typed domain lists", () => {
    expect(splitDomains(" a.example, b.example\nc.example;; ")).toEqual([
      "a.example",
      "b.example",
      "c.example",
    ]);
  });
});
