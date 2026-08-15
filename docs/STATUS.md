# Build status

Last verified: **2026-08-15**, from a clean `mvn -B clean verify`.

| | Command | Result |
|---|---|---|
| Backend unit | `mvn test` | **37 pass**, 0 fail, 0 error |
| Backend integration (Testcontainers) | `mvn verify` | **88 pass**, 0 fail, 0 error |
| Frontend unit | `npm test` (vitest) | **19 pass** |
| Frontend types | `npx tsc --noEmit` | clean |
| Frontend lint | `npm run lint` | clean |
| Frontend build | `npm run build` | 9 routes compiled |

Benchmarks: measured and recorded in [benchmarks/README.md](benchmarks/README.md) — no estimated numbers.

Run the backend suite from a *clean* target. A stale `surefire-reports/` from an earlier run will
be read as a current result — that trap cost real debugging time here, and a week-old failure was
briefly mistaken for a live one.

This file draws the exact line between what is built and what is not. It exists because the
project's own quality bar forbids claiming functionality that is not implemented and tested — and
that bar has to apply to the README too.

## Verified working

Everything in this section has code, has tests, and was run.

### Phase 1 — Infrastructure
- `docker compose up` brings up PostgreSQL 16, Redis 7, Kafka 3.8 (KRaft, no ZooKeeper),
  Prometheus, Grafana. **Verified**: all three data services reported healthy; all five topics
  created by `kafka-init`.
- Multi-stage backend Dockerfile with a dependency-cache layer, non-root runtime user and
  container-aware heap sizing.
- Port block offset from the common defaults so the stack coexists with other local projects.
- `.env.example`, `.gitignore` (secrets excluded), MIT licence.

### Phase 2 — Identity, tenancy, authorization
- Flyway migrations V1 + V2: 20 tables, tenant-first composite indexes, CHECK constraints,
  partial unique indexes, `updated_at` triggers, append-only audit trigger.
  **Verified**: applied cleanly from an empty schema; Hibernate `ddl-auto: validate` passes, which
  caught one real type mismatch (`CHAR(64)` vs `VARCHAR(64)`).
- JWT auth (HS256), access + refresh tokens, refresh tokens rejected where access tokens are
  expected. Startup fails on a weak, missing or placeholder signing key.
- API-key auth for machine ingestion; SHA-256 with a throttled `last_used_at` write.
- RBAC: VIEWER / ENGINEER / ADMIN with cumulative authorities.
- Login throttling in Redis, failing open.
- Audit logging in `REQUIRES_NEW` transactions so records survive the rollback of a failed action.
- Standardised error envelope with correlation ids; no internals leaked.
- **13 authorization tests, 10 tenant-isolation tests**, all negatives.

### Phase 3 — Service registry
- CRUD with optimistic locking (409 on concurrent edit) and soft delete preserving incident history.
- Uniqueness enforced by database constraint, not read-then-write.

### Phase 4 — Telemetry ingestion
- Batch and single-event endpoints returning **202 Accepted**.
- Redis sliding-window rate limiter in Lua (atomic server-side), charged **per event** so batching
  cannot bypass quota. Fails open if Redis is down.
- Validation: tenant-owned service, clock-skew bounds, event-age bounds, intra-batch duplicates.
- **8 ingestion tests** including 429 behaviour and per-tenant quota isolation.

### Phase 5 — Kafka pipeline
- Producer: `acks=all`, `enable.idempotence=true`, bounded in-flight for ordering, lz4, and
  `max.block.ms=5s` so a broker outage is a fast 503 rather than a 60-second thread hang.
- Consumer: batch listener, manual offset commit, `ErrorHandlingDeserializer` wrapping
  `JsonDeserializer`, consumer-pinned target type (producer `__TypeId__` headers not trusted).
- Retry classification: transient failures retried with exponential backoff; permanent failures
  routed straight to `<topic>-dlt`.
