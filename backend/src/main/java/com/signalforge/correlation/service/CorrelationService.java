package com.signalforge.correlation.service;

import com.signalforge.correlation.domain.EvidenceBundle;
import com.signalforge.deployment.domain.Deployment;
import com.signalforge.deployment.repository.DeploymentRepository;
import com.signalforge.incident.domain.Incident;
import com.signalforge.platform.config.SignalForgeProperties;
import com.signalforge.registry.domain.ServiceEntity;
import com.signalforge.registry.repository.ServiceRepository;
import com.signalforge.telemetry.query.TelemetryQueryRepository;
import com.signalforge.telemetry.query.WindowStats;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic root-cause correlation.
 *
 * <p>No machine learning, and that is a feature rather than a limitation. Every number this
 * produces can be traced to a specific fact with a timestamp, which means an engineer can disagree
 * with it and check. A model that emitted "87% likely the payment deploy" with no derivation would
 * be less useful during an outage, not more.
 *
 * <p>The scoring rubric is a hand-written heuristic, tuned to the shape of real incidents:
 *
 * <ul>
 *   <li><b>Deployment proximity</b> dominates. Most incidents are caused by a change, and closeness
 *       in time is the strongest cheap signal available. Score decays linearly with distance.
 *   <li><b>Same service beats a dependency.</b> A deploy of the failing service outranks a deploy
 *       of something it calls.
 *   <li><b>Shared traces</b> are hard evidence of involvement, not a coincidence — two services in
 *       the same distributed trace were handling the same request.
 *   <li><b>A dominant error signature</b> points at a specific failure mode rather than general
 *       unhealthiness.
 * </ul>
 *
 * <p>Weights live as named constants so they can be argued with in review instead of being buried
 * as magic numbers.
 */
@Service
public class CorrelationService {

  private static final Logger log = LoggerFactory.getLogger(CorrelationService.class);

  /** A deployment landing right before the incident is the strongest single signal. */
  private static final int DEPLOYMENT_BASE_CONFIDENCE = 55;

  private static final int DEPLOYMENT_SAME_SERVICE_BONUS = 25;
  private static final int DEPLOYMENT_FAILED_STATUS_BONUS = 10;
  private static final int SHARED_TRACE_BASE_CONFIDENCE = 50;
  private static final int ERROR_SIGNATURE_BASE_CONFIDENCE = 40;
  private static final int LATENCY_REGRESSION_CONFIDENCE = 35;

  /** An error type accounting for at least this share of errors is treated as dominant. */
  private static final double DOMINANT_SIGNATURE_SHARE = 0.5;

  /** A p95 this many times the baseline counts as a regression worth reporting. */
  private static final double LATENCY_REGRESSION_MULTIPLE = 2.0;

  private final DeploymentRepository deploymentRepository;
  private final ServiceRepository serviceRepository;
  private final TelemetryQueryRepository telemetryQuery;
  private final SignalForgeProperties properties;

  public CorrelationService(
      DeploymentRepository deploymentRepository,
      ServiceRepository serviceRepository,
      TelemetryQueryRepository telemetryQuery,
      SignalForgeProperties properties) {
    this.deploymentRepository = deploymentRepository;
    this.serviceRepository = serviceRepository;
    this.telemetryQuery = telemetryQuery;
    this.properties = properties;
  }

  @Transactional(readOnly = true)
  public EvidenceBundle buildFor(Incident incident) {
    UUID organizationId = incident.getOrganizationId();
    SignalForgeProperties.Correlation config = properties.correlation();

    Instant incidentStart = incident.getStartedAt();
    Instant windowStart = incidentStart.minus(config.evidenceWindow());
    Instant windowEnd =
        incident.getResolvedAt() != null
            ? incident.getResolvedAt()
            : incidentStart.plus(config.evidenceWindow());

    ServiceEntity primary =
        incident.getPrimaryServiceId() == null
            ? null
            : serviceRepository
                .findByIdInOrganization(incident.getPrimaryServiceId(), organizationId)
                .orElse(null);

    List<EvidenceBundle.DeploymentEvidence> deployments =
        gatherDeployments(organizationId, incident, incidentStart, config.deploymentLookback());

    List<EvidenceBundle.ServiceEvidence> related =
        gatherRelatedServices(organizationId, incident, incidentStart, windowEnd, config);

    List<EvidenceBundle.ErrorSignatureEvidence> signatures =
        gatherErrorSignatures(organizationId, incident, incidentStart, windowEnd, config);

    List<String> traceIds =
        incident.getPrimaryServiceId() == null
            ? List.of()
            : telemetryQuery.failingTraceIds(
                organizationId,
                incident.getPrimaryServiceId(),
                incidentStart,
                windowEnd,
                config.maxTraceSamples());

    EvidenceBundle.MetricEvidence metrics =
        gatherMetrics(organizationId, incident, incidentStart, windowEnd, windowStart);

    List<EvidenceBundle.ContributingFactor> factors =
        rank(deployments, related, signatures, metrics, primary);

    return new EvidenceBundle(
        incident.getId(),
        incident.getReference(),
        incident.getTitle(),
        incident.getSeverity().name(),
        incidentStart,
        windowStart,
        windowEnd,
        primary == null ? null : primary.getName(),
        factors,
        deployments,
        related,
        signatures,
        traceIds,
        metrics);
  }

