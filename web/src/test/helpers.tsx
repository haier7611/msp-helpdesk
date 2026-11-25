import { render } from "@testing-library/react";
import axe from "axe-core";
import type { ReactElement } from "react";
import { MemoryRouter } from "react-router";
import { vi } from "vitest";
import type { Role } from "../api/auth";
import { ToastProvider } from "../components/Toast";
import { DisplayNameProvider } from "../DisplayNameProvider";
import { AppRoutes } from "../routes";

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** Stubs fetch so the version footer gets an answer (or the given response). */
export function stubVersionFetch(
  response: () => Response | Promise<never> = () =>
    jsonResponse({ name: "Ticket System", version: "0.1.0", gitSha: "abc1234" }),
) {
  const fetchMock = vi.fn(() => Promise.resolve(response()));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

/** A synthetic signed-in user for the route guards (GET /api/v1/auth/me). */
export function meFor(role: Role, name = "Alex Tran", authLevel = "FULL") {
  return { id: 1, email: "alex@example.test", name, role, authLevel };
}

/** Extra API answers for a test: return a Response for the calls it handles, else undefined. */
export type ApiStub = (path: string, init?: RequestInit) => Response | undefined;

export type RenderOptions = {
  /** Who /api/v1/auth/me says is signed in; by default a customer under /portal, else an admin. */
  role?: Role;
  /** /api/v1/auth/me answers 401 (no session). */
  signedOut?: boolean;
  /** The signed-in user's display name (default "Alex Tran"). */
  name?: string;
  /** The session's sign-in stage from /api/v1/auth/me (default "FULL"). */
  authLevel?: "FULL" | "MFA_REQUIRED" | "MFA_ENROLMENT_REQUIRED";
  api?: ApiStub;
  /** Wrap in the app's DisplayNameProvider (ADR-045), which fetches GET /api/v1/display-name. */
  withDisplayName?: boolean;
};

/**
 * Renders the whole route tree at a path, as the browser would after a deep link. The route guards
 * get a signed-in user from /api/v1/auth/me; every other call gets `response` (the version
 * footer) unless `options.api` answers it.
 */
export function renderAt(
  path: string,
  response: () => Response | Promise<never> = () =>
    jsonResponse({ name: "Ticket System", version: "0.1.0", gitSha: "abc1234" }),
  options: RenderOptions = {},
) {
  const role = options.role ?? (path.startsWith("/portal") ? "CUSTOMER" : "ADMIN");
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    if (url === "/api/v1/auth/csrf") {
      document.cookie = "XSRF-TOKEN=csrf-test-token; path=/";
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    if (url === "/api/v1/auth/me") {
      return Promise.resolve(
        options.signedOut
          ? jsonResponse({ error: "unauthenticated", message: "Please sign in." }, 401)
          : jsonResponse(meFor(role, options.name, options.authLevel)),
      );
    }
    const answer = options.api?.(url, init);
    return answer ? Promise.resolve(answer) : Promise.resolve(response());
  });
  vi.stubGlobal("fetch", fetchMock);
  const routes = (
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        <AppRoutes />
      </ToastProvider>
    </MemoryRouter>
  );
  const view = render(
    options.withDisplayName ? <DisplayNameProvider>{routes}</DisplayNameProvider> : routes,
  );
  return { ...view, fetchMock };
}

export function renderWithToast(ui: ReactElement) {
  return render(<ToastProvider>{ui}</ToastProvider>);
}

/**
 * Runs axe-core on the rendered document. jsdom cannot compute colours, so the colour-contrast
 * rule is off here; contrast is covered by the token pairs (docs in styles/tokens.css) and the
 * manual check in the PR.
 */
export async function axeViolations(root: Element = document.body) {
  const result = await axe.run(root, {
    rules: { "color-contrast": { enabled: false } },
  });
  return result.violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.map((n) => n.target.join(" ")),
  }));
}
