import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { agentApi } from "../../test/agentApi";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";
import { formatMinutes, localDay } from "./reportFormat";

// Reports - SLA on the real API (GET /api/v1/agent/sla-report, spec 4.7). Synthetic data only.

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const rate = (met: number, breached: number, na = 0) => ({
  measured: met + breached,
  met,
  breached,
  notApplicable: na,
  metPercent: met + breached === 0 ? null : Math.round((met * 1000) / (met + breached)) / 10,
});
const stats = (count: number, avg: number | null, median: number | null) => ({
  count,
  averageMinutes: avg,
  medianMinutes: median,
});
const times = (frAvg: number | null, resAvg: number | null, p1 = false) => ({
  businessHours: {
    firstResponse: stats(frAvg === null ? 0 : 3, frAvg, frAvg),
    resolution: stats(resAvg === null ? 0 : 2, resAvg, resAvg),
  },
  clockHours: {
    firstResponse: p1 ? stats(1, 25, 25) : stats(0, null, null),
    resolution: p1 ? stats(1, 190, 190) : stats(0, null, null),
  },
});

const REPORT = {
  from: "2026-10-21",
  to: "2026-11-19",
  zone: "Asia/Ho_Chi_Minh",
  totals: { opened: 12, resolved: 9, stillOpen: 3, duplicates: 1 },
  ageing: { days0to2: 1, days3to7: 1, days8to30: 1, over30: 0 },
  firstResponse: rate(9, 1, 1),
  resolution: rate(8, 1),
  times: times(52, 615, true),
  breached: [
    {
      ticketId: 42,
      reference: "ACD-042",
      organisationId: 1,
      priority: "P2",
      assigneeUserId: 1,
      assigneeName: "Alex Tran",
      target: "RESOLUTION",
      overByMinutes: 95,
    },
    {
      ticketId: 51,
      reference: "BCL-007",
      organisationId: 2,
      priority: "P1",
      assigneeUserId: null,
      assigneeName: null,
      target: "FIRST_RESPONSE",
      overByMinutes: 12,
    },
  ],
  engineers: [
    {
      userId: 1,
      name: "Alex Tran",
      handled: 6,
      resolution: rate(5, 1),
      times: times(40, 600, true),
    },
  ],
  tickets: [{ ticketId: 42 }, { ticketId: 51 }],
  truncated: false,
};

const EMPTY = {
  ...REPORT,
  totals: { opened: 0, resolved: 0, stillOpen: 0, duplicates: 0 },
  ageing: { days0to2: 0, days3to7: 0, days8to30: 0, over30: 0 },
  firstResponse: rate(0, 0),
  resolution: rate(0, 0),
  times: times(null, null),
  breached: [],
  engineers: [],
  tickets: [],
};

function popups() {
  return within(screen.getByTestId("error-popups"));
}

describe("format helpers", () => {
  it("formats minutes compactly", () => {
    expect(formatMinutes(null)).toBe("-");
    expect(formatMinutes(45)).toBe("45m");
    expect(formatMinutes(120)).toBe("2h");
    expect(formatMinutes(125.4)).toBe("2h 05m");
  });

  it("gives local calendar days", () => {
    const now = new Date(2026, 10, 19, 23, 30);
    expect(localDay(0, now)).toBe("2026-11-19");
    expect(localDay(-29, now)).toBe("2026-10-21");
  });
});