  // ---- gathering -------------------------------------------------------------

  private List<EvidenceBundle.DeploymentEvidence> gatherDeployments(
      UUID organizationId, Incident incident, Instant incidentStart, Duration lookback) {

    List<Deployment> candidates =
        deploymentRepository.findInWindow(
            organizationId, incidentStart.minus(lookback), incidentStart);

    Map<UUID, String> serviceNames = serviceNameCache(organizationId);

    return candidates.stream()
        .map(
            d ->
                new EvidenceBundle.DeploymentEvidence(
                    d.getId(),
                    d.getServiceId(),
                    serviceNames.getOrDefault(d.getServiceId(), "(unknown service)"),
                    d.getVersion(),
                    d.getCommitSha(),
                    d.getBranch(),
                    d.getStatus().name(),
                    d.getDeployedBy(),
                    d.effectiveAt(),
                    Duration.between(d.effectiveAt(), incidentStart).toMinutes(),
                    d.getServiceId().equals(incident.getPrimaryServiceId())))
        .toList();
  }

  private List<EvidenceBundle.ServiceEvidence> gatherRelatedServices(
      UUID organizationId,
      Incident incident,
      Instant from,
      Instant to,
      SignalForgeProperties.Correlation config) {

    if (incident.getPrimaryServiceId() == null) {
      return List.of();
    }

    List<UUID> correlated =
        telemetryQuery.correlatedFailingServices(
            organizationId, incident.getPrimaryServiceId(), from, to, config.maxRelatedServices());

    Map<UUID, String> serviceNames = serviceNameCache(organizationId);

    return correlated.stream()
        .map(
            serviceId -> {
              ServiceEntity service =
                  serviceRepository.findByIdInOrganization(serviceId, organizationId).orElse(null);
              return new EvidenceBundle.ServiceEvidence(
                  serviceId,
                  serviceNames.getOrDefault(serviceId, "(unknown service)"),
                  service == null ? "UNKNOWN" : service.getHealthStatus().name(),
                  // Presence in the result already means at least one shared trace;
                  // the exact count is not worth a second query per service here.
                  1L);
            })
        .toList();
  }

  private List<EvidenceBundle.ErrorSignatureEvidence> gatherErrorSignatures(
      UUID organizationId,
      Incident incident,
      Instant from,
      Instant to,
      SignalForgeProperties.Correlation config) {

    if (incident.getPrimaryServiceId() == null) {
      return List.of();
    }

    List<TelemetryQueryRepository.ErrorSignature> raw =
        telemetryQuery.topErrorSignatures(
            organizationId, incident.getPrimaryServiceId(), from, to, config.maxErrorSignatures());

    long total = raw.stream().mapToLong(TelemetryQueryRepository.ErrorSignature::occurrences).sum();
    if (total == 0) {
      return List.of();
    }

    return raw.stream()
        .map(
            s ->
                new EvidenceBundle.ErrorSignatureEvidence(
                    s.errorType(), s.occurrences(), (double) s.occurrences() / total))
        .toList();
  }

  private EvidenceBundle.MetricEvidence gatherMetrics(
      UUID organizationId,
      Incident incident,
      Instant incidentStart,
      Instant windowEnd,
      Instant baselineStart) {

    if (incident.getPrimaryServiceId() == null) {
      return new EvidenceBundle.MetricEvidence(0, 0, 0, 0, 0, null, null);
    }

    WindowStats during =
        telemetryQuery.windowStats(
            organizationId, incident.getPrimaryServiceId(), incidentStart, windowEnd);

    // The equivalent window immediately before the incident. Without a baseline
    // "p95 was 2100ms" is unactionable - the question is always "compared to what".
    WindowStats baseline =
        telemetryQuery.windowStats(
            organizationId, incident.getPrimaryServiceId(), baselineStart, incidentStart);

    return new EvidenceBundle.MetricEvidence(
        during.requestCount(),
        during.errorCount(),
        during.errorRate().doubleValue() * 100,
        during.p95LatencyMs(),
        during.p50LatencyMs(),
        baseline.isEmpty() ? null : baseline.errorRate().doubleValue() * 100,
        baseline.isEmpty() ? null : baseline.p95LatencyMs());
  }

  private Map<UUID, String> serviceNameCache(UUID organizationId) {
    Map<UUID, String> names = new HashMap<>();
    serviceRepository.findActive(organizationId).forEach(s -> names.put(s.getId(), s.getName()));
    return names;
  }

  // ---- ranking ---------------------------------------------------------------

