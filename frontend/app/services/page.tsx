"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ServiceSummary, api } from "@/lib/api";
import { AppShell, EmptyState, ErrorNotice, HealthBadge, SeverityBadge, Spinner } from "@/components/ui";

export default function ServicesPage() {
  return <AppShell><ServiceList /></AppShell>;
}

function ServiceList() {
  const [services, setServices] = useState<ServiceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    api.listServices().then(setServices).catch(setError).finally(() => setLoading(false));
  }, []);

  if (loading) return <Spinner />;
  if (error) return <ErrorNotice error={error} />;

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold">Services</h1>
      {services.length === 0 ? (
        <EmptyState title="No services registered."
          hint="POST /api/v1/services to register one, then send telemetry with an API key." />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="pb-2">Service</th><th className="pb-2">Environment</th>
                <th className="pb-2">Criticality</th><th className="pb-2">SLA p95</th>
                <th className="pb-2">Error budget</th><th className="pb-2">Health</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {services.map((s) => (
                <tr key={s.id} className="hover:bg-slate-900/50">
                  <td className="py-2">
                    <Link href={`/services/${s.id}`} className="font-medium hover:text-sky-300">{s.name}</Link>
                    {s.team && <span className="ml-2 text-xs text-slate-500">{s.team}</span>}
                  </td>
                  <td className="py-2 text-slate-400">{s.environment}</td>
                  <td className="py-2"><SeverityBadge severity={s.criticality} /></td>
                  <td className="py-2 text-slate-400">{s.expectedP95LatencyMs}ms</td>
                  <td className="py-2 text-slate-400">{(s.expectedErrorRate * 100).toFixed(2)}%</td>
                  <td className="py-2"><HealthBadge health={s.healthStatus} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
