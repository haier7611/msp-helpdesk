import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  CSRF_HEADER,
  MESSAGES,
  apiFetch,
  csrfToken,
  friendlyMessage,
  readCookie,
  setUnauthorisedHandler,
} from "./client";
import { fetchVersion } from "./version";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function stubFetch(response: Response | Error) {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    void input;
    void init;
    return response instanceof Error ? Promise.reject(response) : Promise.resolve(response);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function headersOf(fetchMock: ReturnType<typeof stubFetch>): Record<string, string> {
  const init = fetchMock.mock.calls[0]?.[1];
  return (init?.headers ?? {}) as Record<string, string>;
}

const identity = (body: unknown) => body;

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  setUnauthorisedHandler(null);
});

describe("readCookie", () => {
  it("finds a cookie among several and decodes it", () => {
    expect(readCookie("XSRF-TOKEN", "a=1; XSRF-TOKEN=ab%2Fcd; b=2")).toBe("ab/cd");
  });

  it("returns undefined when the cookie is missing or the string is empty", () => {
    expect(readCookie("XSRF-TOKEN", "a=1; b=2")).toBeUndefined();
    expect(readCookie("XSRF-TOKEN", "")).toBeUndefined();
  });

  it("does not match a cookie whose name only ends with the wanted name", () => {
    expect(readCookie("XSRF-TOKEN", "OTHER-XSRF-TOKEN=nope")).toBeUndefined();
  });

  it("keeps a malformed escape as it is instead of throwing", () => {
    expect(readCookie("XSRF-TOKEN", "XSRF-TOKEN=%E0%A4%A")).toBe("%E0%A4%A");
  });
});

