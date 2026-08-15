# Interview preparation guide

Aggressive follow-ups for every résumé bullet, with answers grounded **only** in what is actually
implemented. Where the honest answer is "I didn't do that", it says so — a confident wrong answer
is far more damaging than a scoped admission.

---

## Architecture

**Why a modular monolith and not microservices?**

Seven JVMs at ~400 MB each is 2.8 GB before serving a request, on a machine with 8 GB allocated to
Docker. Microservices buy independent deployment and independent scaling; I had one developer, one
release cadence, and exactly one component with a different scaling profile — telemetry ingestion —
which I addressed with the `api`/`worker` profile split instead. The module boundaries are the
valuable part and they survive either way; if ingestion ever needs to be its own deployable, it is
extracting a package with an already-explicit interface rather than untangling a ball of mud.

**What would make you split it?**

More than one team needing an independent release cadence, or the detection engine developing a
materially different scaling profile from ingestion. Not traffic alone — I'd scale the `worker`
fleet first.

**You said two runtime roles. Is that actually wired?**

Partially, and this is a gap. The profiles are described and the code is structured for it, but
today both run in one process. Splitting them is a configuration change, not a refactor, because
the Kafka listeners and the scheduler are already isolated behind `@ConditionalOnProperty` and
profile guards.

---

## Kafka

**What delivery semantics?**

At-least-once. I do not claim exactly-once, and I'd push back on anyone who does for a
Kafka→PostgreSQL flow without a distributed transaction or an outbox. Kafka's exactly-once applies
to Kafka-to-Kafka within a transaction; this pipeline terminates in PostgreSQL.

**Then how do you avoid double-counting?**

Idempotent writes. The producer supplies the `eventId`, and `telemetry_events` has a unique
constraint on `(organization_id, event_id)`. The insert is `ON CONFLICT DO NOTHING RETURNING
event_id`. Redelivery writes nothing.

**Why the `RETURNING`? Isn't `DO NOTHING` sufficient?**

That is exactly the bug I shipped and then caught. `DO NOTHING` suppressed the duplicate *rows*
correctly, but I was computing the per-minute rollups from the *input batch*, so a redelivered
batch of 20 events produced 20 rows and a rollup reading 40. The dashboard would have shown a
service serving twice its real traffic. `RETURNING` tells me which rows were genuinely inserted,
and rollups are computed only from those. `TelemetryIdempotencyIT.rollupsAreNotDoubleCounted` is
the test that failed and drove the fix.

**Why not check "have I seen this id" in application code?**

It is a read-then-write race. The consumer runs `concurrency=3` against a 6-partition topic; after
a rebalance two threads can legitimately hold the same record, both pass the check, both insert.
The unique index is evaluated by PostgreSQL under its own concurrency control and cannot race with
itself. `concurrentDuplicateDeliveryIsSafe` drives three threads at the same batch and asserts
exactly one row per event.

**Why key by `service_id`?**

Per-service ordering. A `SERVICE_DOWN` followed by `SERVICE_RECOVERED` must not be consumed out of
order and leave a service permanently marked down. Keying by organization would put a large
tenant's whole traffic on one partition and cap throughput at one consumer thread.

**Tell me about your dead-letter handling.**

Transient failures retry with exponential backoff — 500 ms, 1 s, 2 s, 4 s, five attempts total.
Permanent ones (`DeserializationException`, `MessageConversionException`, my own
`PermanentMessageException`) skip retries entirely and go straight to `<topic>-dlt`. Retrying a
poison message forever blocks its partition and starves everything behind it.

**Did that actually work when you first wrote it?**

No, and it is worth explaining why. Deserialization happens inside `poll()`, *before* the listener
runs, so `DefaultErrorHandler` never saw the failure — the container just threw, seeked back, and
re-fetched the same bad record forever. My DLQ configuration was structurally unreachable. The fix
is wrapping the value deserializer in `ErrorHandlingDeserializer`, which converts the failure into
a well-formed record carrying a `DeserializationException` that the error handler *can* route.
An end-to-end test caught it.

**Why not RabbitMQ?**

