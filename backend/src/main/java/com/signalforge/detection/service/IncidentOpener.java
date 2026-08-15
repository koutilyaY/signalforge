package com.signalforge.detection.service;

import com.signalforge.detection.domain.IncidentSeverity;
import com.signalforge.incident.domain.Alert;
import com.signalforge.incident.domain.Incident;
import com.signalforge.incident.domain.IncidentTimelineEntry;
import com.signalforge.incident.repository.AlertRepository;
import com.signalforge.incident.repository.IncidentRepository;
import com.signalforge.incident.repository.IncidentTimelineRepository;
import com.signalforge.notification.StreamHub;
import com.signalforge.registry.domain.ServiceEntity;
import com.signalforge.telemetry.query.TelemetryQueryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a breached evaluation into an alert, and — if warranted — an incident.
 *
 * <p>The dedup and cooldown rules, which are the whole difference between a useful alerting system
 * and a pager that everyone mutes:
 *
 * <ol>
 *   <li><b>One open incident per fingerprint.</b> Enforced by the partial unique index {@code
 *       uq_incidents_active_fingerprint}, not by a read-then-write in application code. Concurrent
 *       evaluators race into the insert and exactly one wins; the loser catches the constraint
 *       violation and attaches its alert to the winner's incident.
 *   <li><b>Cooldown after resolution.</b> A rule that resolved 30 seconds ago and immediately
 *       breaches again is usually flapping, not a new outage. Within {@code cooldown_seconds} of
 *       the last resolution the alert is still recorded but marked SUPPRESSED and opens nothing.
 * </ol>
 *
 * <p>Alerts are always persisted, including suppressed ones. "We saw this and chose not to page" is
 * exactly what you need in the record when reviewing an incident that was missed.
 */
@Component
public class IncidentOpener {

  private static final Logger log = LoggerFactory.getLogger(IncidentOpener.class);

  private final IncidentRepository incidentRepository;
  private final AlertRepository alertRepository;
  private final IncidentTimelineRepository timelineRepository;
  private final JdbcTemplate jdbcTemplate;
  private final StreamHub streamHub;
  private final TelemetryQueryRepository telemetryQuery;

  public IncidentOpener(
      IncidentRepository incidentRepository,
      AlertRepository alertRepository,
      IncidentTimelineRepository timelineRepository,
      JdbcTemplate jdbcTemplate,
      StreamHub streamHub,
      TelemetryQueryRepository telemetryQuery) {
    this.incidentRepository = incidentRepository;
    this.alertRepository = alertRepository;
    this.timelineRepository = timelineRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.streamHub = streamHub;
    this.telemetryQuery = telemetryQuery;
  }

