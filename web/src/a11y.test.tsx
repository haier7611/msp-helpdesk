import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { axeViolations, renderAt } from "./test/helpers";

// axe-core on every demo page (canvas screens 3-5 and 7), plus sign-in and a placeholder. The
// WP-05 agent screens with real data run axe in pages/agent/*.test.tsx (queue, ticket with both
// composer tabs and the triage dialog, new ticket, Customers, Organisations).
const PAGES: [string, string][] = [
  ["/agent/queue", "My tickets"],
  ["/admin/supporters", "Agents"],
  ["/agent/settings/profile", "Profile and signature"],
  ["/portal/new-ticket", "Raise a support ticket"],
  ["/agent/reports", "Reports - SLA"],
  ["/sign-in", "Sign in"],
  ["/admin/audit-log", "Audit log"],
];

describe("accessibility (axe)", () => {
  it("the axe helper does report problems (guards against a silently passing check)", async () => {
    render(
      <main>
        <button type="button"></button>
        <input />
      </main>,
    );
    const ids = (await axeViolations()).map((v) => v.id);
    expect(ids).toEqual(expect.arrayContaining(["button-name", "label"]));
  });

  it.each(PAGES)(
    "%s has no axe violations",
    async (path, heading) => {
      renderAt(path);
      await screen.findByRole("heading", { level: 1, name: heading });
      // Optional probe: keep the old 1 s wait when the footer is absent.
      await screen.findByTestId("api-version", undefined, { timeout: 1000 }).catch(() => undefined);
      expect(await axeViolations()).toEqual([]);
    },
    15000,
  );

  it("the invite modal has no axe violations", async () => {
    renderAt("/admin/supporters");
    fireEvent.click(await screen.findByRole("button", { name: "Invite agent" }));
    await screen.findByRole("dialog", { name: "Invite agent" });
    expect(await axeViolations()).toEqual([]);
  });

  it("the signature plain-text preview has no axe violations", async () => {
    renderAt("/agent/settings/profile");
    fireEvent.click(await screen.findByRole("button", { name: "Plain text" }));
    expect(await axeViolations()).toEqual([]);
  });
});
