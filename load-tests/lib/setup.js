// Shared bootstrap for every k6 scenario.
//
// Each run creates its OWN organization with a unique slug rather than reusing a
// fixture. Two reasons: the per-tenant rate limiter would otherwise carry state
// between runs and make the second run slower for reasons unrelated to the code,
// and a run that pollutes a shared tenant makes the next benchmark
// unreproducible.

import http from "k6/http";
import { check, fail } from "k6";

export const BASE_URL = __ENV.SF_BASE_URL || "http://localhost:8099";

/** Creates an organization, a service, and returns credentials for the run. */
export function bootstrap(label) {
  const slug = `bench-${label}-${Date.now()}`.toLowerCase().slice(0, 60);

  const registration = http.post(
    `${BASE_URL}/api/v1/auth/register-organization`,
    JSON.stringify({
      organizationName: `Benchmark ${label}`,
      organizationSlug: slug,
      adminEmail: `admin@${slug}.test`,
      adminFullName: "Benchmark Admin",
      adminPassword: "benchmark-correct-horse-battery",
    }),
    { headers: { "Content-Type": "application/json" } },
  );

  if (registration.status !== 201) {
    fail(`bootstrap failed to register organization: ${registration.status} ${registration.body}`);
  }

  const token = registration.json("accessToken");
  const authHeaders = {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };

  // Raise the ingestion quota well above what the benchmark will use. The
  // limiter is a correctness feature tested elsewhere; leaving it at the default
  // here would measure the limiter rather than the ingestion path.
  const orgUpdate = http.patch(
    `${BASE_URL}/api/v1/organization`,
    JSON.stringify({ ingestRateLimitPerMinute: 5000000 }),
    { headers: authHeaders },
  );
  // Non-fatal: if the endpoint is absent the default quota may still be enough.
  if (orgUpdate.status >= 400 && orgUpdate.status !== 404) {
    console.warn(`could not raise rate limit: ${orgUpdate.status}`);
  }

  const service = http.post(
    `${BASE_URL}/api/v1/services`,
    JSON.stringify({
      name: "checkout-service",
      environment: "PRODUCTION",
      criticality: "HIGH",
      expectedP95LatencyMs: 500,
      expectedErrorRate: 0.01,
    }),
    { headers: authHeaders },
  );

  if (service.status !== 201) {
    fail(`bootstrap failed to create service: ${service.status} ${service.body}`);
  }

  return {
    token,
    slug,
    serviceId: service.json("id"),
    headers: authHeaders,
  };
}

/** Builds one telemetry event. `iteration`/`vu` keep event ids unique across VUs. */
export function telemetryEvent(serviceId, vu, iteration, index) {
  // A deterministic-but-unique UUID per event. Reusing ids would be silently
  // absorbed by the idempotency constraint and the benchmark would measure
  // deduplication instead of insertion.
  const eventId = uuid(vu, iteration, index);
  const latency = latencySample();
  const failed = Math.random() < 0.02;

  return {
    eventId,
    serviceId,
    occurredAt: new Date().toISOString(),
    eventType: "HTTP_REQUEST",
    httpMethod: "GET",
    httpPath: "/checkout",
    statusCode: failed ? 500 : 200,
    latencyMs: latency,
    traceId: `trace-${vu}-${iteration}-${index}`,
    instanceKey: `instance-${vu % 4}`,
  };
}

/**
 * Log-normal-ish latency so the histogram has a realistic long tail. A uniform
 * distribution would make p95 meaninglessly close to p50 and hide exactly the
 * behaviour the percentile machinery exists to surface.
 */
function latencySample() {
  const roll = Math.random();
  if (roll < 0.5) return 20 + Math.floor(Math.random() * 40);
  if (roll < 0.85) return 60 + Math.floor(Math.random() * 140);
  if (roll < 0.97) return 200 + Math.floor(Math.random() * 300);
  return 500 + Math.floor(Math.random() * 2500);
}

function uuid(vu, iteration, index) {
  const hex = (n, len) => n.toString(16).padStart(len, "0").slice(-len);
  const rand = Math.floor(Math.random() * 0xffffffff);
  return (
    `${hex(vu, 8)}-${hex(iteration, 4)}-4${hex(index, 3)}-` +
    `a${hex(rand & 0xfff, 3)}-${hex(rand, 8)}${hex(Date.now() & 0xffff, 4)}`
  );
}

export function checkAccepted(response) {
  return check(response, {
    "status is 202": (r) => r.status === 202,
  });
}