  /**
   * Records the alert and opens or updates an incident.
   *
   * <p>Runs in its own transaction so that one service's detection failure cannot roll back the
   * whole sweep's work for every other service.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Outcome handleBreach(RuleEvaluator.Evaluation evaluation, ServiceEntity service) {
    UUID organizationId = service.getOrganizationId();
    String fingerprint = evaluation.rule().fingerprintFor(service.getId());
    Instant now = Instant.now();

    IncidentSeverity severity =
        evaluation.rule().getSeverity().escalateFor(service.getCriticality());

    Alert alert =
        new Alert(
            organizationId,
            service.getId(),
            evaluation.rule().getId(),
            fingerprint,
            severity,
            truncate(evaluation.summary(), 500),
            evaluation.observedValue(),
            evaluation.thresholdValue(),
            evaluation.sampleSize(),
            evaluation.windowStart(),
            evaluation.windowEnd(),
            now);

    Optional<Incident> existing =
        incidentRepository.findActiveByFingerprint(organizationId, fingerprint);

    if (existing.isPresent()) {
      Incident incident = existing.get();
      // A sustained breach can get worse. Severity ratchets up but never down,
      // so an incident that peaked at CRITICAL is not quietly downgraded to HIGH
      // while it is still burning.
      IncidentSeverity before = incident.getSeverity();
      incident.raiseSeverityTo(severity);
      if (incident.getSeverity() != before) {
        appendTimeline(
            incident,
            IncidentTimelineEntry.Kind.SEVERITY_CHANGE,
            "Severity raised to " + incident.getSeverity(),
            evaluation.summary(),
            now,
            Map.of("from", before.name(), "to", incident.getSeverity().name()));
      }
      alert.attachTo(incident.getId());
      alertRepository.save(alert);
      appendAlertToTimeline(incident, alert, evaluation, now);
      return new Outcome(incident, alert, false);
    }

    if (isInCooldown(organizationId, fingerprint, evaluation.rule().cooldown(), now)) {
      alert.suppress();
      alertRepository.save(alert);
      log.debug(
          "Alert suppressed by cooldown org={} fingerprint={} cooldown={}",
          organizationId,
          fingerprint,
          evaluation.rule().cooldown());
      return new Outcome(null, alert, false);
    }

    try {
      Incident incident = createIncident(evaluation, service, severity, fingerprint, now);
      alert.attachTo(incident.getId());
      alertRepository.save(alert);
      appendAlertToTimeline(incident, alert, evaluation, now);
      log.info(
          "Opened incident {} severity={} service={} detectionLatency={}ms",
          incident.getReference(),
          incident.getSeverity(),
          service.getName(),
          incident.timeToDetect() == null ? -1 : incident.timeToDetect().toMillis());

      // Push to any connected dashboards. Deliberately after the incident is
      // persisted and deliberately swallowed on failure - a broken SSE
      // subscriber must never roll back incident creation.
      try {
        streamHub.broadcast(
            organizationId,
            StreamHub.Events.INCIDENT_OPENED,
            Map.of(
                "incidentId", incident.getId().toString(),
                "reference", incident.getReference(),
                "title", incident.getTitle(),
                "severity", incident.getSeverity().name(),
                "status", incident.getStatus().name(),
                "serviceName", service.getName(),
                "detectedAt", incident.getDetectedAt().toString()));
      } catch (RuntimeException broadcastFailure) {
        log.warn("Failed to broadcast incident {}", incident.getReference(), broadcastFailure);
      }

      return new Outcome(incident, alert, true);

    } catch (DataIntegrityViolationException raceLost) {
      // Another evaluator opened the incident between our read and our insert.
      // This is the expected, correct outcome of the unique index doing its job,
      // not an error - attach to the winner.
      Incident winner =
          incidentRepository
              .findActiveByFingerprint(organizationId, fingerprint)
              .orElseThrow(() -> raceLost);
      alert.attachTo(winner.getId());
      alertRepository.save(alert);
      log.debug(
          "Lost incident-creation race for fingerprint={}, attached to {}",
          fingerprint,
          winner.getReference());
      return new Outcome(winner, alert, false);
    }
  }

  private Incident createIncident(
      RuleEvaluator.Evaluation evaluation,
      ServiceEntity service,
      IncidentSeverity severity,
      String fingerprint,
      Instant now) {

    String reference = nextReference(service.getOrganizationId());

    Incident incident =
        new Incident(
            service.getOrganizationId(),
            reference,
            "%s on %s".formatted(titleFor(evaluation), service.getName()),
            severity,
            fingerprint,
            // startedAt is the window start, not now. The gap between the two IS
            // the detection latency, and reporting it as zero would make the
            // headline metric of this platform meaningless.
            evaluation.windowStart(),
            now);
    incident.setDescription(evaluation.summary());
    incident.setDetectionRuleId(evaluation.rule().getId());
    incident.setPrimaryServiceId(service.getId());
    // The moment the evidence existed. detected_at minus this is the real
    // detection latency; see Incident.timeToDetect().
    incident.setSignalObservedAt(
        telemetryQuery.latestEventAt(
            service.getOrganizationId(),
            service.getId(),
            evaluation.windowStart(),
            evaluation.windowEnd()));

    Incident saved = incidentRepository.saveAndFlush(incident);

    jdbcTemplate.update(
        """
        INSERT INTO incident_services (organization_id, incident_id, service_id, role)
        VALUES (?, ?, ?, 'PRIMARY')
        ON CONFLICT (incident_id, service_id) DO NOTHING
        """,
        service.getOrganizationId(),
        saved.getId(),
        service.getId());

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("ruleType", evaluation.ruleType().name());
    metadata.put("ruleId", String.valueOf(evaluation.rule().getId()));
    metadata.put("observed", String.valueOf(evaluation.observedValue()));
    metadata.put("threshold", String.valueOf(evaluation.thresholdValue()));
    if (saved.timeToDetect() != null) {
      metadata.put("detectionLatencyMs", saved.timeToDetect().toMillis());
    }
    metadata.put("evidenceWindowAgeMs", saved.evidenceWindowAge().toMillis());

    appendTimeline(
        saved,
        IncidentTimelineEntry.Kind.DETECTED,
        "Incident detected by rule '%s'".formatted(evaluation.rule().getName()),
        evaluation.summary(),
        now,
        metadata);

    return saved;
  }

  /**
   * Per-organization sequence for human-friendly references (INC-1, INC-2, …).
   *
   * <p>A single UPDATE … RETURNING, so concurrent detectors serialise on the row rather than
   * racing. A global database sequence would leak how many incidents other tenants have.
   */
  private String nextReference(UUID organizationId) {
    Long value =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO incident_counters (organization_id, last_value)
            VALUES (?, 1)
            ON CONFLICT (organization_id)
            DO UPDATE SET last_value = incident_counters.last_value + 1
            RETURNING last_value
            """,
            Long.class,
            organizationId);
    return "INC-" + (value == null ? 1 : value);
  }

  private boolean isInCooldown(
      UUID organizationId, String fingerprint, Duration cooldown, Instant now) {
    if (cooldown.isZero() || cooldown.isNegative()) {
      return false;
    }
    return incidentRepository
        .findMostRecentlyResolved(organizationId, fingerprint)
        .map(Incident::getResolvedAt)
        .filter(resolvedAt -> resolvedAt.isAfter(now.minus(cooldown)))
        .isPresent();
  }

  private void appendAlertToTimeline(
      Incident incident, Alert alert, RuleEvaluator.Evaluation evaluation, Instant now) {
    appendTimeline(
        incident,
        IncidentTimelineEntry.Kind.ALERT,
        "Rule '%s' fired".formatted(evaluation.rule().getName()),
        alert.getSummary(),
        now,
        Map.of(
            "alertId", String.valueOf(alert.getId()),
            "observed", String.valueOf(evaluation.observedValue()),
            "threshold", String.valueOf(evaluation.thresholdValue())));
  }

  private void appendTimeline(
      Incident incident,
      IncidentTimelineEntry.Kind kind,
      String title,
      String detail,
      Instant occurredAt,
      Map<String, Object> metadata) {
    timelineRepository.save(
        new IncidentTimelineEntry(
            incident.getOrganizationId(),
            incident.getId(),
            occurredAt,
            kind,
            truncate(title, 300),
            truncate(detail, 4000),
            null,
            metadata));
  }

  private static String titleFor(RuleEvaluator.Evaluation evaluation) {
    return switch (evaluation.ruleType()) {
      case ERROR_RATE -> "Elevated error rate";
      case P95_LATENCY -> "Latency SLA breach";
      case SERVICE_DOWN -> "Service down";
      case KAFKA_LAG -> "Kafka consumer lag";
      case DATABASE_ERROR_SPIKE -> "Database error spike";
      case CORRELATED_ERRORS -> "Correlated multi-service failure";
    };
  }

  private static String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  /**
   * @param incident the incident this alert belongs to, or null when suppressed by cooldown
   * @param created true only when this call opened a brand new incident
   */
  public record Outcome(Incident incident, Alert alert, boolean created) {}
}
