# Contributing

## Prerequisites

- Java 21 (`brew install openjdk@21`)
- Docker Desktop with ~2 GB free in the VM
- Node 22+ for the frontend

## Local loop

```bash
cp .env.example .env
printf 'SF_JWT_SECRET=%s\n' "$(openssl rand -base64 48)" >> .env
docker compose up -d postgres redis kafka
docker compose up kafka-init

cd backend && mvn spring-boot:run
cd frontend && npm install && npm run dev
```

## Before opening a PR

```bash
cd backend && mvn spotless:apply && mvn verify
cd frontend && npx tsc --noEmit && npm run build
```

CI runs the same commands plus a Docker image build and dependency/secret scanning.

## House rules

These are not style preferences — each one exists because violating it caused a real bug here.

**Every tenant-scoped query takes `organizationId` explicitly.** Never add a repository method that
loads a tenant-scoped row by id alone. `JpaRepository.findById` is inherited and unscoped; do not
call it outside `iam`.

**Cross-tenant access returns 404, never 403.** A 403 confirms the resource exists.

**Never trust producer type headers in a Kafka consumer.** The target type is pinned consumer-side.

**Aggregates are computed from what was actually written**, not from the input batch. See
`TelemetryWriter` and ADR-0007 — this is the bug that made a service look like it served twice its
real traffic.

**New detection rules must be exercised by a negative test.** "Fires above threshold" is not enough;
assert it stays quiet below, and that the minimum sample size guard holds.

**Comments explain *why*, not *what*.** If a line needs a comment to say what it does, rename
something instead.

## Testing

Integration tests end in `IT` and run under failsafe with real PostgreSQL, Redis and Kafka via
Testcontainers. Unit tests run under surefire.

Do not substitute H2 or `EmbeddedKafka`. This codebase depends on partial unique indexes, `jsonb`,
`ON CONFLICT … RETURNING` and real consumer rebalancing — a green suite against a substitute would
be worse than no suite, because it would be trusted.

## Migrations

Flyway, forward-only. Never edit an applied migration; add a new one. `ddl-auto: validate` means a
schema/entity mismatch fails startup — that is intentional and has already caught one real bug.
