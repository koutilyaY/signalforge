package com.signalforge.messaging;

import com.signalforge.telemetry.domain.EventType;
import com.signalforge.telemetry.domain.Severity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The wire format on {@code telemetry-events}.
 *
 * <p>Kept as a separate record from the HTTP DTO and from the JPA entity on purpose. They change
 * for different reasons: the HTTP DTO changes when the public API changes, the entity when the
 * schema changes, and this when the topic contract changes. Collapsing them means a database column
 * rename silently becomes a breaking change for every consumer that has already read from the
 * topic.
 *
 * <p>Note {@code organizationId} travels in the payload. A consumer must never infer the tenant
 * from ambient state - there is no request context in a Kafka listener.
 *
 * <p>Compatibility: new fields must be added as nullable with a default so that a consumer running
 * old code can still deserialize a message written by new code. Removing or retyping a field is a
 * breaking change and needs a new topic. See ADR-0006.
 */
public record TelemetryEventMessage(
    UUID eventId,
    UUID organizationId,
    UUID serviceId,
    Instant occurredAt,
    EventType eventType,
    Severity severity,
    String traceId,
    String spanId,
    String correlationId,
    String httpMethod,
    String httpPath,
    Integer statusCode,
    Integer latencyMs,
    String errorType,
    String errorMessage,
    String instanceKey,
    Long consumerLag,
    Map<String, Object> metadata,
    /** When the API accepted it. Ingest-to-persist lag is {@code now - ingestedAt}. */
    Instant ingestedAt) {

  /**
   * Partition key. Keying by service id means every event for one service lands on one partition,
   * which gives per-service ordering - a SERVICE_DOWN followed by SERVICE_RECOVERED can never be
   * consumed out of order and leave a service permanently marked down.
   *
   * <p>Keying by organization instead would put a large tenant's entire traffic on one partition
   * and cap throughput at one consumer thread.
   */
  public String partitionKey() {
    return serviceId.toString();
  }
}
