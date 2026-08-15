package com.signalforge.telemetry.api;

import com.signalforge.telemetry.domain.EventType;
import com.signalforge.telemetry.domain.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TelemetryDtos {

  private TelemetryDtos() {}

  /**
   * One telemetry event.
   *
   * <p>{@code eventId} is supplied by the producer, not generated here. That is the whole basis of
   * duplicate suppression: a client that retries a failed POST sends the same eventId, and the
   * unique index on {@code (organization_id, event_id)} makes the retry a no-op. If the server
   * minted the id, every retry would create a new row and inflate the metrics the platform exists
   * to compute.
   */
  public record IngestEventRequest(
      @NotNull UUID eventId,
      @NotNull UUID serviceId,
      @NotNull Instant occurredAt,
      @NotNull EventType eventType,
      Severity severity,
      @Size(max = 64) String traceId,
      @Size(max = 32) String spanId,
      @Size(max = 120) String correlationId,
      @Size(max = 10) String httpMethod,
      @Size(max = 500) String httpPath,
      @Min(100) @Max(599) Integer statusCode,
      @Min(0) @Max(3600000) Integer latencyMs,
      @Size(max = 200) String errorType,
      @Size(max = 2000) String errorMessage,
      @Size(max = 200) String instanceKey,
      @Min(0) Long consumerLag,
      Map<String, Object> metadata) {}

  /** Batch envelope. Batch size is capped by {@code signalforge.ingestion.max-batch-size}. */
  public record IngestBatchRequest(
      @NotEmpty @Size(max = 500) @Valid List<IngestEventRequest> events) {}

  /**
   * Ingestion is asynchronous: a 202 means "durably queued to Kafka", not "written to PostgreSQL".
   * Saying otherwise would be a lie the client would build retry logic on top of.
   *
   * @param accepted events published to Kafka
   * @param rejected events that failed validation, with the reason
   */
  public record IngestResponse(int accepted, List<RejectedEvent> rejected) {

    public record RejectedEvent(UUID eventId, String reason) {}

    public static IngestResponse of(int accepted, List<RejectedEvent> rejected) {
      return new IngestResponse(accepted, rejected == null ? List.of() : rejected);
    }
  }
}
