# Résumé integration

## Where this goes

Under a **PROJECTS** section — a new section, since the current résumé has none. Place it after
PROFESSIONAL EXPERIENCE and before EDUCATION.

**Not** under Cognizant. SignalForge was not company work, and putting a personal project under an
employer is the kind of thing that ends an interview badly when someone asks a colleague about it.

---

## The bullets

> **SignalForge — AI-Assisted Production Incident & Reliability Platform**
> *Java 21, Spring Boot 3, Kafka, PostgreSQL, Redis, Next.js, OpenTelemetry* · [github.com/…]
>
> - Built an event-driven reliability platform that ingests service telemetry through Kafka into
>   PostgreSQL and automatically correlates failures with recent deployments, shared distributed
>   traces and error signatures, sustaining **64,000 events/sec at 23 ms p95** in local k6 load
>   tests.
>
> - Implemented idempotent Kafka consumers using producer-supplied event IDs, PostgreSQL
>   `ON CONFLICT … RETURNING` and retry classification with dead-letter routing, proving
>   duplicate-safe processing under concurrent redelivery through Testcontainers integration tests
>   against a real broker.
>
> - Optimized dashboard percentile queries by maintaining per-minute latency histograms on the write
>   path instead of computing `percentile_cont` over raw events, reducing median execution time from
>   **755 ms to 0.04 ms** on a 2.1M-row dataset with p95 accuracy within 0.1%.
>
> - Enforced multi-tenant isolation in depth — token-derived tenant scoping on every query, backed
>   by PostgreSQL row-level security with the application running as a non-owning database role —
>   and verified it with **125 automated tests** including cross-tenant and role-based negative
>   cases, covering PostgreSQL, Redis and Kafka through Testcontainers.

---

## Where each number comes from

Every figure traces to a command anyone can re-run. If an interviewer asks "how did you measure
that", the answer is a file path.

| Claim | Evidence |
|---|---|
| 64,000 events/sec, 23 ms p95 | `docs/benchmarks/README.md` §1 — measured 63,991 events/sec, p95 23.2 ms. Rounded **down**. |
| 755 ms → 0.04 ms | `docs/benchmarks/README.md` §2 — medians of 5 runs each (754.8 ms, 0.040 ms) on 2,154,690 rows |
| p95 accuracy within 0.1% | Same section — exact 449.0 ms vs interpolated 449.4 ms = 0.09% |
| 125 tests | `mvn verify` → 37 unit + 88 integration, 0 failures (plus 19 frontend Vitest tests) |
| Row-level security | `RowLevelSecurityIT` — raw SQL with no tenant predicate returns nothing; `V4__row_level_security.sql` |
| Idempotency under concurrent redelivery | `TelemetryIdempotencyIT.concurrentDuplicateDeliveryIsSafe` — 3 threads, same batch |
| Cross-tenant negatives | `TenantIsolationIT` (10 tests), `AuthorizationIT` (13 tests) |

---

## Claims deliberately NOT made

Listing these because the temptation to make them is exactly what an experienced reviewer probes.

**"Reduced MTTR by N%."** No such measurement exists. It would require a controlled A/B with real
engineers under real incidents; the project spec calls for one and it has not been run. Any MTTR
number here would be fabricated.

**"Processes N million events daily."** Nothing has run for a day. The benchmark is a 70-second
load test.

**"99.9% uptime" or any availability figure.** Never measured. There is no production deployment.

**"Improved performance by 60%."** The real number is far more impressive and, more importantly,
reproducible. Vague percentages invite "compared to what, measured how" — and having a real answer
is the entire advantage.

**"Exactly-once delivery."** The system is explicitly at-least-once with idempotent consumers.
Claiming exactly-once falls apart in about ninety seconds of questioning — see
[ADR-0007](adr/ADR-0007-at-least-once-delivery.md).

**Anything about the AI beyond what it does.** It summarises an evidence bundle from a local model
and discards ungrounded claims. It is not "ML-powered root cause analysis" — the correlation is
deterministic and the LLM is an optional narrator over it.

---

## Honest framing when asked

The bullets describe a personal project benchmarked on a laptop. If asked directly, the accurate
framing is:

> "It's a single-node local stack, benchmarked under contention on my own machine with the load
> generator competing for CPU. The absolute throughput would be different on real hardware — what I
> trust is the query optimisation ratio and the correctness properties, because those are
> hardware-independent. The interesting parts are the failure modes I found: the rollup aggregation
> was double-counting on Kafka redelivery, dead-letter routing was configured but structurally
> unreachable, my own detection-latency metric turned out to be measuring the wrong thing, and my
> first row-level-security migration applied cleanly while enforcing absolutely nothing, because
> Postgres exempts table owners from RLS and the app was connecting as the owner."

That last sentence is worth more than the throughput number. Finding your own bugs and saying so is
the signal.

---

## Skills section additions

The current TECHNICAL SKILLS section already lists Java, Spring Boot, Kafka, PostgreSQL, Redis,
React/Next.js, OpenTelemetry, Prometheus, Grafana, JUnit, Mockito and Testcontainers — all genuinely
exercised here, so no additions are strictly needed.

Two worth adding if there is room:

- **Data and Messaging:** append `Kafka consumer groups, idempotent processing, dead-letter queues`
- **Cloud, DevOps and Quality:** append `k6` (used for the load tests above)

Do not add "LLM integration" or similar unless prepared to discuss prompt grounding and
hallucination mitigation in detail — which, given `IncidentAiServiceTest`, is defensible.
