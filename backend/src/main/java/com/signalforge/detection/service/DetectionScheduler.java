package com.signalforge.detection.service;

import com.signalforge.platform.config.SignalForgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the detection sweep on a fixed interval.
 *
 * <p>Separated from {@link DetectionService} so the evaluation logic can be invoked directly and
 * deterministically from tests, and so the schedule can be disabled entirely with one property.
 *
 * <p><b>The interval is the floor on detection latency.</b> At the default 15 seconds, a breach
 * that begins immediately after a sweep is noticed up to 15 seconds later. Lowering it improves
 * detection latency and increases database load proportionally; this is the trade-off to reach for
 * first when tuning, and the reason {@code signalforge.detection.evaluation-interval} exists.
 *
 * <p>Single-instance only. Running two `worker` replicas would double the sweeps — harmless for
 * correctness, because incident dedup is enforced by a database unique index rather than by
 * assuming one evaluator, but wasteful. A distributed lock in Redis is the natural next step.
 */
@Component
@ConditionalOnProperty(value = "signalforge.detection.enabled", havingValue = "true")
public class DetectionScheduler {

  private static final Logger log = LoggerFactory.getLogger(DetectionScheduler.class);

  private final DetectionService detectionService;

  public DetectionScheduler(DetectionService detectionService, SignalForgeProperties properties) {
    this.detectionService = detectionService;
    log.info(
        "Detection scheduler enabled, interval={}", properties.detection().evaluationInterval());
  }

  // fixedDelay, not fixedRate: the next sweep starts after the previous one
  // finishes. With fixedRate a sweep that runs longer than the interval would
  // have the next one launched on top of it, and under load that compounds into
  // overlapping sweeps hammering the database.
  @Scheduled(
      fixedDelayString = "${signalforge.detection.evaluation-interval:15s}",
      initialDelayString = "10s")
  public void sweep() {
    try {
      DetectionService.SweepResult result = detectionService.sweepAllTenants();
      if (result.incidentsOpened() > 0) {
        log.info(
            "Detection sweep evaluated {} rule/service pairs and opened {} incident(s)",
            result.evaluated(),
            result.incidentsOpened());
      }
    } catch (RuntimeException e) {
      // Never let an exception kill the scheduled task - Spring stops
      // rescheduling a @Scheduled method whose thread dies from an unchecked
      // throw in some configurations, and silent detection failure is the worst
      // possible failure mode for this system.
      log.error("Detection sweep failed", e);
    }
  }
}
