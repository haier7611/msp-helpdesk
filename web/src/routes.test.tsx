import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { apiFetch } from "./api/client";
import {
  ADMIN_PLACEHOLDERS,
  AGENT_PLACEHOLDERS,
  PORTAL_PLACEHOLDERS,
  SETTINGS_PLACEHOLDERS,
} from "./navigation";
import { jsonResponse, renderAt, stubVersionFetch } from "./test/helpers";

// Pages on the real API since WP-11a (the portal since WP-06, Reports since reports-real-data): no
// Demo data banner.
const API_ROUTES: [string, string][] = [
  ["/portal/new-ticket", "Raise a support ticket"],
  ["/portal/account", "My account"],
  ["/agent/settings/profile", "Profile and signature"],
  ["/agent/settings/preferences", "Preferences"],
  ["/agent/settings/notifications", "Notifications"],
  ["/agent/settings/out-of-office", "Out of office"],
  ["/admin/supporters", "Agents"],
  ["/agent/reports", "Reports - SLA"],
];

const PLACEHOLDER_ROUTES: [string, string][] = [
  ...AGENT_PLACEHOLDERS.map((p): [string, string] => [`/agent/${p.path}`, p.title]),
  ...SETTINGS_PLACEHOLDERS.map((p): [string, string] => [`/agent/settings/${p.path}`, p.title]),
  ...ADMIN_PLACEHOLDERS.map((p): [string, string] => [`/admin/${p.path}`, p.title]),
  ...PORTAL_PLACEHOLDERS.map((p): [string, string] => [`/portal/${p.path}`, p.title]),
];

describe("routes", () => {
  it.each(API_ROUTES)("%s renders its page without the demo banner", async (path, heading) => {
    renderAt(path);
    expect(await screen.findByRole("heading", { level: 1, name: heading })).toBeInTheDocument();
    expect(screen.queryByText("Demo data")).toBeNull();
  });

  it.each(PLACEHOLDER_ROUTES)("%s renders a placeholder titled %s", async (path, heading) => {
    renderAt(path);
    expect(await screen.findByRole("heading", { level: 1, name: heading })).toBeInTheDocument();
    expect(
      screen.getByText("This screen is not ready yet. It arrives in a later update."),
    ).toBeInTheDocument();
  });

  it.each([
    ["/", "My tickets"],
    ["/agent", "My tickets"],
    ["/agent/settings", "Profile and signature"],
    ["/admin", "Agents"],
    ["/portal", "Raise a support ticket"],
  ])("%s redirects to its default page", async (path, heading) => {
    renderAt(path);
    expect(await screen.findByRole("heading", { level: 1, name: heading })).toBeInTheDocument();
  });

  it("/sign-in renders the sign-in form without the app chrome", async () => {
    renderAt("/sign-in");
    expect(await screen.findByRole("heading", { level: 1, name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByLabelText("Email")).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Main" })).not.toBeInTheDocument();
    // Microsoft sign-in is off by default (ADR-025), so the shell does not offer it.
    expect(screen.queryByRole("button", { name: /Microsoft/ })).not.toBeInTheDocument();
  });

  it("an unknown path shows Page not found", async () => {
    renderAt("/nowhere/at-all");
    expect(
      await screen.findByRole("heading", { level: 1, name: "Page not found" }),
    ).toBeInTheDocument();
  });

  it("the agent and admin layouts mark the current top-bar link", async () => {
    renderAt("/admin/supporters");
    const main = await screen.findByRole("navigation", { name: "Main" });
    expect(within(main).getByRole("link", { name: "Admin" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    const sections = screen.getByRole("navigation", { name: "Admin sections" });
    expect(within(sections).getByRole("link", { name: "Agents" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  it("the portal has no supporter navigation (customers never see /agent links)", async () => {
    renderAt("/portal/new-ticket");
    await screen.findByRole("heading", { level: 1, name: "Raise a support ticket" });
    expect(screen.queryByRole("navigation", { name: "Main" })).not.toBeInTheDocument();
    for (const link of screen.getAllByRole("link")) {
      expect(link.getAttribute("href")).not.toMatch(/^\/(agent|admin)/);
    }
  });

  // WP-05: the queue, ticket, new ticket, Customers and Organisations screens use real data;
  // their tests (empty state, not found, organisation filter) are in pages/agent/*.test.tsx.

  it("shows the API version in the footer", async () => {
    renderAt("/agent/queue");
    expect(await screen.findByTestId("api-version")).toHaveTextContent("0.1.0");
    expect(screen.getByText("abc1234")).toBeInTheDocument();
  });

  it("shows a calm footer message when the API cannot be reached", async () => {
    renderAt("/agent/queue", () => Promise.reject(new TypeError("Failed to fetch")));
    expect(await screen.findByText("Cannot reach the API right now.")).toBeInTheDocument();
    expect(screen.queryByTestId("api-version")).not.toBeInTheDocument();
  });

  it("the account menu opens, links to My settings and closes on Escape", async () => {
    renderAt("/agent/queue");
    const button = await screen.findByRole("button", { name: "Account menu for Alex Tran" });
    expect(button).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("link", { name: "My settings" })).toHaveAttribute(
      "href",
      "/agent/settings",
    );
    fireEvent.keyDown(screen.getByRole("link", { name: "My settings" }), { key: "Escape" });
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(button).toHaveFocus();
  });

  it("a 401 from any API call sends the user to /sign-in", async () => {
    renderAt("/agent/queue");
    await screen.findByRole("heading", { level: 1, name: "My tickets" });
    stubVersionFetch(() => jsonResponse({ status: 401 }, 401));

    await act(async () => {
      await apiFetch("/api/v1/agent/anything", (b) => b).catch(() => undefined);
    });

    await waitFor(() =>
      expect(screen.getByRole("heading", { level: 1, name: "Sign in" })).toBeInTheDocument(),
    );
  });
});
