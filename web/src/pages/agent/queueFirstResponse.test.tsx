import { act, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axeViolations, renderAt } from "../../test/helpers";
import { agentApi, summaryRow, triageRow } from "../../test/agentApi";
import { QUEUE_REFRESH_MS } from "./QueuePage";

// ADR-047 Decision 1: the queue's First response column, the organisation under the subject, and
// the once-a-minute background reload (paused while the tab is hidden).

const AS_OF = "2026-11-23T10:00:00Z";

function timer(state: string, extra: Record<string, unknown> = {}) {
  return {
    state,
    remainingMinutes: null,
    breachedByMinutes: null,
    dueAt: null,
    businessDayMinutes: 540,
    asOf: AS_OF,
    ...extra,
  };
}

const rows = [
  {
    ...summaryRow,
    id: 1,
    reference: "ACD-001",
    firstResponseTimer: timer("RUNNING", { remainingMinutes: 85 }),
  },
  {
    ...summaryRow,
    id: 2,
    reference: "ACD-002",
    firstResponseTimer: timer("RUNNING", { remainingMinutes: 8 }),
  },
  {
    ...summaryRow,
    id: 3,
    reference: "ACD-003",
    firstResponseTimer: timer("BREACHED", { breachedByMinutes: 25 }),
  },
  { ...summaryRow, id: 4, reference: "ACD-004", firstResponseTimer: timer("MET") },
  {
    ...summaryRow,
    id: 5,
    reference: "ACD-005",
    firstResponseTimer: timer("PAUSED", { remainingMinutes: 180 }),
  },
  { ...summaryRow, id: 6, reference: "ACD-006", firstResponseTimer: timer("NOT_APPLICABLE") },
  {
    ...summaryRow,
    id: 7,
    reference: "ACD-007",
    firstResponseTimer: timer("STOPPED_OUTSIDE_HOURS", { remainingMinutes: 600 }),
  },
  { ...triageRow, id: 8, reference: "TRI-008" },
];

function listResponse(body: unknown[]) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json", "X-Total-Count": String(body.length) },
  });
}

function ticketCalls(calls: { path: string }[]) {
  return calls.filter((c) => c.path.startsWith("/api/v1/agent/tickets?"));
}

function cellOf(reference: string) {
  const row = screen.getByRole("link", { name: reference }).closest("tr");
  if (!row) throw new Error(`no row for ${reference}`);
  return within(row).getByTestId("fr-cell");
}

