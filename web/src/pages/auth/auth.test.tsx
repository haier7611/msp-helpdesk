import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { tokenFromFragment } from "../../api/auth";
import { ToastProvider } from "../../components/Toast";
import { AppRoutes } from "../../routes";
import { axeViolations, jsonResponse } from "../../test/helpers";

type Handler = (body: unknown) => Response;

/** Stubs fetch by path; records each call's path, method and JSON body. */
function stubApi(handlers: Record<string, Handler>) {
  const calls: { path: string; method: string; body: unknown }[] = [];
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const path = typeof input === "string" ? input : input.toString();
    const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
    calls.push({ path, method: init?.method ?? "GET", body });
    if (path === "/api/v1/auth/me" && !handlers[path]) {
      // After a sign-in the route guard asks who is signed in: answer with that user.
      const signedIn = [...calls].reverse().find((c) => handlers[c.path] && c.method === "POST");
      const answer = signedIn && handlers[signedIn.path];
      if (signedIn && answer) {
        return Promise.resolve(answer(signedIn.body));
      }
    }
    if (path === "/api/v1/auth/csrf") {
      document.cookie = "XSRF-TOKEN=csrf-test-token; path=/";
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    const handler = handlers[path];
    if (handler) {
      return Promise.resolve(handler(body));
    }
    return Promise.resolve(
      jsonResponse({ name: "Ticket System", version: "0.1.0", gitSha: "abc1234" }),
    );
  });
  vi.stubGlobal("fetch", fetchMock);
  return { calls, fetchMock };
}

function renderPath(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        <AppRoutes />
      </ToastProvider>
    </MemoryRouter>,
  );
}

const customer = {
  id: 7,
  email: "sam@example.test",
  name: "Sam",
  role: "CUSTOMER",
  authLevel: "FULL",
};
const agent = { ...customer, role: "AGENT" };
const TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.unstubAllGlobals();
});

