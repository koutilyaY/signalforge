import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useIncidentStream, type StreamEvent } from "./stream";
import { tokens } from "./api";

/**
 * The SSE reader, exercised where it is actually fragile: the boundary between
 * network chunks and protocol frames.
 *
 * A `ReadableStream` hands over whatever arrived, which has no relationship to
 * where SSE frames end. The buffering in `useIncidentStream` is what bridges
 * that, and it is the kind of code that looks obviously correct and silently
 * drops or duplicates an event when a frame straddles two chunks.
 *
 * `fetch` is replaced with a hand-rolled object rather than a real `Response`
 * because the hook only touches `ok`, `body` and `body.getReader()`, and
 * jsdom's Response does not accept a stream body.
 */

const USER = {
  id: "u1",
  email: "engineer@acme.test",
  fullName: "Engineer",
  role: "ENGINEER" as const,
  organizationId: "org-1",
  organizationName: "Acme",
  organizationSlug: "acme",
};

/** A stream whose chunks arrive when the test says so. */
function controllableBody() {
  const encoder = new TextEncoder();
  const queue: Uint8Array[] = [];
  let resolveNext: (() => void) | null = null;
  let closed = false;

  return {
    push(text: string) {
      queue.push(encoder.encode(text));
      resolveNext?.();
      resolveNext = null;
    },
    close() {
      closed = true;
      resolveNext?.();
      resolveNext = null;
    },
    getReader() {
      return {
        async read(): Promise<{ done: boolean; value?: Uint8Array }> {
          while (queue.length === 0 && !closed) {
            await new Promise<void>((resolve) => {
              resolveNext = resolve;
            });
          }
          if (queue.length > 0) return { done: false, value: queue.shift()! };
          return { done: true };
        },
      };
    },
  };
}

let fetchMock: ReturnType<typeof vi.fn>;
let body: ReturnType<typeof controllableBody>;

/** Lets the connect() effect run to the point where it has called fetch. */
async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 5));
  });
}

/**
 * Pushes a chunk and lets the reader loop drain it.
 *
 * Wrapped in `act` because the resulting `setState` happens on a microtask the
 * test did not start, which React otherwise warns about - correctly, since an
 * unwrapped update can be observed half-applied.
 */
async function emit(text: string) {
  await act(async () => {
    body.push(text);
    await new Promise((resolve) => setTimeout(resolve, 5));
  });
}

beforeEach(() => {
  window.sessionStorage.clear();
  tokens.set("access-token", "refresh-token", USER);

  body = controllableBody();
  fetchMock = vi.fn().mockResolvedValue({ ok: true, body });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  // Unmount before closing the body. Closing it first ends the reader loop,
  // which sets `connected: false` on a component the test has stopped driving -
  // a state update outside act(), and a reconnect timer that outlives the test.
  cleanup();
  body.close();
  vi.unstubAllGlobals();
});

describe("useIncidentStream", () => {
  it("sends the token in the Authorization header and never in the URL", async () => {
    // The whole reason this hook exists instead of EventSource. A token in a
    // query string is a token in proxy logs, browser history and Referer.
    renderHook(() => useIncidentStream());

    await settle();
    expect(fetchMock).toHaveBeenCalled();

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).not.toContain("access-token");
    expect(String(url)).not.toContain("token=");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer access-token");
  });

  it("does not connect at all when there is no token", async () => {
    window.sessionStorage.clear();

    renderHook(() => useIncidentStream());
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("delivers a complete frame", async () => {
    const received: StreamEvent[] = [];
    renderHook(() => useIncidentStream((e) => received.push(e)));
    await settle();
    expect(fetchMock).toHaveBeenCalled();

    await emit('event: incident.opened\ndata: {"reference":"INC-1"}\n\n');

    expect(received).toHaveLength(1);
    expect(received[0].name).toBe("incident.opened");
    expect(received[0].data).toEqual({ reference: "INC-1" });
  });

  it("reassembles a frame split across two chunks, delivering it exactly once", async () => {
    const received: StreamEvent[] = [];
    renderHook(() => useIncidentStream((e) => received.push(e)));
    await settle();
    expect(fetchMock).toHaveBeenCalled();

    // The split lands mid-JSON, which is where a naive per-chunk parse breaks.
    await emit('event: incident.opened\ndata: {"refere');
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(received).toHaveLength(0); // a partial frame must not be delivered

    await emit('nce":"INC-2"}\n\n');

    expect(received).toHaveLength(1);
    expect(received[0].data).toEqual({ reference: "INC-2" });
  });

  it("delivers both frames when two arrive in one chunk", async () => {
    const received: StreamEvent[] = [];
    renderHook(() => useIncidentStream((e) => received.push(e)));
    await settle();
    expect(fetchMock).toHaveBeenCalled();

    await emit('event: a\ndata: {"n":1}\n\nevent: b\ndata: {"n":2}\n\n');

    expect(received).toHaveLength(2);
    expect(received.map((e) => e.name)).toEqual(["a", "b"]);
  });

  it("ignores heartbeat comments", async () => {
    // The server sends `:keep-alive` to stop intermediaries closing an idle
    // connection. Surfacing those as events would make the UI think something
    // happened every 15 seconds.
    const received: StreamEvent[] = [];
    renderHook(() => useIncidentStream((e) => received.push(e)));
    await settle();
    expect(fetchMock).toHaveBeenCalled();

    await emit(":keep-alive\n\n");
    await emit('event: real\ndata: {"n":1}\n\n');

    expect(received).toHaveLength(1);
    expect(received[0].name).toBe("real");
  });

  it("drops a malformed frame without tearing down the stream", async () => {
    const received: StreamEvent[] = [];
    renderHook(() => useIncidentStream((e) => received.push(e)));
    await settle();
    expect(fetchMock).toHaveBeenCalled();

    await emit("event: broken\ndata: {not json}\n\n");
    await emit('event: fine\ndata: {"n":1}\n\n');

    expect(received).toHaveLength(1);
    expect(received[0].name).toBe("fine");
    // One bad frame must not cost the connection - a reconnect would be a
    // guaranteed loop against a server that keeps sending that frame.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("counts events and exposes the latest", async () => {
    const { result } = renderHook(() => useIncidentStream());
    await settle();
    expect(fetchMock).toHaveBeenCalled();

    await emit('event: one\ndata: {"n":1}\n\n');
    expect(result.current.eventCount).toBe(1);

    await emit('event: two\ndata: {"n":2}\n\n');
    expect(result.current.eventCount).toBe(2);
    expect(result.current.lastEvent?.name).toBe("two");
    expect(result.current.connected).toBe(true);
  });

  it("aborts the request when the component unmounts", async () => {
    const { unmount } = renderHook(() => useIncidentStream());
    await settle();
    expect(fetchMock).toHaveBeenCalled();

    const signal = fetchMock.mock.calls[0][1].signal as AbortSignal;
    expect(signal.aborted).toBe(false);

    unmount();

    // Without this the reader keeps the connection open after the dashboard is
    // gone, and the backoff loop reconnects it forever.
    expect(signal.aborted).toBe(true);
  });
});
