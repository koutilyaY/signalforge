package com.signalforge.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A human note on an incident. */
@Entity
@Table(name = "incident_comments")
public class IncidentComment {

  @Id
  @GeneratedValue
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "incident_id", nullable = false, updatable = false)
  private UUID incidentId;

  @Column(name = "author_user_id", updatable = false)
  private UUID authorUserId;

  @Column(name = "body", nullable = false, length = 4000)
  private String body;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;

  protected IncidentComment() {}

  public IncidentComment(UUID organizationId, UUID incidentId, UUID authorUserId, String body) {
    this.organizationId = organizationId;
    this.incidentId = incidentId;
    this.authorUserId = authorUserId;
    this.body = body;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getIncidentId() {
    return incidentId;
  }

  public UUID getAuthorUserId() {
    return authorUserId;
  }

  public String getBody() {
    return body;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
