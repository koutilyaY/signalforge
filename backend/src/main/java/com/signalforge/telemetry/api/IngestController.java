package com.signalforge.telemetry.api;

import com.signalforge.iam.auth.AuthenticatedPrincipal;
import com.signalforge.iam.repository.OrganizationRepository;
import com.signalforge.platform.error.ApiException;
import com.signalforge.platform.error.ErrorCode;
import com.signalforge.telemetry.ingest.IngestionRateLimiter;
import com.signalforge.telemetry.ingest.TelemetryIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Telemetry ingestion.
 *
 * <p>Returns <b>202 Accepted</b>, not 200. The events are durably published to Kafka but not yet in
 * PostgreSQL when this responds, and the status code should say so.
 */
@RestController
@RequestMapping("/api/v1/ingest")
@Tag(name = "Telemetry ingestion")
public class IngestController {

  private final TelemetryIngestService ingestService;
  private final IngestionRateLimiter rateLimiter;
  private final OrganizationRepository organizationRepository;

  public IngestController(
      TelemetryIngestService ingestService,
      IngestionRateLimiter rateLimiter,
      OrganizationRepository organizationRepository) {
    this.ingestService = ingestService;
    this.rateLimiter = rateLimiter;
    this.organizationRepository = organizationRepository;
  }

  @PostMapping("/events")
  @PreAuthorize("hasRole('ENGINEER')")
  @Operation(summary = "Publish a batch of telemetry events (API key or ENGINEER token)")
  public ResponseEntity<TelemetryDtos.IngestResponse> ingest(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @Valid @RequestBody TelemetryDtos.IngestBatchRequest request) {

    Integer configuredLimit =
        organizationRepository
            .findById(principal.organizationId())
            .map(org -> org.getIngestRateLimitPerMinute())
            .orElse(null);

    // Cost is the event count, not 1. Otherwise a client bypasses its quota
    // entirely by batching 500 events into a single "request".
    int cost = request.events().size();
    IngestionRateLimiter.Decision decision =
        rateLimiter.tryAcquire(principal.organizationId(), configuredLimit, cost);

    if (!decision.allowed()) {
      throw new ApiException(
          ErrorCode.RATE_LIMIT_EXCEEDED,
          "Ingestion rate limit exceeded for this organization",
          null,
          Map.of(
              "limitPerMinute", decision.limit(),
              "observedInWindow", decision.observed(),
              "retryAfterSeconds", 60));
    }

    TelemetryDtos.IngestResponse response = ingestService.ingest(principal, request);

    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header("X-RateLimit-Limit", String.valueOf(decision.limit()))
        .header("X-RateLimit-Remaining", String.valueOf(decision.remaining()))
        .header("X-RateLimit-Window-Seconds", "60")
        .body(response);
  }

  /** Convenience single-event form; wraps the batch path so behaviour cannot drift between them. */
  @PostMapping("/event")
  @PreAuthorize("hasRole('ENGINEER')")
  @Operation(summary = "Publish a single telemetry event")
  public ResponseEntity<TelemetryDtos.IngestResponse> ingestOne(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @Valid @RequestBody TelemetryDtos.IngestEventRequest request) {
    return ingest(principal, new TelemetryDtos.IngestBatchRequest(List.of(request)));
  }
}
