package com.signalforge.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalforge.correlation.domain.EvidenceBundle;
import com.signalforge.correlation.service.CorrelationService;
import com.signalforge.incident.domain.Incident;
import com.signalforge.incident.repository.IncidentRepository;
import com.signalforge.platform.config.SignalForgeProperties;
import com.signalforge.platform.error.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrative incident summaries from a local LLM.
 *
 * <p>Three properties this design guarantees, in order of importance:
 *
 * <ol>
 *   <li><b>The AI can never block incident detection.</b> Nothing in the detection or correlation
 *       path calls this class. It is invoked only when someone opens the RCA panel, and the summary
 *       lives in its own table — an unreachable model means a missing row, not a missing incident.
 *   <li><b>The model sees only the evidence bundle.</b> It is handed a rendered view of {@link
 *       EvidenceBundle} and nothing else — no database access, no tools, no retrieval. It cannot
 *       cite a log line that was never collected because it has never seen one.
 *   <li><b>Unsupported output is rejected, not displayed.</b> Every returned cause is checked
 *       against the evidence that was supplied. A cause naming a service or version that does not
 *       appear in the bundle is dropped. If nothing survives, the honest answer is returned.
 * </ol>
 *
 * <p>Point 3 is the part that matters. A summariser without it is a hallucination pipeline pointed
 * at an on-call engineer during an outage.
 */
@Service
public class IncidentAiService {

  private static final Logger log = LoggerFactory.getLogger(IncidentAiService.class);

  static final String INSUFFICIENT_EVIDENCE = "Insufficient evidence to determine root cause.";

  private static final String SYSTEM_PROMPT =
      """
      You are an incident analysis assistant for a site reliability platform.

      You will be given a structured evidence bundle about a single production incident.
      Your entire response must be derived from that bundle.

      Absolute rules:
      1. Use ONLY facts present in the evidence bundle. Never introduce a service name,
         version, error type, timestamp or metric that does not appear in it.
      2. Every stated cause must cite the specific evidence lines that support it.
      3. If the bundle contains no correlating evidence, reply with exactly:
         "Insufficient evidence to determine root cause."
      4. Never speculate about logs, metrics, code or systems that are not in the bundle.
      5. Do not invent numbers. Quote the numbers given, unchanged.

      Respond as strict JSON with this shape and nothing else:
      {
        "summary": "two or three sentences describing what happened",
        "likelyCauses": [
          {"cause": "...", "evidence": ["...", "..."]}
        ],
        "recommendedSteps": ["...", "..."]
      }
      """;

  private final OllamaClient ollamaClient;
  private final CorrelationService correlationService;
  private final IncidentRepository incidentRepository;
  private final ObjectMapper objectMapper;
  private final SignalForgeProperties properties;
  private final Counter generatedCounter;
  private final Counter unavailableCounter;
  private final Counter rejectedClaimsCounter;

  public IncidentAiService(
      OllamaClient ollamaClient,
      CorrelationService correlationService,
      IncidentRepository incidentRepository,
      ObjectMapper objectMapper,
      SignalForgeProperties properties,
      MeterRegistry meterRegistry) {
    this.ollamaClient = ollamaClient;
    this.correlationService = correlationService;
    this.incidentRepository = incidentRepository;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.generatedCounter =
        Counter.builder("signalforge.ai.summaries")
            .tag("outcome", "generated")
            .register(meterRegistry);
    this.unavailableCounter =
        Counter.builder("signalforge.ai.summaries")
            .tag("outcome", "unavailable")
            .register(meterRegistry);
    this.rejectedClaimsCounter =
        Counter.builder("signalforge.ai.rejected_claims")
            .description("Model-stated causes discarded for not being supported by the evidence")
            .register(meterRegistry);
  }

