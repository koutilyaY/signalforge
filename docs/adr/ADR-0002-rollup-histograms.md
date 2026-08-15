# ADR-0002: Pre-aggregated histogram rollups for percentile queries

**Status:** Accepted · **Date:** 2026-08-07

## Context

The dashboard and every latency detection rule need error rate and p95 latency for a service over a
window. The obvious implementation is `percentile_cont` over `telemetry_events`: exact, one query,
no extra schema.

Measured on 2,154,690 rows it takes a **median of 754.8 ms**, with a sequential scan feeding an
aggregate whose sort spills to temp files. At a 15-second detection interval across many services,
that is not viable.

## Decision

Maintain `telemetry_minute_rollups` incrementally on the write path: per organization, per service,
per minute, a cumulative latency histogram with fixed boundaries (5/10/25/50/100/250/500/1000/2500/
5000 ms plus `+Inf`) alongside request, error and sum counters.

Percentiles are interpolated from the histogram the way Prometheus' `histogram_quantile` does.

Crucially, rollups are updated **only from rows that `INSERT … RETURNING` confirmed were written**,
never from the input batch — see ADR-0007.

## Measured outcome

| | Before | After |
|---|---|---|
| Median execution | 754.8 ms | **0.040 ms** |
| Rows scanned | 2,226,360 | 2 |
| Storage | 453 MB | 40 kB |

## The cost, stated

The result is an **approximation**. Against exact values on the same dataset:

| | Exact | Interpolated | Error |
|---|---|---|---|
| p50 | 59.0 ms | 76.2 ms | **+29.2%** |
| p95 | 449.0 ms | 449.4 ms | +0.09% |
| p99 | 2163.0 ms | 2165.2 ms | +0.10% |

p95 and p99 are excellent. **p50 is bad**, because 59 ms falls in the wide `(50, 100]` bucket and
linear interpolation assumes uniformity the distribution does not have.

This is acceptable *specifically because* SLA rules are written against p95. It would not be
acceptable for a product surfacing median latency as a headline number.

## Consequences

**Positive** — detection can run at a 15-second interval; the read path is independent of retention;
raw events remain available for exact answers when needed.

**Negative** — a second write per batch; approximate percentiles; the boundaries are baked into both
the schema and `WindowStats.BOUNDARIES` and must be changed together; **the median is unreliable**
and any future feature depending on it must query raw rows or the buckets must be narrowed.
