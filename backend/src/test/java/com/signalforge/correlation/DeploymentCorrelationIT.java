package com.signalforge.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import com.signalforge.correlation.domain.EvidenceBundle;
import com.signalforge.correlation.service.CorrelationService;
import com.signalforge.deployment.domain.Deployment;
import com.signalforge.deployment.domain.DeploymentStatus;
import com.signalforge.deployment.repository.DeploymentRepository;
import com.signalforge.detection.domain.IncidentSeverity;
import com.signalforge.incident.domain.Incident;
import com.signalforge.incident.repository.IncidentRepository;
import com.signalforge.messaging.TelemetryEventMessage;
import com.signalforge.platform.tenant.TenantBinder;
import com.signalforge.registry.domain.Environment;
import com.signalforge.registry.domain.ServiceEntity;
import com.signalforge.registry.repository.ServiceRepository;
import com.signalforge.support.AbstractIntegrationTest;
import com.signalforge.support.TenantFixture;
import com.signalforge.telemetry.domain.EventType;
import com.signalforge.telemetry.domain.Severity;
import com.signalforge.telemetry.persistence.TelemetryWriter;
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
 * Deployment correlation and evidence ranking.
 *
 * <p>The assertions that matter most here are the negative ones. A correlator that always produces
 * a confident-looking answer is worse than no correlator: it teaches engineers to distrust it
 * exactly when they most need to trust it.
 */
@DisplayName("Deployment correlation and RCA")
class DeploymentCorrelationIT extends AbstractIntegrationTest {

  @Autowired private CorrelationService correlationService;
  @Autowired private DeploymentRepository deploymentRepository;
  @Autowired private IncidentRepository incidentRepository;
  @Autowired private ServiceRepository serviceRepository;
  @Autowired private TelemetryWriter telemetryWriter;
  @Autowired private TenantFixture tenantFixture;
  @Autowired private TenantBinder tenantBinder;

  private TenantFixture.Tenant tenant;
  private Instant incidentStart;

  @BeforeEach
  void setUp() {
    tenant = tenantFixture.createTenant("rca");
    incidentStart = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(5, ChronoUnit.MINUTES);
  }

  @Nested
  @DisplayName("deployment proximity")
  class DeploymentProximity {

    @Test
    @DisplayName("surfaces a deployment that landed shortly before the incident")
    void findsRecentDeployment() {
      recordDeployment(tenant.serviceId(), "2.7.4", incidentStart.minus(13, ChronoUnit.MINUTES));
      Incident incident = persistIncident();

      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      assertThat(bundle.deployments()).hasSize(1);
      assertThat(bundle.deployments().get(0).minutesBeforeIncident()).isEqualTo(13);
      assertThat(bundle.factors())
          .extracting(EvidenceBundle.ContributingFactor::kind)
          .contains("DEPLOYMENT");
    }

    @Test
    @DisplayName("ranks a closer deployment above a more distant one")
    void closerDeploymentRanksHigher() {
      ServiceEntity other = registerService("payment-service");
      recordDeployment(other.getId(), "1.0.0", incidentStart.minus(55, ChronoUnit.MINUTES));
      recordDeployment(other.getId(), "1.0.1", incidentStart.minus(3, ChronoUnit.MINUTES));

      Incident incident = persistIncident();
      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      List<EvidenceBundle.ContributingFactor> deployFactors =
          bundle.factors().stream().filter(f -> f.kind().equals("DEPLOYMENT")).toList();

      assertThat(deployFactors).hasSize(2);
      assertThat(deployFactors.get(0).summary())
          .as("the deployment 3 minutes before must outrank the one 55 minutes before")
          .contains("1.0.1");
      assertThat(deployFactors.get(0).confidence())
          .isGreaterThan(deployFactors.get(1).confidence());
    }

