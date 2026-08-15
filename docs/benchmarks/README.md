# Benchmarks

Every number on this page was produced by running the commands shown, on the hardware described,
against the running stack. Nothing here is estimated, extrapolated or rounded in a flattering
direction. Where a result is unflattering it is reported anyway — two of them below are.

## Environment

| | |
|---|---|
| Host | Apple Silicon macOS, 18 GB RAM, 11 cores |
| Docker VM | 8 GB allocated, **shared with ~17 unrelated containers** (Flink, Trino, other projects) |
| PostgreSQL | 16.13 in Docker, `shared_buffers=256MB`, `work_mem=16MB` |
| Kafka | apache/kafka 3.8.0, KRaft, single broker, 6 partitions on `telemetry-events` |
| Backend | Java 21.0.12, Spring Boot 3.5.16, run via `mvn spring-boot:run` (**not** a tuned production JVM) |
| k6 | run from the same host as the server |
| Date | 2026-08-07 |

**This is a laptop, under contention, with the load generator competing with the server for CPU.**
Absolute throughput would be materially different on dedicated hardware. The *ratios* and the
*shapes* are the meaningful part.

---

## 1. Telemetry ingestion throughput

```bash
RUN_LABEL=baseline k6 run load-tests/ingestion.js
```

Ramp 1→10 VUs over 15s (warm-up), 30 VUs for 45s (steady state), 10s ramp-down.
Batch size 50 events per request.

| Metric | Result |
|---|---|
| Requests | 90,202 |
| Events published | 4,509,950 |
| **Events/sec** | **63,991** |
| Requests/sec | 1,280 |
| Error rate | 0.00% |
| p50 latency | 10.1 ms |
| **p95 latency** | **23.2 ms** |
| p99 latency | 34.6 ms |
| max latency | 400.6 ms |

**What this measures:** authenticate → rate limit → validate → publish to Kafka → 202.
It deliberately does **not** wait for the record to reach PostgreSQL; that is asynchronous by
design, and including it would conflate producer latency with consumer throughput.

**What this does not measure:** consumer throughput. During this run the consumer persisted roughly
1.1M of the 4.5M published events before the run ended; it drained the remainder afterwards. The
publish rate exceeding the persist rate is the queue doing its job — that is precisely why
ingestion returns 202 rather than 200.

---

## 2. Query optimisation: pre-aggregated rollups vs `percentile_cont`

This is the headline optimisation. The dashboard needs error rate and p95 latency for a service
over a window. The obvious implementation computes them from raw telemetry; SignalForge instead
maintains a per-minute histogram rollup on the write path.

**Dataset:** 2,154,690 telemetry rows for one service. `ANALYZE` run before measuring.

### Before — exact percentiles over raw rows

```sql
SELECT COUNT(*), COUNT(*) FILTER (WHERE status_code >= 400),
       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms),
       percentile_cont(0.50) WITHIN GROUP (ORDER BY latency_ms)
FROM telemetry_events
WHERE organization_id = ? AND service_id = ?
  AND occurred_at >= now() - interval '30 minutes';
```

5 consecutive `EXPLAIN (ANALYZE)` runs: **1146.1, 748.6, 758.9, 754.8, 745.5 ms** → median **754.8 ms**

Plan: `Seq Scan` over 2,226,360 rows feeding an `Aggregate`, with a sort spilling to temp files
(`temp read=6410 written=5234` blocks). The planner is right to prefer a sequential scan here —
the predicate matches essentially the whole table, so the index would be pure overhead.

### After — interpolated from the rollup histogram

```sql
SELECT SUM(request_count), SUM(error_count), SUM(le_250), SUM(le_500), SUM(le_1000),
       SUM(le_2500), SUM(le_5000), SUM(le_inf), MAX(latency_max_ms)
FROM telemetry_minute_rollups
WHERE organization_id = ? AND service_id = ?
  AND bucket_start >= now() - interval '30 minutes';
```

5 consecutive runs: **0.045, 0.040, 0.037, 0.041, 0.036 ms** → median **0.040 ms**

| | Before | After |
|---|---|---|
| Median execution time | 754.8 ms | **0.040 ms** |
| Rows scanned | 2,226,360 | 2 |
| Storage | 453 MB (`telemetry_events`) | 40 kB (`telemetry_minute_rollups`) |

That is a **~18,900×** reduction in query time on this dataset. The multiple grows with retention,
because the rollup query cost is a function of window length while the raw query is a function of
event volume.

### The accuracy cost — reported honestly

The rollup answer is an **approximation**. Measured against the exact values on the same 1.87M-row
dataset:

