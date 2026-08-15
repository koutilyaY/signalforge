# Security

## Reporting

This is a portfolio project with no production deployment and no users. If you find something
anyway, open a GitHub issue.

## Threat model

The interesting adversary here is **an authenticated user of one tenant trying to reach another
tenant's data**. Everything else — an unauthenticated attacker, a malicious telemetry producer — is
also handled, but tenant isolation is where a bug would be most damaging and least visible.

## Authentication

**Humans** authenticate with email + password and receive a JWT access token (30 min) and refresh
token (7 days), both HS256.

- Refresh tokens carry `typ: refresh` and are **rejected** where an access token is expected.
  Without that check the long-lived token silently becomes a long-lived access token.
- The application **refuses to start** on a missing, short (<32 byte) or placeholder signing key.
  A misconfigured signing key is not something to discover in production.
- Tokens are re-validated against the database on refresh, so a role change or account disable
  takes effect rather than waiting out the token lifetime.

**Machines** (telemetry producers, CI) authenticate with `X-API-Key`.

- 256 bits of `SecureRandom`, shown once at creation, stored only as SHA-256.
- **SHA-256, not bcrypt** — deliberately, and this is not a shortcut. An API key is machine-generated
  high-entropy material with nothing to brute force, and it is verified on *every ingestion request*
  where a ~250 ms bcrypt would be a self-inflicted denial of service. Passwords, which are
  low-entropy and human-chosen, use bcrypt at cost 12.

### Anti-enumeration

Unknown account and wrong password return an **identical** code and message, and an unknown email
still pays a bcrypt verification against a dummy hash so response time does not leak. Verified by
smoke test and covered in `AuthorizationIT`.

Failed logins are throttled per email in Redis (8 attempts / 15 min).

## Authorization

Two independent layers, both must pass:

1. **Path rules** in `SecurityConfig`. `anyRequest().authenticated()` means a controller added
   tomorrow is protected by default rather than public by default.
2. **`@PreAuthorize` on methods**, plus tenant scoping inside every query. Path rules cannot express
   "this incident belongs to your organization"; only the query can.

| Role | Can |
|---|---|
| VIEWER | Read services, incidents, deployments, analytics |
| ENGINEER | + manage services, ingest telemetry, record deployments, drive incident lifecycle |
| ADMIN | + organization settings, users, API keys, audit log |

Authorities are cumulative — an ADMIN holds `ROLE_ENGINEER` and `ROLE_VIEWER` — so
`hasRole('ENGINEER')` does the right thing without enumerating roles at every endpoint.

An ingestion API key authenticates as ENGINEER: enough to write telemetry and register deployments,
never enough to change organization settings or manage users.

**13 authorization tests, all denials.** A suite that only checks "ADMIN can do X" cannot tell a
working authorization model from one that lets everyone do everything.

## Tenant isolation

See [ADR-0003](docs/adr/ADR-0003-tenant-isolation.md) for the full design. The short version:

- The organization id comes from a signed token claim, never from client input.
- Every tenant-scoped query includes `organization_id` in its `WHERE` clause.
- Cross-tenant access returns **404, not 403** — a 403 confirms the resource exists.
- Cache keys are tenant-prefixed; a shared key would leak quota across tenants.
- Kafka messages carry `organizationId` in the payload; a consumer never infers tenant from ambient
  state.
- The telemetry unique constraint is `(organization_id, event_id)`, not `event_id` alone, so two
  tenants generating the same UUID cannot suppress each other's data.

**PostgreSQL row-level security is implemented and enforcing.** Policies on 18 tenant-scoped tables
back the application-level scoping above, so a repository method that forgets its `WHERE` clause
returns nothing rather than another tenant's rows. The application connects as `signalforge_app`, a
non-owning non-superuser role, because RLS does not apply to superusers or table owners — the
policies are inert without that. Verified by `RowLevelSecurityIT`, which runs unscoped raw SQL and
asserts the database withholds the rows.

