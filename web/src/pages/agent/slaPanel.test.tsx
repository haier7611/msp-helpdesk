import { act, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SlaPanelData, SlaPanelTarget } from "../../api/agent";
import { formatDateTime } from "../../format";
import { agentApi, detail } from "../../test/agentApi";
import { axeViolations, jsonResponse, renderAt } from "../../test/helpers";
import { SlaPanel } from "./SlaPanel";
import {
  formatClockStart,
  formatDuration,
  formatLeft,
  RESYNC_MS,
  RETRY_MS,
  TICK_MS,
} from "./slaPanelFormat";

// ADR-042 SLA panel. Synthetic data only.

const NOW = "2026-11-23T10:00:00Z";

function target(over: Partial<SlaPanelTarget> = {}): SlaPanelTarget {
  return {
    state: "RUNNING",
    targetMinutes: 480,
    dueAt: "2026-11-23T13:20:00Z",
    warnAt: "2026-11-23T15:00:00Z",
    remainingBusinessMinutes: 200,
    usedMinutes: 280,
    metAt: null,
    breachedByMinutes: null,
    nextClockStart: null,
    clockStopsAt: "2026-11-23T17:30:00Z",
    pausedReason: null,
    ...over,
  };
}

function panel(fr: Partial<SlaPanelTarget>, res: Partial<SlaPanelTarget> = {}): SlaPanelData {
  return {
    asOf: NOW,
    clock: "BUSINESS_HOURS",
    timezone: "Europe/London",
    priority: "P3",
    policyLabel: "P3 Medium - business hours (Europe/London)",
    pendingOnVendorPauses: false,
    businessDayMinutes: 540,
    firstResponse: target(fr),
    resolution: target({ targetMinutes: 1620, remainingBusinessMinutes: 1500, ...res }),
  };
}

const MET = { state: "MET" as const, remainingBusinessMinutes: null, clockStopsAt: null };

function show(p: SlaPanelData, load = vi.fn(() => Promise.resolve(p))) {
  const view = render(<SlaPanel ticketId={42} initial={p} load={load} />);
  return { ...view, load };
}