describe("Reports - SLA page", () => {
  it("shows the real summary, breaches and engineers, with no demo banner", async () => {
    const { api, calls } = agentApi({
      "GET /api/v1/agent/sla-report": () => jsonResponse(REPORT),
    });
    renderAt("/agent/reports", undefined, { role: "AGENT", api });
    const summary = await screen.findByRole("list", { name: "Summary" }, { timeout: 5000 });
    expect(within(summary).getByText("12")).toBeInTheDocument();
    expect(within(summary).getByText("90%")).toBeInTheDocument();
    expect(within(summary).getByText("88.9%")).toBeInTheDocument();
    expect(within(summary).getByText("8 of 9 met")).toBeInTheDocument();
    expect(screen.getByText(/1 duplicate counted as opened/)).toBeInTheDocument();
    expect(screen.getByText("Internal only - never shown to clients")).toBeInTheDocument();
    expect(screen.queryByText("Demo data")).toBeNull();

    const breaches = screen.getByRole("table", { name: "Breached tickets" });
    expect(within(breaches).getByRole("link", { name: "ACD-042" })).toHaveAttribute(
      "href",
      "/agent/tickets/ACD-042",
    );
    expect(within(breaches).getByText("1h 35m (business)")).toBeInTheDocument();
    expect(within(breaches).getByText("12m")).toBeInTheDocument();
    expect(await within(breaches).findByText("Beta Clinic")).toBeInTheDocument();
    expect(within(breaches).getByText("Unassigned")).toBeInTheDocument();

    const times = screen.getByRole("table", { name: "Response and resolution times" });
    // Average and median of the business-hours resolution time (both 615 minutes here).
    expect(within(times).getAllByText("10h 15m")).toHaveLength(2);
    const engineers = screen.getByRole("table", { name: "SLA by engineer" });
    expect(within(engineers).getByRole("rowheader", { name: "Alex Tran" })).toBeInTheDocument();
    expect(within(engineers).getByText("83.3%")).toBeInTheDocument();
    expect(within(engineers).getByText("P1: 3h 10m")).toBeInTheDocument();

    // The first load asks for the last 30 days with no other filter.
    const first = calls.find((c) => c.path.startsWith("/api/v1/agent/sla-report?"));
    expect(first?.path).toBe(`/api/v1/agent/sla-report?from=${localDay(-29)}&to=${localDay()}`);
    expect(await axeViolations()).toEqual([]);
  }, 20000);

  it("lists a breached Email triage ticket (no organisation, ADR-047)", async () => {
    const triage = {
      ...REPORT,
      breached: [
        {
          ticketId: 60,
          reference: "TRI-003",
          organisationId: null,
          priority: "P3",
          assigneeUserId: null,
          assigneeName: null,
          target: "FIRST_RESPONSE",
          overByMinutes: 30,
        },
      ],
    };
    const { api } = agentApi({
      "GET /api/v1/agent/sla-report": () => jsonResponse(triage),
    });
    renderAt("/agent/reports", undefined, { role: "AGENT", api });
    const breaches = await screen.findByRole(
      "table",
      { name: "Breached tickets" },
      { timeout: 5000 },
    );
    expect(within(breaches).getByRole("link", { name: "TRI-003" })).toBeInTheDocument();
    expect(within(breaches).getByText("Email triage")).toBeInTheDocument();
  }, 20000);

  it("applies the filters to the report and the CSV export", async () => {
    const createObjectURL = vi.fn(() => "blob:sla");
    // jsdom has no object URLs; give the download path a stand-in.
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    const { api, calls } = agentApi({
      "GET /api/v1/agent/sla-report": () => jsonResponse(REPORT),
      "GET /api/v1/agent/sla-report/csv": () =>
        new Response("reference\r\nACD-042\r\n", {
          status: 200,
          headers: { "Content-Type": "text/csv" },
        }),
    });
    renderAt("/agent/reports", undefined, { role: "AGENT", api });
    await screen.findByRole("list", { name: "Summary" }, { timeout: 5000 });
    await screen.findByRole("option", { name: "Beta Clinic" });
    await screen.findByRole("option", { name: "Minh Le" });
    await screen.findByRole("option", { name: "Alert" });

    fireEvent.change(screen.getByLabelText(/^From/), { target: { value: "2026-11-01" } });
    fireEvent.change(screen.getByLabelText(/^To/), { target: { value: "2026-11-15" } });
    fireEvent.change(screen.getByLabelText(/^Organisation/), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText(/^Engineer/), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText(/^Request type/), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText(/^Priority/), { target: { value: "P1" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    const query =
      "from=2026-11-01&to=2026-11-15&organisationId=2&engineer=2&requestTypeId=2&priority=P1";
    await waitFor(() =>
      expect(calls.some((c) => c.path === `/api/v1/agent/sla-report?${query}`)).toBe(true),
    );

    const exportButton = screen.getByRole("button", { name: "Export CSV" });
    await waitFor(() => expect(exportButton).toBeEnabled());
    fireEvent.click(exportButton);
    expect(
      await screen.findByText("SLA report exported. The export is recorded in the audit log."),
    ).toBeInTheDocument();
    expect(calls.some((c) => c.path === `/api/v1/agent/sla-report/csv?${query}`)).toBe(true);
    expect(click).toHaveBeenCalled();
    expect(createObjectURL).toHaveBeenCalled();
  }, 20000);

  it("refuses an end date before the start date without calling the server", async () => {
    const { api, calls } = agentApi({
      "GET /api/v1/agent/sla-report": () => jsonResponse(REPORT),
    });
    renderAt("/agent/reports", undefined, { role: "AGENT", api });
    await screen.findByRole("list", { name: "Summary" }, { timeout: 5000 });
    const before = calls.length;
    fireEvent.change(screen.getByLabelText(/^From/), { target: { value: "2026-11-15" } });
    fireEvent.change(screen.getByLabelText(/^To/), { target: { value: "2026-11-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    expect(
      await screen.findByText("The end date must be on or after the start date."),
    ).toBeInTheDocument();
    expect(calls.slice(before).some((c) => c.path.startsWith("/api/v1/agent/sla-report"))).toBe(
      false,
    );
  }, 20000);

  it("shows an empty state when nothing matches", async () => {
    const { api } = agentApi({ "GET /api/v1/agent/sla-report": () => jsonResponse(EMPTY) });
    renderAt("/agent/reports", undefined, { role: "AGENT", api });
    expect(
      await screen.findByText("No tickets in this period for these filters.", undefined, {
        timeout: 5000,
      }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Summary" })).toBeNull();
    expect(screen.queryByRole("table", { name: "Breached tickets" })).toBeNull();
    expect(await axeViolations()).toEqual([]);
  }, 20000);

  it("a failed load pops up an error without the server's raw text", async () => {
    const { api } = agentApi({
      "GET /api/v1/agent/sla-report": () =>
        jsonResponse({ error: "server_error", message: "NPE at line 42" }, 500),
    });
    renderAt("/agent/reports", undefined, { role: "AGENT", api });
    expect(
      await popups().findByText(
        "The SLA report could not be loaded. Something went wrong on our side. Please try again.",
        undefined,
        { timeout: 5000 },
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/NPE at line 42/)).toBeNull();
    expect(screen.getByRole("button", { name: "Export CSV" })).toBeDisabled();
    expect(await axeViolations()).toEqual([]);
  }, 20000);

  it("a refused CSV export pops up the server's message", async () => {
    const { api } = agentApi({
      "GET /api/v1/agent/sla-report": () => jsonResponse(REPORT),
      "GET /api/v1/agent/sla-report/csv": () =>
        jsonResponse(
          { error: "invalid_request", message: "The range can be at most 400 days." },
          400,
        ),
    });
    renderAt("/agent/reports", undefined, { role: "AGENT", api });
    await screen.findByRole("list", { name: "Summary" }, { timeout: 5000 });
    fireEvent.click(screen.getByRole("button", { name: "Export CSV" }));
    expect(
      await popups().findByText("The CSV was not exported. The range can be at most 400 days."),
    ).toBeInTheDocument();
  }, 20000);
});
