"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { CurrentUser, HealthStatus, IncidentStatus, Severity, tokens } from "@/lib/api";
import { useIncidentStream } from "@/lib/stream";

/** Severity colours defined once so a CRITICAL badge is the same red everywhere. */
const SEVERITY_CLASSES: Record<Severity, string> = {
  LOW: "bg-blue-500/15 text-blue-300 ring-1 ring-blue-500/30",
  MEDIUM: "bg-yellow-500/15 text-yellow-300 ring-1 ring-yellow-500/30",
  HIGH: "bg-orange-500/15 text-orange-300 ring-1 ring-orange-500/30",
  CRITICAL: "bg-red-500/15 text-red-300 ring-1 ring-red-500/30",
};

const STATUS_CLASSES: Record<IncidentStatus, string> = {
  OPEN: "bg-red-500/15 text-red-300 ring-1 ring-red-500/30",
  ACKNOWLEDGED: "bg-amber-500/15 text-amber-300 ring-1 ring-amber-500/30",
  INVESTIGATING: "bg-sky-500/15 text-sky-300 ring-1 ring-sky-500/30",
  MITIGATED: "bg-indigo-500/15 text-indigo-300 ring-1 ring-indigo-500/30",
  RESOLVED: "bg-emerald-500/15 text-emerald-300 ring-1 ring-emerald-500/30",
};

const HEALTH_CLASSES: Record<HealthStatus, string> = {
  HEALTHY: "bg-emerald-500/15 text-emerald-300 ring-1 ring-emerald-500/30",
  DEGRADED: "bg-amber-500/15 text-amber-300 ring-1 ring-amber-500/30",
  DOWN: "bg-red-500/15 text-red-300 ring-1 ring-red-500/30",
  // UNKNOWN is shown as its own state, never dressed up as healthy - a service
  // that has stopped reporting is exactly what you need to notice.
  UNKNOWN: "bg-slate-500/15 text-slate-300 ring-1 ring-slate-500/30",
};

export function SeverityBadge({ severity }: { severity: Severity }) {
  return <span className={`badge ${SEVERITY_CLASSES[severity]}`}>{severity}</span>;
}

export function StatusBadge({ status }: { status: IncidentStatus }) {
  return <span className={`badge ${STATUS_CLASSES[status]}`}>{status}</span>;
}

export function HealthBadge({ health }: { health: HealthStatus }) {
  return <span className={`badge ${HEALTH_CLASSES[health]}`}>{health}</span>;
}

/** Formats a duration in ms as a compact human string. */
export function formatDuration(ms: number | undefined | null): string {
  if (ms === undefined || ms === null) return "—";
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${minutes % 60}m`;
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

export function formatTime(iso: string | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function Spinner({ label = "Loading…" }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 py-8 text-sm text-slate-400">
      <span className="h-3 w-3 animate-spin rounded-full border-2 border-slate-600 border-t-sky-400" />
      {label}
    </div>
  );
}

export function ErrorNotice({ error }: { error: unknown }) {
  const message = error instanceof Error ? error.message : String(error);
  const correlationId =
    error && typeof error === "object" && "correlationId" in error
      ? (error as { correlationId?: string }).correlationId
      : undefined;

  return (
    <div className="card border-red-900/60 bg-red-950/30">
      <p className="text-sm text-red-200">{message}</p>
      {correlationId && (
        // Surfaced so a user can quote it in a bug report and an operator can
        // find the exact request in the logs.
        <p className="mt-1 font-mono text-xs text-red-400/70">
          correlation id: {correlationId}
        </p>
      )}
    </div>
  );
}

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="card text-center">
      <p className="text-sm text-slate-300">{title}</p>
      {hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
    </div>
  );
}

const NAV = [
  { href: "/dashboard", label: "Overview" },
  { href: "/services", label: "Services" },
  { href: "/incidents", label: "Incidents" },
  { href: "/deployments", label: "Deployments" },
];

/**
 * App shell. Also guards every page behind a token check - an unauthenticated
 * visitor is bounced to /login before any data fetch is attempted.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [checked, setChecked] = useState(false);

  const { connected, eventCount } = useIncidentStream();

  useEffect(() => {
    const current = tokens.user;
    if (!current || !tokens.access) {
      router.replace("/login");
      return;
    }
    setUser(current);
    setChecked(true);
  }, [router]);

  if (!checked) return <Spinner label="Checking session…" />;

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-800 bg-slate-900/50">
        <div className="mx-auto flex max-w-7xl items-center gap-6 px-6 py-3">
          <Link href="/dashboard" className="text-sm font-bold tracking-tight">
            Signal<span className="text-sky-400">Forge</span>
          </Link>

          <nav className="flex gap-1">
            {NAV.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                className={`rounded-md px-3 py-1.5 text-sm ${
                  pathname.startsWith(item.href)
                    ? "bg-slate-800 text-white"
                    : "text-slate-400 hover:text-slate-200"
                }`}
              >
                {item.label}
              </Link>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-4 text-xs text-slate-400">
            <span
              className="flex items-center gap-1.5"
              title={
                connected
                  ? `Live stream connected — ${eventCount} event(s) received`
                  : "Live stream disconnected, retrying with backoff"
              }
            >
              <span
                className={`h-2 w-2 rounded-full ${
                  connected ? "bg-emerald-400" : "bg-slate-600"
                }`}
              />
              {connected ? "Live" : "Reconnecting"}
            </span>

            <span>
              {user?.organizationName} · {user?.role}
            </span>

            <button
              className="btn-ghost"
              onClick={() => {
                tokens.clear();
                router.replace("/login");
              }}
            >
              Sign out
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-7xl px-6 py-6">{children}</main>
    </div>
  );
}