/** Lets a resolved load() land (fake timers do not run promise callbacks). */
async function flush() {
  for (let i = 0; i < 3; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
}

/** The big value of a row: the time left, or the final state (screen-reader text is separate). */
function visible(kind: "firstResponse" | "resolution") {
  return within(row(kind)).getByTestId(`sla-text-${kind}`).textContent ?? "";
}

/** The state of a row: its word (the icon is checked separately) and status. */
function state(kind: "firstResponse" | "resolution") {
  const el = within(row(kind)).getByTestId(`sla-state-${kind}`);
  return { el, word: el.textContent ?? "", status: el.getAttribute("data-status") };
}

/** Every state carries an icon and a word, never colour alone. */
function expectIconAndWord(kind: "firstResponse" | "resolution", word: string) {
  const s = state(kind);
  expect(s.el.querySelector("svg")).not.toBeNull();
  expect(s.word).toContain(word);
}

function row(kind: "firstResponse" | "resolution") {
  return screen.getByTestId(`sla-${kind}`);
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date(NOW));
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("formatDuration", () => {
  it("uses minutes, hours and days of the priority's clock", () => {
    expect(formatDuration(42, 540)).toBe("42m");
    expect(formatDuration(200, 540)).toBe("3h 20m");
    expect(formatDuration(120, 540)).toBe("2h");
    expect(formatDuration(2 * 540 + 180, 540)).toBe("2d 3h");
    expect(formatDuration(1440, 1440)).toBe("1d");
    expect(formatDuration(-5, 540)).toBe("0m");
  });
});

describe("formatLeft", () => {
  it("shows days, hours and minutes, leaving out zero parts", () => {
    expect(formatLeft(12, 540)).toBe("12m");
    expect(formatLeft(219, 540)).toBe("3h 39m");
    expect(formatLeft(540 + 219, 540)).toBe("1d 3h 39m");
    expect(formatLeft(540, 540)).toBe("1d");
    expect(formatLeft(120, 540)).toBe("2h");
    expect(formatLeft(0, 540)).toBe("0m");
  });
});

describe("SLA panel", () => {
  it("running: counts down once a minute and only then", async () => {
    show(panel({}, MET));
    expect(screen.getByRole("heading", { level: 2, name: "SLA" })).toBeInTheDocument();
    expect(row("firstResponse")).toHaveTextContent(/^First response3h 20mOn track/);
    expect(visible("firstResponse")).toBe("3h 20m");
    expectIconAndWord("firstResponse", "On track");
    expect(state("firstResponse").status).toBe("onTrack");
    expect(row("firstResponse")).toHaveTextContent("First response: On track, 3h 20m left");
    expect(visible("resolution")).toBe("Met");
    expect(row("firstResponse")).toHaveAttribute("data-tone", "ok");
    // Target, due time and policy live in the tooltip, not on screen.
    expect(within(row("firstResponse")).getByTestId("sla-text-firstResponse")).toHaveAttribute(
      "title",
      `Target 8h, due ${formatDateTime("2026-11-23T13:20:00Z")}. Policy: P3 Medium - business hours (Europe/London)`,
    );
    // Nothing else is visible: the label, the time and the state only.
    expect(
      row("firstResponse").querySelectorAll(":scope > span:not([class*=srOnly])"),
    ).toHaveLength(2);
    const shown = row("firstResponse").cloneNode(true) as HTMLElement;
    shown.querySelectorAll("[class*=srOnly]").forEach((n) => n.remove());
    expect(shown.textContent).toBe("First response3h 20mOn track");
    // A tick timer and the re-sync timer.
    expect(vi.getTimerCount()).toBe(2);
    act(() => {
      vi.advanceTimersByTime(TICK_MS);
    });
    expect(visible("firstResponse")).toBe("3h 19m");
    vi.useRealTimers();
    expect(await axeViolations()).toEqual([]);
  });

  it("turns amber from the warn point and red in the last 10%", () => {
    show(panel({ warnAt: "2026-11-23T09:00:00Z" }, { remainingBusinessMinutes: 100 }));
    expect(row("firstResponse")).toHaveAttribute("data-tone", "warn");
    expect(row("resolution")).toHaveAttribute("data-tone", "danger");
    // Both say "At risk" with the warning icon, not only a colour.
    expectIconAndWord("firstResponse", "At risk");
    expectIconAndWord("resolution", "At risk");
    expect(row("resolution")).toHaveTextContent("Resolution: At risk, 1h 40m left");
    expect(screen.getByTestId("sla-card")).toHaveAttribute("data-worst", "danger");
  });

  it("paused: waiting on client with the time left frozen, no ticking", async () => {
    show(
      panel(MET, {
        state: "PAUSED",
        pausedReason: "CLIENT",
        dueAt: null,
        clockStopsAt: null,
        remainingBusinessMinutes: 200,
      }),
    );
    expect(visible("resolution")).toBe("3h 20m");
    expectIconAndWord("resolution", "Paused");
    expect(row("resolution")).toHaveTextContent(
      "Resolution: Paused - waiting on client, 3h 20m left",
    );
    expect(within(row("resolution")).getByTestId("sla-text-resolution")).toHaveAttribute(
      "title",
      expect.stringContaining("no due time while paused"),
    );
    // Only the re-sync timer: nothing ticks while paused.
    expect(vi.getTimerCount()).toBe(1);
    act(() => {
      vi.advanceTimersByTime(TICK_MS * 3);
    });
    expect(visible("resolution")).toBe("3h 20m");
    vi.useRealTimers();
    expect(await axeViolations()).toEqual([]);
  });

  it("paused on vendor says so in the hidden text", () => {
    show(panel(MET, { state: "PAUSED", pausedReason: "VENDOR", clockStopsAt: null }));
    expect(visible("resolution")).toBe("2d 7h");
    expect(state("resolution").status).toBe("paused");
    expect(row("resolution")).toHaveTextContent("Paused - waiting on vendor, 2d 7h left");
  });

  it("outside business hours: clock stopped until the next opening, no ticking", async () => {
    const next = "2026-11-24T08:30:00Z";
    show(
      panel(
        { state: "STOPPED_OUTSIDE_HOURS", nextClockStart: next, clockStopsAt: null },
        { state: "STOPPED_OUTSIDE_HOURS", nextClockStart: next, clockStopsAt: null },
      ),
    );
    expect(visible("firstResponse")).toBe("3h 20m");
    expect(within(row("firstResponse")).getByTestId("sla-text-firstResponse")).toHaveAttribute(
      "title",
      expect.stringContaining(`clock stopped until ${formatClockStart(next, new Date(NOW))}`),
    );
    // Not ticking, but still on track by its time left; the words say the clock is stopped.
    expectIconAndWord("firstResponse", "On track");
    expect(row("firstResponse")).toHaveTextContent("clock stopped outside business hours");
    expect(formatClockStart(next, new Date(NOW))).toMatch(/^[A-Z][a-z]{2} \d{2}:\d{2}$/);
    expect(vi.getTimerCount()).toBe(1);
    act(() => {
      vi.advanceTimersByTime(TICK_MS * 2);
    });
    expect(visible("firstResponse")).toBe("3h 20m");
    vi.useRealTimers();
    expect(await axeViolations()).toEqual([]);
  });

  it("met: green tick with the time taken; no timers once everything is final", async () => {
    show(
      panel(
        { ...MET, usedMinutes: 42, metAt: "2026-11-23T09:42:00Z" },
        { ...MET, usedMinutes: 2 * 540 + 180, metAt: "2026-11-25T12:00:00Z" },
      ),
    );
    expect(visible("firstResponse")).toBe("Met");
    expect(row("firstResponse")).toHaveTextContent("Met - answered in 42m");
    expect(row("firstResponse")).toHaveAttribute("data-tone", "met");
    expectIconAndWord("firstResponse", "Met");
    expect(state("firstResponse").status).toBe("met");
    expect(row("resolution")).toHaveTextContent("Met - resolved in 2d 3h");
    expect(state("resolution").el).toHaveAttribute(
      "title",
      expect.stringContaining(`met ${formatDateTime("2026-11-25T12:00:00Z")}`),
    );
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
    expect(await axeViolations()).toEqual([]);
  });

  it("breached: red cross with how late, kept after resolution", async () => {
    show(
      panel(
        {
          state: "BREACHED",
          breachedByMinutes: 75,
          remainingBusinessMinutes: null,
          dueAt: "2026-11-23T09:00:00Z",
        },
        { ...MET, usedMinutes: 300 },
      ),
    );
    expect(visible("firstResponse")).toBe("1h 15m over");
    expectIconAndWord("firstResponse", "Breached");
    expect(state("firstResponse").status).toBe("breached");
    expect(row("firstResponse")).toHaveTextContent("Breached by 1h 15m");
    expect(row("firstResponse")).toHaveAttribute("data-tone", "breached");
    expect(state("firstResponse").el).toHaveAttribute(
      "title",
      expect.stringContaining(`was due ${formatDateTime("2026-11-23T09:00:00Z")}`),
    );
    vi.useRealTimers();
    expect(await axeViolations()).toEqual([]);
  });

  it("not applicable rows are hidden, and the section when both are", () => {
    const { unmount } = show(panel({ state: "NOT_APPLICABLE" }, MET));
    expect(screen.queryByTestId("sla-firstResponse")).not.toBeInTheDocument();
    expect(row("resolution")).toBeInTheDocument();
    unmount();
    show(panel({ state: "NOT_APPLICABLE" }, { state: "NOT_APPLICABLE" }));
    expect(screen.queryByRole("heading", { name: "SLA" })).not.toBeInTheDocument();
  });

  it("re-syncs from the server every few minutes", async () => {
    const fresh = panel({ remainingBusinessMinutes: 150 }, MET);
    const load = vi.fn(() => Promise.resolve({ ...fresh, asOf: "2026-11-23T10:05:00Z" }));
    show(panel({}, MET), load);
    act(() => {
      vi.advanceTimersByTime(RESYNC_MS);
    });
    await flush();
    expect(load).toHaveBeenCalledWith(42, expect.any(AbortSignal));
    expect(row("firstResponse")).toHaveTextContent("2h 30m");
  });

  it("asks the server again when a countdown reaches zero", async () => {
    const breached = panel(
      { state: "BREACHED", breachedByMinutes: 1, remainingBusinessMinutes: null },
      MET,
    );
    const load = vi.fn(() => Promise.resolve(breached));
    show(panel({ remainingBusinessMinutes: 1 }, MET), load);
    act(() => {
      vi.advanceTimersByTime(TICK_MS);
    });
    await flush();
    expect(load).toHaveBeenCalledTimes(1);
    expect(row("firstResponse")).toHaveTextContent("Breached by 1m");
  });

  it("stops counting when today's business hours end and asks the server", async () => {
    const load = vi.fn(() => new Promise<SlaPanelData | null>(() => undefined));
    show(panel({ clockStopsAt: "2026-11-23T10:02:00Z" }, MET), load);
    act(() => {
      vi.advanceTimersByTime(TICK_MS * 4);
    });
    // Counted 2 minutes, not 4 (and before the 5-minute re-sync timer).
    expect(row("firstResponse")).toHaveTextContent("3h 18m");
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("announces state changes politely, not every tick", async () => {
    const load = vi.fn(() =>
      Promise.resolve(
        panel({ state: "PAUSED", pausedReason: "CLIENT", clockStopsAt: null }, { ...MET }),
      ),
    );
    const { container } = show(panel({}, MET), load);
    const live = container.querySelector("[aria-live='polite']");
    expect(live).toHaveTextContent("First response: running. Resolution: met.");
    act(() => {
      vi.advanceTimersByTime(TICK_MS);
    });
    expect(live).toHaveTextContent("First response: running. Resolution: met.");
    expect(live?.textContent).not.toMatch(/\d+h|\d+m/);
    act(() => {
      vi.advanceTimersByTime(RESYNC_MS);
    });
    await flush();
    expect(live).toHaveTextContent("First response: paused. Resolution: met.");
  });

  it("a failed refresh is quiet but visible", async () => {
    const load = vi.fn(() => Promise.reject(new Error("offline")));
    show(panel({}, MET), load);
    act(() => {
      vi.advanceTimersByTime(RESYNC_MS);
    });
    await flush();
    expect(screen.getByText(/could not be refreshed/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("retries a failed refresh soon and clears the warning on the next success", async () => {
    const good = panel({ remainingBusinessMinutes: 150 }, MET);
    const load = vi
      .fn<(id: number, signal?: AbortSignal) => Promise<SlaPanelData | null>>()
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue(good);
    show(panel({}, MET), load);
    act(() => {
      vi.advanceTimersByTime(RESYNC_MS);
    });
    await flush();
    expect(screen.getByText(/could not be refreshed/)).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(RETRY_MS);
    });
    await flush();
    expect(load).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(/could not be refreshed/)).not.toBeInTheDocument();
    expect(row("firstResponse")).toHaveTextContent("2h 30m");
  });
});

describe("SLA panel on the ticket page", () => {
  it("sits in the ticket header next to the title, not in the Details sidebar", async () => {
    vi.useRealTimers();
    const slaPanel = panel({}, MET);
    const { api } = agentApi({
      "GET /api/v1/agent/tickets/42": () => jsonResponse({ ...detail, slaPanel }),
      "GET /api/v1/agent/tickets/42/sla": () => jsonResponse(slaPanel),
    });
    renderAt("/agent/tickets/ACD-042", undefined, { role: "AGENT", api });
    const title = await screen.findByRole("heading", { level: 1 }, { timeout: 5000 });
    const sla = await screen.findByRole("region", { name: "SLA" });
    // One card only, in the same header row as the title, before the conversation.
    expect(screen.getAllByRole("region", { name: "SLA" })).toHaveLength(1);
    const head = title.parentElement?.parentElement;
    expect(head).not.toBeNull();
    expect(head).toContainElement(sla);
    expect(title.parentElement).not.toContainElement(sla);
    expect(
      sla.compareDocumentPosition(screen.getByRole("region", { name: "Conversation" })) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    const details = screen.getByRole("complementary", { name: "Ticket details" });
    expect(within(details).queryByRole("region", { name: "SLA" })).not.toBeInTheDocument();
    expect(within(details).queryByTestId("sla-firstResponse")).not.toBeInTheDocument();
    expect(within(sla).getByTestId("sla-firstResponse")).toHaveTextContent("3h 20m");
    expect(within(sla).getByTestId("sla-state-firstResponse")).toHaveTextContent("On track");
    expect(await axeViolations()).toEqual([]);
  });

  it("shows nothing when the ticket has no panel (triage)", async () => {
    vi.useRealTimers();
    const { api } = agentApi();
    renderAt("/agent/tickets/ACD-042", undefined, { role: "AGENT", api });
    await screen.findByRole("heading", { level: 1 }, { timeout: 5000 });
    expect(screen.queryByRole("region", { name: "SLA" })).not.toBeInTheDocument();
  });
});
