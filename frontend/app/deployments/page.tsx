"use client";

import { useEffect, useState } from "react";
import { DeploymentSummary, ServiceSummary, api } from "@/lib/api";
import { AppShell, EmptyState, ErrorNotice, Spinner, formatDuration, formatTime } from "@/components/ui";

export default function DeploymentsPage() {
  return <AppShell><DeploymentList /></AppShell>;
}

function DeploymentList() {
  const [deployments, setDeployments] = useState<DeploymentSummary[]>([]);
  const [services, setServices] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    Promise.all([api.listDeployments(), api.listServices()])
      .then(([d, s]) => {
        setDeployments(d);
        setServices(Object.fromEntries(s.map((x: ServiceSummary) => [x.id, x.name])));
      })
      .catch(setError)
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spinner />;
  if (error) return <ErrorNotice error={error} />;

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold">Deployments</h1>
      {deployments.length === 0 ? (
        <EmptyState title="No deployments recorded."
          hint="Have CI POST to /api/v1/deployments at rollout time — this is what powers deployment correlation." />
      ) : (
        <div className="space-y-2">
          {deployments.map((d) => (
            <div key={d.id} className="card flex flex-wrap items-center gap-3 text-sm">
              <span className="font-medium">{services[d.serviceId] ?? "(unknown service)"}</span>
              <span className="font-mono text-xs text-slate-400">{d.version}</span>
              <span className="badge bg-slate-800 text-slate-300">{d.status}</span>
              {d.commitSha && <span className="font-mono text-xs text-slate-500">{d.commitSha.slice(0, 8)}</span>}
              {d.deployedBy && <span className="text-xs text-slate-500">by {d.deployedBy}</span>}
              <span className="ml-auto text-xs text-slate-400">
                {formatTime(d.startedAt)} · {formatDuration(d.durationSeconds * 1000)}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
