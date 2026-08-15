"use client";

import { useEffect, useRef, useState } from "react";
import { API_BASE, tokens } from "./api";

/**
 * Live incident stream over SSE.
 *
 * The browser's native `EventSource` cannot set request headers, and the usual
 * workaround is to put the access token in a query string. That is refused here:
 * URLs land in proxy logs, browser history and `Referer` headers, so a token in
 * a query string is a token you have leaked.
 *
 * Instead this reads the `text/event-stream` response with `fetch()` and a
 * `ReadableStream` reader, which keeps the bearer token in an `Authorization`
 * header. The cost is that automatic reconnection - free with `EventSource` -
 * has to be implemented here, which is what the backoff loop below does.
 */

export interface StreamEvent {
  name: string;
  data: Record<string, unknown>;
}

interface StreamState {
  connected: boolean;
  lastEvent: StreamEvent | null;
  eventCount: number;
}

const INITIAL_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;

export function useIncidentStream(onEvent?: (event: StreamEvent) => void): StreamState {
  const [state, setState] = useState<StreamState>({
    connected: false,
    lastEvent: null,
    eventCount: 0,
  });

  // Held in a ref so changing the callback does not tear down and rebuild the
  // connection on every render.
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;

  useEffect(() => {
    const controller = new AbortController();
    let backoff = INITIAL_BACKOFF_MS;
    let stopped = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;

    async function connect() {
      const token = tokens.access;
      if (!token || stopped) return;

      try {
        const response = await fetch(`${API_BASE}/api/v1/stream`, {
          headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
          signal: controller.signal,
        });

        if (!response.ok || !response.body) {
          throw new Error(`stream failed: ${response.status}`);
        }

        setState((s) => ({ ...s, connected: true }));
        backoff = INITIAL_BACKOFF_MS; // a successful connect resets the backoff

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (!stopped) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });

          // SSE frames are separated by a blank line. Anything after the last
          // separator is a partial frame and stays in the buffer.
          const frames = buffer.split("\n\n");
          buffer = frames.pop() ?? "";

          for (const frame of frames) {
            const event = parseFrame(frame);
            if (!event) continue;
            setState((s) => ({
              connected: true,
              lastEvent: event,
              eventCount: s.eventCount + 1,
            }));
            handlerRef.current?.(event);
          }
        }
      } catch {
        // The error itself carries nothing actionable: any failure here means the
        // stream is gone, and the response is the same backoff either way.
        if (controller.signal.aborted || stopped) return;
      }

      if (stopped) return;

      setState((s) => ({ ...s, connected: false }));

      // Exponential backoff with a ceiling. Without the ceiling a long outage
      // ends with the client waiting hours to notice recovery; without backoff
      // at all, every dashboard hammers a struggling API.
      reconnectTimer = setTimeout(connect, backoff);
      backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

    void connect();

    return () => {
      stopped = true;
      controller.abort();
      if (reconnectTimer) clearTimeout(reconnectTimer);
    };
  }, []);

  return state;
}

/** Parses one SSE frame. Returns null for comments (`:keep-alive`) and junk. */
function parseFrame(frame: string): StreamEvent | null {
  let name = "message";
  const dataLines: string[] = [];

  for (const line of frame.split("\n")) {
    if (line.startsWith(":")) continue; // comment / heartbeat
    if (line.startsWith("event:")) {
      name = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trim());
    }
  }

  if (dataLines.length === 0) return null;

  try {
    return { name, data: JSON.parse(dataLines.join("\n")) };
  } catch {
    return null;
  }
}
