# ADR-0001: Modular monolith, not seven microservices

**Status:** Accepted
**Date:** 2026-08-07

## Context

The problem domain naturally decomposes into seven bounded contexts: identity and tenancy,
service registry, telemetry ingestion, incident detection, incident management, correlation/RCA,
and notification.

The obvious move for a portfolio project is to make each one a separate Spring Boot service,
because a diagram with seven boxes looks more impressive than a diagram with one.

## Decision

Build a **modular monolith**: one deployable artifact containing seven modules with enforced
boundaries, plus the ability to run the same artifact in two roles via Spring profiles
(`api` serves HTTP, `worker` runs Kafka consumers and the detection scheduler).

Modules communicate through published interfaces and Kafka topics. No module reads another
module's tables.

## Rationale

**Seven JVMs cost roughly 400MB each.** The stated constraint is that this runs locally for $0.
On a laptop already running Postgres, Redis, Kafka, Prometheus and Grafana, seven additional JVMs
is 2.8GB before a single request is served. That is not a hypothetical: this project was developed
on a machine with 8GB allocated to Docker.

**Microservices buy independent deployment and independent scaling.** Neither is a real
requirement here. There is one team of one, one release cadence, and the only component with a
genuinely different scaling profile is telemetry ingestion — which is addressed by the
`api`/`worker` profile split without needing a separate codebase.

**Microservices cost distributed transactions, network partitions between every module, and
schema-coupled HTTP contracts.** Those costs are real and immediate; the benefits are speculative
and deferred.

**The module boundaries are the valuable part, and they survive either way.** If the ingestion
module ever needs to scale independently as a separate deployable, the work is extracting a
package with an already-explicit interface — not untangling a ball of mud.

## Consequences

**Positive**
- Whole platform starts with `docker compose up` and roughly 1.2GB of RAM.
- One transaction boundary where one is needed; no saga machinery for incident creation.
- Refactoring across module boundaries is a compile-time error, not a runtime 404.

**Negative**
- A memory leak or crash in any module takes the whole process down. Mitigated by the
  `api`/`worker` split, so a runaway consumer does not take the API with it.
- Module boundaries are enforced by convention and code review, not by the network. A future
  ArchUnit test asserting that `com.signalforge.incident` never imports
  `com.signalforge.telemetry.persistence` would make this structural.
- Scaling is all-or-nothing per role.

## What would change this decision

- More than one team needing independent release cadence.
- Telemetry ingestion needing to scale beyond what one `worker` fleet can absorb, *and* the
  detection engine having a materially different scaling profile from it.
- A compliance requirement to physically isolate one tenant's data plane.
