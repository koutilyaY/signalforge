package com.signalforge.correlation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything SignalForge knows about why an incident might have happened.
 *
 * <p>This is the single source of truth for root-cause reasoning. The deterministic ranker produces
 * it, the incident detail page renders it, and — when enabled — the LLM receives exactly this and
 * nothing else. That last point is the whole design: an assistant that can only see the evidence
 * bundle cannot invent a log line that was never collected.
 *
 * @param factors ranked most-likely-first; empty when the evidence genuinely does not support any
 *     conclusion, which is a legitimate and important answer
 */
public record EvidenceBundle(
    UUID incidentId,
    String incidentReference,
    String incidentTitle,
    String severity,
    Instant incidentStartedAt,
    Instant windowStart,
    Instant windowEnd,
    String primaryServiceName,
    List<ContributingFactor> factors,
    List<DeploymentEvidence> deployments,
    List<ServiceEvidence> relatedServices,
    List<ErrorSignatureEvidence> errorSignatures,
    List<String> sampleTraceIds,
    MetricEvidence metrics) {

  /** True when nothing correlated at all. Callers must say so rather than guessing. */
  public boolean isInconclusive() {
    return factors.isEmpty();
  }

  /**
   * One ranked hypothesis.
   *
   * @param confidence 0-100. Deliberately called confidence, not probability: it is a deterministic
   *     score from a hand-written rubric, not a calibrated statistical estimate, and labelling it
   *     "probability" would overclaim.
   * @param evidence the concrete facts that produced this score; a factor with no evidence is a
   *     bug, not a hunch
   */
  public record ContributingFactor(
      String kind, String summary, int confidence, List<String> evidence) {}

  public record DeploymentEvidence(
      UUID deploymentId,
      UUID serviceId,
      String serviceName,
      String version,
      String commitSha,
      String branch,
      String status,
      String deployedBy,
      Instant effectiveAt,
      /** Negative means the deployment landed before the incident began. */
      long minutesBeforeIncident,
      boolean isPrimaryService) {}

  public record ServiceEvidence(
      UUID serviceId, String serviceName, String healthStatus, long sharedTraceCount) {}

  public record ErrorSignatureEvidence(String errorType, long occurrences, double shareOfErrors) {}

  public record MetricEvidence(
      long requestCount,
      long errorCount,
      double errorRatePercent,
      double p95LatencyMs,
      double p50LatencyMs,
      Double baselineErrorRatePercent,
      Double baselineP95LatencyMs) {}
}
