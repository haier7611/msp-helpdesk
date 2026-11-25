import { act, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchDisplayName, parseDisplayName } from "./api/displayName";
import { DEFAULT_DISPLAY_NAME, DISPLAY_NAME_STORAGE_KEY } from "./displayNameContext";
import { agentApi } from "./test/agentApi";
import { axeViolations, jsonResponse, renderAt, type ApiStub } from "./test/helpers";

// ADR-045: the top bar, the sign-in card and the tab title show the configured support name.
// Synthetic names only - never a real product or client name.

const CONFIGURED = "Example Helpdesk Team";

// The real loader, wrapped so a test can wait for the call to settle instead of sleeping.
vi.mock("./api/displayName", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api/displayName")>();
  return { ...actual, fetchDisplayName: vi.fn(actual.fetchDisplayName) };
});

/** Waits until the provider's display-name call has failed and React has applied the outcome. */
async function displayNameCallSettled(): Promise<void> {
  const loader = vi.mocked(fetchDisplayName);
  await waitFor(() => expect(loader).toHaveBeenCalled());
  const pending = loader.mock.results.map((r) => Promise.resolve(r.value).catch(() => undefined));
  await act(async () => {
    await Promise.all(pending);
  });
}

/** Answers only the display-name call; a Promise answer (pending or rejected) is passed through. */
function displayNameApi(answer: () => Response | Promise<never>): ApiStub {
  return (path) =>
    path === "/api/v1/display-name" ? (answer() as unknown as Response) : undefined;
}

const configured = displayNameApi(() => jsonResponse({ name: CONFIGURED }));
const serverDown = displayNameApi(() =>
  jsonResponse({ error: "server_error", message: "boom" }, 500),
);

function brand(): HTMLElement {
  // The brand line in the sign-in card sits next to the "TS" mark, above the h1.
  return screen.getByRole("heading", { level: 1 }).previousElementSibling as HTMLElement;
}

beforeEach(() => {
  // Each test waits only on its own calls (an earlier test leaves one pending for ever).
  vi.mocked(fetchDisplayName).mockClear();
  localStorage.removeItem(DISPLAY_NAME_STORAGE_KEY);
  document.title = "";
});

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  localStorage.removeItem(DISPLAY_NAME_STORAGE_KEY);
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe("support name (ADR-045)", () => {
  it("the sign-in card and tab title show the configured name", async () => {
    const { fetchMock } = renderAt("/sign-in", undefined, {
      signedOut: true,
      api: configured,
      withDisplayName: true,
    });
    await screen.findByRole("heading", { level: 1, name: "Sign in" });
    await waitFor(() => expect(within(brand()).getByText(CONFIGURED)).toBeInTheDocument());
    expect(document.title).toBe(CONFIGURED);
    expect(localStorage.getItem(DISPLAY_NAME_STORAGE_KEY)).toBe(CONFIGURED);
    const calls = fetchMock.mock.calls.filter(([url]) => url === "/api/v1/display-name");
    expect(calls).toHaveLength(1);
    expect(await axeViolations()).toEqual([]);
  });

  it("the agent top bar shows the configured name", async () => {
    const { api } = agentApi({
      "GET /api/v1/display-name": () => jsonResponse({ name: CONFIGURED }),
    });
    renderAt("/agent/queue", undefined, { role: "AGENT", api, withDisplayName: true });
    const home = await screen.findByRole("link", { name: CONFIGURED }, { timeout: 5000 });
    expect(home).toHaveAttribute("href", "/agent/queue");
    expect(screen.queryByText(DEFAULT_DISPLAY_NAME)).not.toBeInTheDocument();
  });

  it("falls back to the neutral name on an error, with no pop-up", async () => {
    renderAt("/sign-in", undefined, { signedOut: true, api: serverDown, withDisplayName: true });
    await screen.findByRole("heading", { level: 1, name: "Sign in" });
    // Wait for the failed call to settle, then check nothing changed.
    await displayNameCallSettled();
    expect(within(brand()).getByText(DEFAULT_DISPLAY_NAME)).toBeInTheDocument();
    expect(document.title).toBe(DEFAULT_DISPLAY_NAME);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText(/boom|went wrong/i)).not.toBeInTheDocument();
  });

  it("falls back to the neutral name when the network fails", async () => {
    renderAt("/sign-in", undefined, {
      signedOut: true,
      api: displayNameApi(() => Promise.reject(new TypeError("Failed to fetch"))),
      withDisplayName: true,
    });
    await screen.findByRole("heading", { level: 1, name: "Sign in" });
    await displayNameCallSettled();
    expect(within(brand()).getByText(DEFAULT_DISPLAY_NAME)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows the last known name straight away while it loads (no swap on return visits)", async () => {
    localStorage.setItem(DISPLAY_NAME_STORAGE_KEY, CONFIGURED);
    renderAt("/sign-in", undefined, {
      signedOut: true,
      api: displayNameApi(() => new Promise<never>(() => {})),
      withDisplayName: true,
    });
    await screen.findByRole("heading", { level: 1, name: "Sign in" });
    expect(within(brand()).getByText(CONFIGURED)).toBeInTheDocument();
    expect(document.title).toBe(CONFIGURED);
  });

  it("without the provider the neutral name shows", async () => {
    renderAt("/sign-in", undefined, { signedOut: true });
    await screen.findByRole("heading", { level: 1, name: "Sign in" });
    expect(within(brand()).getByText(DEFAULT_DISPLAY_NAME)).toBeInTheDocument();
  });

  it.each([[{}], [{ name: "" }], [{ name: "   " }], [{ name: 42 }], [{ name: "x".repeat(101) }]])(
    "rejects an unexpected answer %j",
    (body) => {
      expect(() => parseDisplayName(body)).toThrow();
    },
  );

  it("accepts and trims a name", () => {
    expect(parseDisplayName({ name: `  ${CONFIGURED} ` })).toBe(CONFIGURED);
  });
});