  @Transactional(readOnly = true)
  public AiSummaryResponse summarise(UUID organizationId, UUID incidentId) {
    Incident incident =
        incidentRepository
            .findByIdInOrganization(incidentId, organizationId)
            .orElseThrow(() -> ApiException.notFound("Incident", incidentId));

    if (!ollamaClient.isEnabled()) {
      unavailableCounter.increment();
      return AiSummaryResponse.unavailable(
          "AI assistant is disabled. Set SF_AI_ENABLED=true and run a local Ollama to enable it.");
    }

    EvidenceBundle bundle = correlationService.buildFor(incident);

    // Short-circuit before spending a model call. If the deterministic
    // correlator found nothing, there is nothing for a language model to
    // summarise, and asking it anyway is precisely how you get a confident
    // fabrication.
    if (bundle.isInconclusive()) {
      return AiSummaryResponse.inconclusive(ollamaClient.model());
    }

    if (!ollamaClient.isReachable()) {
      unavailableCounter.increment();
      return AiSummaryResponse.unavailable(
          "AI assistant is enabled but the model server is not reachable.");
    }

    Instant started = Instant.now();
    Optional<String> raw = ollamaClient.generate(SYSTEM_PROMPT, renderEvidence(bundle));

    if (raw.isEmpty()) {
      unavailableCounter.increment();
      return AiSummaryResponse.unavailable("AI assistant did not return a usable response.");
    }

    Optional<ParsedSummary> parsed = parse(raw.get());
    if (parsed.isEmpty()) {
      unavailableCounter.increment();
      return AiSummaryResponse.unavailable("AI assistant returned an unparseable response.");
    }

    ParsedSummary summary = groundCheck(parsed.get(), bundle);

    if (summary.likelyCauses().isEmpty()) {
      // Everything it said failed the grounding check.
      return AiSummaryResponse.inconclusive(ollamaClient.model());
    }

    generatedCounter.increment();
    return new AiSummaryResponse(
        true,
        null,
        ollamaClient.model(),
        summary.summary(),
        summary.likelyCauses(),
        summary.recommendedSteps(),
        Instant.now(),
        Duration.between(started, Instant.now()).toMillis());
  }

  /**
   * Renders the evidence bundle as plain text for the prompt.
   *
   * <p>Text rather than raw JSON: small local models follow labelled prose noticeably better than
   * nested JSON, and this keeps the token count down on a 3B model running on a laptop.
   */
  String renderEvidence(EvidenceBundle bundle) {
    StringBuilder sb = new StringBuilder();
    int budget = properties.ai().maxEvidenceItems();

    sb.append("INCIDENT\n");
    sb.append("  Reference: ").append(bundle.incidentReference()).append('\n');
    sb.append("  Title: ").append(bundle.incidentTitle()).append('\n');
    sb.append("  Severity: ").append(bundle.severity()).append('\n');
    sb.append("  Started at: ").append(bundle.incidentStartedAt()).append('\n');
    sb.append("  Affected service: ").append(bundle.primaryServiceName()).append('\n');

    sb.append("\nMETRICS DURING THE INCIDENT\n");
    EvidenceBundle.MetricEvidence m = bundle.metrics();
    sb.append("  Requests: ").append(m.requestCount()).append('\n');
    sb.append("  Errors: ").append(m.errorCount()).append('\n');
    sb.append(String.format("  Error rate: %.2f%%%n", m.errorRatePercent()));
    sb.append(String.format("  p95 latency: %.0f ms%n", m.p95LatencyMs()));
    if (m.baselineP95LatencyMs() != null) {
      sb.append(
          String.format("  Baseline p95 before incident: %.0f ms%n", m.baselineP95LatencyMs()));
    }
    if (m.baselineErrorRatePercent() != null) {
      sb.append(
          String.format(
              "  Baseline error rate before incident: %.2f%%%n", m.baselineErrorRatePercent()));
    }

    if (!bundle.deployments().isEmpty()) {
      sb.append("\nDEPLOYMENTS BEFORE THE INCIDENT\n");
      bundle.deployments().stream()
          .limit(budget)
          .forEach(
              d ->
                  sb.append("  - ")
                      .append(d.serviceName())
                      .append(" version ")
                      .append(d.version())
                      .append(", ")
                      .append(d.minutesBeforeIncident())
                      .append(" minutes before the incident, status ")
                      .append(d.status())
                      .append(d.isPrimaryService() ? " (this is the affected service)" : "")
                      .append('\n'));
    }

    if (!bundle.relatedServices().isEmpty()) {
      sb.append("\nOTHER SERVICES FAILING IN THE SAME TRACES\n");
      bundle.relatedServices().stream()
          .limit(budget)
          .forEach(
              s ->
                  sb.append("  - ")
                      .append(s.serviceName())
                      .append(" is ")
                      .append(s.healthStatus())
                      .append('\n'));
    }

    if (!bundle.errorSignatures().isEmpty()) {
      sb.append("\nERROR SIGNATURES\n");
      bundle.errorSignatures().stream()
          .limit(budget)
          .forEach(
              e ->
                  sb.append(
                      String.format(
                          "  - %s: %d occurrences (%.0f%% of errors)%n",
                          e.errorType(), e.occurrences(), e.shareOfErrors() * 100)));
    }

    sb.append("\nDETERMINISTIC CORRELATION ALREADY COMPUTED\n");
    bundle
        .factors()
        .forEach(
            f -> {
              sb.append("  - [")
                  .append(f.confidence())
                  .append("% confidence] ")
                  .append(f.summary())
                  .append('\n');
              f.evidence().forEach(e -> sb.append("      evidence: ").append(e).append('\n'));
            });

    return sb.toString();
  }