    @Test
    @DisplayName("ranks a deployment of the failing service above a deployment of another service")
    void sameServiceDeploymentRanksHigher() {
      ServiceEntity other = registerService("inventory-service");
      // Both equally close in time, so only the same-service bonus separates them.
      Instant when = incidentStart.minus(10, ChronoUnit.MINUTES);
      recordDeployment(other.getId(), "9.9.9", when);
      recordDeployment(tenant.serviceId(), "2.7.4", when);

      Incident incident = persistIncident();
      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      EvidenceBundle.ContributingFactor top =
          bundle.factors().stream()
              .filter(f -> f.kind().equals("DEPLOYMENT"))
              .findFirst()
              .orElseThrow();

      assertThat(top.summary()).contains("checkout-service");
    }

    @Test
    @DisplayName("ignores a deployment that landed after the incident began")
    void ignoresLaterDeployment() {
      // A rollback, typically. It cannot be the cause of something already broken.
      recordDeployment(tenant.serviceId(), "2.7.5", incidentStart.plus(10, ChronoUnit.MINUTES));
      Incident incident = persistIncident();

      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      assertThat(bundle.factors())
          .extracting(EvidenceBundle.ContributingFactor::kind)
          .doesNotContain("DEPLOYMENT");
    }

    @Test
    @DisplayName("ignores a deployment older than the lookback window")
    void ignoresAncientDeployment() {
      recordDeployment(tenant.serviceId(), "1.0.0", incidentStart.minus(6, ChronoUnit.HOURS));
      Incident incident = persistIncident();

      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      assertThat(bundle.deployments()).isEmpty();
    }

