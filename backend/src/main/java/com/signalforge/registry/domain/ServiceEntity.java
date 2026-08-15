package com.signalforge.registry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A registered service being monitored.
 *
 * <p>Named {@code ServiceEntity} rather than {@code Service} to avoid a collision with Spring's
 * {@code @Service} stereotype in every file that imports both - the small ugliness buys a lot of
 * clarity at the call sites.
 *
 * <p>{@code version} drives JPA optimistic locking. Services are edited by humans from a UI where
 * two engineers on the same team plausibly open the same service and save; last-write-wins would
 * silently discard one of them. The conflict surfaces as HTTP 409.
 */
@Entity
@Table(name = "services")
public class ServiceEntity {

  @Id
  @GeneratedValue
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "description", length = 1000)
  private String description;

  @Column(name = "environment", nullable = false, length = 20)
  private String environment;

  @Column(name = "team", length = 120)
  private String team;

  @Column(name = "repository_url", length = 500)
  private String repositoryUrl;

  @Column(name = "health_endpoint", length = 500)
  private String healthEndpoint;

  @Column(name = "criticality", nullable = false, length = 20)
  private String criticality = Criticality.MEDIUM.name();

  @Column(name = "expected_p95_latency_ms", nullable = false)
  private int expectedP95LatencyMs = 500;

  @Column(name = "expected_error_rate", nullable = false, precision = 5, scale = 4)
  private BigDecimal expectedErrorRate = new BigDecimal("0.0100");

  @Column(name = "health_status", nullable = false, length = 20)
  private String healthStatus = HealthStatus.UNKNOWN.name();

  @Column(name = "health_changed_at")
  private Instant healthChangedAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;

  protected ServiceEntity() {}

  public ServiceEntity(UUID organizationId, String name, Environment environment) {
    this.organizationId = organizationId;
    this.name = name;
    this.environment = environment.name();
  }

  /** Records a health transition, keeping {@code healthChangedAt} truthful. */
  public boolean applyHealth(HealthStatus status, Instant at) {
    if (this.healthStatus.equals(status.name())) {
      return false;
    }
    this.healthStatus = status.name();
    this.healthChangedAt = at;
    return true;
  }

  public boolean isArchived() {
    return archivedAt != null;
  }

  public void archive() {
    if (archivedAt == null) {
      this.archivedAt = Instant.now();
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
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

  public Environment getEnvironment() {
    return Environment.valueOf(environment);
  }

  public void setEnvironment(Environment environment) {
    this.environment = environment.name();
  }

  public String getTeam() {
    return team;
  }

  public void setTeam(String team) {
    this.team = team;
  }

  public String getRepositoryUrl() {
    return repositoryUrl;
  }

  public void setRepositoryUrl(String repositoryUrl) {
    this.repositoryUrl = repositoryUrl;
  }

  public String getHealthEndpoint() {
    return healthEndpoint;
  }

  public void setHealthEndpoint(String healthEndpoint) {
    this.healthEndpoint = healthEndpoint;
  }

  public Criticality getCriticality() {
    return Criticality.valueOf(criticality);
  }

  public void setCriticality(Criticality criticality) {
    this.criticality = criticality.name();
  }

  public int getExpectedP95LatencyMs() {
    return expectedP95LatencyMs;
  }

  public void setExpectedP95LatencyMs(int expectedP95LatencyMs) {
    this.expectedP95LatencyMs = expectedP95LatencyMs;
  }

  public BigDecimal getExpectedErrorRate() {
    return expectedErrorRate;
  }

  public void setExpectedErrorRate(BigDecimal expectedErrorRate) {
    this.expectedErrorRate = expectedErrorRate;
  }

  public HealthStatus getHealthStatus() {
    return HealthStatus.valueOf(healthStatus);
  }

  public Instant getHealthChangedAt() {
    return healthChangedAt;
  }

  public Instant getArchivedAt() {
    return archivedAt;
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