Replay. Detection rules evaluate over time windows, and if I change a rule or fix a consumer bug I
want to reprocess history. Kafka's retained, offset-addressed log gives me that; a queue that
deletes on acknowledgement does not. Consumer groups also let me add a second independent consumer
of the same telemetry without the producer knowing.

**What's `max.block.ms` set to and why?**

5 seconds, down from the 60-second default. `send()` blocks that long fetching metadata when the
broker is unreachable, so the default would tie up a request thread for a full minute per ingestion
call during a Kafka outage — the API would stop responding well before Kafka came back. I found
this because an integration test timed out at exactly 60 seconds.

---

## PostgreSQL and performance

**Walk me through the query optimisation.**

The dashboard needs error rate and p95 latency per service per window. The obvious implementation
is `percentile_cont` over raw telemetry: exact, and it scans every row in the window. On 2.15M rows
that measured a median of 754.8 ms with the sort spilling to temp files. Instead I maintain a
per-minute rollup with a cumulative latency histogram — fixed buckets at 5/10/25/50/100/250/500/
1000/2500/5000 ms plus `+Inf` — updated incrementally on the write path. The same query against
rollups is a median of 0.040 ms, scanning 2 rows instead of 2.2M.

**What did that cost you?**

Accuracy, and I measured it rather than hand-waving. p95 was 449.0 ms exact vs 449.4 ms
interpolated — 0.09% error. p99 was 0.10%. But **p50 was off by 29%**: the true median of 59 ms
falls inside the wide `(50, 100]` bucket, and linear interpolation assumes a uniform spread within
a bucket, which this distribution violates. The SLA rules are written against p95 where the
approximation is excellent, and I've documented that anything needing an accurate median must not
read it from the rollups.

**Why that specific index?**

`(organization_id, service_id, occurred_at DESC)`. Tenant first because every query starts with it
and it is the most selective predicate available. `occurred_at DESC` so `ORDER BY occurred_at DESC`
is a backwards index scan with no sort step. Notably, the benchmark query above uses a **sequential
scan** instead, and the planner is right — the predicate matches essentially the whole table, so the
index would be pure overhead.

**Why JDBC batching for telemetry when everything else uses JPA?**

Telemetry is write-once, read-by-aggregate. Persisting 500 events through `EntityManager` means 500
managed entities, 500 dirty checks and a first-level cache growing for no benefit, since none of
these rows is read back in the transaction. The ORM earns its keep on incidents and services, where
there is real object graph and optimistic locking.

**How do you prevent one flapping service from creating a hundred incidents?**

A partial unique index: `(organization_id, fingerprint) WHERE status <> 'RESOLVED'`. Concurrent
detectors race into the insert and exactly one wins; the loser catches the constraint violation and
attaches its alert to the winner's incident. Application-level dedup would race across the three
consumer threads.

---

## Multi-tenancy and security

**How does tenant isolation work?**

Three layers. First, the organization id comes from a JWT claim and **no endpoint accepts it as a
path variable, query parameter or body field** — a client has no way to name a tenant, so
horizontal escalation has no input to attack. Second, every repository method touching a
tenant-scoped table takes `organizationId` and puts it in the `WHERE` clause. Third, PostgreSQL
row-level security as a database-enforced backstop, so a repository method that forgets its `WHERE`
clause fails closed instead of leaking.

**Why is cross-tenant access a 404 and not a 403?**

A 403 confirms the resource exists and belongs to someone else, which is an existence oracle.
`TenantIsolationIT` asserts that a real-but-foreign id and a random UUID produce byte-identical
error codes.

**What was hard about adding RLS?**

Two things, and the first one nearly shipped as a lie. I wrote the policies, the migration applied
cleanly, and every assertion in `RowLevelSecurityIT` failed — the database was still handing back
other tenants' rows. The cause is that **PostgreSQL exempts superusers and table owners from RLS
unconditionally**, and the `postgres` Docker image makes `POSTGRES_USER` a superuser. The
application was connecting as the owner, so the policies were decorative. The fix is a separate
`signalforge_app` role with DML grants and no ownership; Flyway still migrates as the owner. Had
the test not existed, the repository would now contain a migration that reads as a security control
and enforces nothing.

