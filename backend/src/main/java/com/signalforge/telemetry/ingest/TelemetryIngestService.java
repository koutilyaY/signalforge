package com.signalforge.telemetry.ingest;

import com.signalforge.iam.auth.AuthenticatedPrincipal;
import com.signalforge.messaging.KafkaTopics;
import com.signalforge.messaging.TelemetryEventMessage;
import com.signalforge.platform.config.SignalForgeProperties;
import com.signalforge.platform.error.ApiException;
import com.signalforge.registry.repository.ServiceRepository;
import com.signalforge.telemetry.api.TelemetryDtos;
import com.signalforge.telemetry.domain.Severity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates telemetry and publishes it to Kafka.
 *
 * <p>The API deliberately does <b>not</b> write to PostgreSQL. The ingestion endpoint is the
 * highest-volume path in the system and the one most likely to be hammered exactly when the
 * database is already struggling - which is during an incident, which is when this platform must
 * keep working. Publishing to Kafka and returning 202 decouples producer availability from database
 * health, and lets the durable write happen in batches on the consumer side.
 */
@Service
public class TelemetryIngestService {

  private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

  private final KafkaTemplate<String, Object> kafkaTemplate;
  private final ServiceRepository serviceRepository;
  private final SignalForgeProperties properties;
  private final Counter acceptedCounter;
  private final Counter rejectedCounter;
  private final Timer publishTimer;

  public TelemetryIngestService(
      KafkaTemplate<String, Object> kafkaTemplate,
      ServiceRepository serviceRepository,
      SignalForgeProperties properties,
      MeterRegistry meterRegistry) {
    this.kafkaTemplate = kafkaTemplate;
    this.serviceRepository = serviceRepository;
    this.properties = properties;
    this.acceptedCounter =
        Counter.builder("signalforge.ingest.events")
            .tag("outcome", "accepted")
            .description("Telemetry events published to Kafka")
            .register(meterRegistry);
    this.rejectedCounter =
        Counter.builder("signalforge.ingest.events")
            .tag("outcome", "rejected")
            .description("Telemetry events rejected at ingestion")
            .register(meterRegistry);
    this.publishTimer =
        Timer.builder("signalforge.ingest.publish")
            .description("Time to validate and publish a telemetry batch")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
  }

  @Transactional(readOnly = true)
  public TelemetryDtos.IngestResponse ingest(
      AuthenticatedPrincipal principal, TelemetryDtos.IngestBatchRequest request) {

    return publishTimer.record(() -> doIngest(principal, request));
  }

  private TelemetryDtos.IngestResponse doIngest(
      AuthenticatedPrincipal principal, TelemetryDtos.IngestBatchRequest request) {

    UUID organizationId = principal.organizationId();
    int maxBatch = properties.ingestion().maxBatchSize();
    if (request.events().size() > maxBatch) {
      throw ApiException.validation(
          "Batch exceeds the maximum of %d events".formatted(maxBatch),
          Map.of("maxBatchSize", maxBatch, "received", request.events().size()));
    }

    // One query for the whole batch rather than one per event: a 500-event batch
    // would otherwise issue 500 selects.
    Set<UUID> knownServiceIds = new HashSet<>();
    serviceRepository.findActive(organizationId).forEach(s -> knownServiceIds.add(s.getId()));

    Instant now = Instant.now();
    Instant oldestAllowed = now.minus(properties.ingestion().maxEventAge());
    Instant newestAllowed = now.plus(properties.ingestion().maxClockSkew());

    List<TelemetryDtos.IngestResponse.RejectedEvent> rejected = new ArrayList<>();
    List<TelemetryEventMessage> toPublish = new ArrayList<>(request.events().size());
    Set<UUID> seenInBatch = new HashSet<>();

    for (TelemetryDtos.IngestEventRequest event : request.events()) {
      String reason = validate(event, knownServiceIds, seenInBatch, oldestAllowed, newestAllowed);
      if (reason != null) {
        rejected.add(new TelemetryDtos.IngestResponse.RejectedEvent(event.eventId(), reason));
        continue;
      }
      seenInBatch.add(event.eventId());
      toPublish.add(toMessage(organizationId, event, now));
    }

    for (TelemetryEventMessage message : toPublish) {
      // Fire-and-forget against the template's own retry/idempotence settings.
      // Blocking on each send would serialise the batch and destroy throughput;
      // the producer already guarantees ordering per key and retries internally.
      kafkaTemplate
          .send(KafkaTopics.TELEMETRY_EVENTS, message.partitionKey(), message)
          .whenComplete(
              (result, error) -> {
                if (error != null) {
                  // The client already got a 202. This is the honest failure
                  // mode of asynchronous ingestion and it must be visible.
                  log.error(
                      "Failed to publish telemetry event {} for org {}",
                      message.eventId(),
                      message.organizationId(),
                      error);
                }
              });
    }

    acceptedCounter.increment(toPublish.size());
    rejectedCounter.increment(rejected.size());

    return TelemetryDtos.IngestResponse.of(toPublish.size(), rejected);
  }

  /** Returns null when the event is acceptable, otherwise a short human-readable reason. */
  private String validate(
      TelemetryDtos.IngestEventRequest event,
      Set<UUID> knownServiceIds,
      Set<UUID> seenInBatch,
      Instant oldestAllowed,
      Instant newestAllowed) {

    if (!knownServiceIds.contains(event.serviceId())) {
      // Also the tenant check: knownServiceIds only ever contains this
      // organization's services, so a service id belonging to another tenant is
      // rejected here exactly like an unknown one.
      return "unknown service for this organization";
    }
    if (seenInBatch.contains(event.eventId())) {
      // Caught here rather than left to the database: a batch that contains the
      // same eventId twice is a producer bug worth telling the client about,
      // not something to silently absorb via ON CONFLICT.
      return "duplicate eventId within the same batch";
    }
    if (event.occurredAt().isBefore(oldestAllowed)) {
      // Backfilled or clock-skewed events would corrupt every time-window
      // computation downstream, so they are refused rather than quietly stored.
      return "occurredAt is older than the accepted window";
    }
    if (event.occurredAt().isAfter(newestAllowed)) {
      return "occurredAt is in the future beyond the allowed clock skew";
    }
    if (event.eventType().countsTowardTraffic() && event.latencyMs() == null) {
      return "latencyMs is required for HTTP_REQUEST events";
    }
    return null;
  }

  private static TelemetryEventMessage toMessage(
      UUID organizationId, TelemetryDtos.IngestEventRequest event, Instant ingestedAt) {
    return new TelemetryEventMessage(
        event.eventId(),
        organizationId,
        event.serviceId(),
        event.occurredAt(),
        event.eventType(),
        event.severity() == null ? defaultSeverity(event) : event.severity(),
        event.traceId(),
        event.spanId(),
        event.correlationId(),
        event.httpMethod(),
        event.httpPath(),
        event.statusCode(),
        event.latencyMs(),
        event.errorType(),
        event.errorMessage(),
        event.instanceKey(),
        event.consumerLag(),
        event.metadata() == null ? Map.of() : event.metadata(),
        ingestedAt);
  }

  private static Severity defaultSeverity(TelemetryDtos.IngestEventRequest event) {
    if (event.eventType().isError()) {
      return Severity.ERROR;
    }
    if (event.statusCode() != null && event.statusCode() >= 500) {
      return Severity.ERROR;
    }
    return Severity.INFO;
  }

  /** Exposed so the controller can build rate-limit headers without recomputing the window. */
  public Duration windowDuration() {
    return Duration.ofMinutes(1);
  }
}