function type(label: string | RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

describe("tokenFromFragment", () => {
  it("reads the token and ignores anything else", () => {
    expect(tokenFromFragment("#token=abc_DEF-123")).toBe("abc_DEF-123");
    expect(tokenFromFragment("#x=1&token=abc")).toBe("abc");
    expect(tokenFromFragment("")).toBeNull();
    expect(tokenFromFragment("#token=<script>")).toBeNull();
  });
});

describe("sign-in page", () => {
  it("signs a customer in with the CSRF header and goes to the portal", async () => {
    const { calls } = stubApi({ "/api/v1/auth/sign-in": () => jsonResponse(customer) });
    renderPath("/sign-in");
    type("Email", " sam@example.test ");
    type("Password", "Violet-Tractor-Sundial-42");
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(
      await screen.findByRole("heading", { level: 1, name: "Raise a support ticket" }),
    ).toBeInTheDocument();
    const signIn = calls.find((c) => c.path === "/api/v1/auth/sign-in");
    expect(signIn?.body).toEqual({
      email: "sam@example.test",
      password: "Violet-Tractor-Sundial-42",
    });
    // The page also asks whether Microsoft sign-in is on (WP-10); the CSRF cookie still comes
    // before the first POST.
    expect(calls.filter((c) => c.path !== "/api/v1/auth/microsoft")[0]?.path).toBe(
      "/api/v1/auth/csrf",
    );
  });

  it("sends supporters to the queue", async () => {
    stubApi({ "/api/v1/auth/sign-in": () => jsonResponse(agent) });
    renderPath("/sign-in");
    type("Email", "a@example.test");
    type("Password", "Violet-Tractor-Sundial-42");
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "My tickets" }),
    ).toBeInTheDocument();
  });

  it("shows the server's message for wrong details and stays on the page", async () => {
    stubApi({
      "/api/v1/auth/sign-in": () =>
        jsonResponse(
          { error: "invalid_credentials", message: "The email or password is not right." },
          401,
        ),
    });
    renderPath("/sign-in");
    type("Email", "a@example.test");
    type("Password", "wrong-password-here");
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect((await screen.findAllByRole("alert"))[0]).toHaveTextContent(
      "The email or password is not right.",
    );
    expect(screen.getByRole("heading", { level: 1, name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toHaveValue("");
  });

  it("shows the wait time when throttled", async () => {
    stubApi({
      "/api/v1/auth/sign-in": () =>
        jsonResponse(
          {
            error: "too_many_requests",
            message: "Too many attempts. Please wait 4 seconds and try again.",
          },
          429,
        ),
    });
    renderPath("/sign-in");
    type("Email", "a@example.test");
    type("Password", "x");
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect((await screen.findAllByRole("alert"))[0]).toHaveTextContent("wait 4 seconds");
  });

  it("asks for the email before sending a sign-in link, then shows the same answer for anyone", async () => {
    const { calls } = stubApi({
      "/api/v1/auth/magic-link/request": () => jsonResponse({ message: "ok" }, 202),
    });
    renderPath("/sign-in");
    fireEvent.click(screen.getByRole("button", { name: "Email me a sign-in link" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter your email address first.");

    type("Email", "sam@example.test");
    fireEvent.click(screen.getByRole("button", { name: "Email me a sign-in link" }));
    expect(
      await screen.findByText(/If that email address can use a sign-in link/),
    ).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v1/auth/magic-link/request")?.body).toEqual({
      email: "sam@example.test",
    });
  });

  it("has no axe violations", async () => {
    stubApi({});
    renderPath("/sign-in");
    await screen.findByRole("heading", { level: 1, name: "Sign in" });
    expect(await axeViolations()).toEqual([]);
  });
});

describe("reset password pages", () => {
  it("forgot password always shows the same confirmation", async () => {
    stubApi({ "/api/v1/auth/password-reset/request": () => jsonResponse({ message: "ok" }, 202) });
    renderPath("/forgot-password");
    type("Email", "ghost@example.test");
    fireEvent.click(screen.getByRole("button", { name: "Send reset link" }));
    expect(await screen.findByText(/If that email address has an account/)).toBeInTheDocument();
  });

  it("checks length and confirmation before calling the server", async () => {
    const { calls } = stubApi({});
    renderPath(`/reset-password#token=${TOKEN}`);
    type("New password", "short");
    type("Repeat new password", "short");
    fireEvent.click(screen.getByRole("button", { name: "Change password" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Use at least 12 characters.");

    type("New password", "Copper-Meadow-Kettle-58");
    type("Repeat new password", "Copper-Meadow-Kettle-59");
    fireEvent.click(screen.getByRole("button", { name: "Change password" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("do not match");
    expect(calls.some((c) => c.path.includes("password-reset"))).toBe(false);
  });

  it("sends the token from the fragment and shows success", async () => {
    const { calls } = stubApi({
      "/api/v1/auth/password-reset/confirm": () => new Response(null, { status: 204 }),
    });
    renderPath(`/reset-password#token=${TOKEN}`);
    type("New password", "Copper-Meadow-Kettle-58");
    type("Repeat new password", "Copper-Meadow-Kettle-58");
    fireEvent.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByText(/Your password has been changed/)).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v1/auth/password-reset/confirm")?.body).toEqual({
      token: TOKEN,
      password: "Copper-Meadow-Kettle-58",
    });
  });

  it("shows the server's reason for a used or expired link", async () => {
    stubApi({
      "/api/v1/auth/password-reset/confirm": () =>
        jsonResponse({ error: "invalid_link", message: "This link is not valid any more." }, 400),
    });
    renderPath(`/reset-password#token=${TOKEN}`);
    type("New password", "Copper-Meadow-Kettle-58");
    type("Repeat new password", "Copper-Meadow-Kettle-58");
    fireEvent.click(screen.getByRole("button", { name: "Change password" }));
    expect((await screen.findAllByRole("alert"))[0]).toHaveTextContent(
      "This link is not valid any more.",
    );
  });

  it("explains a link without a token", async () => {
    stubApi({});
    renderPath("/reset-password");
    expect(await screen.findByRole("alert")).toHaveTextContent("This link is not complete.");
  });
});

describe("accept invitation page", () => {
  it("accepts an agent invitation with the password only - no code field (ADR-046)", async () => {
    const { calls } = stubApi({
      "/api/v1/auth/invitations/inspect": () =>
        jsonResponse({ email: "robin@example.test", name: "Robin" }),
      "/api/v1/auth/invitations/accept": () => new Response(null, { status: 204 }),
    });
    renderPath(`/accept-invitation#token=${TOKEN}`);
    expect(await screen.findByText("robin@example.test")).toBeInTheDocument();
    expect(screen.queryByLabelText(/code/i)).not.toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);

    type("Password", "Copper-Meadow-Kettle-58");
    type("Repeat password", "Copper-Meadow-Kettle-58");
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));
    expect(await screen.findByText(/Your account is ready/)).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v1/auth/invitations/accept")?.body).toEqual({
      token: TOKEN,
      password: "Copper-Meadow-Kettle-58",
    });
  });

  it("still works when an older server also answers codeRequired", async () => {
    stubApi({
      "/api/v1/auth/invitations/inspect": () =>
        jsonResponse({ email: "sam@example.test", name: "Sam", codeRequired: true }),
    });
    renderPath(`/accept-invitation#token=${TOKEN}`);
    await screen.findByText("sam@example.test");
    expect(screen.queryByLabelText(/code/i)).not.toBeInTheDocument();
  });

  it("shows the server's reason when accepting fails", async () => {
    stubApi({
      "/api/v1/auth/invitations/inspect": () =>
        jsonResponse({ email: "robin@example.test", name: "Robin" }),
      "/api/v1/auth/invitations/accept": () =>
        jsonResponse({ error: "invalid_link", message: "This link is not valid any more." }, 400),
    });
    renderPath(`/accept-invitation#token=${TOKEN}`);
    await screen.findByText("robin@example.test");
    type("Password", "Copper-Meadow-Kettle-58");
    type("Repeat password", "Copper-Meadow-Kettle-58");
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));
    expect((await screen.findAllByRole("alert"))[0]).toHaveTextContent(
      "This link is not valid any more.",
    );
  });

  it("shows an expired invitation clearly", async () => {
    stubApi({
      "/api/v1/auth/invitations/inspect": () =>
        jsonResponse({ error: "invalid_link", message: "This link is not valid any more." }, 400),
    });
    renderPath(`/accept-invitation#token=${TOKEN}`);
    expect(await screen.findByRole("alert")).toHaveTextContent("This link is not valid any more.");
  });
});

