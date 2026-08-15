package com.signalforge.deployment.domain;

import com.signalforge.registry.domain.Environment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A release of a service.
 *
 * <p>The single highest-value correlation signal in the system. Most production incidents are
 * caused by a change, and the change with a timestamp attached is the cheapest thing to correlate
 * against — no statistics, no model, just "what shipped shortly before this broke".
 */
@Entity
@Table(name = "deployments")
public class Deployment {

  @Id
  @GeneratedValue
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "service_id", nullable = false, updatable = false)
  private UUID serviceId;

  @Column(name = "version", nullable = false, length = 80)
  private String version;

  @Column(name = "commit_sha", length = 80)
  private String commitSha;

  @Column(name = "branch", length = 200)
  private String branch;

  @Column(name = "environment", nullable = false, length = 20, updatable = false)
  private String environment;

  @Column(name = "status", nullable = false, length = 20)
  private String status = DeploymentStatus.IN_PROGRESS.name();

  @Column(name = "deployed_by", length = 200)
  private String deployedBy;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;

  protected Deployment() {}

  public Deployment(
      UUID organizationId,
      UUID serviceId,
      String version,
      Environment environment,
      Instant startedAt) {
    this.organizationId = organizationId;
    this.serviceId = serviceId;
    this.version = version;
    this.environment = environment.name();
    this.startedAt = startedAt;
  }

  /**
   * The moment this deployment could first have affected production traffic.
   *
   * <p>Correlation measures from completion, not from start: a rollout that began at 13:00 and
   * finished at 13:59 did not cause a 13:05 incident, and measuring from the start would rank it as
   * if it had.
   */
  public Instant effectiveAt() {
    return completedAt != null ? completedAt : startedAt;
  }

  public void markCompleted(DeploymentStatus finalStatus, Instant at) {
    this.status = finalStatus.name();
    this.completedAt = at;
  }

  public Duration durationSoFar() {
    return Duration.between(startedAt, completedAt == null ? Instant.now() : completedAt);
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

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getCommitSha() {
    return commitSha;
  }

  public void setCommitSha(String commitSha) {
    this.commitSha = commitSha;
  }

  public String getBranch() {
    return branch;
  }

  public void setBranch(String branch) {
    this.branch = branch;
  }

  public Environment getEnvironment() {
    return Environment.valueOf(environment);
  }

  public DeploymentStatus getStatus() {
    return DeploymentStatus.valueOf(status);
  }

  public void setStatus(DeploymentStatus status) {
    this.status = status.name();
  }

  public String getDeployedBy() {
    return deployedBy;
  }

  public void setDeployedBy(String deployedBy) {
    this.deployedBy = deployedBy;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
