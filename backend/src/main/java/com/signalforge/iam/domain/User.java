package com.signalforge.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A human account.
 *
 * <p>{@code organizationId} is stored as a raw UUID rather than a {@code @ManyToOne} association on
 * purpose: it is read on every request to build the principal, and a lazy association would either
 * trigger an extra select or tempt code into {@code user.getOrganization().getId()} inside a
 * detached context.
 */
@Entity
@Table(name = "users")
public class User {

  @Id
  @GeneratedValue
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "email", nullable = false, length = 320)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 120)
  private String passwordHash;

  @Column(name = "full_name", nullable = false, length = 200)
  private String fullName;

  @Column(name = "role_code", nullable = false, length = 20)
  private String roleCode;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;

  protected User() {}

  public User(UUID organizationId, String email, String passwordHash, String fullName, Role role) {
    this.organizationId = organizationId;
    this.email = normalizeEmail(email);
    this.passwordHash = passwordHash;
    this.fullName = fullName;
    this.roleCode = role.name();
  }

  /** Emails are compared case-insensitively; store them folded so the unique index enforces it. */
  public static String normalizeEmail(String raw) {
    return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = normalizeEmail(email);
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public String getFullName() {
    return fullName;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }

  public Role getRole() {
    return Role.from(roleCode);
  }

  public void setRole(Role role) {
    this.roleCode = role.name();
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  public void setLastLoginAt(Instant lastLoginAt) {
    this.lastLoginAt = lastLoginAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