The residual risk is credential scope, not policy coverage: an attacker holding the **Flyway/owner**
credentials could disable the policies. Those are separate credentials, used only for migrations.

## Input handling

- Bean Validation on every request DTO; violations return a structured field list.
- Telemetry batches capped at 500 events; events outside the clock-skew and age bounds are rejected
  rather than silently stored, because a producer with a broken clock otherwise poisons every
  time-window computation downstream.
- Client-supplied correlation ids are accepted only if they match `^[A-Za-z0-9_.:-]{8,120}$` —
  without that, a caller could inject newlines or a multi-kilobyte string into every log line.
- `X-Forwarded-For` is length-capped and only the left-most entry is taken; the header is
  attacker-controlled.
- Kafka consumers **do not trust producer `__TypeId__` headers**. The target type is pinned on the
  consumer, because trusting a producer-supplied class name is the shape of a deserialization gadget
  attack.

## Rate limiting

Per-organization sliding-window counter in Redis, implemented as a single Lua script so the
read-compute-write is atomic server-side. Cost is charged **per event, not per request**, so a
client cannot bypass its quota by batching. Returns 429 with `X-RateLimit-*` headers.

## Error handling

One envelope for every non-2xx response: stable machine-readable `code`, safe message, timestamp,
correlation id.

Never returned to a client: stack traces, SQL, constraint names, class names, dependency details.
`server.error.include-stacktrace: never` and `include-message: never` are set as a backstop.
Unexpected exceptions are logged in full with the correlation id and rendered as an opaque 500 — the
correlation id is the operator's only bridge from a user's screenshot to the internals.

## Audit log

Append-only, enforced by a database trigger that rejects `UPDATE` and `DELETE` regardless of what
the ORM attempts. Records login success/failure/lockout, organization and user changes, API key
lifecycle, service changes, and every incident transition.

Audit writes run in `REQUIRES_NEW` transactions so a record for a **denied or failed** action
survives the rollback of the transaction that failed — otherwise the most security-relevant events
would be exactly the ones never recorded.

## Secrets

- `.env` is git-ignored; `.env.example` carries placeholders only.
- The application refuses to start on the placeholder JWT secret.
- No secret is logged. The gitleaks job in CI scans full history.
- CI uses a test-only signing key from workflow env, never a real one.

## Frontend

Tokens live in `sessionStorage`, not `localStorage` — they die with the tab rather than persisting
on a shared machine. **Neither is immune to XSS**; an httpOnly cookie would be, at the cost of
needing CSRF protection back. This is a documented trade-off, not an oversight.

CSRF is disabled server-side, which is safe **only because** the credential is a bearer token in a
header rather than a cookie the browser attaches automatically.

The SSE stream is read with `fetch()` + `ReadableStream` rather than `EventSource`, specifically so
the bearer token stays in an `Authorization` header instead of a query string that would land in
proxy logs, browser history and `Referer` headers.

Response headers set: HSTS, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`. CORS allows explicit origins only, never `*` with credentials.

## Dependency and supply chain

OWASP Dependency-Check and gitleaks run in CI. Dependency-Check is **advisory rather than a merge
gate** — CVE feeds produce false positives on transitive test-scope dependencies, and a build that
cries wolf gets ignored. Findings are reviewed.

## Known gaps

Listed because an undocumented gap is worse than a documented one:

1. **No row-level security.** Isolation rests on application code.
2. **No token revocation list.** A stolen access token is valid until it expires (30 min).
3. **No MFA.**
4. **Login throttle fails open** when Redis is down — deliberate, see ADR-0005 reasoning in
   `LoginThrottle`.
5. **`/actuator/prometheus` is unauthenticated** so Prometheus can scrape it inside the compose
   network. In a real deployment this belongs on an internal interface or behind network policy.
6. **No penetration test.** The security properties here are the ones the test suite asserts;
   nobody has attacked this adversarially.
