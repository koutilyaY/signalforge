import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Tests for the API client's two load-bearing behaviours: the tenant id never
 * leaves the client, and a 401 is retried exactly once through a single shared
 * refresh.
 *
 * The module keeps `refreshInFlight` at module scope, so every test re-imports
 * it through `vi.resetModules()`. Sharing that state between tests would make
 * the single-flight assertions pass for the wrong reason.
 */

type FetchMock = ReturnType<typeof vi.fn>;

async function freshModule() {
  vi.resetModules();
  return import("./api");
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const USER = {
  id: "u1",
  email: "engineer@acme.test",
  fullName: "Engineer",
  role: "ENGINEER" as const,
  organizationId: "org-1",
  organizationName: "Acme",
  organizationSlug: "acme",
};

let fetchMock: FetchMock;

beforeEach(() => {
  window.sessionStorage.clear();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

describe("ApiError", () => {
  it("treats 401 and the token codes as re-authenticable, and nothing else", async () => {
    const { ApiError } = await freshModule();

    expect(new ApiError("ANY", "m", 401).isAuthFailure).toBe(true);
    expect(new ApiError("TOKEN_EXPIRED", "m", 400).isAuthFailure).toBe(true);
    expect(new ApiError("TOKEN_INVALID", "m", 400).isAuthFailure).toBe(true);

    // 403 is the important negative: the caller is authenticated fine, they
    // are just not allowed. Refreshing would be a pointless round trip and
    // would mask an authorization bug as a flaky login.
    expect(new ApiError("ACCESS_DENIED", "m", 403).isAuthFailure).toBe(false);
    expect(new ApiError("RESOURCE_NOT_FOUND", "m", 404).isAuthFailure).toBe(false);
  });
});

describe("request", () => {
  it("attaches the bearer token from session storage", async () => {
    const { api, tokens } = await freshModule();
    tokens.set("access-token", "refresh-token", USER);
    fetchMock.mockResolvedValue(jsonResponse(USER));

    await api.me();

    const headers = fetchMock.mock.calls[0][1].headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer access-token");
  });

  it("never sends an organization id, in the URL or the body", async () => {
    // Rule 1 of the client contract: the tenant is inside the signed token and
    // the server reads it from there. A client that can name a tenant is a
    // client that can be told to name someone else's.
    const { api, tokens } = await freshModule();
    tokens.set("access-token", "refresh-token", USER);
    // A fresh Response per call: a body can only be read once, so a shared
    // mockResolvedValue fails on the second request for reasons unrelated to
    // what this test is about.
    fetchMock.mockImplementation(async () => jsonResponse([]));

    await api.listServices("PRODUCTION");
    await api.listIncidents("OPEN");
    await api.createService({ name: "checkout", environment: "PRODUCTION" });
    await api.commentOnIncident("inc-1", "looking at it");

    for (const [url, init] of fetchMock.mock.calls) {
      expect(String(url).toLowerCase()).not.toContain("organization");
      expect(String(init?.body ?? "").toLowerCase()).not.toContain("organizationid");
    }
  });

  it("returns undefined for 204 rather than trying to parse an empty body", async () => {
    const { api } = await freshModule();
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(api.me()).resolves.toBeUndefined();
  });

  it("lifts the server's error envelope into ApiError, correlation id included", async () => {
    const { api, ApiError } = await freshModule();
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          code: "VALIDATION_FAILED",
          message: "name must not be blank",
          correlationId: "corr-123",
          violations: [{ field: "name", message: "must not be blank" }],
        },
        400,
      ),
    );

    const error = await api.me().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as InstanceType<typeof ApiError>;
    expect(apiError.code).toBe("VALIDATION_FAILED");
    expect(apiError.correlationId).toBe("corr-123");
    expect(apiError.violations).toHaveLength(1);
  });

  it("falls back to UNKNOWN when the error body is not JSON", async () => {
    // A 502 from a proxy returns HTML. Blowing up on JSON.parse here would
    // replace a useful status with a parse error.
    const { api } = await freshModule();
    fetchMock.mockResolvedValue(
      new Response("<html>Bad Gateway</html>", { status: 502, statusText: "Bad Gateway" }),
    );

    const error = (await api.me().catch((e: unknown) => e)) as { code: string; status: number };

    expect(error.code).toBe("UNKNOWN");
    expect(error.status).toBe(502);
  });
});

