package com.signalforge.detection;

import static org.assertj.core.api.Assertions.assertThat;

import com.signalforge.detection.domain.DetectionRule;
import com.signalforge.detection.domain.IncidentSeverity;
import com.signalforge.detection.domain.RuleType;
import com.signalforge.detection.repository.DetectionRuleRepository;
import com.signalforge.detection.service.DetectionService;
import com.signalforge.incident.domain.Alert;
import com.signalforge.incident.domain.Incident;
import com.signalforge.incident.domain.IncidentStatus;
import com.signalforge.incident.repository.AlertRepository;
import com.signalforge.incident.repository.IncidentRepository;
import com.signalforge.incident.repository.IncidentTimelineRepository;
import com.signalforge.messaging.TelemetryEventMessage;
import com.signalforge.platform.tenant.TenantBinder;
import com.signalforge.registry.domain.Criticality;
import com.signalforge.registry.domain.ServiceEntity;
import com.signalforge.registry.repository.ServiceRepository;
import com.signalforge.support.AbstractIntegrationTest;
import com.signalforge.support.TenantFixture;
import com.signalforge.telemetry.domain.EventType;
import com.signalforge.telemetry.domain.Severity;
import com.signalforge.telemetry.persistence.TelemetryWriter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The detection engine, driven synchronously.
 *
 * <p>The scheduler is disabled in the test profile and {@link
 * DetectionService#evaluateOrganization} is called explicitly with a fixed {@code windowEnd}.
 * Waiting on a background timer would make every assertion here a race.
 */
@DisplayName("Incident detection engine")
class DetectionEngineIT extends AbstractIntegrationTest {

  @Autowired private DetectionService detectionService;
  @Autowired private DetectionRuleRepository ruleRepository;
  @Autowired private IncidentRepository incidentRepository;
  @Autowired private AlertRepository alertRepository;
  @Autowired private IncidentTimelineRepository timelineRepository;
  @Autowired private ServiceRepository serviceRepository;
  @Autowired private TelemetryWriter telemetryWriter;
  @Autowired private TenantFixture tenantFixture;
  @Autowired private TenantBinder tenantBinder;

  private TenantFixture.Tenant tenant;
  private Instant now;
  private Instant windowEnd;

  @BeforeEach
  void setUp() {
    tenant = tenantFixture.createTenant("detect");
    now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    // Evaluate as of one minute past the telemetry so the events sit inside the
    // window rather than on its exclusive upper boundary.
    windowEnd = now.plus(Duration.ofMinutes(1));
  }

  @Nested
  @DisplayName("error rate rule")
  class ErrorRateRule {

    @Test
    @DisplayName("opens an incident when the failure ratio exceeds the service SLA")
    void firesAboveThreshold() {
      seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.HIGH, 20);
      // 100 requests, 30 of them 500s = 30% error rate against a 1% SLA.
      seedHttp(70, 200, 50);
      seedHttp(30, 500, 50);

      DetectionService.SweepResult result = evaluate(tenant.organizationId());

      assertThat(result.incidentsOpened()).isEqualTo(1);

      Incident incident = onlyIncident();
      assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
      assertThat(incident.getTitle()).contains("Elevated error rate");
      assertThat(incident.getPrimaryServiceId()).isEqualTo(tenant.serviceId());
      assertThat(incident.getReference()).startsWith("INC-");
    }

    @Test
    @DisplayName("stays quiet when the failure ratio is within the SLA")
    void doesNotFireBelowThreshold() {
      seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.HIGH, 20);
      seedHttp(200, 200, 50); // zero errors

      evaluate(tenant.organizationId());

      assertThat(openCount(tenant.organizationId())).isZero();
    }

    @Test
    @DisplayName("ignores a window with too few requests to be meaningful")
    void respectsMinimumSampleSize() {
      seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.HIGH, 20);
      // One request, and it failed. That is a 100% error rate and complete noise.
      seedHttp(1, 500, 50);

      evaluate(tenant.organizationId());

      assertThat(openCount(tenant.organizationId()))
          .as("a single failure must not page anyone")
          .isZero();
    }
  }

  @Nested
  @DisplayName("latency rule")
  class LatencyRule {

    @Test
    @DisplayName("fires when interpolated p95 exceeds the service SLA")
    void firesOnSlowRequests() {
      setServiceP95Sla(200);
      seedRule(RuleType.P95_LATENCY, null, IncidentSeverity.MEDIUM, 20);
      // 50 fast, 50 slow - p95 lands well above 200ms.
      seedHttp(50, 200, 40);
      seedHttp(50, 200, 3000);

      evaluate(tenant.organizationId());

      Incident incident = onlyIncident();
      assertThat(incident.getTitle()).contains("Latency SLA breach");
    }

    @Test
    @DisplayName("stays quiet when every request is comfortably inside the SLA")
    void doesNotFireWhenFast() {
      setServiceP95Sla(500);
      seedRule(RuleType.P95_LATENCY, null, IncidentSeverity.MEDIUM, 20);
      seedHttp(100, 200, 40);

      evaluate(tenant.organizationId());

      assertThat(openCount(tenant.organizationId())).isZero();
    }
  }

  @Nested
  @DisplayName("deduplication and cooldown")
  class DeduplicationAndCooldown {

    @Test
    @DisplayName("a sustained breach produces one incident, not one per sweep")
    void repeatedSweepsProduceOneIncident() {
      seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.HIGH, 20);
      seedHttp(50, 200, 50);
      seedHttp(50, 500, 50);

      evaluate(tenant.organizationId());
      evaluate(tenant.organizationId());
      evaluate(tenant.organizationId());

      assertThat(openCount(tenant.organizationId()))
          .as("the partial unique index must collapse these into one incident")
          .isEqualTo(1);
    }

    @Test
    @DisplayName("but every firing is still recorded as its own alert")
    void everyFiringProducesAnAlert() {
      seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.HIGH, 20);
      seedHttp(50, 200, 50);
      seedHttp(50, 500, 50);

      evaluate(tenant.organizationId());
      evaluate(tenant.organizationId());

      Incident incident = onlyIncident();
      List<Alert> alerts =
          tenantBinder.callAs(
              tenant.organizationId(),
              () -> alertRepository.findForIncident(tenant.organizationId(), incident.getId()));
      assertThat(alerts)
          .as("collapsing alerts into the incident would discard how long the breach held")
          .hasSize(2);
    }

    @Test
    @DisplayName("a breach inside the cooldown after resolution is suppressed, not re-opened")
    void cooldownSuppressesImmediateRecurrence() {
      DetectionRule rule = seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.HIGH, 20);
      rule.setCooldownSeconds(3600);
      tenantBinder.runAs(tenant.organizationId(), () -> ruleRepository.saveAndFlush(rule));

      seedHttp(50, 200, 50);
      seedHttp(50, 500, 50);

      evaluate(tenant.organizationId());
      Incident first = onlyIncident();

      // Resolve it, then breach again immediately - classic flapping.
      tenantBinder.runAs(
          tenant.organizationId(),
          () -> {
            first.transitionTo(IncidentStatus.RESOLVED, tenant.engineer().userId(), Instant.now());
            incidentRepository.saveAndFlush(first);
          });

      evaluate(tenant.organizationId());

      assertThat(openCount(tenant.organizationId()))
          .as("cooldown must stop a flapping service re-paging immediately")
          .isZero();

      // Bound, like every other read here: an unbound read returns nothing under
      // RLS, which would fail this assertion for a reason that has nothing to do
      // with whether the suppressed alert was written.
      assertThat(
              tenantBinder.callAs(
                  tenant.organizationId(),
                  () ->
                      alertRepository.findRecent(
                          tenant.organizationId(),
                          org.springframework.data.domain.PageRequest.of(0, 10))))
          .as("the suppressed alert is still recorded")
          .anyMatch(a -> a.getStatus() == Alert.Status.SUPPRESSED);
    }

    @Test
    @DisplayName("a breach after the cooldown has elapsed opens a new incident")
    void newIncidentAfterCooldownElapses() {
      DetectionRule rule = seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.HIGH, 20);
      rule.setCooldownSeconds(0);
      tenantBinder.runAs(tenant.organizationId(), () -> ruleRepository.saveAndFlush(rule));

      seedHttp(50, 200, 50);
      seedHttp(50, 500, 50);

      evaluate(tenant.organizationId());
      Incident first = onlyIncident();
      tenantBinder.runAs(
          tenant.organizationId(),
          () -> {
            first.transitionTo(IncidentStatus.RESOLVED, tenant.engineer().userId(), Instant.now());
            incidentRepository.saveAndFlush(first);
          });

      evaluate(tenant.organizationId());

      assertThat(openCount(tenant.organizationId()))
          .as("a recurrence after cooldown is a genuinely new outage")
          .isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("severity")
  class SeverityEscalation {

    @Test
    @DisplayName("is escalated by the affected service's criticality")
    void escalatesForCriticalService() {
      tenantBinder.runAs(
          tenant.organizationId(),
          () -> {
            ServiceEntity s = serviceRepository.findById(tenant.serviceId()).orElseThrow();
            s.setCriticality(Criticality.CRITICAL); // +3 steps
            serviceRepository.saveAndFlush(s);
          });

      seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.LOW, 20);
      seedHttp(50, 200, 50);
      seedHttp(50, 500, 50);

      evaluate(tenant.organizationId());

      assertThat(onlyIncident().getSeverity())
          .as("LOW on a CRITICAL service should not page like LOW on a batch job")
          .isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    @DisplayName("is unchanged for a low-criticality service")
    void doesNotEscalateForLowCriticality() {
      tenantBinder.runAs(
          tenant.organizationId(),
          () -> {
            ServiceEntity s = serviceRepository.findById(tenant.serviceId()).orElseThrow();
            s.setCriticality(Criticality.LOW); // +0 steps
            serviceRepository.saveAndFlush(s);
          });

      seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.MEDIUM, 20);
      seedHttp(50, 200, 50);
      seedHttp(50, 500, 50);

      evaluate(tenant.organizationId());

      assertThat(onlyIncident().getSeverity()).isEqualTo(IncidentSeverity.MEDIUM);
    }
  }

  @Nested
  @DisplayName("measurement and evidence")
  class MeasurementAndEvidence {

    @Test
    @DisplayName("measures detection latency from the observed signal, never zero")
    void recordsDetectionLatency() {
      seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.HIGH, 20);
      seedHttp(50, 200, 50);
      seedHttp(50, 500, 50);

      evaluate(tenant.organizationId());

      Incident incident = onlyIncident();
      assertThat(incident.getStartedAt())
          .as("startedAt is the window start, not the moment of detection")
          .isBefore(incident.getDetectedAt());
      assertThat(incident.timeToDetect())
          .as("detection latency must be a real measurement")
          .isPositive();
    }

    @Test
    @DisplayName("writes a DETECTED entry and an ALERT entry to the timeline")
    void buildsTimeline() {
      seedRule(RuleType.ERROR_RATE, null, IncidentSeverity.HIGH, 20);
      seedHttp(50, 200, 50);
      seedHttp(50, 500, 50);

      evaluate(tenant.organizationId());

      Incident incident = onlyIncident();
      assertThat(
              tenantBinder.callAs(
                  tenant.organizationId(),
                  () -> timelineRepository.findTimeline(incident.getId(), tenant.organizationId())))
          .extracting(e -> e.getKind().name())
          .contains("DETECTED", "ALERT");
    }
  }

  @Nested
  @DisplayName("tenant isolation")
  class TenantIsolation {

    @Test
    @DisplayName("one organization's telemetry cannot trigger another organization's rules")
    void detectionIsScopedToTenant() {
      TenantFixture.Tenant other = tenantFixture.createTenant("detect-other");

      // A rule belonging to `other`, but the failing traffic belongs to `tenant`.
      DetectionRule foreignRule =
          new DetectionRule(
              other.organizationId(),
              "other-org error rate",
              RuleType.ERROR_RATE,
              IncidentSeverity.HIGH);
      foreignRule.setMinSampleSize(20);
      tenantBinder.runAs(other.organizationId(), () -> ruleRepository.saveAndFlush(foreignRule));

      seedHttp(50, 200, 50);
      seedHttp(50, 500, 50);

      evaluate(other.organizationId());

      assertThat(openCount(other.organizationId()))
          .as("org B must not open an incident from org A's failures")
          .isZero();
    }
  }

  // ---- helpers ---------------------------------------------------------------

  private DetectionRule seedRule(
      RuleType type, BigDecimal threshold, IncidentSeverity severity, int minSample) {
    DetectionRule rule =
        new DetectionRule(tenant.organizationId(), type.name() + "-rule", type, severity);
    rule.setThreshold(threshold);
    rule.setWindowSeconds(600);
    rule.setMinSampleSize(minSample);
    rule.setCooldownSeconds(0);
    // RLS applies to test fixtures exactly as it does to production code.
    return tenantBinder.callAs(tenant.organizationId(), () -> ruleRepository.saveAndFlush(rule));
  }

  private void setServiceP95Sla(int millis) {
    tenantBinder.runAs(
        tenant.organizationId(),
        () -> {
          ServiceEntity service = serviceRepository.findById(tenant.serviceId()).orElseThrow();
          service.setExpectedP95LatencyMs(millis);
          serviceRepository.saveAndFlush(service);
        });
  }

  /** Writes {@code count} HTTP_REQUEST events through the real persistence path. */
  private void seedHttp(int count, int statusCode, int latencyMs) {
    List<TelemetryEventMessage> batch = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      batch.add(
          new TelemetryEventMessage(
              UUID.randomUUID(),
              tenant.organizationId(),
              tenant.serviceId(),
              now,
              EventType.HTTP_REQUEST,
              statusCode >= 500 ? Severity.ERROR : Severity.INFO,
              "trace-" + i,
              null,
              null,
              "GET",
              "/checkout",
              statusCode,
              latencyMs,
              null,
              null,
              "instance-1",
              null,
              Map.of(),
              Instant.now()));
    }
    tenantBinder.runAs(tenant.organizationId(), () -> telemetryWriter.write(batch));
  }

  /**
   * Production binds the tenant in {@code sweepAllTenants} before calling the evaluator; a test
   * calling the evaluator directly has to do the same, or row-level security correctly hides
   * everything from it.
   */
  private DetectionService.SweepResult evaluate(java.util.UUID organizationId) {
    return tenantBinder.callAs(
        organizationId, () -> detectionService.evaluateOrganization(organizationId, windowEnd));
  }

  private long openCount(java.util.UUID organizationId) {
    return tenantBinder.callAs(organizationId, () -> incidentRepository.countOpen(organizationId));
  }

  private Incident onlyIncident() {
    List<Incident> incidents =
        tenantBinder.callAs(
            tenant.organizationId(),
            () ->
                incidentRepository.findPage(
                    tenant.organizationId(),
                    null,
                    org.springframework.data.domain.PageRequest.of(0, 10)));
    assertThat(incidents).hasSize(1);
    return incidents.get(0);
  }
}
