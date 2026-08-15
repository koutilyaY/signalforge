"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import {
  AiSummary,
  EvidenceBundle,
  IncidentDetail,
  IncidentStatus,
  api,
} from "@/lib/api";
import {
  AppShell,
  ErrorNotice,
  SeverityBadge,
  Spinner,
  StatusBadge,
  formatDuration,
  formatTime,
} from "@/components/ui";

export default function IncidentDetailPage() {
  return (
    <AppShell>
      <IncidentView />
    </AppShell>
  );
}

function IncidentView() {
  const params = useParams<{ id: string }>();
  const incidentId = params.id;

  const [detail, setDetail] = useState<IncidentDetail | null>(null);
  const [evidence, setEvidence] = useState<EvidenceBundle | null>(null);
  const [ai, setAi] = useState<AiSummary | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [comment, setComment] = useState("");

  const load = useCallback(async () => {
    try {
      const [d, e] = await Promise.all([
        api.getIncident(incidentId),
        api.getCorrelation(incidentId),
      ]);
      setDetail(d);
      setEvidence(e);
      setError(null);
    } catch (e) {
      setError(e);
    }
  }, [incidentId]);

  useEffect(() => {
    void load();
  }, [load]);

  // The AI summary is fetched separately and its failure is contained here.
  // If Ollama is down, the incident page must still render everything else -
  // that is the whole point of the assistant being optional.
  useEffect(() => {
    api
      .getAiSummary(incidentId)
      .then(setAi)
      .catch(() => setAi({ available: false, reason: "AI assistant is not reachable" }));
  }, [incidentId]);

  async function transition(status: IncidentStatus) {
    if (!detail) return;
    setBusy(true);
    try {
      const updated = await api.transitionIncident(
        incidentId,
        status,
        undefined,
        detail.version,
      );
      setDetail(updated);
      setError(null);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  async function submitComment(event: React.FormEvent) {
    event.preventDefault();
    if (!comment.trim()) return;
    setBusy(true);
    try {
      await api.commentOnIncident(incidentId, comment.trim());
      setComment("");
      await load();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  if (error && !detail) return <ErrorNotice error={error} />;
  if (!detail) return <Spinner />;

  const incident = detail.incident;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <SeverityBadge severity={incident.severity} />
        <StatusBadge status={incident.status} />
        <h1 className="text-lg font-semibold">{incident.title}</h1>
        <span className="font-mono text-xs text-slate-500">{incident.reference}</span>
      </div>

      {error != null && <ErrorNotice error={error} />}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Metric label="Started" value={formatTime(incident.startedAt)} />
        <Metric label="Detection latency" value={formatDuration(incident.timeToDetectMs)}
          hint="breach → incident created" />
        <Metric label="Time to acknowledge" value={formatDuration(incident.timeToAcknowledgeMs)} />
        <Metric label="Duration" value={formatDuration(incident.durationSeconds * 1000)} />
      </div>

      {/* Lifecycle actions come straight from the server's allowedTransitions,
          so the UI never has to duplicate the state machine. */}
      {detail.allowedTransitions.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {detail.allowedTransitions.map((status) => (
            <button key={status} className="btn-ghost" disabled={busy}
              onClick={() => transition(status)}>
              {status === "ACKNOWLEDGED" ? "Acknowledge" : `Mark ${status.toLowerCase()}`}
            </button>
          ))}
        </div>
      )}

      <section className="card">
        <h2 className="mb-3 text-sm font-semibold text-slate-300">
          Likely contributing factors
        </h2>
        {!evidence || evidence.factors.length === 0 ? (
          <p className="text-sm text-slate-400">
            Insufficient evidence to determine a root cause. No deployments, shared traces
            or dominant error signatures correlate with this incident.
          </p>
        ) : (
          <ol className="space-y-3">
            {evidence.factors.map((factor, index) => (
              <li key={index} className="rounded-md border border-slate-800 p-3">
                <div className="flex items-center gap-2">
                  <span className="badge bg-slate-700/50 text-slate-200">{factor.kind}</span>
                  <span className="text-sm font-medium">{factor.summary}</span>
                  <span className="ml-auto text-xs text-slate-400">
                    {factor.confidence}% confidence
                  </span>
                </div>
                <ul className="mt-2 space-y-1">
                  {factor.evidence.map((line, i) => (
                    <li key={i} className="text-xs text-slate-400">— {line}</li>
                  ))}
                </ul>
              </li>
            ))}
          </ol>
        )}
      </section>

      <AiPanel summary={ai} />

      <section className="card">
        <h2 className="mb-3 text-sm font-semibold text-slate-300">Timeline</h2>
        <ol className="space-y-3">
          {detail.timeline.map((entry) => (
            <li key={entry.id} className="flex gap-3">
              <span className="w-32 shrink-0 font-mono text-xs text-slate-500">
                {formatTime(entry.occurredAt)}
              </span>
              <span className="badge shrink-0 bg-slate-800 text-slate-300">{entry.kind}</span>
              <div className="min-w-0">
                <p className="text-sm">{entry.title}</p>
                {entry.detail && <p className="text-xs text-slate-500">{entry.detail}</p>}
              </div>
            </li>
          ))}
        </ol>
      </section>

      {evidence && evidence.deployments.length > 0 && (
        <section className="card">
          <h2 className="mb-3 text-sm font-semibold text-slate-300">Recent deployments</h2>
          <div className="space-y-2">
            {evidence.deployments.map((d) => (
              <div key={d.deploymentId} className="flex items-center gap-3 text-sm">
                <span className="font-medium">{d.serviceName}</span>
                <span className="font-mono text-xs text-slate-400">{d.version}</span>
                {d.isPrimaryService && (
                  <span className="badge bg-orange-500/15 text-orange-300">affected service</span>
                )}
                <span className="ml-auto text-xs text-slate-400">
                  {d.minutesBeforeIncident} min before incident
                </span>
              </div>
            ))}
          </div>
        </section>
      )}

      <section className="card">
        <h2 className="mb-3 text-sm font-semibold text-slate-300">Comments</h2>
        <div className="space-y-2">
          {detail.comments.map((c) => (
            <div key={c.id} className="rounded-md border border-slate-800 p-2">
              <p className="text-sm">{c.body}</p>
              <p className="text-xs text-slate-500">{formatTime(c.createdAt)}</p>
            </div>
          ))}
        </div>
        <form onSubmit={submitComment} className="mt-3 flex gap-2">
          <input className="input" placeholder="Add a note…" value={comment}
            onChange={(e) => setComment(e.target.value)} />
          <button className="btn-primary" disabled={busy || !comment.trim()}>Post</button>
        </form>
      </section>
    </div>
  );
}

/**
 * The AI panel is explicitly allowed to be absent. It renders a plain
 * explanation rather than an error, because "no LLM configured" is a supported
 * operating mode, not a failure.
 */
function AiPanel({ summary }: { summary: AiSummary | null }) {
  if (!summary) return null;

  return (
    <section className="card">
      <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-slate-300">
        AI incident summary
        {summary.model && (
          <span className="badge bg-slate-800 font-mono text-slate-400">{summary.model}</span>
        )}
      </h2>

      {!summary.available ? (
        <p className="text-sm text-slate-400">
          {summary.reason ?? "AI assistant is not enabled."} Detection, correlation and
          incident management are unaffected.
        </p>
      ) : (
        <div className="space-y-3">
          <p className="text-sm text-slate-200">{summary.summary}</p>

          {summary.likelyCauses && summary.likelyCauses.length > 0 && (
            <div>
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                Likely causes
              </h3>
              <ul className="mt-1 space-y-2">
                {summary.likelyCauses.map((cause, i) => (
                  <li key={i}>
                    <p className="text-sm">{cause.cause}</p>
                    <ul className="mt-0.5">
                      {cause.evidence.map((e, j) => (
                        <li key={j} className="text-xs text-slate-500">— {e}</li>
                      ))}
                    </ul>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {summary.recommendedSteps && summary.recommendedSteps.length > 0 && (
            <div>
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                Recommended investigation
              </h3>
              <ol className="mt-1 list-inside list-decimal space-y-0.5">
                {summary.recommendedSteps.map((step, i) => (
                  <li key={i} className="text-xs text-slate-400">{step}</li>
                ))}
              </ol>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

function Metric({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="card">
      <p className="text-xs uppercase tracking-wide text-slate-500">{label}</p>
      <p className="mt-1 text-lg font-semibold">{value}</p>
      {hint && <p className="text-xs text-slate-500">{hint}</p>}
    </div>
  );
}
