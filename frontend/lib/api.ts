/**
 * Typed client for the SignalForge API.
 *
 * Two rules the rest of the app depends on:
 *
 *  1. The organization id is NEVER sent by the client. It lives inside the signed
 *     JWT and the server reads it from there. Any frontend code that thinks it
 *     needs to pass a tenant id is a bug.
 *  2. Errors come back in one envelope, so `ApiError` carries the server's stable
 *     `code` and the correlation id. UI code branches on `code`, never on the
 *     human-readable message.
 */

const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8099";

const TOKEN_KEY = "sf.accessToken";
const REFRESH_KEY = "sf.refreshToken";
const USER_KEY = "sf.user";

export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
    readonly correlationId?: string,
    readonly violations?: FieldViolation[],
  ) {
    super(message);
    this.name = "ApiError";
  }

  /** True when re-authenticating could plausibly fix this. */
  get isAuthFailure(): boolean {
    return (
      this.status === 401 ||
      this.code === "TOKEN_EXPIRED" ||
      this.code === "TOKEN_INVALID"
    );
  }
}

export interface FieldViolation {
  field: string;
  message: string;
  rejectedValue?: unknown;
}

// ---------------------------------------------------------------------------
// Token storage
//
// sessionStorage, not localStorage: the token dies with the tab rather than
// persisting on a shared machine. Neither is immune to XSS - an httpOnly cookie
// would be, at the cost of needing CSRF protection back. Documented in
// SECURITY.md rather than left as an accident.
// ---------------------------------------------------------------------------

export const tokens = {
  get access(): string | null {
    if (typeof window === "undefined") return null;
    return window.sessionStorage.getItem(TOKEN_KEY);
  },
  get refresh(): string | null {
    if (typeof window === "undefined") return null;
    return window.sessionStorage.getItem(REFRESH_KEY);
  },
  set(access: string, refresh: string, user: CurrentUser) {
    window.sessionStorage.setItem(TOKEN_KEY, access);
    window.sessionStorage.setItem(REFRESH_KEY, refresh);
    window.sessionStorage.setItem(USER_KEY, JSON.stringify(user));
  },
  get user(): CurrentUser | null {
    if (typeof window === "undefined") return null;
    const raw = window.sessionStorage.getItem(USER_KEY);
    return raw ? (JSON.parse(raw) as CurrentUser) : null;
  },
  clear() {
    window.sessionStorage.removeItem(TOKEN_KEY);
    window.sessionStorage.removeItem(REFRESH_KEY);
    window.sessionStorage.removeItem(USER_KEY);
  },
};

// ---------------------------------------------------------------------------
// Core request
// ---------------------------------------------------------------------------

let refreshInFlight: Promise<boolean> | null = null;

async function request<T>(
  path: string,
  init: RequestInit = {},
  retryOnAuthFailure = true,
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");

  const access = tokens.access;
  if (access) headers.set("Authorization", `Bearer ${access}`);

  const response = await fetch(`${API_BASE}${path}`, { ...init, headers });

  if (response.status === 204) return undefined as T;

  if (!response.ok) {
    const error = await toApiError(response);

    // One transparent refresh attempt, then give up. Multiple concurrent 401s
    // share a single refresh call so a page with six widgets does not fire six
    // refreshes and invalidate its own token mid-flight.
    if (error.isAuthFailure && retryOnAuthFailure && tokens.refresh) {
      const refreshed = await refreshAccessToken();
      if (refreshed) return request<T>(path, init, false);
    }
    throw error;
  }

  return (await response.json()) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = await response.json();
    return new ApiError(
      body.code ?? "UNKNOWN",
      body.message ?? response.statusText,
      response.status,
      body.correlationId,
      body.violations,
    );
  } catch {
    return new ApiError("UNKNOWN", response.statusText, response.status);
  }
}

