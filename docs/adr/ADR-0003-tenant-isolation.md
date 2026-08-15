# ADR-0003: Tenant isolation enforced at the query, not the filter

**Status:** Accepted
**Date:** 2026-08-07

## Context

SignalForge is multi-tenant. Organization A must never observe organization B's services,
incidents, deployments, telemetry or audit records.

The usual failure mode is not a missing check — it is a check that exists in most places and is
forgotten in one, on an endpoint added six months later by someone who did not know the convention.

## Decision

Three independent layers, each of which would have to fail for a leak to occur.

### 1. The organization id comes only from the signed token

`AuthenticatedPrincipal` carries `organizationId`, populated from a JWT claim. **No controller
accepts an organization id as a path variable, query parameter, header or body field.** There is
no code path where a client can name the tenant it wants to act as, so horizontal privilege
escalation has no input to attack.

### 2. Every tenant-scoped query takes `organizationId` as a parameter

Repositories expose `findByIdInOrganization(id, organizationId)`, not `findById(id)`. The tenant
discriminator is in the `WHERE` clause, so a foreign id returns an empty `Optional` — identical to
a nonexistent id.

Consequently, cross-tenant access reports **404, not 403**. A 403 would confirm that the resource
exists and belongs to someone else, which is an existence oracle. `TenantIsolationIT`
asserts that a real-but-foreign id and a random UUID produce the same error code.

`organization_id` is a real column on every tenant-scoped table rather than a join away. A join
would make every read more expensive and every index less selective.

### 3. PostgreSQL row-level security *(implemented — `V4__row_level_security.sql`)*

Layers 1 and 2 are application code, and application code has bugs. RLS policies scoped to
`current_setting('signalforge.current_org')` make isolation hold even when application code is
wrong: a repository method that forgets its `WHERE` clause returns nothing instead of leaking.

Policies cover 18 tenant-scoped tables. `TenantAwareDataSource` stamps the setting on every
connection; `TenantBinder` stamps it on in-flight transactions for paths that need to act as a
specific tenant.

**The runtime connects as `signalforge_app`, which does not own the tables and is not a superuser.**
This is the part that is easy to get wrong: PostgreSQL exempts superusers and table owners from RLS
unconditionally, and the `postgres` image makes `POSTGRES_USER` a superuser — so the first version
of this migration applied cleanly and enforced nothing. Flyway still migrates as the owner, which
means owner credentials remain a real escalation path; they are separate credentials and used only
for migrations.

Three narrow `SECURITY DEFINER` functions are declared in the schema as deliberate bypasses:
login-by-email and API-key-by-hash (both run *before* a tenant is known), and an ids-only helper
for the detection sweep. That last one returns identifiers rather than rows, so the sweep gets a
work list and then re-reads each tenant's data through the policies. A test asserts no fourth
bypass exists.

## Testing

Isolation is proven by **negative** tests. A positive test ("A can read A's data") passes just as
happily against a system with no isolation at all.

`TenantIsolationIT` covers, at both the HTTP and repository layers:

- A cannot read, update or archive B's service
- A's listing and counts contain only A's rows
- An **ADMIN** of A gets nothing from B — privilege does not cross the tenant line
- Foreign and nonexistent ids are indistinguishable
- A control assertion that the same id *does* resolve for its owner, so the tests above cannot pass
  merely because the id was wrong

The unique constraint on telemetry is `(organization_id, event_id)`, not `event_id` alone, so two
tenants generating the same UUID do not collide — one tenant cannot suppress another's telemetry.
`TelemetryIdempotencyIT.eventIdIsScopedToOrganization` covers this.

## Consequences

**Positive**
- Isolation failures require two independent mistakes, not one.
- Tenant-first composite indexes (`organization_id` leading) match the access pattern exactly.

**Negative**
- Every repository method signature carries `organizationId`. This is verbose, and deliberately so:
  it is impossible to write a tenant-scoped query and forget the tenant, because the method will
  not compile without it.
- `JpaRepository.findById` is inherited and unscoped. Convention plus tests guard this; an ArchUnit
  rule banning its use outside `iam` would make it structural.