- Topics declared via `KafkaAdmin.NewTopics` rather than left to broker auto-creation.
- Idempotent writes: chunked multi-row `INSERT … ON CONFLICT DO NOTHING RETURNING`, with rollups
  computed **only** from what `RETURNING` gave back.
- **7 tests** including three-thread concurrent redelivery and a real-broker end-to-end replay.

### Phase 6 — Incident detection
- Rules engine over `detection_rules`: ERROR_RATE, P95_LATENCY, SERVICE_DOWN, KAFKA_LAG,
  DATABASE_ERROR_SPIKE, CORRELATED_ERRORS.
- p95 interpolated from the rollup histogram the way `histogram_quantile` does; documented as an
  approximation, with the open-ended `+Inf` bucket falling back to the observed maximum rather than
  extrapolating a number nobody measured.
- A null rule threshold means "use the service's own SLA", so one organization-wide rule holds each
  service to its own target.
- `min_sample_size` floor on ratio rules only — one SERVICE_DOWN event is signal, one failed request
  in a quiet window is noise.
- Incident dedup enforced by the partial unique index, not by application code; the losing side of
  a concurrent race catches the constraint violation and attaches its alert to the winner.
- Cooldown after resolution suppresses flapping; suppressed alerts are still persisted.
- Severity escalated by service criticality.
- Default rule set seeded automatically for every new organization.
- **13 detection tests + 8 percentile unit tests.**

### Phase 7 — Incident lifecycle
- `OPEN → ACKNOWLEDGED → INVESTIGATING → MITIGATED → RESOLVED`, transitions declared in one place
  on the enum. RESOLVED is terminal; recurrence is a new incident.
- Timestamps set on first entry into a state, so flapping between states cannot corrupt the
  duration metrics.
- Optimistic locking (409 on stale version), timeline entries, comments, audit records.
- Detail endpoint returns timeline, alerts, affected services, comments and the set of legal next
  transitions in one round trip.
- **12 lifecycle tests.**

### Phase 8 — Deployment correlation and RCA
- Deployment tracking written by CI (`POST /api/v1/deployments`), environment validated against the
  registered service.
- Correlation measures from **effective time** (`COALESCE(completed_at, started_at)`), so a rollout
  that began an hour before but finished five minutes before is judged by the latter.
- Deterministic ranked factors: deployment proximity (linear decay over the lookback window,
  same-service bonus, failed-rollout bonus), shared-trace correlation, dominant error signature,
  latency regression against the pre-incident baseline.
- Weights are named constants, not magic numbers, so the rubric can be argued with in review.
- **12 correlation tests**, including the negatives: a deployment after the incident is ignored, one
  outside the lookback is ignored, evenly-spread errors produce no "dominant signature" claim, and
  an incident with no correlating evidence reports **zero** factors rather than a guess.

### Phase 9 — Frontend and live updates
- SSE hub, tenant-scoped: emitters are keyed by organization and no code path writes to all of them.
- The stream is consumed with `fetch()` + `ReadableStream`, not `EventSource`, so the bearer token
  stays in an `Authorization` header instead of a query string that lands in proxy logs and browser
  history. Reconnection with exponential backoff is hand-rolled as the cost of that choice.
- Next.js 15 / React 19 / TypeScript / Tailwind. Routes: login (+ first-run org bootstrap),
  dashboard, services, service detail, incidents, incident detail, deployments.
- Incident detail renders the timeline, ranked contributing factors with their evidence, correlated
  deployments, comments, and lifecycle buttons driven by the server's `allowedTransitions`.
- **6 stream-hub tests**, including cross-tenant broadcast isolation and dead-subscriber reaping.

### Phase 10 — Observability
- Grafana dashboard provisioned from `observability/grafana/dashboards/`: ingestion throughput and
  publish latency, consumer outcomes (including duplicates absorbed and poison messages), end-to-end
  pipeline lag, detection sweep duration, HTTP percentiles by endpoint, Hikari pool saturation, open
  SSE connections, AI outcomes and rejected claims.
- Custom meters registered across ingestion, consumer, detection, stream and AI paths.

