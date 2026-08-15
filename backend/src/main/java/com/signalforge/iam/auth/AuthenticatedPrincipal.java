package com.signalforge.iam.auth;

import com.signalforge.iam.domain.Role;
import com.signalforge.platform.tenant.TenantPrincipal;
import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * What Spring Security carries as the authenticated principal.
 *
 * <p>Crucially this holds the organization id. Controllers obtain the tenant from here via
 * {@code @AuthenticationPrincipal}; there is no code path in the application that reads an
 * organization id from a path variable, query parameter or header, which is what makes horizontal
 * escalation structurally impossible rather than merely unlikely.
 *
 * <p>Authorities are granted cumulatively: an ADMIN also holds ROLE_ENGINEER and ROLE_VIEWER, so a
 * {@code @PreAuthorize("hasRole('ENGINEER')")} on an endpoint does the right thing for admins
 * without listing every role.
 */
public record AuthenticatedPrincipal(
    UUID userId, UUID organizationId, String email, Role role, TenantPrincipal.PrincipalKind kind)
    implements Principal {

  @Override
  public String getName() {
    return email;
  }

  public Collection<? extends GrantedAuthority> authorities() {
    return java.util.Arrays.stream(Role.values())
        .filter(role::satisfies)
        .map(r -> new SimpleGrantedAuthority(r.authority()))
        .map(GrantedAuthority.class::cast)
        .toList();
  }

  public TenantPrincipal toTenantPrincipal() {
    return new TenantPrincipal(organizationId, userId, email, role.name(), kind);
  }

  public static AuthenticatedPrincipal ofUser(
      UUID userId, UUID organizationId, String email, Role role) {
    return new AuthenticatedPrincipal(
        userId, organizationId, email, role, TenantPrincipal.PrincipalKind.USER);
  }

  /**
   * An ingestion API key authenticates as ENGINEER: enough to write telemetry and register
   * deployments, never enough to change organization settings or manage users.
   */
  public static AuthenticatedPrincipal ofApiKey(UUID organizationId, String keyName) {
    return new AuthenticatedPrincipal(
        null,
        organizationId,
        "apikey:" + keyName,
        Role.ENGINEER,
        TenantPrincipal.PrincipalKind.API_KEY);
  }

  public boolean isApiKey() {
    return kind == TenantPrincipal.PrincipalKind.API_KEY;
  }

  public static List<String> allAuthorityNames() {
    return java.util.Arrays.stream(Role.values()).map(Role::authority).toList();
  }
}