  private List<EvidenceBundle.ContributingFactor> rank(
      List<EvidenceBundle.DeploymentEvidence> deployments,
      List<EvidenceBundle.ServiceEvidence> related,
      List<EvidenceBundle.ErrorSignatureEvidence> signatures,
      EvidenceBundle.MetricEvidence metrics,
      ServiceEntity primary) {

    List<EvidenceBundle.ContributingFactor> factors = new ArrayList<>();
    long lookbackMinutes = properties.correlation().deploymentLookback().toMinutes();

    for (EvidenceBundle.DeploymentEvidence deployment : deployments) {
      long minutesBefore = deployment.minutesBeforeIncident();
      if (minutesBefore < 0) {
        continue; // landed after the incident began; cannot be the cause
      }

      // Linear decay: a deploy 2 minutes before scores near the base, one at the
      // edge of the lookback window scores near zero.
      double proximity = 1.0 - ((double) minutesBefore / Math.max(1, lookbackMinutes));
      int confidence = (int) Math.round(DEPLOYMENT_BASE_CONFIDENCE * proximity);

      if (deployment.isPrimaryService()) {
        confidence += DEPLOYMENT_SAME_SERVICE_BONUS;
      }
      if ("FAILED".equals(deployment.status())) {
        // A half-applied rollout is more suspicious than a clean one.
        confidence += DEPLOYMENT_FAILED_STATUS_BONUS;
      }
      confidence = clamp(confidence);

      List<String> evidence = new ArrayList<>();
      evidence.add(
          "Deployment of %s version %s became effective at %s, %d minute(s) before the incident began"
              .formatted(
                  deployment.serviceName(),
                  deployment.version(),
                  deployment.effectiveAt(),
                  minutesBefore));
      if (deployment.commitSha() != null) {
        evidence.add(
            "Commit %s on branch %s".formatted(deployment.commitSha(), deployment.branch()));
      }
      if (deployment.deployedBy() != null) {
        evidence.add("Deployed by " + deployment.deployedBy());
      }
      evidence.add("Deployment status: " + deployment.status());

      factors.add(
          new EvidenceBundle.ContributingFactor(
              "DEPLOYMENT",
              "%s deployment %s, %d minute(s) before incident start"
                  .formatted(deployment.serviceName(), deployment.version(), minutesBefore),
              confidence,
              evidence));
    }

    if (!related.isEmpty()) {
      List<String> evidence = new ArrayList<>();
      evidence.add(
          "%d other service(s) reported errors within the same distributed traces"
              .formatted(related.size()));
      related.forEach(
          s -> evidence.add("%s is currently %s".formatted(s.serviceName(), s.healthStatus())));

      factors.add(
          new EvidenceBundle.ContributingFactor(
              "CORRELATED_SERVICES",
              "Failure spans %d service(s) sharing distributed traces".formatted(related.size()),
              clamp(SHARED_TRACE_BASE_CONFIDENCE + Math.min(related.size() * 5, 20)),
              evidence));
    }

    if (!signatures.isEmpty()) {
      EvidenceBundle.ErrorSignatureEvidence dominant = signatures.get(0);
      if (dominant.shareOfErrors() >= DOMINANT_SIGNATURE_SHARE) {
        factors.add(
            new EvidenceBundle.ContributingFactor(
                "ERROR_SIGNATURE",
                "%s accounts for %.0f%% of errors"
                    .formatted(dominant.errorType(), dominant.shareOfErrors() * 100),
                clamp(
                    ERROR_SIGNATURE_BASE_CONFIDENCE
                        + (int) Math.round(dominant.shareOfErrors() * 30)),
                List.of(
                    "%d of %d errors share the signature '%s'"
                        .formatted(
                            dominant.occurrences(),
                            signatures.stream()
                                .mapToLong(EvidenceBundle.ErrorSignatureEvidence::occurrences)
                                .sum(),
                            dominant.errorType()),
                    "A single dominant signature points at one failure mode rather than general degradation")));
      }
    }

    if (metrics.baselineP95LatencyMs() != null
        && metrics.baselineP95LatencyMs() > 0
        && metrics.p95LatencyMs() >= metrics.baselineP95LatencyMs() * LATENCY_REGRESSION_MULTIPLE) {
      factors.add(
          new EvidenceBundle.ContributingFactor(
              "LATENCY_REGRESSION",
              "p95 latency rose from %.0fms to %.0fms"
                  .formatted(metrics.baselineP95LatencyMs(), metrics.p95LatencyMs()),
              LATENCY_REGRESSION_CONFIDENCE,
              List.of(
                  "Baseline p95 in the window before the incident: %.0fms"
                      .formatted(metrics.baselineP95LatencyMs()),
                  "p95 during the incident: %.0fms".formatted(metrics.p95LatencyMs()),
                  "Request volume during the incident: %d".formatted(metrics.requestCount()))));
    }

    factors.sort(Comparator.comparingInt(EvidenceBundle.ContributingFactor::confidence).reversed());

    if (factors.isEmpty()) {
      log.debug(
          "No correlating evidence found for incident on service {}",
          primary == null ? "unknown" : primary.getName());
    }
    return factors;
  }

  private static int clamp(int confidence) {
    return Math.max(0, Math.min(100, confidence));
  }
}
