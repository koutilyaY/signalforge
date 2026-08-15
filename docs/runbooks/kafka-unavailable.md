# Runbook: Kafka unavailable

## Expected behaviour

Unlike Redis, Kafka failure is **not** silently tolerated — ingestion returns 503. Returning 202
would be a lie: it means "durably queued", and nothing was queued.

| Component | Behaviour |
|---|---|
| Ingestion API | 503 after ~5 s (`max.block.ms`), **not** a 60 s hang |
| Consumer | Retries connection; no data loss, offsets are committed server-side |
| Detection | **Continues** — it reads rollups from PostgreSQL, not Kafka |
| Already-persisted telemetry | Unaffected |

The 5-second `max.block.ms` matters: at the 60-second default, every ingestion request would tie up
a servlet thread for a full minute and the API would stop responding well before Kafka came back.

## Symptoms

- `ERROR Failed to publish telemetry event … for org …`
- Ingestion 503 `DEPENDENCY_UNAVAILABLE`
- Grafana: `signalforge_ingest_events_total{outcome="accepted"}` drops to zero

## Diagnosis

```bash
docker compose ps kafka
docker exec sf-kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092
docker exec sf-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

## Recovery

```bash
docker compose restart kafka
docker compose up kafka-init   # idempotent; recreates any missing topic
```

Consumers reconnect automatically and resume from their committed offsets. **Redelivery on recovery
is expected and harmless** — the write path is idempotent (ADR-0007).

## Inspecting the dead-letter topic

```bash
docker exec sf-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic telemetry-events-dlt --from-beginning --max-messages 10
```

Records land here after exhausted retries, or immediately for non-retryable failures
(deserialization, malformed payload). A rising DLT is a producer bug, not a broker problem.
