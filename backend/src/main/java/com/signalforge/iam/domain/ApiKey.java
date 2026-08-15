package com.signalforge.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Credential a monitored service uses to POST telemetry.
 *
 * <p>Only the SHA-256 of the key is persisted. The plaintext is returned exactly once, at creation,
 * and is unrecoverable afterwards - the same reason GitHub shows a PAT once.
 *
 * <p>SHA-256 rather than bcrypt is deliberate here and is <em>not</em> the right choice for
 * passwords: an API key is 256 bits of machine-generated entropy, so there is nothing to brute
 * force, and this hash is computed on every single ingestion request where a 100ms bcrypt would be
 * a self-inflicted denial of service.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

  /** Human-visible prefix so operators can identify a key in a list without revealing it. */
  public static final String KEY_PREFIX = "sfk_";

  @Id
  @GeneratedValue
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "key_hash", nullable = false, length = 64, updatable = false)
  private String keyHash;

  @Column(name = "key_prefix", nullable = false, length = 16, updatable = false)
  private String keyPrefix;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  protected ApiKey() {}

  public ApiKey(
      UUID organizationId, String name, String keyHash, String keyPrefix, UUID createdBy) {
    this.organizationId = organizationId;
    this.name = name;
    this.keyHash = keyHash;
    this.keyPrefix = keyPrefix;
    this.createdBy = createdBy;
  }

  public boolean isActive() {
    return revokedAt == null;
  }

  public void revoke() {
    if (revokedAt == null) {
      this.revokedAt = Instant.now();
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

  public String getKeyHash() {
    return keyHash;
  }

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(Instant lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
