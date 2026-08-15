# ADR-0005: Redis-backed limits fail open

**Status:** Accepted · **Date:** 2026-08-07

## Context

Redis backs two protective features: per-organization ingestion rate limiting and per-email login
throttling. Both must decide what to do when Redis is unreachable.

Failing **closed** is the reflexive security answer: if you cannot verify the limit, deny.

## Decision

Both **fail open**. If Redis is unavailable, ingestion proceeds unthrottled and login throttling is
skipped. Both log at WARN and expose the degradation as a metric. Redis is also excluded from the
`readiness` health group.

## Rationale

**For ingestion:** this platform's entire purpose is to keep working during an incident. Dropping a
customer's telemetry because *our* cache is down would blind them at precisely the worst moment,
and would do so for a reason unrelated to their system. The failure mode of failing open is a
temporary unenforced quota; the failure mode of failing closed is total observability loss during
an outage.

**For login:** failing closed converts a Redis outage into a **total authentication outage**. That
is a far more likely and far more damaging incident than the brute-force attempt being guarded
against. Passwords are still bcrypt at cost 12, so the underlying protection is intact — only the
attempt counter is lost.

**Excluding Redis from readiness** follows from the same reasoning: if the app is designed to keep
serving without Redis, a Redis outage must not pull every instance out of the load balancer.

## When this would be wrong

If SignalForge handled payments, or if the rate limiter were the only thing standing between a
tenant and a cost-incurring downstream, failing open would be indefensible. It is defensible here
because the resource being protected is our own database capacity, and the cost of over-admitting
is bounded and recoverable.

## Consequences

**Positive** — a cache outage degrades one feature instead of the platform; no cascading failure.

**Negative** — a sustained Redis outage leaves ingestion unthrottled, so a misbehaving producer
could pressure PostgreSQL. Mitigation is the runbook's instruction to throttle at the proxy.
Brute-force protection is genuinely absent during the outage.
