import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";
import { agentApi, summaryRow, triageRow } from "../../test/agentApi";

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

function ticketCalls(calls: { path: string }[]) {
  return calls.filter((c) => c.path.startsWith("/api/v1/agent/tickets?")).map((c) => c.path);
}

describe("Agent queue", () => {
  it("lists My tickets from the API with organisation, assignee and total", async () => {
    const { api, calls } = agentApi();
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    const link = await screen.findByRole("link", { name: "ACD-042" }, { timeout: 5000 });
    expect(link).toHaveAttribute("href", "/agent/tickets/ACD-042");
    const table = screen.getByRole("table", { name: "My tickets queue" });
    // ADR-047: the organisation sits in the grey line under the subject.
    expect(within(table).getByText("Acme Dental - Maria Gomez - General")).toBeInTheDocument();
    expect(within(table).getByText("Alex Tran")).toBeInTheDocument();
    expect(screen.getByText("1 ticket")).toBeInTheDocument();
    expect(ticketCalls(calls)[0]).toBe("/api/v1/agent/tickets?assignee=me&page=0&limit=50");
    // No WP-09 SLA fields yet: no SLA column.
    expect(within(table).queryByRole("columnheader", { name: "SLA" })).not.toBeInTheDocument();
  });

  it("a bare queue opens the supporter's default view from preferences (WP-11a)", async () => {
    const { api, calls } = agentApi({
      "GET /api/v1/agent/me/preferences": () =>
        jsonResponse({ composerTab: "INTERNAL_NOTE", queueView: "unassigned" }),
    });
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    expect(
      await screen.findByRole("heading", { level: 1, name: "Unassigned" }, { timeout: 5000 }),
    ).toBeInTheDocument();
    await waitFor(() => expect(ticketCalls(calls).length).toBeGreaterThan(0));
    // My tickets is never fetched first.
    expect(ticketCalls(calls)).toEqual([
      "/api/v1/agent/tickets?excludeTriage=true&assignee=none&page=0&limit=50",
    ]);
    expect(screen.getByRole("link", { name: "My tickets" })).toHaveAttribute(
      "href",
      "/agent/queue?view=mine",
    );
  });

  it.each([
    ["unassigned", "excludeTriage=true&assignee=none", "Unassigned"],
    ["pending-client", "status=PENDING_ON_CLIENT", "Pending on client"],
    ["pending-vendor", "status=PENDING_ON_VENDOR", "Pending on vendor"],
    ["triage", "triage=true", "Email triage"],
    ["all", "", "All open tickets"],
  ])("view %s asks the API for %s", async (view, query, title) => {
    const { api, calls } = agentApi();
    renderAt(`/agent/queue?view=${view}`, undefined, { role: "AGENT", api });
    expect(await screen.findByRole("heading", { level: 1, name: title })).toBeInTheDocument();
    await waitFor(() => expect(ticketCalls(calls).length).toBeGreaterThan(0));
    const expected = `/api/v1/agent/tickets?${query ? `${query}&` : ""}page=0&limit=50`;
    expect(ticketCalls(calls)).toContain(expected);
  });

  it("filters by organisation from the sidebar and marks out-of-office supporters", async () => {
    const { api, calls } = agentApi();
    renderAt("/agent/queue?org=1", undefined, { role: "AGENT", api });
    expect(
      await screen.findByRole("heading", { level: 1, name: "Acme Dental" }),
    ).toBeInTheDocument();
    expect(ticketCalls(calls)).toContain("/api/v1/agent/tickets?organisationId=1&page=0&limit=50");
    expect(
      await screen.findByRole("option", { name: "Minh Le (out of office)" }),
    ).toBeInTheDocument();
  });

  it("applies filters into the URL, the API call and the CSV export link", async () => {
    const { api, calls } = agentApi();
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    await screen.findByRole("link", { name: "ACD-042" }, { timeout: 5000 });
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "outlook" } });
    fireEvent.change(screen.getByLabelText("Priority"), { target: { value: "P2" } });
    fireEvent.change(screen.getByLabelText("Sort"), { target: { value: "updated" } });
    fireEvent.click(screen.getByLabelText("Include closed"));
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() =>
      expect(ticketCalls(calls)).toContain(
        "/api/v1/agent/tickets?assignee=me&priority=P2&q=outlook&sort=updated&includeClosed=true&page=0&limit=50",
      ),
    );
    expect(screen.getByRole("link", { name: "Export CSV" })).toHaveAttribute(
      "href",
      "/api/v1/agent/tickets/export?assignee=me&priority=P2&q=outlook&sort=updated&includeClosed=true",
    );
  });

  it("pages through results 50 at a time", async () => {
    const { api, calls } = agentApi({
      "GET /api/v1/agent/tickets": () =>
        new Response(JSON.stringify([summaryRow]), {
          status: 200,
          headers: { "Content-Type": "application/json", "X-Total-Count": "120" },
        }),
    });
    renderAt("/agent/queue?view=all", undefined, { role: "AGENT", api });
    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    expect(ticketCalls(calls)).toContain("/api/v1/agent/tickets?page=1&limit=50");
  });

  it("shows triage rows as Email triage and SLA badges once the API sends them", async () => {
    const { api } = agentApi({
      "GET /api/v1/agent/tickets": () =>
        new Response(
          JSON.stringify([
            { ...triageRow },
            { ...summaryRow, slaState: "BREACHED", slaText: "Breached 12 min" },
          ]),
          { status: 200, headers: { "Content-Type": "application/json", "X-Total-Count": "2" } },
        ),
    });
    renderAt("/agent/queue?view=all", undefined, { role: "AGENT", api });
    const table = await screen.findByRole("table", { name: "All open tickets queue" });
    expect(
      await within(table).findByText(
        (_, el) =>
          el?.tagName === "DIV" && el.textContent === "Email triage - someone@gmail.com - General",
      ),
    ).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "SLA" })).toBeInTheDocument();
    expect(within(table).getByText("Breached 12 min")).toBeInTheDocument();
  });

  it("shows the empty state and the server's error", async () => {
    const empty = agentApi({
      "GET /api/v1/agent/tickets": () =>
        new Response("[]", {
          status: 200,
          headers: { "Content-Type": "application/json", "X-Total-Count": "0" },
        }),
    });
    const first = renderAt("/agent/queue", undefined, { role: "AGENT", api: empty.api });
    expect(await screen.findByText("No tickets match this view.")).toBeInTheDocument();
    first.unmount();

    const failing = agentApi({
      "GET /api/v1/agent/tickets": () =>
        jsonResponse(
          { error: "invalid_request", message: "sort is newest, oldest, updated or priority." },
          400,
        ),
    });
    renderAt("/agent/queue?sort=bad", undefined, { role: "AGENT", api: failing.api });
    expect(await screen.findByRole("alert")).toHaveTextContent("sort is newest");
  });

  it("has no axe violations", async () => {
    const { api } = agentApi();
    renderAt("/agent/queue", undefined, { role: "AGENT", api });
    await screen.findByRole("link", { name: "ACD-042" }, { timeout: 5000 });
    expect(await axeViolations()).toEqual([]);
  });
});
