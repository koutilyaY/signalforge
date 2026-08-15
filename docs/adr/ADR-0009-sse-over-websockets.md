# ADR-0009: Server-sent events, not WebSockets

**Status:** Accepted · **Date:** 2026-08-07

## Context

The incident list and dashboard must update without a manual refresh when an incident opens,
changes severity or resolves.

## Decision

Server-sent events over plain HTTP. The client reads the stream with `fetch()` and a
`ReadableStream` reader rather than the browser's native `EventSource`.

## Why SSE

**Every message travels server → client.** Actions go through the normal REST API, where they get
the same authorization, validation and audit treatment as everything else. WebSockets would buy
bidirectionality nobody needs, and cost a second authentication path and a second authorization
surface — one that is easy to secure less carefully than the REST API precisely because it looks
different.

SSE is also plain HTTP: it survives proxies and corporate middleboxes that mangle WebSocket
upgrades, and it needs no additional infrastructure.

## Why fetch() instead of EventSource

`EventSource` cannot set request headers. The near-universal workaround is putting the access token
in a query string — and a token in a query string is a token you have leaked, into proxy logs,
browser history and `Referer` headers.

Reading the stream with `fetch()` keeps the bearer token in an `Authorization` header. The cost is
that automatic reconnection, free with `EventSource`, must be implemented by hand; `lib/stream.ts`
does it with exponential backoff capped at 30 s.

## Consequences

**Positive** — no second auth path; no token in a URL; works through ordinary HTTP infrastructure;
tenant-scoped fan-out is trivial because emitters are keyed by organization.

**Negative** — reconnection logic is ours to maintain and test. **The hub is in-process**: two API
replicas each serve only their own subscribers, so an incident opened on replica A does not reach a
browser connected to replica B. The `notification-events` Kafka topic exists for this fix — every
replica consumes it and fans out locally — but that is not built.
