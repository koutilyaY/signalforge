package com.signalforge.detection.domain;

import com.signalforge.telemetry.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A configurable condition that opens an incident when it holds.
 *
 * <p>{@code serviceId} being null means "every service in this organization", which is how an
 * organization gets sane coverage without hand-writing a rule per service — and how a newly
 * registered service is protected the moment it starts reporting.
 *
 * <p>{@code threshold} being null means "use the target configured on the service itself" ({@code
 * expected_p95_latency_ms}, {@code expected_error_rate}). That indirection matters: an SLA belongs
 * to the service, and copying it into every rule guarantees the two drift apart.
 */
@Entity
@Table(name = "detection_rules")
public class DetectionRule {

  @Id
  @GeneratedValue
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "service_id")
  private UUID serviceId;

  @Column(name = "name", nullable = false, length = 160)
  private String name;

  @Column(name = "description", length = 1000)
  private String description;

  @Column(name = "rule_type", nullable = false, length = 30)
  private String ruleType;

  @Column(name = "threshold", precision = 12, scale = 4)
  private BigDecimal threshold;

  @Column(name = "window_seconds", nullable = false)
  private int windowSeconds = 300;

  @Column(name = "min_sample_size", nullable = false)
  private int minSampleSize = 20;

  @Column(name = "severity", nullable = false, length = 10)
  private String severity = "HIGH";

  @Column(name = "cooldown_seconds", nullable = false)
  private int cooldownSeconds = 900;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;

  protected DetectionRule() {}

  public DetectionRule(
      UUID organizationId, String name, RuleType ruleType, IncidentSeverity severity) {
    this.organizationId = organizationId;
    this.name = name;
    this.ruleType = ruleType.name();
    this.severity = severity.name();
  }

  /** Applies to every service in the organization rather than one named service. */
  public boolean isOrganizationWide() {
    return serviceId == null;
  }

  public Duration window() {
    return Duration.ofSeconds(windowSeconds);
  }

  public Duration cooldown() {
    return Duration.ofSeconds(cooldownSeconds);
  }

  /**
   * Stable identity for "this rule firing on this service". Two evaluations of the same rule
   * against the same service produce the same fingerprint, which is what the partial unique index
   * on {@code incidents} uses to guarantee one open incident rather than one per tick.
   */
  public String fingerprintFor(UUID targetServiceId) {
    return "%s:%s:%s".formatted(ruleType, targetServiceId, id);
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

  public void setServiceId(UUID serviceId) {
    this.serviceId = serviceId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public RuleType getRuleType() {
    return RuleType.valueOf(ruleType);
  }

  public BigDecimal getThreshold() {
    return threshold;
  }

  public void setThreshold(BigDecimal threshold) {
    this.threshold = threshold;
  }

  public int getWindowSeconds() {
    return windowSeconds;
  }

  public void setWindowSeconds(int windowSeconds) {
    this.windowSeconds = windowSeconds;
  }

  public int getMinSampleSize() {
    return minSampleSize;
  }

  public void setMinSampleSize(int minSampleSize) {
    this.minSampleSize = minSampleSize;
  }

  public IncidentSeverity getSeverity() {
    return IncidentSeverity.valueOf(severity);
  }

  public void setSeverity(IncidentSeverity severity) {
    this.severity = severity.name();
  }

  public int getCooldownSeconds() {
    return cooldownSeconds;
  }

  public void setCooldownSeconds(int cooldownSeconds) {
    this.cooldownSeconds = cooldownSeconds;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Telemetry severity that a breach of this rule should stamp on emitted events. */
  public Severity telemetrySeverity() {
    return getSeverity() == IncidentSeverity.CRITICAL ? Severity.CRITICAL : Severity.ERROR;
  }
}