| Percentile | Exact (`percentile_cont`) | Interpolated (histogram) | Error |
|---|---|---|---|
| p50 | 59.0 ms | 76.2 ms | **+29.2%** |
| p95 | 449.0 ms | 449.4 ms | +0.09% |
| p99 | 2163.0 ms | 2165.2 ms | +0.10% |

**p95 and p99 are accurate to within a tenth of a percent. p50 is off by 29%.**

That is not a bug, it is the arithmetic being visible: the true p50 of 59 ms falls inside the wide
`(50, 100]` bucket, and linear interpolation assumes observations are spread uniformly across a
bucket. This distribution is skewed toward the low end of that bucket, so interpolation overshoots.
p95 and p99 land in buckets where the distribution happens to be closer to uniform.

**Consequence, stated plainly:** SignalForge's SLA rules are written against p95, where the
approximation is excellent. Any future feature that depends on an accurate median must not read it
from the rollups. Narrowing the buckets around the median would fix it at the cost of more columns.

---

## 3. Read-path latency

```bash
RUN_LABEL=seeded k6 run load-tests/dashboard-read.js
```

20 concurrent VUs, 45 s, against a database holding 4.3M telemetry rows across 4 organizations.

| Endpoint | p50 | p95 | p99 |
|---|---|---|---|
| `GET /api/v1/incidents` | 3.4 ms | 7.4 ms | 13.6 ms |
| `GET /api/v1/services` | 3.3 ms | 7.3 ms | 13.3 ms |

Aggregate 4,991 req/s, 0.00% errors.

Measured separately against the simulator's seeded incident (10 sequential requests):

| Endpoint | min | p50 | max |
|---|---|---|---|
| `GET /incidents/{id}` | 4.0 ms | 4.7 ms | 7.7 ms |
| `GET /incidents/{id}/correlation` | 8.5 ms | 9.1 ms | 21.6 ms |

Correlation is roughly 2× the cost of the detail view, which is why it is a **separate endpoint**
rather than part of the detail payload — the list and detail views are polled constantly, the RCA
panel is opened deliberately.

---

## 4. Incident detection latency

```bash
./scripts/incident-simulator.sh
```

The simulator registers an organization, establishes a healthy baseline, records a deployment,
injects a correlated two-service failure, and polls until an incident exists.

| | Result |
|---|---|
| **Client-observed detection latency** | **10,849 ms** |
| Incident opened | `INC-5` — "Database error spike on payment-service", severity CRITICAL |
| Correlation produced | 2 ranked factors, both evidence-backed |
| Lifecycle | acknowledged in 1,412 ms, resolved in 1,732 ms, 6 timeline entries |

Detection latency is **bounded below by `signalforge.detection.evaluation-interval`** (default 15 s).
A breach occurring immediately after a sweep waits for the next one. 10.8 s is consistent with a
uniformly-distributed arrival inside a 15 s window.

### A defect this benchmark found

The simulator reported `timeToDetectMs = 300,023 ms` — five minutes — for an incident that was
genuinely detected in under eleven seconds.

That figure is not wrong, it is **mislabelled**. `Incident.startedAt` is set to the *detection
window start*, and the breaching rule used a 300-second window, so `detectedAt - startedAt` is
dominated by the window length and tells you almost nothing about detection speed.

The window-start semantic is correct for evidence gathering — the incident's supporting data really
does begin there. But deriving a metric called "time to detect" from it is misleading, and it would
have gone unnoticed without an end-to-end simulation that measured the same thing independently
from the outside.

**Status: fixed after this run.** `V3__incident_signal_observed_at.sql` adds `signal_observed_at` —
the timestamp of the most recent telemetry the breaching evaluation actually saw — and
`Incident.timeToDetect()` is now `detectedAt - signalObservedAt`, which is what an SRE means by
detection latency. The old window-start-to-detection figure survives as `evidenceWindowAge()`, named
for what it measures. Incidents predating the migration return **null** rather than a backfilled
number nobody measured.

**The 300,023 ms above is reported as-measured, from the pre-fix run.** The client-observed 10,849 ms
is unaffected — it was measured from outside the system and never depended on the broken field. This
section has deliberately not been rewritten to show a flattering post-fix number, because that run
has not happened; re-running `./scripts/incident-simulator.sh` is what would earn one.

---

## Reproducing

```bash
docker compose up -d
cd backend && mvn spring-boot:run          # or use the compose backend service

k6 run load-tests/ingestion.js             # section 1
./scripts/incident-simulator.sh            # section 4
k6 run load-tests/dashboard-read.js        # section 3
```

Section 2 needs a populated database — run the ingestion benchmark first, then the SQL in
[query-optimization.md](query-optimization.md).

Raw k6 JSON output is written to `load-tests/results/`.