  private Optional<ParsedSummary> parse(String raw) {
    try {
      // Small models frequently wrap JSON in prose or a markdown fence. Extract
      // the outermost object rather than failing the whole thing over garnish.
      int start = raw.indexOf('{');
      int end = raw.lastIndexOf('}');
      if (start < 0 || end <= start) {
        return Optional.empty();
      }
      var node = objectMapper.readTree(raw.substring(start, end + 1));

      String summary = node.path("summary").asText("");
      if (summary.isBlank()) {
        return Optional.empty();
      }

      List<LikelyCause> causes = new ArrayList<>();
      node.path("likelyCauses")
          .forEach(
              c -> {
                String cause = c.path("cause").asText("");
                List<String> evidence = new ArrayList<>();
                c.path("evidence").forEach(e -> evidence.add(e.asText()));
                if (!cause.isBlank()) {
                  causes.add(new LikelyCause(cause, evidence));
                }
              });

      List<String> steps = new ArrayList<>();
      node.path("recommendedSteps").forEach(s -> steps.add(s.asText()));

      return Optional.of(new ParsedSummary(summary, causes, steps));
    } catch (Exception e) {
      log.warn("Could not parse AI response: {}", e.toString());
      return Optional.empty();
    }
  }

  /**
   * Drops any stated cause that is not anchored in the supplied evidence.
   *
   * <p>The check is intentionally simple and conservative: a cause must either cite at least one
   * evidence line, or mention a service name, version string or error type that actually appears in
   * the bundle. It will occasionally discard a fair paraphrase. That is the correct direction to
   * err — a dropped true statement costs an engineer nothing, while a retained invented one sends
   * them to debug a service that was never involved.
   */
  ParsedSummary groundCheck(ParsedSummary summary, EvidenceBundle bundle) {
    List<String> anchors = new ArrayList<>();
    bundle
        .deployments()
        .forEach(
            d -> {
              anchors.add(d.serviceName());
              anchors.add(d.version());
            });
    bundle.relatedServices().forEach(s -> anchors.add(s.serviceName()));
    bundle.errorSignatures().forEach(e -> anchors.add(e.errorType()));
    if (bundle.primaryServiceName() != null) {
      anchors.add(bundle.primaryServiceName());
    }

    List<String> lowered =
        anchors.stream().filter(a -> a != null && !a.isBlank()).map(String::toLowerCase).toList();

    List<LikelyCause> grounded = new ArrayList<>();
    for (LikelyCause cause : summary.likelyCauses()) {
      boolean hasEvidence = !cause.evidence().isEmpty();
      String haystack = cause.cause().toLowerCase();
      boolean mentionsKnownEntity = lowered.stream().anyMatch(haystack::contains);

      if (hasEvidence || mentionsKnownEntity) {
        grounded.add(cause);
      } else {
        rejectedClaimsCounter.increment();
        log.info("Discarded ungrounded AI cause: {}", cause.cause());
      }
    }

    return new ParsedSummary(summary.summary(), grounded, summary.recommendedSteps());
  }

  record ParsedSummary(
      String summary, List<LikelyCause> likelyCauses, List<String> recommendedSteps) {}

  public record LikelyCause(String cause, List<String> evidence) {}

  /**
   * @param available false whenever there is no usable AI output, for any reason. The UI renders
   *     {@code reason} as an explanation rather than an error.
   */
  public record AiSummaryResponse(
      boolean available,
      String reason,
      String model,
      String summary,
      List<LikelyCause> likelyCauses,
      List<String> recommendedSteps,
      Instant generatedAt,
      Long generationMs) {

    static AiSummaryResponse unavailable(String reason) {
      return new AiSummaryResponse(false, reason, null, null, List.of(), List.of(), null, null);
    }

    /** Evidence exists but supports no conclusion. Saying so is the correct answer. */
    static AiSummaryResponse inconclusive(String model) {
      return new AiSummaryResponse(
          true, null, model, INSUFFICIENT_EVIDENCE, List.of(), List.of(), Instant.now(), 0L);
    }
  }
}
