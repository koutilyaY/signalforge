"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { IncidentStatus, IncidentSummary, api } from "@/lib/api";
import { StreamEvent, useIncidentStream } from "@/lib/stream";
import {
  AppShell, EmptyState, ErrorNotice, SeverityBadge, Spinner, StatusBadge,
  formatDuration, formatTime,
} from "@/components/ui";

const FILTERS: (IncidentStatus | "ALL")[] = ["ALL", "OPEN", "ACKNOWLEDGED", "INVESTIGATING", "MITIGATED", "RESOLVED"];

export default function IncidentsPage() {
  return <AppShell><IncidentList /></AppShell>;
}

function IncidentList() {
  const [incidents, setIncidents] = useState<IncidentSummary[]>([]);
  const [filter, setFilter] = useState<IncidentStatus | "ALL">("ALL");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async () => {
    try {
      setIncidents(await api.listIncidents(filter === "ALL" ? undefined : filter));
      setError(null);
    } catch (e) { setError(e); } finally { setLoading(false); }
  }, [filter]);

  useEffect(() => { void load(); }, [load]);
  useIncidentStream(useCallback((e: StreamEvent) => { if (e.name.startsWith("incident.")) void load(); }, [load]));

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <h1 className="text-lg font-semibold">Incidents</h1>
        <div className="ml-auto flex gap-1">
          {FILTERS.map((f) => (
            <button key={f} onClick={() => setFilter(f)}
              className={`rounded-md px-2.5 py-1 text-xs ${filter === f ? "bg-slate-700 text-white" : "text-slate-400 hover:text-slate-200"}`}>
              {f}
            </button>
          ))}
        </div>
      </div>

      {error != null && <ErrorNotice error={error} />}
      {loading ? <Spinner /> : incidents.length === 0 ? (
        <EmptyState title="No incidents match this filter." />
      ) : (
        <div className="space-y-2">
          {incidents.map((incident) => (
            <Link key={incident.id} href={`/incidents/${incident.id}`}
              className="card flex items-center gap-4 hover:border-slate-600">
              <SeverityBadge severity={incident.severity} />
              <StatusBadge status={incident.status} />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium">{incident.title}</p>
                <p className="text-xs text-slate-500">
                  {incident.reference} · detected {formatTime(incident.detectedAt)}
                  {" · detection latency "}{formatDuration(incident.timeToDetectMs)}
                </p>
              </div>
              <span className="text-xs text-slate-400">{formatDuration(incident.durationSeconds * 1000)}</span>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
