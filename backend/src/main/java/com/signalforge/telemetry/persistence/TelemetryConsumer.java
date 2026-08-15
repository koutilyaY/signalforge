package com.signalforge.telemetry.persistence;

import com.signalforge.messaging.KafkaTopics;
import com.signalforge.messaging.PermanentMessageException;
import com.signalforge.messaging.TelemetryEventMessage;
import com.signalforge.platform.tenant.TenantBinder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Persists telemetry from Kafka to PostgreSQL.
 *
 * <p><b>Why a batch listener.</b> One-record-at-a-time would mean one transaction and one round
 * trip per event. Consuming up to 500 records per poll and writing them in a single batched
 * transaction is the difference between hundreds and thousands of events per second on the same
 * hardware.
 *
 * <p><b>Idempotency, and why it lives in the database.</b> Delivery is at-least-once: a rebalance,
 * a container restart, or a failure between processing and offset commit will redeliver records
 * that were already applied. Rather than trying to prevent that (which is impossible without
 * distributed transactions), the write is made idempotent - {@code ON CONFLICT DO NOTHING} against
 * {@code (organization_id, event_id)}. Reprocessing is therefore harmless, and the system converges
 * to the same state regardless of how many times a record is delivered.
 *
 * <p><b>Poison messages.</b> A record that can never succeed throws {@link
 * PermanentMessageException}, which the error handler routes straight to {@code
 * telemetry-events-dlt} without retrying. A record that fails transiently is retried with backoff
 * and only dead-lettered after the retries are exhausted.
 */
@Component
public class TelemetryConsumer {

  private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

  private final TelemetryWriter writer;
  private final Counter processedCounter;
  private final Counter duplicateCounter;
  private final Counter poisonCounter;
  private final Timer batchTimer;
  private final MeterRegistry meterRegistry;
  private final TenantBinder tenantBinder;

  public TelemetryConsumer(
      TelemetryWriter writer, TenantBinder tenantBinder, MeterRegistry meterRegistry) {
    this.writer = writer;
    this.tenantBinder = tenantBinder;
    this.meterRegistry = meterRegistry;
    this.processedCounter =
        Counter.builder("signalforge.consumer.events")
            .tag("outcome", "persisted")
            .register(meterRegistry);
    this.duplicateCounter =
        Counter.builder("signalforge.consumer.events")
            .tag("outcome", "duplicate")
            .description("Records suppressed by the idempotency constraint")
            .register(meterRegistry);
    this.poisonCounter =
        Counter.builder("signalforge.consumer.events")
            .tag("outcome", "poison")
            .register(meterRegistry);
    this.batchTimer =
        Timer.builder("signalforge.consumer.batch")
            .description("Time to persist one Kafka batch")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
  }

  @KafkaListener(
      topics = KafkaTopics.TELEMETRY_EVENTS,
      groupId = KafkaTopics.GROUP_TELEMETRY_PERSIST,
      containerFactory = "kafkaListenerContainerFactory")
  public void consume(List<ConsumerRecord<String, TelemetryEventMessage>> records) {
    if (records.isEmpty()) {
      return;
    }

    Instant started = Instant.now();
    List<TelemetryEventMessage> valid = new ArrayList<>(records.size());

    for (ConsumerRecord<String, TelemetryEventMessage> record : records) {
      TelemetryEventMessage message = record.value();
      try {
        validate(message);
        valid.add(message);
      } catch (PermanentMessageException e) {
        // Drop the individual bad record rather than failing the whole batch -
        // otherwise one malformed message dead-letters 499 good ones with it.
        poisonCounter.increment();
        log.warn(
            "Discarding unprocessable record topic={} partition={} offset={}: {}",
            record.topic(),
            record.partition(),
            record.offset(),
            e.getMessage());
      }
    }

    if (valid.isEmpty()) {
      return;
    }

    // A Kafka batch can span organizations, but a database connection can only
    // carry one tenant for row-level security. Group first, then write each
    // tenant's slice inside its own tenant-bound transaction. In practice most
    // batches are single-tenant, because the topic is partitioned by service id.
    Map<UUID, List<TelemetryEventMessage>> byOrganization =
        valid.stream().collect(Collectors.groupingBy(TelemetryEventMessage::organizationId));

    int inserted = 0;
    for (Map.Entry<UUID, List<TelemetryEventMessage>> entry : byOrganization.entrySet()) {
      inserted += tenantBinder.callAs(entry.getKey(), () -> writer.write(entry.getValue()));
    }
    int duplicates = valid.size() - inserted;

    processedCounter.increment(inserted);
    if (duplicates > 0) {
      duplicateCounter.increment(duplicates);
      log.debug(
          "Suppressed {} duplicate telemetry records in batch of {}", duplicates, valid.size());
    }

    batchTimer.record(Duration.between(started, Instant.now()));
    recordIngestLag(valid);
  }

  /**
   * Structural validation. Anything failing here is malformed and will still be malformed on the
   * next attempt, so it is permanent by definition.
   */
  private static void validate(TelemetryEventMessage message) {
    if (message == null) {
      throw new PermanentMessageException("null message body");
    }
    if (message.eventId() == null) {
      throw new PermanentMessageException("missing eventId");
    }
    if (message.organizationId() == null) {
      throw new PermanentMessageException("missing organizationId - cannot attribute to a tenant");
    }
    if (message.serviceId() == null) {
      throw new PermanentMessageException("missing serviceId");
    }
    if (message.occurredAt() == null) {
      throw new PermanentMessageException("missing occurredAt");
    }
    if (message.eventType() == null) {
      throw new PermanentMessageException("missing eventType");
    }
  }

  /**
   * End-to-end pipeline lag: how long a record spent between being accepted by the API and landing
   * in PostgreSQL. This is the number the ingestion SLO is written against.
   */
  private void recordIngestLag(List<TelemetryEventMessage> batch) {
    Instant now = Instant.now();
    for (TelemetryEventMessage message : batch) {
      if (message.ingestedAt() != null) {
        meterRegistry
            .timer("signalforge.pipeline.ingest_to_persist")
            .record(Duration.between(message.ingestedAt(), now));
      }
    }
  }
}
