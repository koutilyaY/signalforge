package com.signalforge.incident.api;

import com.signalforge.detection.domain.IncidentSeverity;
import com.signalforge.incident.domain.Alert;
import com.signalforge.incident.domain.Incident;
import com.signalforge.incident.domain.IncidentComment;
import com.signalforge.incident.domain.IncidentStatus;
import com.signalforge.incident.domain.IncidentTimelineEntry;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class IncidentDtos {

  private IncidentDtos() {}

  public record TransitionRequest(
      @NotNull IncidentStatus status,
      @Size(max = 4000) String note,
      /** Optimistic-lock token from a prior read; rejected with 409 when stale. */
      Long version) {}

  public record CommentRequest(@NotNull @Size(min = 1, max = 4000) String body) {}

  /** List view. Deliberately lean - the incident list is polled and must stay cheap. */
  public record IncidentSummary(
      UUID id,
      String reference,
      String title,
      IncidentSeverity severity,
      IncidentStatus status,
      String source,
      UUID primaryServiceId,
      Instant startedAt,
      Instant detectedAt,
      Instant acknowledgedAt,
      Instant resolvedAt,
      long durationSeconds,
      Long timeToDetectMs,
      Long evidenceWindowAgeMs,
      Long timeToAcknowledgeMs,
      Long timeToResolveMs) {

    public static IncidentSummary from(Incident incident) {
      return new IncidentSummary(
          incident.getId(),
          incident.getReference(),
          incident.getTitle(),
          incident.getSeverity(),
          incident.getStatus(),
          incident.getSource(),
          incident.getPrimaryServiceId(),
          incident.getStartedAt(),
          incident.getDetectedAt(),
          incident.getAcknowledgedAt(),
          incident.getResolvedAt(),
          incident.duration().toSeconds(),
          incident.timeToDetect() == null ? null : incident.timeToDetect().toMillis(),
          incident.evidenceWindowAge().toMillis(),
          incident.timeToAcknowledge() == null ? null : incident.timeToAcknowledge().toMillis(),
          incident.timeToResolve() == null ? null : incident.timeToResolve().toMillis());
    }
  }

  /** Detail view: everything the incident page renders, in one round trip. */
  public record IncidentDetail(
      IncidentSummary incident,
      String description,
      String resolutionNote,
      long version,
      List<AffectedService> affectedServices,
      List<TimelineEntry> timeline,
      List<AlertSummary> alerts,
      List<CommentResponse> comments,
      /** Which transitions the caller may make next, so the UI need not duplicate the rules. */
      Set<IncidentStatus> allowedTransitions) {}

  public record AffectedService(UUID serviceId, String name, String role, String healthStatus) {}

  public record TimelineEntry(
      UUID id,
      Instant occurredAt,
      String kind,
      String title,
      String detail,
      UUID actorUserId,
      Map<String, Object> metadata) {

    public static TimelineEntry from(IncidentTimelineEntry entry) {
      return new TimelineEntry(
          entry.getId(),
          entry.getOccurredAt(),
          entry.getKind().name(),
          entry.getTitle(),
          entry.getDetail(),
          entry.getActorUserId(),
          entry.getMetadata());
    }
  }

  public record AlertSummary(
      UUID id,
      String status,
      IncidentSeverity severity,
      String summary,
      BigDecimal observedValue,
      BigDecimal thresholdValue,
      Integer sampleSize,
      Instant windowStart,
      Instant windowEnd,
      Instant triggeredAt) {

    public static AlertSummary from(Alert alert) {
      return new AlertSummary(
          alert.getId(),
          alert.getStatus().name(),
          alert.getSeverity(),
          alert.getSummary(),
          alert.getObservedValue(),
          alert.getThresholdValue(),
          alert.getSampleSize(),
          alert.getWindowStart(),
          alert.getWindowEnd(),
          alert.getTriggeredAt());
    }
  }

  public record CommentResponse(UUID id, UUID authorUserId, String body, Instant createdAt) {

    public static CommentResponse from(IncidentComment comment) {
      return new CommentResponse(
          comment.getId(), comment.getAuthorUserId(), comment.getBody(), comment.getCreatedAt());
    }
  }
}
