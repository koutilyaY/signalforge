package com.signalforge.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A tenant. Every other tenant-scoped row in the system points at one of these. */
@Entity
@Table(name = "organizations")
public class Organization {

  // Assigned in Java, not by the database. Row-level security needs the tenant
  // set on the connection BEFORE the organization row is inserted, and a
  // DB-generated id is not knowable until after. See V4__row_level_security.sql.
  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "slug", nullable = false, length = 80, updatable = false)
  private String slug;

  @Column(name = "ingest_rate_limit_per_min", nullable = false)
  private int ingestRateLimitPerMinute = 6000;

  @Column(name = "telemetry_retention_days", nullable = false)
  private int telemetryRetentionDays = 14;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;

  protected Organization() {}

  public Organization(UUID id, String name, String slug) {
    this.id = id;
    this.name = name;
    this.slug = slug;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSlug() {
    return slug;
  }

  public int getIngestRateLimitPerMinute() {
    return ingestRateLimitPerMinute;
  }

  public void setIngestRateLimitPerMinute(int ingestRateLimitPerMinute) {
    this.ingestRateLimitPerMinute = ingestRateLimitPerMinute;
  }

  public int getTelemetryRetentionDays() {
    return telemetryRetentionDays;
  }

  public void setTelemetryRetentionDays(int telemetryRetentionDays) {
    this.telemetryRetentionDays = telemetryRetentionDays;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