describe("token refresh", () => {
  it("refreshes once on 401 and replays the original request", async () => {
    const { api, tokens } = await freshModule();
    tokens.set("stale-token", "refresh-token", USER);

    fetchMock
      .mockResolvedValueOnce(jsonResponse({ code: "TOKEN_EXPIRED", message: "expired" }, 401))
      .mockResolvedValueOnce(
        jsonResponse({
          accessToken: "new-token",
          refreshToken: "new-refresh",
          tokenType: "Bearer",
          expiresInSeconds: 900,
          expiresAt: "2026-01-01T00:00:00Z",
          user: USER,
        }),
      )
      .mockResolvedValueOnce(jsonResponse(USER));

    await expect(api.me()).resolves.toMatchObject({ email: USER.email });

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(String(fetchMock.mock.calls[1][0])).toContain("/api/v1/auth/refresh");
    // The replay must carry the new token, not the one that just failed.
    const replayHeaders = fetchMock.mock.calls[2][1].headers as Headers;
    expect(replayHeaders.get("Authorization")).toBe("Bearer new-token");
  });

  it("gives up after one refresh instead of looping", async () => {
    // Without the retryOnAuthFailure flag this is an infinite recursion against
    // a server that is returning 401 for a reason refreshing cannot fix.
    const { api, tokens } = await freshModule();
    tokens.set("stale-token", "refresh-token", USER);

    fetchMock
      .mockResolvedValueOnce(jsonResponse({ code: "TOKEN_EXPIRED", message: "expired" }, 401))
      .mockResolvedValueOnce(
        jsonResponse({
          accessToken: "new-token",
          refreshToken: "new-refresh",
          tokenType: "Bearer",
          expiresInSeconds: 900,
          expiresAt: "2026-01-01T00:00:00Z",
          user: USER,
        }),
      )
      .mockResolvedValue(jsonResponse({ code: "TOKEN_EXPIRED", message: "still expired" }, 401));

    await expect(api.me()).rejects.toMatchObject({ code: "TOKEN_EXPIRED" });

    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("shares one refresh across concurrent 401s", async () => {
    // Six widgets on a dashboard all 401 at once. Six refreshes would rotate
    // the refresh token six times and invalidate each other's result.
    const { api, tokens } = await freshModule();
    tokens.set("stale-token", "refresh-token", USER);

    let refreshCalls = 0;
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).includes("/auth/refresh")) {
        refreshCalls += 1;
        // Resolve on a later tick so all three callers observe the same
        // in-flight promise; an immediate resolve would hide a missing guard.
        await new Promise((resolve) => setTimeout(resolve, 10));
        return jsonResponse({
          accessToken: "new-token",
          refreshToken: "new-refresh",
          tokenType: "Bearer",
          expiresInSeconds: 900,
          expiresAt: "2026-01-01T00:00:00Z",
          user: USER,
        });
      }
      return refreshCalls === 0
        ? jsonResponse({ code: "TOKEN_EXPIRED", message: "expired" }, 401)
        : jsonResponse(USER, 200);
    });

    await Promise.all([api.me(), api.me(), api.me()]);

    expect(refreshCalls).toBe(1);
  });

  it("clears stored tokens when the refresh itself is rejected", async () => {
    const { api, tokens } = await freshModule();
    tokens.set("stale-token", "dead-refresh", USER);

    fetchMock
      .mockResolvedValueOnce(jsonResponse({ code: "TOKEN_EXPIRED", message: "expired" }, 401))
      .mockResolvedValueOnce(jsonResponse({ code: "TOKEN_INVALID", message: "no" }, 401));

    await expect(api.me()).rejects.toBeDefined();

    // Leaving a dead token in storage means every later request pays two round
    // trips to discover it is still dead.
    expect(tokens.access).toBeNull();
    expect(tokens.refresh).toBeNull();
  });
});
