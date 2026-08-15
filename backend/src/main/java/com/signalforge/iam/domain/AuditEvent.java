package com.signalforge.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only record of a security- or compliance-relevant action.
 *
 * <p>There is deliberately no setter and no update path. The table also carries a database trigger
 * that rejects UPDATE and DELETE outright, so immutability holds even if someone later adds a
 * native query or a careless {@code EntityManager.merge}.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

  @Id
  @GeneratedValue
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "actor_user_id", updatable = false)
  private UUID actorUserId;

  @Column(name = "actor_email", length = 320, updatable = false)
  private String actorEmail;

  @Column(name = "action", nullable = false, length = 80, updatable = false)
  private String action;

  @Column(name = "resource_type", nullable = false, length = 60, updatable = false)
  private String resourceType;

  @Column(name = "resource_id", length = 120, updatable = false)
  private String resourceId;

  @Column(name = "outcome", nullable = false, length = 20, updatable = false)
  private String outcome;

  @Column(name = "ip_address", length = 60, updatable = false)
  private String ipAddress;

  @Column(name = "user_agent", length = 400, updatable = false)
  private String userAgent;

  @Column(name = "correlation_id", length = 120, updatable = false)
  private String correlationId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", nullable = false, updatable = false)
  private Map<String, Object> metadata = Map.of();

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  protected AuditEvent() {}

  public AuditEvent(
      UUID organizationId,
      UUID actorUserId,
      String actorEmail,
      String action,
      String resourceType,
      String resourceId,
      Outcome outcome,
      String ipAddress,
      String userAgent,
      String correlationId,
      Map<String, Object> metadata) {
    this.organizationId = organizationId;
    this.actorUserId = actorUserId;
    this.actorEmail = actorEmail;
    this.action = action;
    this.resourceType = resourceType;
    this.resourceId = resourceId;
    this.outcome = outcome.name();
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.correlationId = correlationId;
    this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public enum Outcome {
    SUCCESS,
    FAILURE,
    DENIED
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public String getActorEmail() {
    return actorEmail;
  }

  public String getAction() {
    return action;
  }

  public String getResourceType() {
    return resourceType;
  }

  public String getResourceId() {
    return resourceId;
  }

  public String getOutcome() {
    return outcome;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
