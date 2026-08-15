package com.signalforge.incident.domain;

import com.signalforge.detection.domain.IncidentSeverity;
import com.signalforge.platform.error.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * An incident.
 *
 * <p>Three timestamps are kept distinct because conflating them destroys the measurements this
 * platform exists to produce:
 *
 * <ul>
 *   <li>{@code startedAt} — when the offending signal began (the detection window's start).
 *   <li>{@code detectedAt} — when SignalForge created the incident. The gap between these two
 *       <em>is</em> detection latency, and it is measured, not estimated.
 *   <li>{@code acknowledgedAt} / {@code resolvedAt} — human response times.
 * </ul>
 */
@Entity
@Table(name = "incidents")
public class Incident {

  @Id
  @GeneratedValue
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "reference", nullable = false, length = 20, updatable = false)
  private String reference;

  @Column(name = "title", nullable = false, length = 300)
  private String title;

  @Column(name = "description", length = 4000)
  private String description;

  @Column(name = "severity", nullable = false, length = 10)
  private String severity;

  @Column(name = "status", nullable = false, length = 20)
  private String status = IncidentStatus.OPEN.name();

  @Column(name = "source", nullable = false, length = 10, updatable = false)
  private String source = "AUTO";

  @Column(name = "fingerprint", nullable = false, length = 200, updatable = false)
  private String fingerprint;

  @Column(name = "detection_rule_id")
  private UUID detectionRuleId;

  @Column(name = "primary_service_id")
  private UUID primaryServiceId;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "detected_at", nullable = false)
  private Instant detectedAt;

  /**
   * Timestamp of the most recent telemetry the breaching evaluation actually saw. Null for
   * incidents created before V3, and for manually-raised incidents.
   */
  @Column(name = "signal_observed_at")
  private Instant signalObservedAt;

  @Column(name = "acknowledged_at")
  private Instant acknowledgedAt;

  @Column(name = "acknowledged_by")
  private UUID acknowledgedBy;

  @Column(name = "mitigated_at")
  private Instant mitigatedAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "resolved_by")
  private UUID resolvedBy;

  @Column(name = "resolution_note", length = 4000)
  private String resolutionNote;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;

  protected Incident() {}

  public Incident(
      UUID organizationId,
      String reference,
      String title,
      IncidentSeverity severity,
      String fingerprint,
      Instant startedAt,
      Instant detectedAt) {
    this.organizationId = organizationId;
    this.reference = reference;
    this.title = title;
    this.severity = severity.name();
    this.fingerprint = fingerprint;
    this.startedAt = startedAt;
    this.detectedAt = detectedAt;
  }

  /**
   * Applies a lifecycle transition, rejecting anything the state machine forbids.
   *
   * @return the previous status, for the timeline entry
   */
  public IncidentStatus transitionTo(IncidentStatus target, UUID actorUserId, Instant at) {
    IncidentStatus current = getStatus();
    if (current == target) {
      throw ApiException.invalidTransition(current.name(), target.name());
    }
    if (!current.canTransitionTo(target)) {
      throw ApiException.invalidTransition(current.name(), target.name());
    }

    this.status = target.name();

    // Timestamps are set on first entry into a state only. Bouncing
    // INVESTIGATING -> MITIGATED -> INVESTIGATING -> MITIGATED must not keep
    // moving mitigatedAt forward, or the duration metrics become fiction.
    switch (target) {
      case ACKNOWLEDGED -> {
        if (acknowledgedAt == null) {
          acknowledgedAt = at;
          acknowledgedBy = actorUserId;
        }
      }
      case MITIGATED -> {
        if (mitigatedAt == null) {
          mitigatedAt = at;
        }
      }
      case RESOLVED -> {
        resolvedAt = at;
        resolvedBy = actorUserId;
        // Skipping acknowledgement is legal, but leaving acknowledgedAt null
        // would make time-to-acknowledge unmeasurable for those incidents.
        if (acknowledgedAt == null) {
          acknowledgedAt = at;
          acknowledgedBy = actorUserId;
        }
      }
      default -> {}
    }
    return current;
  }

  /**
   * How long SignalForge took to notice, measured from the moment the evidence existed.
   *
   * <p>Deliberately NOT {@code detectedAt - startedAt}. {@code startedAt} is the detection
   * <em>window</em> start, so that difference is dominated by the rule's window length and says
   * nothing about detection speed - a five-minute-window rule would report five minutes for an
   * incident found in ten seconds. That was a real defect, found by the end-to-end incident
   * simulator rather than by any unit test.
   *
   * @return null when {@code signalObservedAt} is unknown, rather than a misleading number
   */
  public Duration timeToDetect() {
    return signalObservedAt == null ? null : Duration.between(signalObservedAt, detectedAt);
  }

  /**
   * How far back the evidence supporting this incident reaches. Useful context on the incident
   * page; explicitly not a detection-speed metric.
   */
  public Duration evidenceWindowAge() {
    return Duration.between(startedAt, detectedAt);
  }

  public Duration timeToAcknowledge() {
    return acknowledgedAt == null ? null : Duration.between(detectedAt, acknowledgedAt);
  }

  public Duration timeToResolve() {
    return resolvedAt == null ? null : Duration.between(detectedAt, resolvedAt);
  }

  /** Elapsed time, still running for an open incident. */
  public Duration duration() {
    return Duration.between(startedAt, resolvedAt == null ? Instant.now() : resolvedAt);
  }

  public void raiseSeverityTo(IncidentSeverity candidate) {
    if (candidate.atLeast(getSeverity())) {
      this.severity = candidate.name();
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getReference() {
    return reference;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public IncidentSeverity getSeverity() {
    return IncidentSeverity.valueOf(severity);
  }

  public IncidentStatus getStatus() {
    return IncidentStatus.valueOf(status);
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public UUID getDetectionRuleId() {
    return detectionRuleId;
  }

  public void setDetectionRuleId(UUID detectionRuleId) {
    this.detectionRuleId = detectionRuleId;
  }

  public UUID getPrimaryServiceId() {
    return primaryServiceId;
  }

  public void setPrimaryServiceId(UUID primaryServiceId) {
    this.primaryServiceId = primaryServiceId;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getDetectedAt() {
    return detectedAt;
  }

  public Instant getSignalObservedAt() {
    return signalObservedAt;
  }

  public void setSignalObservedAt(Instant signalObservedAt) {
    this.signalObservedAt = signalObservedAt;
  }

  public Instant getAcknowledgedAt() {
    return acknowledgedAt;
  }

  public UUID getAcknowledgedBy() {
    return acknowledgedBy;
  }

  public Instant getMitigatedAt() {
    return mitigatedAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public UUID getResolvedBy() {
    return resolvedBy;
  }

  public String getResolutionNote() {
    return resolutionNote;
  }

  public void setResolutionNote(String resolutionNote) {
    this.resolutionNote = resolutionNote;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