The second was the detection sweep. `findAllEnabledAcrossTenants()` is deliberately cross-tenant —
it reads rule *definitions* to build the sweep list — and a policy keyed on a session GUC breaks it
by design. I resolved it with a narrow `SECURITY DEFINER` function that returns **ids only**, so the
bypass hands back a work list rather than tenant data, and the sweep then binds each tenant and
re-reads through the policies. There are exactly three such bypasses — login-by-email,
API-key-by-hash, and that one — and a test asserts a fourth has not appeared.

**How do you know the policies actually apply?**

`RowLevelSecurityIT` issues raw SQL with no tenant predicate at all, including `SELECT` by exact
primary key, and asserts nothing comes back. It also covers cross-tenant `INSERT` (blocked by
`WITH CHECK`), `UPDATE` and `DELETE`. A test that only queries through the repository layer would
pass even with RLS disabled, because the repository adds its own `WHERE` clause — it would be
testing the layer above the one under test.

**Why SHA-256 for API keys but bcrypt for passwords?**

Different threat models. A password is low-entropy and human-chosen, so it needs a deliberately slow
KDF to make brute force expensive. An API key is 256 bits of `SecureRandom` output — there is
nothing to brute force — and it is verified on *every ingestion request*, where a 250 ms bcrypt
would be a self-inflicted denial of service.

**How do you avoid user enumeration on login?**

Same error code and message for unknown account and wrong password, and an unknown email still pays
a bcrypt verification against a dummy hash so response time does not leak. Failed attempts are
throttled per email in Redis.

**Your login throttle fails open if Redis is down. Isn't that a vulnerability?**

It is a deliberate trade-off and I'd defend it. Failing closed turns a Redis outage into a total
authentication outage — a far more likely and far more damaging incident than the brute-force
attempt it guards against. Passwords are still bcrypt, the failure is logged at WARN, and it's
documented in the runbook. If this were a bank I'd choose differently.

---

## Redis

**What is Redis actually used for?**

Rate limiting and login throttling. It is not the system of record for anything; PostgreSQL is
authoritative.

**What happens when Redis dies?**

Ingestion continues unthrottled and login throttling is skipped. Both fail open, deliberately: this
platform's entire job is to keep working during an incident, and dropping a customer's observability
data because our cache is down would blind them at the worst possible moment. Redis is also
excluded from the readiness probe group, so a Redis outage does not pull the API out of the load
balancer.

**Walk me through the rate limiter.**

A sliding-window counter over two adjacent one-minute buckets, weighting the previous bucket by how
much of it still falls in the trailing 60 seconds. A plain fixed window lets a client send 2× quota
across a boundary — full quota at 11:59:59 and again at 12:00:00 — which is exactly the burst you
were trying to prevent. The whole read-compute-write is one Lua script so it is atomic server-side;
separate GET/INCR round trips would race across API replicas under precisely the concurrent load
the limiter exists for.

**Can a client bypass it by batching?**

No — cost is charged per event, not per request. `batchingDoesNotBypassQuota` asserts a single batch
larger than the whole quota is refused outright.

---

## The AI assistant

**What happens if Ollama is unavailable?**

Nothing breaks. It is architecturally incapable of blocking detection: no code on the detection or
correlation path calls it, the summary lives in its own table, and the endpoint returns
`available: false` with a reason rather than a 503. Every failure path in the client returns
`Optional.empty()` — the caller has no reason to catch.

**How do you stop it hallucinating a cause?**

Three layers. It sees only a rendered evidence bundle — no tools, no retrieval, no database access —
so it cannot cite a log line that was never collected. If the deterministic correlator found no
evidence, it short-circuits to "Insufficient evidence to determine root cause" **without calling
the model at all**, because asking an LLM to explain an incident with no evidence is precisely how
you get a confident fabrication. And every returned cause is ground-checked against the bundle:
a cause naming a service or version absent from the evidence is discarded and counted in a metric.

**Isn't that check crude?**

Yes, deliberately. It will occasionally discard a fair paraphrase. That is the correct direction to
err — a dropped true statement costs an engineer nothing, while a retained invented one sends them
to debug a system that was never involved.