function setVisibility(state: "visible" | "hidden") {
  Object.defineProperty(document, "visibilityState", { configurable: true, value: state });
  document.dispatchEvent(new Event("visibilitychange"));
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
  setVisibility("visible");
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe("Queue First response column (ADR-047)", () => {
  it("replaces the Organisation column and words every state, red never by colour alone", async () => {
    const { api } = agentApi({ "GET /api/v1/agent/tickets": () => listResponse(rows) });
    renderAt("/agent/queue?view=all", undefined, { role: "AGENT", api });
    const table = await screen.findByRole("table", { name: "All open tickets queue" });
    await within(table).findByRole("link", { name: "ACD-001" });

    expect(within(table).getByRole("columnheader", { name: "First response" })).toBeVisible();
    expect(
      within(table).queryByRole("columnheader", { name: "Organisation" }),
    ).not.toBeInTheDocument();

    // Time left, formatted as on the ticket's SLA card; plain colour above 10 minutes.
    const running = cellOf("ACD-001");
    expect(running).toHaveTextContent("1h 25m left");
    expect(running).toHaveAttribute("data-state", "running");
    expect(running.querySelector("svg")).toBeNull();

    // 10 minutes or less: red, with an icon and hidden words.
    const soon = cellOf("ACD-002");
    expect(soon).toHaveAttribute("data-state", "soon");
    expect(soon.className).toMatch(/danger/);
    expect(soon.querySelector("svg")).not.toBeNull();
    expect(soon).toHaveTextContent("Due soon: 8m left");

    const breached = cellOf("ACD-003");
    expect(breached).toHaveAttribute("data-state", "breached");
    expect(breached.className).toMatch(/breached/);
    expect(breached.querySelector("svg")).not.toBeNull();
    expect(breached).toHaveTextContent("Breached: 25m over");

    expect(cellOf("ACD-004")).toHaveTextContent(/^Met/);
    expect(cellOf("ACD-004").querySelector("svg")).not.toBeNull();

    const paused = cellOf("ACD-005");
    expect(paused).toHaveTextContent("Paused, 3h left");
    expect(paused.querySelector("svg")).not.toBeNull();

    expect(cellOf("ACD-006")).toHaveTextContent("-No first-response target");
    expect(cellOf("ACD-006")).toHaveAttribute("data-state", "none");
    // Triage without SLA data yet: a dash too.
    expect(cellOf("TRI-008")).toHaveAttribute("data-state", "none");

    // Outside business hours the time left stays, with the reason for screen readers.
    const stopped = cellOf("ACD-007");
    expect(stopped).toHaveTextContent("1d 1h left, clock stopped outside business hours");
    expect(stopped).toHaveAttribute("title", "Clock stopped outside business hours");
  });

  it("10 minutes exactly is red, 11 is not", async () => {
    const { api } = agentApi({
      "GET /api/v1/agent/tickets": () =>
        listResponse([
          { ...rows[0], firstResponseTimer: timer("RUNNING", { remainingMinutes: 10 }) },
          {
            ...rows[1],
            firstResponseTimer: timer("RUNNING", { remainingMinutes: 11 }),
          },
        ]),
    });
    renderAt("/agent/queue?view=all", undefined, { role: "AGENT", api });
    await screen.findByRole("link", { name: "ACD-001" });
    expect(cellOf("ACD-001")).toHaveAttribute("data-state", "soon");
    expect(cellOf("ACD-002")).toHaveAttribute("data-state", "running");
  });

  it("shows the organisation in the grey line under the subject; triage says Email triage", async () => {
    const { api } = agentApi({ "GET /api/v1/agent/tickets": () => listResponse(rows) });
    renderAt("/agent/queue?view=all", undefined, { role: "AGENT", api });
    const table = await screen.findByRole("table", { name: "All open tickets queue" });
    await within(table).findByRole("link", { name: "ACD-001" });
    expect(within(table).getAllByText("Acme Dental - Maria Gomez - General")).toHaveLength(7);
    expect(
      within(table).getByText(
        (_, el) =>
          el?.tagName === "DIV" && el.textContent === "Email triage - someone@gmail.com - General",
      ),
    ).toBeInTheDocument();
  });

  it("offers the first-response sort", async () => {
    const { api, calls } = agentApi();
    renderAt("/agent/queue?view=all&sort=first_response", undefined, { role: "AGENT", api });
    await screen.findByRole("link", { name: "ACD-042" });
    expect(screen.getByRole("option", { name: "First response due" })).toBeInTheDocument();
    expect(ticketCalls(calls).map((c) => c.path)).toContain(
      "/api/v1/agent/tickets?sort=first_response&page=0&limit=50",
    );
  });

  it("reloads every minute without a spinner, pauses while hidden, reloads on return", async () => {
    let remaining = 30;
    const { api, calls } = agentApi({
      "GET /api/v1/agent/tickets": () =>
        listResponse([
          { ...rows[0], firstResponseTimer: timer("RUNNING", { remainingMinutes: remaining }) },
        ]),
    });
    renderAt("/agent/queue?view=all", undefined, { role: "AGENT", api });
    await screen.findByRole("link", { name: "ACD-001" });
    expect(cellOf("ACD-001")).toHaveTextContent("30m");
    expect(ticketCalls(calls)).toHaveLength(1);
    const link = screen.getByRole("link", { name: "ACD-001" });
    link.focus();

    remaining = 29;
    await act(() => vi.advanceTimersByTimeAsync(QUEUE_REFRESH_MS));
    await waitFor(() => expect(cellOf("ACD-001")).toHaveTextContent("29m"));
    expect(ticketCalls(calls)).toHaveLength(2);
    // Same row element and focus kept; never a loading state for a background reload.
    expect(screen.getByRole("link", { name: "ACD-001" })).toBe(link);
    expect(document.activeElement).toBe(link);
    expect(screen.queryByText("Loading...")).not.toBeInTheDocument();

    act(() => setVisibility("hidden"));
    await act(() => vi.advanceTimersByTimeAsync(QUEUE_REFRESH_MS * 3));
    expect(ticketCalls(calls)).toHaveLength(2);

    remaining = 25;
    act(() => setVisibility("visible"));
    await waitFor(() => expect(cellOf("ACD-001")).toHaveTextContent("25m"));
    expect(ticketCalls(calls)).toHaveLength(3);
    await act(() => vi.advanceTimersByTimeAsync(QUEUE_REFRESH_MS));
    await waitFor(() => expect(ticketCalls(calls)).toHaveLength(4));
  });

  it("a failed background reload keeps the rows and shows one quiet notice", async () => {
    let fail = false;
    const { api, calls } = agentApi({
      "GET /api/v1/agent/tickets": () =>
        fail
          ? new Response(JSON.stringify({ error: "server_error", message: "Down" }), {
              status: 503,
              headers: { "Content-Type": "application/json" },
            })
          : listResponse([rows[0]]),
    });
    renderAt("/agent/queue?view=all", undefined, { role: "AGENT", api });
    await screen.findByRole("link", { name: "ACD-001" });

    fail = true;
    await act(() => vi.advanceTimersByTimeAsync(QUEUE_REFRESH_MS));
    await waitFor(() => expect(screen.getByTestId("queue-stale")).toBeInTheDocument());
    await act(() => vi.advanceTimersByTimeAsync(QUEUE_REFRESH_MS));
    await waitFor(() => expect(ticketCalls(calls)).toHaveLength(3));
    expect(screen.getAllByTestId("queue-stale")).toHaveLength(1);
    expect(screen.getByTestId("queue-stale")).toHaveTextContent(
      "The queue could not be refreshed.",
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "ACD-001" })).toBeInTheDocument();
    expect(cellOf("ACD-001")).toHaveTextContent("1h 25m");

    // The next good reload clears the notice.
    fail = false;
    await act(() => vi.advanceTimersByTimeAsync(QUEUE_REFRESH_MS));
    await waitFor(() => expect(screen.queryByTestId("queue-stale")).not.toBeInTheDocument());
  });

  it("has no axe violations with every state", async () => {
    const { api } = agentApi({ "GET /api/v1/agent/tickets": () => listResponse(rows) });
    renderAt("/agent/queue?view=all", undefined, { role: "AGENT", api });
    await screen.findByRole("link", { name: "ACD-001" });
    vi.useRealTimers();
    expect(await axeViolations()).toEqual([]);
  });
});
