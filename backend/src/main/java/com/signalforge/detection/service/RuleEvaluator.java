package com.signalforge.detection.service;

import com.signalforge.detection.domain.DetectionRule;
import com.signalforge.detection.domain.RuleType;
import com.signalforge.registry.domain.ServiceEntity;
import com.signalforge.telemetry.domain.EventType;
import com.signalforge.telemetry.query.TelemetryQueryRepository;
import com.signalforge.telemetry.query.WindowStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Decides whether one rule is currently breached for one service.
 *
 * <p>Pure measurement and comparison — no persistence, no incident creation, no side effects. That
 * separation is what makes the thresholds testable without a database and keeps the "should we
 * page?" logic readable in one place.
 */
@Component
public class RuleEvaluator {

  private final TelemetryQueryRepository telemetryQuery;

  public RuleEvaluator(TelemetryQueryRepository telemetryQuery) {
    this.telemetryQuery = telemetryQuery;
  }

  /**
   * @param windowEnd exclusive upper bound; the window is {@code [windowEnd - rule.window,
   *     windowEnd)}
   */
  public Evaluation evaluate(DetectionRule rule, ServiceEntity service, Instant windowEnd) {
    Instant windowStart = windowEnd.minus(rule.window());
    UUID organizationId = service.getOrganizationId();
    UUID serviceId = service.getId();

    return switch (rule.getRuleType()) {
      case ERROR_RATE -> evaluateErrorRate(rule, service, windowStart, windowEnd);
      case P95_LATENCY -> evaluateP95(rule, service, windowStart, windowEnd);
      case SERVICE_DOWN ->
          countRule(
              rule,
              serviceId,
              windowStart,
              windowEnd,
              telemetryQuery.countEvents(
                  organizationId, serviceId, EventType.SERVICE_DOWN, windowStart, windowEnd),
              defaultThreshold(rule, BigDecimal.ONE),
              "%d service-down event(s)");
      case DATABASE_ERROR_SPIKE ->
          countRule(
              rule,
              serviceId,
              windowStart,
              windowEnd,
              telemetryQuery.countEvents(
                  organizationId, serviceId, EventType.DATABASE_ERROR, windowStart, windowEnd),
              defaultThreshold(rule, BigDecimal.valueOf(5)),
              "%d database error(s)");
      case KAFKA_LAG ->
          countRule(
              rule,
              serviceId,
              windowStart,
              windowEnd,
              telemetryQuery.maxConsumerLag(organizationId, serviceId, windowStart, windowEnd),
              defaultThreshold(rule, BigDecimal.valueOf(10_000)),
              "consumer lag reached %d messages");
      case CORRELATED_ERRORS ->
          countRule(
              rule,
              serviceId,
              windowStart,
              windowEnd,
              telemetryQuery
                  .correlatedFailingServices(organizationId, serviceId, windowStart, windowEnd, 50)
                  .size(),
              defaultThreshold(rule, BigDecimal.ONE),
              "%d other service(s) failing in the same traces");
    };
  }

  private Evaluation evaluateErrorRate(
      DetectionRule rule, ServiceEntity service, Instant windowStart, Instant windowEnd) {

    WindowStats stats =
        telemetryQuery.windowStats(
            service.getOrganizationId(), service.getId(), windowStart, windowEnd);

    // A null threshold on the rule means "use this service's own SLA". The SLA
    // lives on the service so one organization-wide rule can hold every service
    // to its own target instead of a single flattened number.
    BigDecimal threshold =
        rule.getThreshold() != null ? rule.getThreshold() : service.getExpectedErrorRate();

    // Without this floor, a window containing one request that happened to fail
    // reads as a 100% error rate and pages someone at 3am over a single blip.
    if (stats.requestCount() < rule.getMinSampleSize()) {
      return Evaluation.notBreached(
          rule,
          service.getId(),
          windowStart,
          windowEnd,
          stats.errorRate(),
          threshold,
          (int) stats.requestCount(),
          "sample too small (%d < %d)".formatted(stats.requestCount(), rule.getMinSampleSize()));
    }

    BigDecimal observed = stats.errorRate();
    boolean breached = observed.compareTo(threshold) > 0;

    return new Evaluation(
        rule,
        service.getId(),
        breached,
        windowStart,
        windowEnd,
        observed,
        threshold,
        (int) stats.requestCount(),
        breached
            ? "error rate %s%% exceeded threshold %s%% over %d requests"
                .formatted(asPercent(observed), asPercent(threshold), stats.requestCount())
            : "within threshold");
  }