**Why a local model rather than a hosted API?**

The project constraint was $0 and no paid API, but there is a better reason: incident evidence
contains service names, error messages and deployment metadata. Shipping that to a third party is a
data-egress decision an SRE team should make deliberately, not one a tool makes for them.

---

## Observability

**How do you know your own pipeline is healthy?**

Custom Micrometer meters on every stage: ingest accepted/rejected, publish latency, consumer
persisted/duplicate/poison, end-to-end ingest-to-persist lag, detection sweep duration, AI outcomes
and rejected claims. All scraped by Prometheus and on a provisioned Grafana dashboard.

**Is distributed tracing working?**

Configured, **not verified**. Micrometer tracing, the OTLP exporter and `observationEnabled` on the
listener container are all wired, but I have no test asserting that a single trace id actually spans
API → Kafka → consumer → PostgreSQL. Until I check that, I'd describe tracing as configured rather
than working. It is in `docs/STATUS.md` as a known gap.

---

## Testing

**Why Testcontainers rather than H2?**

This codebase depends on behaviour H2 does not reproduce: partial unique indexes, `jsonb`,
`gen_random_uuid()`, the append-only audit trigger, and `ON CONFLICT … RETURNING`. A green H2 suite
would tell me almost nothing about whether the real thing works — the two most important bugs I
found were both in behaviour H2 cannot express.

**Why real Kafka rather than `EmbeddedKafka`?**

Different codebase, different defaults, and the behaviour under test — producer idempotence,
rebalancing, dead-letter routing — is precisely where those defaults diverge. The
`ErrorHandlingDeserializer` bug would not have surfaced against an embedded broker configured
differently.

**Your tests are mostly negatives. Why?**

Because a positive test ("A can read A's data") passes just as happily against a system with no
isolation at all. The interesting assertions are the denials.

**What's your coverage?**

JaCoCo is wired and reports, but I'm not going to quote a percentage as a quality claim — coverage
measures which lines executed, not whether the assertions were meaningful. What I'd point at
instead is that the suite found three real defects: rollup double-counting, unreachable
dead-lettering, and a mislabelled detection-latency metric.

---

## Performance and scale

**Your benchmark is on a laptop. How much do you trust it?**

The absolute throughput, not much — the load generator was competing with the server for CPU, on a
Docker VM shared with ~17 unrelated containers. What I trust is the *ratio* in the query
optimisation, because that is hardware-independent, and the correctness properties, which are too.
I say so explicitly in the benchmark doc rather than presenting 64k events/sec as a production
number.

**How would this scale to 100× traffic?**

In order: partition `telemetry_events` by time and drop old partitions instead of `DELETE`; scale
the `worker` profile horizontally past the current 6 partitions; move the SSE hub onto the
`notification-events` topic so it works across replicas; add a Redis lock so multiple detection
schedulers don't duplicate sweeps. The read path already scales — that's what the rollups bought.

**What's the first thing that breaks?**

The detection sweep. It is single-instance and evaluates every rule for every service serially
inside one tick. At a few hundred services per tenant with many tenants, sweep duration approaches
the evaluation interval, sweeps back up, and detection latency degrades silently. That's why sweep
duration is on the Grafana dashboard.

**What would you redesign?**

Three things. Row-level security from the beginning, rather than bolting it on after a
cross-tenant sweep query already exists — that ordering cost me a `SECURITY DEFINER` bypass I would
not otherwise need. An outbox for the ingestion publish, so a Kafka outage queues locally instead of
returning 503. And a detection sweep that shards by tenant from the start, since the single-instance
assumption is the thing most likely to break first under real load.

---

## Questions where the answer is "I didn't do that"

Rehearse these. Scoping honestly is a stronger signal than improvising.

- **Row-level security** — designed, documented in ADR-0003, not implemented.
- **MTTR improvement** — no controlled experiment was run; no number exists.
- **Playwright E2E / frontend tests** — the frontend builds and typechecks, but is untested.
- **Verified distributed tracing** — configured, not proven.
- **Multi-replica SSE** — in-process only; two replicas would each serve their own subscribers.
- **Production deployment** — never deployed. Local Docker Compose is the canonical environment.