### Phase 11 — AI incident assistant (optional)
- Local Ollama over plain `java.net.http.HttpClient`. **Every** failure path returns empty; the
  calling code has no reason to catch.
- Three structural guarantees: the AI is never on the detection path; the model sees only the
  rendered evidence bundle (no tools, no retrieval, no database); and every returned cause is
  ground-checked against that bundle, with unsupported ones discarded and counted.
- Short-circuits to "Insufficient evidence to determine root cause." **without calling the model**
  when correlation found nothing — asking an LLM to explain an incident with no evidence is exactly
  how you get a confident fabrication.
- **11 AI tests** with a mocked model, because the property under test is "can unchecked output
  reach a user", which is deterministic logic.

### Phase 12 — Row-level security and architecture enforcement
- **PostgreSQL RLS is implemented and enforcing** (`V4__row_level_security.sql`). Policies on 18
  tenant-scoped tables compare `organization_id` against a `signalforge.current_org` session
  setting, stamped on every connection by `TenantAwareDataSource` and on in-flight transactions by
  `TenantBinder`.
- **The runtime connects as `signalforge_app`, a non-owning non-superuser role.** This is not
  optional polish: PostgreSQL exempts superusers from RLS unconditionally, and the `postgres` image
  makes `POSTGRES_USER` a superuser — so the first version of this migration was silently
  decorative and `RowLevelSecurityIT` failed every assertion. Flyway still runs as the owner.
- Three narrow, schema-declared `SECURITY DEFINER` bypasses, and no others: login-by-email,
  API-key-by-hash, and an ids-only helper for the detection sweep. A test asserts no fourth bypass
  exists.
- **`RowLevelSecurityIT`** issues raw SQL with *no tenant predicate at all* and asserts the database
  returns nothing anyway — including reads by exact primary key, cross-tenant INSERT (blocked by
  `WITH CHECK`), UPDATE and DELETE.
- **11 ArchUnit rules** turning CONTRIBUTING.md conventions into failing tests.
- **11 ArchUnit rules** turning the conventions in CONTRIBUTING.md into failing tests: controllers
  never read ambient tenant state, the ingestion and detection paths structurally cannot depend on
  the AI module (ADR-0011 enforced, not just documented), entities live in domain packages, no field
  injection, no `System.out`, no legacy `java.util.Date` outside the JWT layer.
- Failure behaviour documented per dependency in ARCHITECTURE.md with runbooks for PostgreSQL,
  Redis and Kafka outages.

### Phase 13 — Frontend tests and CI
- **19 frontend unit tests** (Vitest + jsdom) over the two pieces of client code that are actually
  fragile: the API client and the SSE reader.
  - `lib/api.test.ts` (10) — the tenant id never leaves the client (asserted against both the URL
    and the request body on four different calls), 401 refreshes exactly once and replays with the
    *new* token, a second 401 gives up instead of recursing, concurrent 401s share one refresh, and
    403/404 are deliberately **not** treated as re-authenticable.
  - `lib/stream.test.ts` (9) — the token travels in a header and never in the URL, and frames are
    reassembled across chunk boundaries: a frame split mid-JSON is delivered exactly once, two
    frames in one chunk both arrive, heartbeat comments are ignored, and a malformed frame is
    dropped **without** tearing down the connection (a reconnect there is a guaranteed loop).
- GitHub Actions: backend (spotless, unit, Testcontainers integration, coverage), frontend
  (typecheck, lint, unit, production build), Docker image build, and dependency + secret scanning.

### Phase 14 — Benchmarks (all numbers measured)
- **Ingestion**: 63,991 events/sec, p95 23.2 ms, 0.00% errors over 4.5M events.
- **Query optimisation**: `percentile_cont` over 2.15M raw rows median **754.8 ms** → rollup
  histogram median **0.040 ms**; storage 453 MB → 40 kB.
- **Accuracy cost measured and reported**: p95 error 0.09%, p99 error 0.10%, **p50 error 29.2%**.
- **Read path**: incident list p95 7.4 ms, correlation endpoint p50 9.1 ms, 4,991 req/s.
- **Incident simulator**: full deployment-triggered outage driven through the real API;
  client-observed detection latency **10,849 ms** against a 15 s sweep interval.

