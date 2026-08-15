package com.signalforge.platform.tenant;

import java.util.UUID;

/**
 * The authenticated actor for a unit of work.
 *
 * @param organizationId tenant boundary; never null
 * @param userId the acting user, or null for machine principals (API-key ingestion, background
 *     workers)
 * @param email display/audit identity; for machine principals this is the API key name
 * @param role role code, one of VIEWER / ENGINEER / ADMIN, or SYSTEM for background work
 * @param kind how this principal authenticated
 */
public record TenantPrincipal(
    UUID organizationId, UUID userId, String email, String role, PrincipalKind kind) {

  public enum PrincipalKind {
    /** A human with a JWT access token. */
    USER,
    /** A monitored service authenticating with an ingestion API key. */
    API_KEY,
    /** Internal background work (Kafka consumer, scheduler). Bypasses no tenant checks. */
    SYSTEM
  }

  public TenantPrincipal {
    if (organizationId == null) {
      throw new IllegalArgumentException("organizationId is required on every principal");
    }
  }

  public static TenantPrincipal user(UUID organizationId, UUID userId, String email, String role) {
    return new TenantPrincipal(organizationId, userId, email, role, PrincipalKind.USER);
  }

  public static TenantPrincipal apiKey(UUID organizationId, String keyName) {
    return new TenantPrincipal(
        organizationId, null, "apikey:" + keyName, "ENGINEER", PrincipalKind.API_KEY);
  }

  /**
   * Background principal for a specific organization. Note it still carries an organization id -
   * background work is tenant-scoped exactly like user work; there is no "god mode" principal in
   * this system.
   */
  public static TenantPrincipal system(UUID organizationId) {
    return new TenantPrincipal(organizationId, null, "system", "SYSTEM", PrincipalKind.SYSTEM);
  }

  public boolean isMachine() {
    return kind != PrincipalKind.USER;
  }
}
