# ADR-0007: At-least-once delivery with idempotent consumers

**Status:** Accepted
**Date:** 2026-08-07

## Context

Telemetry flows `HTTP → Kafka → consumer → PostgreSQL`. Kafka can redeliver a record when a
consumer rebalances, a container restarts, or a failure occurs between processing a batch and
committing its offset. Any of these produces duplicate events.

Duplicate telemetry is not cosmetic. Every number the product exists to compute — error rate,
p95 latency, request volume — is an aggregate. A pipeline that double-counts makes a healthy
service look like it is serving twice its real traffic, and makes the error-rate denominator wrong.

## Decision

Assume **at-least-once** delivery. Do not claim exactly-once. Achieve *effectively-once business
behaviour* by making the write idempotent in the database:

1. The **producer supplies `eventId`**, not the server. A client retrying a timed-out POST sends
   the same id.
2. `telemetry_events` carries `UNIQUE (organization_id, event_id)`.
3. The insert is `INSERT … ON CONFLICT (organization_id, event_id) DO NOTHING **RETURNING
   event_id**`.
4. **Rollups are computed only from the ids `RETURNING` actually gave back**, never from the input
   batch.

Step 4 is the one that is easy to get wrong and it is the reason this ADR exists.

## Why not exactly-once

Kafka's exactly-once semantics apply to Kafka-to-Kafka flows within a transaction. This pipeline
ends in PostgreSQL, so an atomic "consume + write + commit offset" would need either a distributed
transaction (XA, with its own failure modes and no support in this stack) or a transactional outbox
on both sides. Both are substantial machinery to avoid writing one `ON CONFLICT` clause.

Claiming exactly-once when the implementation is at-least-once plus idempotency is the kind of
claim that falls apart in about ninety seconds of interview questioning.

## Why the database, not the application

An application-level "have I seen this id?" check is a read-then-write race. The consumer runs with
`concurrency=3` against a 6-partition topic; after a rebalance two threads can legitimately hold
the same record. A check-then-insert would let both threads pass the check and both insert.

The unique index is evaluated by PostgreSQL under its own concurrency control. It cannot race with
itself. `TelemetryIdempotencyIT.concurrentDuplicateDeliveryIsSafe` drives three threads at the same
batch simultaneously and asserts exactly one row per event.

## The bug this caught

The first implementation suppressed duplicate *rows* correctly but still incremented the
per-minute rollup from the input batch. Redelivering a 20-event batch produced 20 rows and a
rollup reading 40.

`TelemetryIdempotencyIT.rollupsAreNotDoubleCounted` failed, which is why the write path now uses
`RETURNING` to learn what was genuinely inserted. Suppressing the row is worthless if the aggregate
the dashboard reads is still inflated.

## Consequences

**Positive**
- Redelivery is a no-op end to end, verified against a real broker in `PipelineEndToEndIT`.
- No distributed transaction, no outbox, no saga.
- Consumer restarts and rebalances are safe by construction.

**Negative**
- Producers must generate stable event ids. A producer that mints a fresh UUID per retry defeats
  the whole mechanism. This is documented in the ingestion API contract.
- The unique index costs write throughput and disk on the highest-volume table.
- `RETURNING` on a multi-row insert means the write cannot use JDBC `addBatch`; it uses chunked
  multi-row `INSERT` statements (200 rows each) instead.

## Related

- ADR-0006 (topic contracts and schema compatibility)
- `docs/runbooks/kafka-unavailable.md`
