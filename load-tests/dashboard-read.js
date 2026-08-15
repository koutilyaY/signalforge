// Read-path benchmark: incident list, service list, incident detail, correlation.
//
// This is the benchmark that shows whether the pre-aggregated rollup design
// actually pays for itself. Run it against a seeded database (see
// scripts/seed-benchmark-data.sh) - against an empty one it measures nothing
// but framework overhead.
//
//   k6 run -e RUN_LABEL=baseline load-tests/dashboard-read.js

import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";
import { BASE_URL, bootstrap } from "./lib/setup.js";

const incidentList = new Trend("sf_incident_list", true);
const serviceList = new Trend("sf_service_list", true);
const correlation = new Trend("sf_correlation", true);

export const options = {
  scenarios: {
    reads: {
      executor: "constant-vus",
      vus: 20,
      duration: "45s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  // Reuse an existing seeded tenant when one is supplied, otherwise bootstrap a
  // fresh (empty) one so the script still runs standalone.
  if (__ENV.SF_TOKEN && __ENV.SF_SERVICE_ID) {
    return {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${__ENV.SF_TOKEN}`,
      },
      serviceId: __ENV.SF_SERVICE_ID,
      seeded: true,
    };
  }
  return { ...bootstrap("read"), seeded: false };
}

export default function (context) {
  const incidents = http.get(`${BASE_URL}/api/v1/incidents?size=50`, {
    headers: context.headers,
    tags: { endpoint: "incident-list" },
  });
  check(incidents, { "incidents 200": (r) => r.status === 200 });
  incidentList.add(incidents.timings.duration);

  const services = http.get(`${BASE_URL}/api/v1/services`, {
    headers: context.headers,
    tags: { endpoint: "service-list" },
  });
  check(services, { "services 200": (r) => r.status === 200 });
  serviceList.add(services.timings.duration);

  // Correlation is the expensive read: several aggregate queries over telemetry.
  // Only exercised when there is an incident to correlate.
  if (incidents.status === 200) {
    const body = incidents.json();
    if (Array.isArray(body) && body.length > 0) {
      const target = body[Math.floor(Math.random() * body.length)];
      const rca = http.get(`${BASE_URL}/api/v1/incidents/${target.id}/correlation`, {
        headers: context.headers,
        tags: { endpoint: "correlation" },
      });
      check(rca, { "correlation 200": (r) => r.status === 200 });
      correlation.add(rca.timings.duration);
    }
  }
}

export function handleSummary(data) {
  const label = __ENV.RUN_LABEL || "run";
  return {
    [`load-tests/results/dashboard-${label}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const line = (name, metric) => {
    if (!data.metrics[metric]) return `  ${name.padEnd(20)} (not exercised)\n`;
    const v = data.metrics[metric].values;
    return `  ${name.padEnd(20)} p50 ${v.med.toFixed(1)}ms  p95 ${v["p(95)"].toFixed(1)}ms  p99 ${v["p(99)"].toFixed(1)}ms\n`;
  };

  return `
=== Dashboard read benchmark ===
  requests/sec        ${data.metrics.http_reqs.values.rate.toFixed(1)}
  error rate          ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
${line("incident list", "sf_incident_list")}${line("service list", "sf_service_list")}${line("correlation", "sf_correlation")}`;
}
