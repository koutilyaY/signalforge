package com.signalforge.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalforge.correlation.domain.EvidenceBundle;
import com.signalforge.correlation.service.CorrelationService;
import com.signalforge.detection.domain.IncidentSeverity;
import com.signalforge.incident.domain.Incident;
import com.signalforge.incident.repository.IncidentRepository;
import com.signalforge.platform.config.SignalForgeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The AI assistant's guardrails.
 *
 * <p>These are unit tests with a mocked model, because the property under test is not "does the LLM
 * write good prose" — it is "can the LLM's output reach a user unchecked". That is deterministic
 * logic and deserves a deterministic test.
 */
@DisplayName("AI incident summariser")
class IncidentAiServiceTest {

  private OllamaClient ollama;
  private CorrelationService correlation;
  private IncidentRepository incidents;
  private IncidentAiService service;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID incidentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    ollama = mock(OllamaClient.class);
    correlation = mock(CorrelationService.class);
    incidents = mock(IncidentRepository.class);

    when(ollama.model()).thenReturn("llama3.2:3b");
    when(incidents.findByIdInOrganization(incidentId, organizationId))
        .thenReturn(Optional.of(incident()));

    service =
        new IncidentAiService(
            ollama,
            correlation,
            incidents,
            new ObjectMapper(),
            properties(),
            new SimpleMeterRegistry());
  }

  @Nested
  @DisplayName("availability")
  class Availability {

    @Test
    @DisplayName("reports unavailable, not an error, when the assistant is disabled")
    void disabledIsNotAnError() {
      when(ollama.isEnabled()).thenReturn(false);

      IncidentAiService.AiSummaryResponse response = service.summarise(organizationId, incidentId);

      assertThat(response.available()).isFalse();
      assertThat(response.reason()).contains("disabled");
      // The critical assertion: a disabled assistant must not even reach for the
      // correlation engine, let alone the network.
      verify(ollama, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("reports unavailable when the model server cannot be reached")
    void unreachableModelIsNotAnError() {
      when(ollama.isEnabled()).thenReturn(true);
      when(ollama.isReachable()).thenReturn(false);
      when(correlation.buildFor(org.mockito.ArgumentMatchers.any()))
          .thenReturn(bundleWithEvidence());

      IncidentAiService.AiSummaryResponse response = service.summarise(organizationId, incidentId);

      assertThat(response.available()).isFalse();
      assertThat(response.reason()).contains("not reachable");
      verify(ollama, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("reports unavailable when the model returns nothing usable")
    void emptyModelResponse() {
      when(ollama.isEnabled()).thenReturn(true);
      when(ollama.isReachable()).thenReturn(true);
      when(correlation.buildFor(org.mockito.ArgumentMatchers.any()))
          .thenReturn(bundleWithEvidence());
      when(ollama.generate(anyString(), anyString())).thenReturn(Optional.empty());

      assertThat(service.summarise(organizationId, incidentId).available()).isFalse();
    }

    @Test
    @DisplayName("reports unavailable when the model returns unparseable text")
    void unparseableResponse() {
      when(ollama.isEnabled()).thenReturn(true);
      when(ollama.isReachable()).thenReturn(true);
      when(correlation.buildFor(org.mockito.ArgumentMatchers.any()))
          .thenReturn(bundleWithEvidence());
      when(ollama.generate(anyString(), anyString()))
          .thenReturn(Optional.of("I'm sorry, I can't help with that."));

      assertThat(service.summarise(organizationId, incidentId).available()).isFalse();
    }
  }

  @Nested
  @DisplayName("refusing to speculate")
  class RefusingToSpeculate {

    @Test
    @DisplayName("says 'insufficient evidence' without calling the model when nothing correlates")
    void inconclusiveShortCircuits() {
      when(ollama.isEnabled()).thenReturn(true);
      when(correlation.buildFor(org.mockito.ArgumentMatchers.any())).thenReturn(emptyBundle());

      IncidentAiService.AiSummaryResponse response = service.summarise(organizationId, incidentId);

      assertThat(response.summary()).isEqualTo(IncidentAiService.INSUFFICIENT_EVIDENCE);
      assertThat(response.likelyCauses()).isEmpty();
      // Asking a language model to explain an incident with no evidence is
      // exactly how you get a confident fabrication. Do not ask.
      verify(ollama, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("falls back to 'insufficient evidence' when every stated cause is ungrounded")
    void allCausesRejected() {
      when(ollama.isEnabled()).thenReturn(true);
      when(ollama.isReachable()).thenReturn(true);
      when(correlation.buildFor(org.mockito.ArgumentMatchers.any()))
          .thenReturn(bundleWithEvidence());
      // Names a service that is nowhere in the bundle and cites no evidence.
      when(ollama.generate(anyString(), anyString()))
          .thenReturn(
              Optional.of(
                  """
                  {"summary":"Something broke.",
                   "likelyCauses":[{"cause":"The auth-service cache was cold","evidence":[]}],
                   "recommendedSteps":["Check the cache"]}
                  """));

      IncidentAiService.AiSummaryResponse response = service.summarise(organizationId, incidentId);

      assertThat(response.summary()).isEqualTo(IncidentAiService.INSUFFICIENT_EVIDENCE);
      assertThat(response.likelyCauses()).isEmpty();
    }
  }

  @Nested
  @DisplayName("grounding check")
  class GroundingCheck {

    @Test
    @DisplayName("keeps a cause that names an entity from the evidence bundle")
    void keepsGroundedCause() {
      IncidentAiService.ParsedSummary checked =
          service.groundCheck(
              new IncidentAiService.ParsedSummary(
                  "summary",
                  List.of(
                      new IncidentAiService.LikelyCause(
                          "The payment-service 2.7.4 deployment introduced the regression",
                          List.of())),
                  List.of()),
              bundleWithEvidence());

      assertThat(checked.likelyCauses()).hasSize(1);
    }

    @Test
    @DisplayName("keeps a cause that cites evidence even if it paraphrases")
    void keepsCauseWithCitedEvidence() {
      IncidentAiService.ParsedSummary checked =
          service.groundCheck(
              new IncidentAiService.ParsedSummary(
                  "summary",
                  List.of(
                      new IncidentAiService.LikelyCause(
                          "A recent release is the likely trigger",
                          List.of("Deployment became effective 13 minutes before the incident"))),
                  List.of()),
              bundleWithEvidence());

      assertThat(checked.likelyCauses()).hasSize(1);
    }

    @Test
    @DisplayName("drops a cause naming a service that does not exist in the bundle")
    void dropsInventedService() {
      IncidentAiService.ParsedSummary checked =
          service.groundCheck(
              new IncidentAiService.ParsedSummary(
                  "summary",
                  List.of(
                      new IncidentAiService.LikelyCause(
                          "The redis-cluster failed over", List.of())),
                  List.of()),
              bundleWithEvidence());

      assertThat(checked.likelyCauses())
          .as("an invented cause sends an engineer to debug a system that was never involved")
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("prompt construction")
  class PromptConstruction {

    @Test
    @DisplayName("includes only facts drawn from the evidence bundle")
    void rendersOnlyBundleFacts() {
      String rendered = service.renderEvidence(bundleWithEvidence());

      assertThat(rendered).contains("payment-service");
      assertThat(rendered).contains("2.7.4");
      assertThat(rendered).contains("PaymentGatewayTimeout");
      assertThat(rendered).contains("13 minutes before the incident");
    }

    @Test
    @DisplayName("includes the baseline so the model can describe a change, not just a level")
    void includesBaseline() {
      String rendered = service.renderEvidence(bundleWithEvidence());
      assertThat(rendered).contains("Baseline p95 before incident");
    }
  }

  // ---- fixtures --------------------------------------------------------------

  private Incident incident() {
    Instant started = Instant.parse("2026-08-07T14:00:00Z");
    Incident i =
        new Incident(
            organizationId,
            "INC-42",
            "Elevated error rate on checkout-service",
            IncidentSeverity.HIGH,
            "fingerprint",
            started,
            started.plusSeconds(30));
    i.setPrimaryServiceId(UUID.randomUUID());
    return i;
  }

  private EvidenceBundle emptyBundle() {
    return new EvidenceBundle(
        incidentId,
        "INC-42",
        "Elevated error rate",
        "HIGH",
        Instant.parse("2026-08-07T14:00:00Z"),
        Instant.parse("2026-08-07T13:45:00Z"),
        Instant.parse("2026-08-07T14:15:00Z"),
        "checkout-service",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new EvidenceBundle.MetricEvidence(0, 0, 0, 0, 0, null, null));
  }

  private EvidenceBundle bundleWithEvidence() {
    return new EvidenceBundle(
        incidentId,
        "INC-42",
        "Elevated error rate on checkout-service",
        "HIGH",
        Instant.parse("2026-08-07T14:00:00Z"),
        Instant.parse("2026-08-07T13:45:00Z"),
        Instant.parse("2026-08-07T14:15:00Z"),
        "checkout-service",
        List.of(
            new EvidenceBundle.ContributingFactor(
                "DEPLOYMENT",
                "payment-service deployment 2.7.4, 13 minute(s) before incident start",
                72,
                List.of("Deployment became effective 13 minutes before the incident"))),
        List.of(
            new EvidenceBundle.DeploymentEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "payment-service",
                "2.7.4",
                "abc123",
                "main",
                "SUCCEEDED",
                "ci-pipeline",
                Instant.parse("2026-08-07T13:47:00Z"),
                13,
                false)),
        List.of(
            new EvidenceBundle.ServiceEvidence(
                UUID.randomUUID(), "payment-service", "DEGRADED", 4)),
        List.of(new EvidenceBundle.ErrorSignatureEvidence("PaymentGatewayTimeout", 42, 0.84)),
        List.of("trace-abc", "trace-def"),
        new EvidenceBundle.MetricEvidence(1200, 310, 25.8, 2100, 180, 0.4, 190.0));
  }

  private SignalForgeProperties properties() {
    return new SignalForgeProperties(
        new SignalForgeProperties.Security(
            "test-secret-that-is-long-enough-for-hs256-signing",
            Duration.ofMinutes(30),
            Duration.ofDays(7),
            "test",
            List.of(),
            4,
            5,
            Duration.ofMinutes(1)),
        new SignalForgeProperties.Ingestion(6000, 500, Duration.ofHours(24), Duration.ofMinutes(5)),
        new SignalForgeProperties.Detection(
            false, Duration.ofSeconds(15), Duration.ofMinutes(10), 500),
        new SignalForgeProperties.Correlation(
            Duration.ofMinutes(60), Duration.ofMinutes(15), 10, 25, 10),
        new SignalForgeProperties.Ai(
            true, "http://localhost:11434", "llama3.2:3b", Duration.ofSeconds(45), 40, 0.1),
        new SignalForgeProperties.Cache(
            Duration.ofSeconds(30),
            Duration.ofMinutes(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(30)));
  }
}
