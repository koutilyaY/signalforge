# Roadmap

The 16-phase build is complete. What follows is the work that was **not** done, in the order it
should be picked up, plus the extensions that would matter next.

The gate is unchanged: **nothing here is done until its tests pass.** Anything moved out of this
file and into [STATUS.md](STATUS.md) must have a test behind it, not a description.

For what *is* built and verified, see [STATUS.md](STATUS.md).

---

## Outstanding from the original scope

### 1. Run the Playwright E2E specs

Three tests exist in `frontend/e2e/incident-flow.spec.ts` — organization bootstrap, sign-out
protection, and acknowledge→resolve — and they typecheck. They have **never been executed**: the
Playwright browser download did not resolve on the development machine
(`chromium_headless_shell-1155` missing while `chromium-1155` was present).

They are excluded from both the Vitest config and CI, so nothing currently reports them as passing.

```bash
cd frontend && npx playwright install chromium && npx playwright test
```

Expect them to need adjustment on first run. Once green, add a CI job that brings the stack up with
`docker compose up -d`, waits on `/actuator/health`, and runs them — a UI test that has never run
against a live backend is a guess.

### 2. Failure-injection tests

Failure behaviour is documented per dependency in [ARCHITECTURE.md](../ARCHITECTURE.md) and the
code paths are written for it — the rate limiter fails open, the producer fails fast at 5s — but no
test kills a container and asserts it.

Testcontainers can stop a container mid-test. The assertions that matter:

- **PostgreSQL down** → API returns 503 and readiness fails, rather than hanging until the
  connection pool times out.
- **Redis down** → ingestion still succeeds (rate limiting fails **open**), and login still works.
  This is the one most likely to have silently regressed, because the fail-open path only runs
  when Redis is genuinely unreachable.
- **Kafka down** → ingestion returns 503 within ~5s (`max.block.ms`), not a 60s thread hang.
- **Poison message** → lands in `<topic>-dlt`. The configuration is correct and the bug that made
  it unreachable was fixed, but "configured correctly" is weaker than "proven".

### 3. Reliability Analytics and Organization Settings pages

The two frontend pages from the original spec that were never built. The backend endpoints for
incident history and organization membership already exist.

### 4. Verify OpenTelemetry trace propagation across Kafka

Micrometer tracing and the OTLP exporter are wired and `observationEnabled` is set on the listener
container, but nothing asserts that one trace id spans API → Kafka → consumer → PostgreSQL. Until
a test captures spans and checks the trace id is continuous, distributed tracing is *configured*,
not *working*, and STATUS.md says exactly that.

### 5. The MTTR experiment — or a permanent decision not to claim it

The original spec described a controlled A/B experiment measuring incident resolution time with and
without the correlation output. It was never run, so **no MTTR number appears anywhere in this
repository**.

Running it properly needs a population of comparable simulated incidents and a resolution protocol
fixed in advance. A number produced by resolving a few incidents while knowing which arm is which
would be worse than no number, because it would look like evidence.

---

## Extensions worth building next

### Distribute the SSE hub

The hub is in-process: two API replicas each hold their own emitters, so an incident opened on
replica A never reaches a browser connected to replica B. The `notification-events` topic exists
for exactly this — each replica consumes it and fans out locally. See
[ADR-0009](adr/ADR-0009-sse-over-websockets.md).

### Lock the detection sweep

Dedup is enforced by `uq_incidents_active_fingerprint` rather than by assuming a single evaluator,
so two replicas are **correct** but wasteful — both do the full sweep and one loses the race. A
Redis lock with a lease shorter than the sweep interval would fix the waste without weakening the
database-level guarantee.

### Wire the `api` / `worker` profile split

`SignalForgeApplication` documents the split and today both run in one process. Separating them
lets ingestion scale independently of the consumers, which is the whole argument for the modular
monolith in [ADR-0001](adr/ADR-0001-modular-monolith.md).

### Make `findById` structurally unavailable

`JpaRepository.findById` is inherited and unscoped. Convention, tests and RLS all guard it, but an
ArchUnit rule forbidding its use outside the tenant-binding layer would make the guarantee
structural instead of remembered.
