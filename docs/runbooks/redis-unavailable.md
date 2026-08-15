# Runbook: Redis unavailable

## Expected behaviour

**The platform keeps working.** Redis is not the system of record for anything.

| Feature | Degraded behaviour |
|---|---|
| Ingestion rate limiting | **Fails open** — telemetry is accepted unthrottled |
| Login throttling | **Fails open** — brute-force protection skipped, bcrypt still applies |
| Readiness probe | **Unaffected** — Redis is deliberately excluded from the readiness group |

Both fail open on purpose. Failing closed would turn a cache outage into a total observability or
authentication outage — far more damaging, and far more likely, than the abuse they guard against.

## Symptoms

- `WARN Rate limiter unavailable for org=…, allowing request (failing open)`
- `WARN Login throttle unavailable, allowing attempt (failing open)`
- Grafana: `X-RateLimit-*` headers absent from ingestion responses

## Diagnosis

```bash
docker compose ps redis
docker exec sf-redis redis-cli ping
curl -s localhost:8099/actuator/health | jq '.components.redis'
```

## Recovery

```bash
docker compose restart redis
```

No state needs restoring — every key is a short-lived counter that rebuilds itself. The rate-limit
window will be empty for up to 60 seconds after recovery, so a tenant briefly gets a fresh quota.
That is acceptable and expected.

## If it stays down

Consider temporarily lowering `signalforge.ingestion.default-rate-limit-per-minute` at the load
balancer or reverse proxy, since the application-level limiter is inoperative.
