"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import {
  IncidentSummary,
  ServiceSummary,
  api,
} from "@/lib/api";
import { StreamEvent, useIncidentStream } from "@/lib/stream";
import {
  AppShell,
  EmptyState,
  ErrorNotice,
  HealthBadge,
  SeverityBadge,
  Spinner,
  StatusBadge,
  formatDuration,
  formatTime,
} from "@/components/ui";

export default function DashboardPage() {
  return (
    <AppShell>
      <Dashboard />
    </AppShell>
  );
}

function Dashboard() {
  const [incidents, setIncidents] = useState<IncidentSummary[]>([]);
  const [services, setServices] = useState<ServiceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async () => {
    try {
      const [incidentList, serviceList] = await Promise.all([
        api.listIncidents(),
        api.listServices(),
      ]);
      setIncidents(incidentList);
      setServices(serviceList);
      setError(null);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Refetch on any incident event rather than trying to patch local state from
  // the event payload. The payload is a notification, not a full record, and
  // reconstructing state from partial events is how dashboards drift out of
  // sync with the database.
  useIncidentStream(
    useCallback(
      (event: StreamEvent) => {
        if (event.name.startsWith("incident.")) void load();
      },
      [load],
    ),
  );

  if (loading) return <Spinner />;
  if (error) return <ErrorNotice error={error} />;

  const open = incidents.filter((i) => i.status !== "RESOLVED");
  const resolved = incidents.filter((i) => i.status === "RESOLVED");
  const degraded = services.filter(
    (s) => s.healthStatus === "DEGRADED" || s.healthStatus === "DOWN",
  );

  // MTTR over resolved incidents only. Including open ones would report a
  // steadily-improving MTTR simply because unresolved incidents have no
  // resolution time yet - a genuinely misleading metric.
  const resolvedWithTime = resolved.filter((i) => i.timeToResolveMs != null);
  const mttrMs =
    resolvedWithTime.length === 0
      ? null
      : resolvedWithTime.reduce((sum, i) => sum + (i.timeToResolveMs ?? 0), 0) /
        resolvedWithTime.length;

  const detected = incidents.filter((i) => i.timeToDetectMs != null);
  const medianDetectMs =
    detected.length === 0
      ? null
      : median(detected.map((i) => i.timeToDetectMs ?? 0));

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-lg font-semibold">Overview</h1>
        <p className="text-sm text-slate-400">
          Live reliability posture across {services.length} registered service
          {services.length === 1 ? "" : "s"}
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Active incidents" value={String(open.length)}
          tone={open.length > 0 ? "bad" : "good"} />
        <Stat label="Services degraded" value={String(degraded.length)}
          tone={degraded.length > 0 ? "warn" : "good"} />
        <Stat label="Median detection latency" value={formatDuration(medianDetectMs)}
          hint="threshold breach → incident created" />
        <Stat label="MTTR (resolved)" value={formatDuration(mttrMs)}
          hint={`${resolvedWithTime.length} resolved incident(s)`} />
      </div>

      <section>
        <h2 className="mb-2 text-sm font-semibold text-slate-300">Active incidents</h2>
        {open.length === 0 ? (
          <EmptyState title="No active incidents." hint="Everything registered is currently within its SLA." />
        ) : (
          <div className="space-y-2">
            {open.map((incident) => (
              <Link key={incident.id} href={`/incidents/${incident.id}`}
                className="card flex items-center gap-4 hover:border-slate-600">
                <SeverityBadge severity={incident.severity} />
                <StatusBadge status={incident.status} />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{incident.title}</p>
                  <p className="text-xs text-slate-500">
                    {incident.reference} · detected {formatTime(incident.detectedAt)}
                  </p>
                </div>
                <span className="text-xs text-slate-400">
                  {formatDuration(incident.durationSeconds * 1000)}
                </span>
              </Link>
            ))}
          </div>
        )}
      </section>

      <section>
        <h2 className="mb-2 text-sm font-semibold text-slate-300">Service health</h2>
        {services.length === 0 ? (
          <EmptyState title="No services registered yet."
            hint="Register a service, then send telemetry to POST /api/v1/ingest/events." />
        ) : (
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {services.map((service) => (
              <Link key={service.id} href={`/services/${service.id}`}
                className="card flex items-center justify-between hover:border-slate-600">
                <div>
                  <p className="text-sm font-medium">{service.name}</p>
                  <p className="text-xs text-slate-500">
                    {service.environment} · SLA p95 {service.expectedP95LatencyMs}ms
                  </p>
                </div>
                <HealthBadge health={service.healthStatus} />
              </Link>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function Stat({
  label,
  value,
  hint,
  tone = "neutral",
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: "good" | "warn" | "bad" | "neutral";
}) {
  const toneClass = {
    good: "text-emerald-300",
    warn: "text-amber-300",
    bad: "text-red-300",
    neutral: "text-slate-100",
  }[tone];

  return (
    <div className="card">
      <p className="text-xs uppercase tracking-wide text-slate-500">{label}</p>
      <p className={`mt-1 text-2xl font-semibold ${toneClass}`}>{value}</p>
      {hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
    </div>
  );
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[mid - 1] + sorted[mid]) / 2
    : sorted[mid];
}