describe("apiFetch", () => {
  it("sends the CSRF cookie back as the X-XSRF-TOKEN header on a POST", async () => {
    document.cookie = "XSRF-TOKEN=token-123; path=/";
    const fetchMock = stubFetch(json({ ok: true }));

    await apiFetch("/api/v1/agent/example", identity, { method: "POST", body: { a: 1 } });

    const headers = headersOf(fetchMock);
    expect(headers[CSRF_HEADER]).toBe("token-123");
    expect(headers["Content-Type"]).toBe("application/json");
    expect(fetchMock.mock.calls[0]?.[1]?.credentials).toBe("same-origin");
    expect(fetchMock.mock.calls[0]?.[1]?.body).toBe('{"a":1}');
  });

  it("does not send the CSRF header on a GET", async () => {
    document.cookie = "XSRF-TOKEN=token-123; path=/";
    const fetchMock = stubFetch(json({ ok: true }));

    await apiFetch("/api/v1/version", identity);

    expect(headersOf(fetchMock)[CSRF_HEADER]).toBeUndefined();
  });

  it("sends no CSRF header when there is no cookie yet", async () => {
    const fetchMock = stubFetch(json({ ok: true }));

    await apiFetch("/api/v1/agent/example", identity, { method: "DELETE" });

    expect(headersOf(fetchMock)[CSRF_HEADER]).toBeUndefined();
  });

  it("calls the unauthorised handler and throws ApiError(401) on a 401", async () => {
    const handler = vi.fn();
    setUnauthorisedHandler(handler);
    stubFetch(json({ status: 401 }, 401));

    const error = await apiFetch("/api/v1/agent/example", identity).catch((e: unknown) => e);

    expect(handler).toHaveBeenCalledTimes(1);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
  });

  it("does not call the unauthorised handler for a 403 or 500", async () => {
    const handler = vi.fn();
    setUnauthorisedHandler(handler);
    stubFetch(json({}, 403));
    await expect(apiFetch("/api/v1/admin/x", identity)).rejects.toMatchObject({ status: 403 });
    stubFetch(json({}, 500));
    await expect(apiFetch("/api/v1/admin/x", identity)).rejects.toMatchObject({ status: 500 });
    expect(handler).not.toHaveBeenCalled();
  });

  it("an undo from setUnauthorisedHandler removes only its own handler", async () => {
    const first = vi.fn();
    const undoFirst = setUnauthorisedHandler(first);
    const second = vi.fn();
    setUnauthorisedHandler(second);
    undoFirst();
    stubFetch(json({}, 401));

    await apiFetch("/api/v1/x", identity).catch(() => undefined);

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it("refuses a path outside /api/ so a full URL can never get the CSRF token", async () => {
    const fetchMock = stubFetch(json({}));
    await expect(
      apiFetch("https://evil.example/api/x", identity, { method: "POST" }),
    ).rejects.toThrow("API paths must start with /api/");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("turns a network failure into ApiError(0) with the connection message", async () => {
    stubFetch(new TypeError("Failed to fetch"));
    const error = (await apiFetch("/api/v1/version", identity).catch(
      (e: unknown) => e,
    )) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(0);
    expect(error.message).toBe("Connection lost. Check your internet and try again.");
  });

  it("passes an abort through unchanged (never an error to show)", async () => {
    const abort = Object.assign(new Error("The operation was aborted."), { name: "AbortError" });
    stubFetch(abort);
    await expect(apiFetch("/api/v1/version", identity)).rejects.toBe(abort);
  });

  it("handles 204 No Content without reading a body", async () => {
    stubFetch(new Response(null, { status: 204 }));
    await expect(apiFetch("/api/v1/x", () => "empty", { method: "PUT" })).resolves.toBe("empty");
  });
});

describe("fetchVersion", () => {
  it("returns the version info", async () => {
    stubFetch(json({ name: "Ticket System", version: "0.1.0", gitSha: "abc1234" }));
    await expect(fetchVersion()).resolves.toEqual({
      name: "Ticket System",
      version: "0.1.0",
      gitSha: "abc1234",
    });
  });

  it("rejects a response with the wrong shape", async () => {
    stubFetch(json({ version: 1 }));
    await expect(fetchVersion()).rejects.toThrow("unexpected shape");
  });
});

describe("WP-02b additions", () => {
  it("prefers the __Host- CSRF cookie used behind HTTPS", () => {
    expect(csrfToken("XSRF-TOKEN=plain; __Host-XSRF-TOKEN=secure")).toBe("secure");
    expect(csrfToken("XSRF-TOKEN=plain")).toBe("plain");
    expect(csrfToken("")).toBeUndefined();
  });

  it("asks the server for a CSRF cookie before the first POST of a visit", async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      void init;
      if (input === "/api/v1/auth/csrf") {
        document.cookie = "XSRF-TOKEN=fresh-token; path=/";
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      return Promise.resolve(json({ ok: true }));
    });
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/api/v1/auth/sign-out", identity, { method: "POST", body: {} });

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/v1/auth/csrf");
    const headers = (fetchMock.mock.calls[1]?.[1]?.headers ?? {}) as Record<string, string>;
    expect(headers[CSRF_HEADER]).toBe("fresh-token");
  });

  it("carries the server's error code and message", async () => {
    stubFetch(json({ error: "weak_password", message: "Use at least 12 characters." }, 400));
    const error = (await apiFetch("/api/v1/x", identity).catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe("weak_password");
    expect(error.message).toBe("Use at least 12 characters.");
  });

  it("a failed sign-in (invalid_credentials) does not trigger the sign-in redirect", async () => {
    const handler = vi.fn();
    setUnauthorisedHandler(handler);
    stubFetch(json({ error: "invalid_credentials", message: "Not right." }, 401));
    await apiFetch("/api/v1/auth/sign-in", identity).catch(() => undefined);
    expect(handler).not.toHaveBeenCalled();
  });
});

describe("error messages (no silent failures)", () => {
  async function messageFor(status: number, body: unknown): Promise<string> {
    stubFetch(
      typeof body === "string"
        ? new Response(body, { status, headers: { "Content-Type": "text/html" } })
        : json(body, status),
    );
    const error = (await apiFetch("/api/v1/x", identity, { method: "POST" }).catch(
      (e: unknown) => e,
    )) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    return error.message;
  }

  it.each([
    [403, { error: "forbidden", message: "You do not have access to this." }, MESSAGES.forbidden],
    [403, {}, "You don't have permission to do that."],
    [404, { error: "not_found", message: "We could not find that." }, MESSAGES.notFound],
    [404, {}, "That item no longer exists or you can't see it."],
    [
      409,
      { error: "name_taken", message: "Another organisation already has this name." },
      "Another organisation already has this name.",
    ],
    [409, {}, MESSAGES.conflict],
    [
      413,
      { error: "file_too_large", message: "Files can be at most 20 MB each." },
      "Files can be at most 20 MB each.",
    ],
    [413, "<html>Request Entity Too Large</html>", MESSAGES.tooLarge],
    [400, { error: "subject_required", message: "Enter a subject." }, "Enter a subject."],
    [422, { error: "invalid_request", message: "Choose a file." }, "Choose a file."],
    [400, {}, "Some of the details are missing or not valid."],
    [429, { error: "too_many_requests", message: "Wait 30 seconds." }, "Wait 30 seconds."],
    [429, "<html>Too Many Requests</html>", "Too many attempts - please wait a moment."],
    [
      500,
      { error: "server_error", message: "NullPointerException at Foo.java:42" },
      "Something went wrong on our side. Please try again.",
    ],
    [502, "<html>Bad Gateway</html>", MESSAGES.server],
    [503, {}, MESSAGES.server],
  ])("%i %j -> %s", async (status, body, expected) => {
    expect(await messageFor(status, body)).toBe(expected);
  });

  it("keeps the server's text for a specific 403 refusal code (step-up, two-step)", async () => {
    expect(
      await messageFor(403, {
        error: "mfa_not_enrolled",
        message: "Two-step sign-in is not set up.",
      }),
    ).toBe("Two-step sign-in is not set up.");
  });

  it("never shows a body that is not the known {error, message} shape", async () => {
    // A framework error page: "error" is a reason phrase and "trace" holds a stack trace.
    expect(
      await messageFor(400, { message: "java.lang.IllegalStateException", trace: "at com.x.Y" }),
    ).toBe(MESSAGES.invalid);
    expect(
      await messageFor(409, {
        timestamp: "now",
        status: 409,
        error: "Conflict",
        path: "/api/v1/x",
      }),
    ).toBe(MESSAGES.conflict);
  });

  it("friendlyMessage covers the network case and unknown 4xx", () => {
    expect(friendlyMessage(0)).toBe(MESSAGES.network);
    expect(friendlyMessage(418)).toBe(MESSAGES.generic);
    expect(friendlyMessage(418, "Short and stout.")).toBe("Short and stout.");
  });

  it("a 401 keeps the sign-in redirect and the sign-in form's message", async () => {
    const handler = vi.fn();
    setUnauthorisedHandler(handler);
    expect(await messageFor(401, { error: "unauthenticated", message: "Please sign in." })).toBe(
      "Please sign in.",
    );
    expect(handler).toHaveBeenCalledTimes(1);
  });
});
