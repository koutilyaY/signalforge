# Runbook: PostgreSQL unavailable

## Expected behaviour

PostgreSQL is the system of record. There is no graceful degradation and there should not be —
serving stale or partial reads from a monitoring platform during an incident is worse than
serving nothing.

| Component | Behaviour |
|---|---|
| API | 503; readiness fails, instance leaves the load balancer |
| Consumer | Retries with backoff, then dead-letters; **offsets are not committed**, so nothing is lost |
| Detection | Sweep fails, logged, retried next tick |
| Ingestion | Still returns 202 while Kafka is healthy — telemetry queues in the log |

The last row is the design paying off: a database outage does not lose incoming telemetry, because
the API never writes to PostgreSQL on the ingestion path.

## Diagnosis

```bash
docker compose ps postgres
docker exec sf-postgres pg_isready -U signalforge
curl -s localhost:8099/actuator/health/readiness
docker compose logs postgres --tail 50
```

## Recovery

```bash
docker compose restart postgres
```

Then confirm the consumer drains the backlog that accumulated in Kafka:

```bash
docker exec sf-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group sf-telemetry-persist
```

Lag should fall steadily to zero. If it does not, check for records in the DLT.

## Connection pool exhaustion

Symptom: requests time out while PostgreSQL itself is healthy.

```bash
curl -s localhost:8099/actuator/metrics/hikaricp.connections.active
```

Active approaching `maximum-pool-size` (16) means requests are queuing on a connection. Look for a
long-running query before raising the pool — moving contention from the app into PostgreSQL is more
expensive, not less.