### Phase 15-16 — Documentation and résumé
- ARCHITECTURE.md with Mermaid flow, sequence, state and ER diagrams; SECURITY.md with threat model
  and an explicit known-gaps list; CONTRIBUTING.md; three runbooks; seven ADRs.
- `docs/RESUME.md` — 4 bullets, every number traced to a re-runnable command, plus an explicit list
  of claims deliberately **not** made and why.
- `docs/INTERVIEW-GUIDE.md` — aggressive follow-ups per bullet, including a section of questions
  whose honest answer is "I didn't do that".

## Not built

No code exists for these. They are not stubbed, mocked or partially wired.

| Phase | Scope | State |
|---|---|---|
| 12 | Automated failure-injection tests (PostgreSQL / Redis / Kafka forcibly down) | Not built — failure behaviour is documented and hand-reasoned, not asserted by a test |
| 13 | Playwright E2E **written but never executed** | Three tests in `frontend/e2e/incident-flow.spec.ts` typecheck; the browser binary never resolved locally |
| 14 | MTTR controlled A/B experiment | Not run — benchmarks otherwise complete, and **no MTTR number is claimed anywhere** |
| 15 | Screenshots, Reliability Analytics + Organization Settings pages | Not built |

## Known gaps in what *is* built

Worth stating plainly rather than discovering in review:

1. **Row-level security is implemented, with one documented limit**: the runtime role has DML
   grants and is not a superuser, so it cannot bypass or disable the policies — but an attacker
   holding the *Flyway/owner* credentials could. Those are separate credentials and only used for
   migrations.
2. **`JpaRepository.findById` is inherited and unscoped.** Convention and tests guard it; an
   ArchUnit rule would make it structural.
3. **Dead-letter routing is configured and the deserializer path is fixed, but there is no test
   that asserts a poison message lands in the DLT.** The configuration is correct and the bug that
   made it unreachable was fixed, but "configured correctly" is weaker than "proven", and the
   difference matters.
4. **OpenTelemetry is configured but trace propagation across the Kafka boundary is unverified.**
   Micrometer tracing and the OTLP exporter are wired and `observationEnabled` is set on the
   listener container, but no test asserts that a single trace id actually spans
   API → Kafka → consumer → PostgreSQL. Until that is checked, treat distributed tracing as
   configured rather than working.
5. **The SSE hub is in-process.** Two API replicas each hold their own emitters, so an incident
   opened on replica A does not reach a browser connected to replica B. The `notification-events`
   topic exists for exactly this fix.
6. **Two frontend pages from the original spec are not built**: Reliability Analytics and
   Organization Settings.
7. **Playwright E2E tests are written but have never run.** Three tests covering organization
   bootstrap, sign-out protection and the acknowledge→resolve flow exist and typecheck, but the
   Playwright browser binary would not resolve on the development machine
   (`chromium_headless_shell-1155` missing while `chromium-1155` was present). **They are unverified
   — treat them as unproven until someone runs `npx playwright test` on a clean install.** They are
   deliberately excluded from both the Vitest config and CI, so nothing reports them as passing.
   The 19 Vitest tests cover `lib/`, not the React components or pages.
8. **The MTTR controlled experiment was never run.** No MTTR improvement is claimed anywhere.
9. **The detection scheduler assumes a single instance.** Dedup is enforced by a database unique
   index rather than by assuming one evaluator, so two replicas are correct but wasteful. A Redis
   distributed lock is the natural next step.
10. **The `api` / `worker` profile split is described in `SignalForgeApplication` but not yet
   wired**; today both run in one process.
11. **Failure injection is documented, not tested.** ARCHITECTURE.md states what happens when
   PostgreSQL, Redis or Kafka goes down, and the code paths are written for it (the rate limiter
   fails open, the producer fails fast at 5s), but no automated test kills a container and asserts
   the behaviour.
