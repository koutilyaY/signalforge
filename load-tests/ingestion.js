// Telemetry ingestion benchmark.
//
// Measures the path a monitored service actually exercises: authenticate, rate
// limit, validate, publish to Kafka, return 202. It deliberately does NOT wait
// for the record to reach PostgreSQL - that is asynchronous by design, and
// measuring it here would conflate producer latency with consumer throughput.
// Pipeline lag is measured separately by the
// signalforge_pipeline_ingest_to_persist metric.
//
//   k6 run load-tests/ingestion.js
//   k6 run -e BATCH_SIZE=100 -e SF_BASE_URL=http://localhost:8099 load-tests/ingestion.js

import http from "k6/http";
import { Trend, Counter } from "k6/metrics";
import { BASE_URL, bootstrap, checkAccepted, telemetryEvent } from "./lib/setup.js";

const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || "50", 10);

const eventsPublished = new Counter("sf_events_published");
const batchLatency = new Trend("sf_batch_latency", true);

export const options = {
  scenarios: {
    ingestion: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "15s", target: 10 }, // warm up: JIT, connection pools, page cache
        { duration: "45s", target: 30 }, // steady state - this is what gets reported
        { duration: "10s", target: 0 },
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    // Recorded, not enforced as a gate. A threshold that fails the run on a
    // laptop under variable load produces noise, not signal.
    http_req_duration: ["p(95)<2000"],
    http_req_failed: ["rate<0.05"],
  },
  // The first 15s is warm-up and must not pollute the reported percentiles.
  // k6 has no built-in warm-up exclusion, so the report notes the ramp shape
  // and the summary is read from the steady-state window.
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  const context = bootstrap("ingest");
  console.log(`bootstrapped org=${context.slug} service=${context.serviceId}`);
  return context;
}

export default function (context) {
  const events = [];
  for (let i = 0; i < BATCH_SIZE; i++) {
    events.push(telemetryEvent(context.serviceId, __VU, __ITER, i));
  }

  const response = http.post(
    `${BASE_URL}/api/v1/ingest/events`,
    JSON.stringify({ events }),
    { headers: context.headers, tags: { endpoint: "ingest" } },
  );

  checkAccepted(response);
  batchLatency.add(response.timings.duration);
  if (response.status === 202) {
    eventsPublished.add(BATCH_SIZE);
  }
}

export function handleSummary(data) {
  const label = __ENV.RUN_LABEL || "run";
  return {
    [`load-tests/results/ingestion-${label}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const d = data.metrics.http_req_duration.values;
  const published = data.metrics.sf_events_published
    ? data.metrics.sf_events_published.values.count
    : 0;
  const seconds = data.state.testRunDurationMs / 1000;

  return `
=== Ingestion benchmark ===
  batch size          ${BATCH_SIZE} events/request
  requests            ${data.metrics.http_reqs.values.count}
  events published    ${published}
  events/sec          ${(published / seconds).toFixed(1)}
  requests/sec        ${data.metrics.http_reqs.values.rate.toFixed(1)}
  error rate          ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
  p50 latency         ${d.med.toFixed(1)} ms
  p95 latency         ${d["p(95)"].toFixed(1)} ms
  p99 latency         ${d["p(99)"].toFixed(1)} ms
  max latency         ${d.max.toFixed(1)} ms
`;
}