    @Test
    @DisplayName("measures from completion, not from the moment the rollout started")
    void measuresFromEffectiveTime() {
      // Started an hour before the incident but only finished 5 minutes before.
      Deployment deployment =
          recordDeployment(
              tenant.serviceId(), "3.0.0", incidentStart.minus(60, ChronoUnit.MINUTES));
      deployment.markCompleted(
          DeploymentStatus.SUCCEEDED, incidentStart.minus(5, ChronoUnit.MINUTES));
      tenantBinder.runAs(
          tenant.organizationId(), () -> deploymentRepository.saveAndFlush(deployment));

      Incident incident = persistIncident();
      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      assertThat(bundle.deployments().get(0).minutesBeforeIncident())
          .as("a slow rollout is judged by when it could first affect traffic")
          .isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("honesty")
  class Honesty {

    @Test
    @DisplayName("reports no contributing factors when nothing correlates")
    void inconclusiveWhenNoEvidence() {
      Incident incident = persistIncident();

      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      assertThat(bundle.isInconclusive())
          .as("inventing a cause is worse than admitting there is no evidence")
          .isTrue();
      assertThat(bundle.factors()).isEmpty();
    }

    @Test
    @DisplayName("every factor carries the evidence that produced it")
    void everyFactorIsBackedByEvidence() {
      recordDeployment(tenant.serviceId(), "2.7.4", incidentStart.minus(8, ChronoUnit.MINUTES));
      seedErrors(20, "PaymentGatewayTimeout");
      Incident incident = persistIncident();

      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      assertThat(bundle.factors()).isNotEmpty();
      assertThat(bundle.factors())
          .allSatisfy(
              factor -> {
                assertThat(factor.evidence())
                    .as("a factor with no evidence is a guess, not a finding")
                    .isNotEmpty();
                assertThat(factor.confidence()).isBetween(0, 100);
              });
    }

    @Test
    @DisplayName("factors are ordered by confidence, highest first")
    void factorsAreOrdered() {
      recordDeployment(tenant.serviceId(), "2.7.4", incidentStart.minus(2, ChronoUnit.MINUTES));
      seedErrors(30, "ConnectionPoolExhausted");
      Incident incident = persistIncident();

      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      List<Integer> confidences =
          bundle.factors().stream().map(EvidenceBundle.ContributingFactor::confidence).toList();
      assertThat(confidences).isSortedAccordingTo((a, b) -> Integer.compare(b, a));
    }
  }

  @Nested
  @DisplayName("error signatures")
  class ErrorSignatures {

    @Test
    @DisplayName("identifies a dominant error signature")
    void findsDominantSignature() {
      seedErrors(40, "PaymentGatewayTimeout");
      seedErrors(5, "ValidationError");
      Incident incident = persistIncident();

      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      assertThat(bundle.errorSignatures()).isNotEmpty();
      assertThat(bundle.errorSignatures().get(0).errorType()).isEqualTo("PaymentGatewayTimeout");
      assertThat(bundle.factors())
          .extracting(EvidenceBundle.ContributingFactor::kind)
          .contains("ERROR_SIGNATURE");
    }

    @Test
    @DisplayName("does not claim a dominant signature when errors are evenly spread")
    void noDominantSignatureWhenSpread() {
      seedErrors(10, "ErrorA");
      seedErrors(10, "ErrorB");
      seedErrors(10, "ErrorC");
      Incident incident = persistIncident();

      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      assertThat(bundle.factors())
          .extracting(EvidenceBundle.ContributingFactor::kind)
          .doesNotContain("ERROR_SIGNATURE");
    }
  }

  @Nested
  @DisplayName("tenant isolation")
  class TenantIsolation {

    @Test
    @DisplayName("another organization's deployments never appear as evidence")
    void deploymentsAreScoped() {
      TenantFixture.Tenant other = tenantFixture.createTenant("rca-other");
      Deployment foreign =
          new Deployment(
              other.organizationId(),
              other.serviceId(),
              "6.6.6",
              Environment.PRODUCTION,
              incidentStart.minus(5, ChronoUnit.MINUTES));
      tenantBinder.runAs(other.organizationId(), () -> deploymentRepository.saveAndFlush(foreign));

      Incident incident = persistIncident();
      EvidenceBundle bundle =
          tenantBinder.callAs(tenant.organizationId(), () -> correlationService.buildFor(incident));

      assertThat(bundle.deployments())
          .as("org B's release must never be offered as a cause of org A's incident")
          .isEmpty();
    }
  }

  // ---- helpers ---------------------------------------------------------------

  private Deployment recordDeployment(UUID serviceId, String version, Instant at) {
    Deployment deployment =
        new Deployment(tenant.organizationId(), serviceId, version, Environment.PRODUCTION, at);
    deployment.setCommitSha("abc123def456");
    deployment.setBranch("main");
    deployment.setDeployedBy("ci-pipeline");
    deployment.markCompleted(DeploymentStatus.SUCCEEDED, at);
    return tenantBinder.callAs(
        tenant.organizationId(), () -> deploymentRepository.saveAndFlush(deployment));
  }

  private ServiceEntity registerService(String name) {
    return tenantBinder.callAs(
        tenant.organizationId(),
        () ->
            serviceRepository.saveAndFlush(
                new ServiceEntity(tenant.organizationId(), name, Environment.PRODUCTION)));
  }

  private void seedErrors(int count, String errorType) {
    List<TelemetryEventMessage> batch = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      batch.add(
          new TelemetryEventMessage(
              UUID.randomUUID(),
              tenant.organizationId(),
              tenant.serviceId(),
              incidentStart.plus(1, ChronoUnit.MINUTES),
              EventType.APPLICATION_ERROR,
              Severity.ERROR,
              "trace-" + errorType + "-" + i,
              null,
              null,
              null,
              null,
              null,
              null,
              errorType,
              errorType + " occurred",
              "instance-1",
              null,
              Map.of(),
              Instant.now()));
    }
    tenantBinder.runAs(tenant.organizationId(), () -> telemetryWriter.write(batch));
  }

  private Incident persistIncident() {
    Incident incident =
        new Incident(
            tenant.organizationId(),
            "INC-" + System.nanoTime() % 100000,
            "Elevated error rate on checkout-service",
            IncidentSeverity.HIGH,
            "ERROR_RATE:" + tenant.serviceId() + ":" + UUID.randomUUID(),
            incidentStart,
            incidentStart.plus(30, ChronoUnit.SECONDS));
    incident.setPrimaryServiceId(tenant.serviceId());
    return tenantBinder.callAs(
        tenant.organizationId(), () -> incidentRepository.saveAndFlush(incident));
  }
}
