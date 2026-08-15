# Query optimisation: rollups vs percentile_cont

Reproducible SQL behind [README.md §2](README.md). Run the ingestion benchmark first to populate
the database.

```bash
k6 run load-tests/ingestion.js
docker exec sf-postgres psql -U signalforge -d signalforge -c "ANALYZE telemetry_events;"
```

Grab an org/service pair:

```sql
SELECT organization_id, service_id FROM telemetry_events LIMIT 1;
```

## Before

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(*)                                              AS requests,
       COUNT(*) FILTER (WHERE status_code >= 400)            AS errors,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95,
       percentile_cont(0.50) WITHIN GROUP (ORDER BY latency_ms) AS p50
FROM telemetry_events
WHERE organization_id = :org AND service_id = :svc
  AND occurred_at >= now() - interval '30 minutes';
```

Measured (2,154,690 rows, 5 runs): **1146.1, 748.6, 758.9, 754.8, 745.5 ms** → median **754.8 ms**

Plan: `Seq Scan` over 2,226,360 rows into an `Aggregate`, sort spilling to temp
(`temp read=6410 written=5234`). The sequential scan is correct — the predicate matches nearly the
whole table, so the index would be overhead.

## After

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT SUM(request_count), SUM(error_count),
       SUM(le_250), SUM(le_500), SUM(le_1000), SUM(le_2500), SUM(le_5000), SUM(le_inf),
       MAX(latency_max_ms)
FROM telemetry_minute_rollups
WHERE organization_id = :org AND service_id = :svc
  AND bucket_start >= now() - interval '30 minutes';
```

Measured (5 runs): **0.045, 0.040, 0.037, 0.041, 0.036 ms** → median **0.040 ms**

## Accuracy

```sql
-- exact
SELECT percentile_cont(0.50) WITHIN GROUP (ORDER BY latency_ms) AS p50,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms) AS p99
FROM telemetry_events WHERE organization_id = :org AND service_id = :svc;
```

| Percentile | Exact | Interpolated | Error |
|---|---|---|---|
| p50 | 59.0 ms | 76.2 ms | **+29.2%** |
| p95 | 449.0 ms | 449.4 ms | +0.09% |
| p99 | 2163.0 ms | 2165.2 ms | +0.10% |

The p50 error is the arithmetic being visible, not a defect: 59 ms falls inside the wide
`(50, 100]` bucket, and linear interpolation assumes a uniform spread within a bucket. This
distribution is skewed toward the bucket's low end, so interpolation overshoots.

**SLA rules use p95, where the approximation is excellent. Anything needing an accurate median must
not read it from the rollups.** Narrowing the buckets around the median would fix it at the cost of
more columns.