async function refreshAccessToken(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;

  refreshInFlight = (async () => {
    try {
      const response = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: tokens.refresh }),
      });
      if (!response.ok) {
        tokens.clear();
        return false;
      }
      const body = (await response.json()) as TokenResponse;
      tokens.set(body.accessToken, body.refreshToken, body.user);
      return true;
    } catch {
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

// ---------------------------------------------------------------------------
// Types mirroring the server DTOs
// ---------------------------------------------------------------------------

export type Severity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export type IncidentStatus =
  | "OPEN"
  | "ACKNOWLEDGED"
  | "INVESTIGATING"
  | "MITIGATED"
  | "RESOLVED";
export type HealthStatus = "HEALTHY" | "DEGRADED" | "DOWN" | "UNKNOWN";
export type Environment = "PRODUCTION" | "STAGING" | "DEVELOPMENT";

export interface CurrentUser {
  id: string;
  email: string;
  fullName: string;
  role: "VIEWER" | "ENGINEER" | "ADMIN";
  organizationId: string;
  organizationName: string;
  organizationSlug: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
  expiresAt: string;
  user: CurrentUser;
}

export interface ServiceSummary {
  id: string;
  name: string;
  description?: string;
  environment: Environment;
  team?: string;
  criticality: Severity;
  expectedP95LatencyMs: number;
  expectedErrorRate: number;
  healthStatus: HealthStatus;
  healthChangedAt?: string;
  version: number;
}

export interface IncidentSummary {
  id: string;
  reference: string;
  title: string;
  severity: Severity;
  status: IncidentStatus;
  source: string;
  primaryServiceId?: string;
  startedAt: string;
  detectedAt: string;
  acknowledgedAt?: string;
  resolvedAt?: string;
  durationSeconds: number;
  timeToDetectMs?: number;
  timeToAcknowledgeMs?: number;
  timeToResolveMs?: number;
}

export interface TimelineEntry {
  id: string;
  occurredAt: string;
  kind: string;
  title: string;
  detail?: string;
  metadata?: Record<string, unknown>;
}

export interface IncidentDetail {
  incident: IncidentSummary;
  description?: string;
  resolutionNote?: string;
  version: number;
  affectedServices: {
    serviceId: string;
    name: string;
    role: string;
    healthStatus: HealthStatus;
  }[];
  timeline: TimelineEntry[];
  alerts: {
    id: string;
    status: string;
    severity: Severity;
    summary: string;
    observedValue?: number;
    thresholdValue?: number;
    triggeredAt: string;
  }[];
  comments: { id: string; body: string; createdAt: string }[];
  allowedTransitions: IncidentStatus[];
}

export interface ContributingFactor {
  kind: string;
  summary: string;
  confidence: number;
  evidence: string[];
}

export interface EvidenceBundle {
  incidentId: string;
  incidentReference: string;
  incidentTitle: string;
  severity: Severity;
  incidentStartedAt: string;
  primaryServiceName?: string;
  factors: ContributingFactor[];
  deployments: {
    deploymentId: string;
    serviceName: string;
    version: string;
    commitSha?: string;
    branch?: string;
    status: string;
    deployedBy?: string;
    effectiveAt: string;
    minutesBeforeIncident: number;
    isPrimaryService: boolean;
  }[];
  relatedServices: { serviceId: string; serviceName: string; healthStatus: HealthStatus }[];
  errorSignatures: { errorType: string; occurrences: number; shareOfErrors: number }[];
  sampleTraceIds: string[];
  metrics: {
    requestCount: number;
    errorCount: number;
    errorRatePercent: number;
    p95LatencyMs: number;
    p50LatencyMs: number;
    baselineErrorRatePercent?: number;
    baselineP95LatencyMs?: number;
  };
}

export interface DeploymentSummary {
  id: string;
  serviceId: string;
  version: string;
  commitSha?: string;
  branch?: string;
  environment: Environment;
  status: string;
  deployedBy?: string;
  startedAt: string;
  completedAt?: string;
  durationSeconds: number;
}

export interface AiSummary {
  available: boolean;
  reason?: string;
  model?: string;
  summary?: string;
  likelyCauses?: { cause: string; evidence: string[] }[];
  recommendedSteps?: string[];
  generatedAt?: string;
}

// ---------------------------------------------------------------------------
// Endpoints
// ---------------------------------------------------------------------------

export const api = {
  login: (email: string, password: string) =>
    request<TokenResponse>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),

  registerOrganization: (payload: {
    organizationName: string;
    organizationSlug: string;
    adminEmail: string;
    adminFullName: string;
    adminPassword: string;
  }) =>
    request<TokenResponse>("/api/v1/auth/register-organization", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  me: () => request<CurrentUser>("/api/v1/auth/me"),

  listServices: (environment?: Environment) =>
    request<ServiceSummary[]>(
      `/api/v1/services${environment ? `?environment=${environment}` : ""}`,
    ),

  getService: (id: string) => request<ServiceSummary>(`/api/v1/services/${id}`),

  createService: (payload: Partial<ServiceSummary> & { name: string; environment: Environment }) =>
    request<ServiceSummary>("/api/v1/services", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  listIncidents: (status?: IncidentStatus) =>
    request<IncidentSummary[]>(
      `/api/v1/incidents${status ? `?status=${status}` : ""}`,
    ),

  getIncident: (id: string) => request<IncidentDetail>(`/api/v1/incidents/${id}`),

  getCorrelation: (id: string) =>
    request<EvidenceBundle>(`/api/v1/incidents/${id}/correlation`),

  getAiSummary: (id: string) => request<AiSummary>(`/api/v1/incidents/${id}/ai-summary`),

  transitionIncident: (id: string, status: IncidentStatus, note?: string, version?: number) =>
    request<IncidentDetail>(`/api/v1/incidents/${id}/transitions`, {
      method: "POST",
      body: JSON.stringify({ status, note, version }),
    }),

  commentOnIncident: (id: string, body: string) =>
    request<{ id: string }>(`/api/v1/incidents/${id}/comments`, {
      method: "POST",
      body: JSON.stringify({ body }),
    }),

  listDeployments: (serviceId?: string) =>
    request<DeploymentSummary[]>(
      `/api/v1/deployments${serviceId ? `?serviceId=${serviceId}` : ""}`,
    ),
};

export { API_BASE };
