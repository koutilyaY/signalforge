"use client";

import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { DeploymentSummary, ServiceSummary, api } from "@/lib/api";
import { AppShell, EmptyState, ErrorNotice, HealthBadge, Spinner, formatTime } from "@/components/ui";

export default function ServiceDetailPage() {
  return <AppShell><ServiceDetail /></AppShell>;
}

function ServiceDetail() {
  const params = useParams<{ id: string }>();
  const [service, setService] = useState<ServiceSummary | null>(null);
  const [deployments, setDeployments] = useState<DeploymentSummary[]>([]);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    Promise.all([api.getService(params.id), api.listDeployments(params.id)])
      .then(([s, d]) => { setService(s); setDeployments(d); })
      .catch(setError);
  }, [params.id]);

  if (error) return <ErrorNotice error={error} />;
  if (!service) return <Spinner />;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <h1 className="text-lg font-semibold">{service.name}</h1>
        <HealthBadge health={service.healthStatus} />
        <span className="text-xs text-slate-500">{service.environment}</span>
      </div>

      {service.description && <p className="text-sm text-slate-400">{service.description}</p>}

      <div className="grid gap-4 sm:grid-cols-3">
        <div className="card">
          <p className="text-xs uppercase tracking-wide text-slate-500">SLA p95 latency</p>
          <p className="mt-1 text-lg font-semibold">{service.expectedP95LatencyMs}ms</p>
        </div>
        <div className="card">
          <p className="text-xs uppercase tracking-wide text-slate-500">Error budget</p>
          <p className="mt-1 text-lg font-semibold">{(service.expectedErrorRate * 100).toFixed(2)}%</p>
        </div>
        <div className="card">
          <p className="text-xs uppercase tracking-wide text-slate-500">Health changed</p>
          <p className="mt-1 text-lg font-semibold">{formatTime(service.healthChangedAt)}</p>
        </div>
      </div>

      <section>
        <h2 className="mb-2 text-sm font-semibold text-slate-300">Recent deployments</h2>
        {deployments.length === 0 ? (
          <EmptyState title="No deployments recorded for this service." />
        ) : (
          <div className="space-y-2">
            {deployments.map((d) => (
              <div key={d.id} className="card flex items-center gap-3 text-sm">
                <span className="font-mono">{d.version}</span>
                <span className="badge bg-slate-800 text-slate-300">{d.status}</span>
                {d.branch && <span className="text-xs text-slate-500">{d.branch}</span>}
                <span className="ml-auto text-xs text-slate-400">{formatTime(d.startedAt)}</span>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
