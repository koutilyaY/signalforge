package com.signalforge.detection.service;

import com.signalforge.detection.domain.DetectionRule;
import com.signalforge.detection.domain.IncidentSeverity;
import com.signalforge.detection.domain.RuleType;
import com.signalforge.detection.repository.DetectionRuleRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a new organization with a working default rule set.
 *
 * <p>An observability platform that detects nothing until someone configures it is a platform
 * nobody configures. These defaults are organization-wide (null {@code serviceId}) so they cover
 * every service, including ones registered later.
 *
 * <p>The error-rate and latency rules leave {@code threshold} null on purpose, so each service is
 * judged against its own registered SLA rather than a single flattened number.
 */
@Component
public class DefaultRuleFactory {

  private static final Logger log = LoggerFactory.getLogger(DefaultRuleFactory.class);

  private final DetectionRuleRepository ruleRepository;

  public DefaultRuleFactory(DetectionRuleRepository ruleRepository) {
    this.ruleRepository = ruleRepository;
  }

  @Transactional
  public List<DetectionRule> seedFor(UUID organizationId) {
    List<DetectionRule> rules =
        List.of(
            errorRate(organizationId),
            latency(organizationId),
            serviceDown(organizationId),
            databaseErrors(organizationId),
            kafkaLag(organizationId));

    List<DetectionRule> saved = ruleRepository.saveAll(rules);
    log.info("Seeded {} default detection rules for organization {}", saved.size(), organizationId);
    return saved;
  }

  private static DetectionRule errorRate(UUID organizationId) {
    DetectionRule rule =
        new DetectionRule(
            organizationId,
            "Error rate above service SLA",
            RuleType.ERROR_RATE,
            IncidentSeverity.HIGH);
    rule.setDescription(
        "Fires when the failed-request ratio over 5 minutes exceeds the service's configured "
            + "expected_error_rate. Requires at least 20 requests so a single failure in a quiet "
            + "window cannot read as 100%.");
    rule.setThreshold(null); // use each service's own SLA
    rule.setWindowSeconds(300);
    rule.setMinSampleSize(20);
    rule.setCooldownSeconds(900);
    return rule;
  }

  private static DetectionRule latency(UUID organizationId) {
    DetectionRule rule =
        new DetectionRule(
            organizationId,
            "p95 latency above service SLA",
            RuleType.P95_LATENCY,
            IncidentSeverity.MEDIUM);
    rule.setDescription(
        "Fires when interpolated p95 latency over 5 minutes exceeds the service's configured "
            + "expected_p95_latency_ms.");
    rule.setThreshold(null);
    rule.setWindowSeconds(300);
    rule.setMinSampleSize(20);
    rule.setCooldownSeconds(900);
    return rule;
  }

  private static DetectionRule serviceDown(UUID organizationId) {
    DetectionRule rule =
        new DetectionRule(
            organizationId,
            "Service reported down",
            RuleType.SERVICE_DOWN,
            IncidentSeverity.CRITICAL);
    rule.setDescription("Fires on the first SERVICE_DOWN event in a 2-minute window.");
    rule.setThreshold(BigDecimal.ONE);
    rule.setWindowSeconds(120);
    // A single down event is the signal; there is no ratio to stabilise.
    rule.setMinSampleSize(1);
    rule.setCooldownSeconds(300);
    return rule;
  }

  private static DetectionRule databaseErrors(UUID organizationId) {
    DetectionRule rule =
        new DetectionRule(
            organizationId,
            "Database error spike",
            RuleType.DATABASE_ERROR_SPIKE,
            IncidentSeverity.HIGH);
    rule.setDescription("Fires when 5 or more DATABASE_ERROR events occur within 5 minutes.");
    rule.setThreshold(BigDecimal.valueOf(5));
    rule.setWindowSeconds(300);
    rule.setMinSampleSize(1);
    rule.setCooldownSeconds(600);
    return rule;
  }

  private static DetectionRule kafkaLag(UUID organizationId) {
    DetectionRule rule =
        new DetectionRule(
            organizationId, "Kafka consumer lag", RuleType.KAFKA_LAG, IncidentSeverity.MEDIUM);
    rule.setDescription("Fires when reported consumer lag reaches 10,000 messages.");
    rule.setThreshold(BigDecimal.valueOf(10_000));
    rule.setWindowSeconds(300);
    rule.setMinSampleSize(1);
    rule.setCooldownSeconds(900);
    return rule;
  }
}
