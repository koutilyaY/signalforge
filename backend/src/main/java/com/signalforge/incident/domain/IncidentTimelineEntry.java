package com.signalforge.incident.domain;

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
 * One entry on an incident's timeline. Append-only: there is no setter and nothing updates these.
 *
 * <p>Maps to {@code incident_events}. Named "timeline entry" in Java because {@code IncidentEvent}
 * reads as a domain event in a codebase that also publishes real domain events to Kafka, and the
 * two are not the same thing.
 */
@Entity
@Table(name = "incident_events")
public class IncidentTimelineEntry {

  @Id
  @GeneratedValue
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "incident_id", nullable = false, updatable = false)
  private UUID incidentId;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "kind", nullable = false, length = 30, updatable = false)
  private String kind;

  @Column(name = "title", nullable = false, length = 300, updatable = false)
  private String title;

  @Column(name = "detail", length = 4000, updatable = false)
  private String detail;

  @Column(name = "actor_user_id", updatable = false)
  private UUID actorUserId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", nullable = false, updatable = false)
  private Map<String, Object> metadata = Map.of();

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  protected IncidentTimelineEntry() {}

  public IncidentTimelineEntry(
      UUID organizationId,
      UUID incidentId,
      Instant occurredAt,
      Kind kind,
      String title,
      String detail,
      UUID actorUserId,
      Map<String, Object> metadata) {
    this.organizationId = organizationId;
    this.incidentId = incidentId;
    this.occurredAt = occurredAt;
    this.kind = kind.name();
    this.title = title;
    this.detail = detail;
    this.actorUserId = actorUserId;
    this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  /** Must stay in sync with the CHECK constraint on {@code incident_events.kind}. */
  public enum Kind {
    DETECTED,
    STATUS_CHANGE,
    COMMENT,
    DEPLOYMENT,
    EVIDENCE,
    ALERT,
    RECOVERY,
    AI_SUMMARY,
    SEVERITY_CHANGE
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

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public Kind getKind() {
    return Kind.valueOf(kind);
  }

  public String getTitle() {
    return title;
  }

  public String getDetail() {
    return detail;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