describe("magic link page", () => {
  it("spends the link only when the button is pressed", async () => {
    const { calls } = stubApi({
      "/api/v1/auth/magic-link/consume": () => jsonResponse(customer),
    });
    renderPath(`/magic-link#token=${TOKEN}`);
    const button = await screen.findByRole("button", { name: "Continue to sign in" });
    expect(calls.some((c) => c.path === "/api/v1/auth/magic-link/consume")).toBe(false);

    fireEvent.click(button);
    expect(
      await screen.findByRole("heading", { level: 1, name: "Raise a support ticket" }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(calls.find((c) => c.path === "/api/v1/auth/magic-link/consume")?.body).toEqual({
        token: TOKEN,
      }),
    );
  });
});

describe("setup page", () => {
  it("says so when setup is not available", async () => {
    stubApi({ "/api/v1/auth/setup": () => jsonResponse({ available: false }) });
    renderPath("/setup");
    expect(await screen.findByText(/Setup is not available/)).toBeInTheDocument();
  });

  it("creates the first administrator", async () => {
    const { calls } = stubApi({
      "/api/v1/auth/setup": (body) =>
        body === undefined
          ? jsonResponse({ available: true })
          : jsonResponse({ ...customer, role: "ADMIN" }, 201),
    });
    renderPath("/setup");
    await screen.findByLabelText("Setup token");
    type("Setup token", "setup-SYNTHETIC-token");
    type("Your name", "Alex Admin");
    type("Email", "alex@example.test");
    type("Password", "Copper-Meadow-Kettle-58");
    type("Repeat password", "Copper-Meadow-Kettle-58");
    fireEvent.click(screen.getByRole("button", { name: "Create administrator" }));
    expect(await screen.findByText(/The administrator account is ready/)).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST" && c.path === "/api/v1/auth/setup")?.body).toEqual(
      {
        token: "setup-SYNTHETIC-token",
        email: "alex@example.test",
        name: "Alex Admin",
        password: "Copper-Meadow-Kettle-58",
      },
    );
  });
});
