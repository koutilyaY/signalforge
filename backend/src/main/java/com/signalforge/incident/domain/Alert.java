package com.signalforge.incident.domain;

import com.signalforge.detection.domain.IncidentSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single rule firing.
 *
 * <p>Alerts and incidents are separate on purpose. A rule that breaches on twenty consecutive
 * evaluation ticks produces twenty alerts and <em>one</em> incident. Collapsing the two would
 * either spam the incident list or throw away the evidence of how long and how badly the condition
 * held.
 *
 * <p>{@code incidentId} is null when the alert was suppressed by cooldown — that alert is still
 * recorded, because "we saw this and chose not to page" is exactly what you want in the record when
 * reviewing a missed incident.
 */
@Entity
@Table(name = "alerts")
public class Alert {

  @Id
  @GeneratedValue
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "service_id", nullable = false, updatable = false)
  private UUID serviceId;

  @Column(name = "detection_rule_id")
  private UUID detectionRuleId;

  @Column(name = "incident_id")
  private UUID incidentId;

  @Column(name = "fingerprint", nullable = false, length = 200, updatable = false)
  private String fingerprint;

  @Column(name = "severity", nullable = false, length = 10)
  private String severity;

  @Column(name = "status", nullable = false, length = 20)
  private String status = Status.FIRING.name();

  @Column(name = "summary", nullable = false, length = 500)
  private String summary;

  @Column(name = "observed_value", precision = 14, scale = 4)
  private BigDecimal observedValue;

  @Column(name = "threshold_value", precision = 14, scale = 4)
  private BigDecimal thresholdValue;

  @Column(name = "sample_size")
  private Integer sampleSize;

  @Column(name = "window_start", nullable = false, updatable = false)
  private Instant windowStart;

  @Column(name = "window_end", nullable = false, updatable = false)
  private Instant windowEnd;

  @Column(name = "triggered_at", nullable = false, updatable = false)
  private Instant triggeredAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  protected Alert() {}

  public Alert(
      UUID organizationId,
      UUID serviceId,
      UUID detectionRuleId,
      String fingerprint,
      IncidentSeverity severity,
      String summary,
      BigDecimal observedValue,
      BigDecimal thresholdValue,
      Integer sampleSize,
      Instant windowStart,
      Instant windowEnd,
      Instant triggeredAt) {
    this.organizationId = organizationId;
    this.serviceId = serviceId;
    this.detectionRuleId = detectionRuleId;
    this.fingerprint = fingerprint;
    this.severity = severity.name();
    this.summary = summary;
    this.observedValue = observedValue;
    this.thresholdValue = thresholdValue;
    this.sampleSize = sampleSize;
    this.windowStart = windowStart;
    this.windowEnd = windowEnd;
    this.triggeredAt = triggeredAt;
  }

  public enum Status {
    FIRING,
    RESOLVED,
    SUPPRESSED
  }

  public void suppress() {
    this.status = Status.SUPPRESSED.name();
  }

  public void attachTo(UUID incident) {
    this.incidentId = incident;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getServiceId() {
    return serviceId;
  }

  public UUID getDetectionRuleId() {
    return detectionRuleId;
  }

  public UUID getIncidentId() {
    return incidentId;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public IncidentSeverity getSeverity() {
    return IncidentSeverity.valueOf(severity);
  }

  public Status getStatus() {
    return Status.valueOf(status);
  }

  public String getSummary() {
    return summary;
  }

  public BigDecimal getObservedValue() {
    return observedValue;
  }

  public BigDecimal getThresholdValue() {
    return thresholdValue;
  }

  public Integer getSampleSize() {
    return sampleSize;
  }

  public Instant getWindowStart() {
    return windowStart;
  }

  public Instant getWindowEnd() {
    return windowEnd;
  }

  public Instant getTriggeredAt() {
    return triggeredAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