  private Evaluation evaluateP95(
      DetectionRule rule, ServiceEntity service, Instant windowStart, Instant windowEnd) {

    WindowStats stats =
        telemetryQuery.windowStats(
            service.getOrganizationId(), service.getId(), windowStart, windowEnd);

    BigDecimal threshold =
        rule.getThreshold() != null
            ? rule.getThreshold()
            : BigDecimal.valueOf(service.getExpectedP95LatencyMs());

    if (stats.requestCount() < rule.getMinSampleSize()) {
      return Evaluation.notBreached(
          rule,
          service.getId(),
          windowStart,
          windowEnd,
          BigDecimal.ZERO,
          threshold,
          (int) stats.requestCount(),
          "sample too small (%d < %d)".formatted(stats.requestCount(), rule.getMinSampleSize()));
    }

    BigDecimal observed =
        BigDecimal.valueOf(stats.p95LatencyMs()).setScale(2, RoundingMode.HALF_UP);
    boolean breached = observed.compareTo(threshold) > 0;

    return new Evaluation(
        rule,
        service.getId(),
        breached,
        windowStart,
        windowEnd,
        observed,
        threshold,
        (int) stats.requestCount(),
        breached
            ? "p95 latency %sms exceeded SLA %sms over %d requests"
                .formatted(
                    observed.toPlainString(), threshold.toPlainString(), stats.requestCount())
            : "within SLA");
  }

  private Evaluation countRule(
      DetectionRule rule,
      UUID serviceId,
      Instant windowStart,
      Instant windowEnd,
      long observedCount,
      BigDecimal threshold,
      String summaryTemplate) {

    BigDecimal observed = BigDecimal.valueOf(observedCount);
    // Count rules use >= so a threshold of 1 fires on the first occurrence.
    // Ratio rules use > so "at most 1%" is not breached by exactly 1%.
    boolean breached = observed.compareTo(threshold) >= 0 && observedCount > 0;

    return new Evaluation(
        rule,
        serviceId,
        breached,
        windowStart,
        windowEnd,
        observed,
        threshold,
        null,
        breached ? summaryTemplate.formatted(observedCount) : "below threshold");
  }

  private static BigDecimal defaultThreshold(DetectionRule rule, BigDecimal fallback) {
    return rule.getThreshold() != null ? rule.getThreshold() : fallback;
  }

  private static String asPercent(BigDecimal ratio) {
    return ratio
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString();
  }

  /**
   * The outcome of evaluating one rule against one service over one window.
   *
   * @param sampleSize request count backing the measurement, or null for count-based rules where
   *     the observed value is itself the sample
   */
  public record Evaluation(
      DetectionRule rule,
      UUID serviceId,
      boolean breached,
      Instant windowStart,
      Instant windowEnd,
      BigDecimal observedValue,
      BigDecimal thresholdValue,
      Integer sampleSize,
      String summary) {

    static Evaluation notBreached(
        DetectionRule rule,
        UUID serviceId,
        Instant windowStart,
        Instant windowEnd,
        BigDecimal observed,
        BigDecimal threshold,
        Integer sampleSize,
        String reason) {
      return new Evaluation(
          rule, serviceId, false, windowStart, windowEnd, observed, threshold, sampleSize, reason);
    }

    public RuleType ruleType() {
      return rule.getRuleType();
    }
  }
}
