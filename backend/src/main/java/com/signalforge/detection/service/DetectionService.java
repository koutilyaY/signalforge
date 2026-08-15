package com.signalforge.detection.service;

import com.signalforge.detection.domain.DetectionRule;
import com.signalforge.detection.repository.DetectionRuleRepository;
import com.signalforge.platform.config.SignalForgeProperties;
import com.signalforge.platform.tenant.TenantBinder;
import com.signalforge.registry.domain.HealthStatus;
import com.signalforge.registry.domain.ServiceEntity;
import com.signalforge.registry.repository.ServiceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates detection rules and opens incidents.
 *
 * <p>Deliberately <b>not</b> annotated {@code @Scheduled}. The schedule lives in {@link
 * DetectionScheduler}, so this class can be driven directly and synchronously from tests. A
 * detection engine that can only be exercised by waiting for a timer produces a flaky suite and a
 * slow one.
 *
 * <p>A rule with a null {@code serviceId} fans out across every active service in its organization,
 * which is how a newly registered service is covered the moment it starts reporting.
 */
@Service
public class DetectionService {

  private static final Logger log = LoggerFactory.getLogger(DetectionService.class);

  private final DetectionRuleRepository ruleRepository;
  private final ServiceRepository serviceRepository;
  private final RuleEvaluator evaluator;
  private final IncidentOpener incidentOpener;
  private final SignalForgeProperties properties;
  private final TenantBinder tenantBinder;
  private final Counter incidentsOpenedCounter;
  private final Counter evaluationsCounter;
  private final Timer sweepTimer;

  public DetectionService(
      DetectionRuleRepository ruleRepository,
      ServiceRepository serviceRepository,
      RuleEvaluator evaluator,
      IncidentOpener incidentOpener,
      SignalForgeProperties properties,
      TenantBinder tenantBinder,
      MeterRegistry meterRegistry) {
    this.ruleRepository = ruleRepository;
    this.serviceRepository = serviceRepository;
    this.evaluator = evaluator;
    this.incidentOpener = incidentOpener;
    this.properties = properties;
    this.tenantBinder = tenantBinder;
    this.incidentsOpenedCounter =
        Counter.builder("signalforge.detection.incidents_opened")
            .description("Incidents opened by the detection engine")
            .register(meterRegistry);
    this.evaluationsCounter =
        Counter.builder("signalforge.detection.evaluations")
            .description("Rule evaluations performed")
            .register(meterRegistry);
    this.sweepTimer =
        Timer.builder("signalforge.detection.sweep")
            .description("Time to evaluate every enabled rule across all tenants")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
  }

  /** Evaluates every enabled rule across every tenant. Called by the scheduler. */
  public SweepResult sweepAllTenants() {
    return sweepAllTenants(Instant.now());
  }

  public SweepResult sweepAllTenants(Instant windowEnd) {
    Instant started = Instant.now();

    // Row-level security means a plain cross-tenant SELECT returns nothing, so
    // the sweep asks the one narrow SECURITY DEFINER function in the schema for
    // the list of tenant ids - and nothing else - then binds each tenant in turn
    // before touching any of its data. See V4__row_level_security.sql.
    List<UUID> organizationIds = ruleRepository.organizationsWithEnabledRules();

    int evaluated = 0;
    int opened = 0;
    for (UUID organizationId : organizationIds) {
      try {
        SweepResult result =
            tenantBinder.callAs(
                organizationId, () -> evaluateOrganization(organizationId, windowEnd));
        evaluated += result.evaluated();
        opened += result.incidentsOpened();
      } catch (RuntimeException e) {
        // One tenant's bad data must not stop detection for every other tenant.
        log.error("Detection sweep failed for organization {}", organizationId, e);
      }
    }

    sweepTimer.record(Duration.between(started, Instant.now()));
    return new SweepResult(evaluated, opened);
  }

  /** Evaluates every enabled rule for one organization. This is what tests call. */
  @Transactional(readOnly = true)
  public SweepResult evaluateOrganization(UUID organizationId, Instant windowEnd) {
    return evaluateOrganization(
        organizationId, ruleRepository.findEnabled(organizationId), windowEnd);
  }

  private SweepResult evaluateOrganization(
      UUID organizationId, List<DetectionRule> rules, Instant windowEnd) {

    if (rules.isEmpty()) {
      return new SweepResult(0, 0);
    }

    int maxRules = properties.detection().maxRulesPerEvaluation();
    if (rules.size() > maxRules) {
      // Bounded rather than silently truncated: an operator needs to know that
      // some rules were not evaluated this tick.
      log.warn(
          "Organization {} has {} enabled rules, evaluating the first {}. Raise "
              + "signalforge.detection.max-rules-per-evaluation or disable unused rules.",
          organizationId,
          rules.size(),
          maxRules);
      rules = rules.subList(0, maxRules);
    }

    // One query for all services rather than one per rule.
    Map<UUID, ServiceEntity> services = new HashMap<>();
    serviceRepository.findActive(organizationId).forEach(s -> services.put(s.getId(), s));

    Duration idleGrace = properties.detection().idleServiceGrace();
    int evaluated = 0;
    int opened = 0;

    for (DetectionRule rule : rules) {
      List<ServiceEntity> targets = targetsFor(rule, services);

      for (ServiceEntity service : targets) {
        // A service that has reported nothing recently is not necessarily
        // healthy, but it is not judgeable either - evaluating it would either
        // produce a false "0% error rate is fine" or a false breach on an empty
        // window. SERVICE_DOWN detection is the right tool for silence.
        if (service.getHealthChangedAt() != null
            && service.getHealthStatus() == HealthStatus.UNKNOWN
            && service.getHealthChangedAt().isBefore(windowEnd.minus(idleGrace))) {
          continue;
        }

        try {
          RuleEvaluator.Evaluation evaluation = evaluator.evaluate(rule, service, windowEnd);
          evaluated++;
          evaluationsCounter.increment();

          if (evaluation.breached()) {
            IncidentOpener.Outcome outcome = incidentOpener.handleBreach(evaluation, service);
            if (outcome.created()) {
              opened++;
              incidentsOpenedCounter.increment();
            }
          }
        } catch (RuntimeException e) {
          // A single failing rule must not abort the rest of the sweep.
          log.error(
              "Rule '{}' failed for service {} in organization {}",
              rule.getName(),
              service.getName(),
              organizationId,
              e);
        }
      }
    }

    return new SweepResult(evaluated, opened);
  }

  private static List<ServiceEntity> targetsFor(
      DetectionRule rule, Map<UUID, ServiceEntity> services) {
    if (rule.isOrganizationWide()) {
      return new ArrayList<>(services.values());
    }
    ServiceEntity target = services.get(rule.getServiceId());
    return target == null ? List.of() : List.of(target);
  }

  public record SweepResult(int evaluated, int incidentsOpened) {}
}
