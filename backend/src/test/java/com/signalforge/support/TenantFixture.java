package com.signalforge.support;

import com.signalforge.iam.auth.JwtService;
import com.signalforge.iam.domain.Organization;
import com.signalforge.iam.domain.Role;
import com.signalforge.iam.domain.User;
import com.signalforge.iam.repository.OrganizationRepository;
import com.signalforge.iam.repository.UserRepository;
import com.signalforge.platform.tenant.TenantBinder;
import com.signalforge.registry.domain.Environment;
import com.signalforge.registry.domain.ServiceEntity;
import com.signalforge.registry.repository.ServiceRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Builds real tenants directly through the repositories.
 *
 * <p>Deliberately bypasses the HTTP API: a test asserting that org A cannot read org B's data
 * should not also depend on the registration endpoint working, or a regression there would show up
 * as a confusing isolation failure.
 */
@Component
public class TenantFixture {

  private static final AtomicInteger COUNTER = new AtomicInteger();

  private final OrganizationRepository organizationRepository;
  private final UserRepository userRepository;
  private final ServiceRepository serviceRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final TenantBinder tenantBinder;

  public TenantFixture(
      OrganizationRepository organizationRepository,
      UserRepository userRepository,
      ServiceRepository serviceRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      TenantBinder tenantBinder) {
    this.organizationRepository = organizationRepository;
    this.userRepository = userRepository;
    this.serviceRepository = serviceRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.tenantBinder = tenantBinder;
  }

  /**
   * An organization with one user of each role, plus one registered service.
   *
   * <p>Runs inside a bound tenant context because row-level security applies to test fixtures
   * exactly as it does to production code - which is the point.
   */
  public Tenant createTenant(String slugPrefix) {
    int n = COUNTER.incrementAndGet();
    String slug = slugPrefix + "-" + n;
    UUID organizationId = UUID.randomUUID();

    return tenantBinder.callAs(organizationId, () -> buildTenant(organizationId, slug, n));
  }

  private Tenant buildTenant(UUID organizationId, String slug, int n) {
    Organization organization =
        organizationRepository.saveAndFlush(new Organization(organizationId, "Org " + slug, slug));

    User admin = createUser(organization.getId(), "admin-" + n + "@test.local", Role.ADMIN);
    User engineer = createUser(organization.getId(), "eng-" + n + "@test.local", Role.ENGINEER);
    User viewer = createUser(organization.getId(), "viewer-" + n + "@test.local", Role.VIEWER);

    ServiceEntity service =
        serviceRepository.saveAndFlush(
            new ServiceEntity(organization.getId(), "checkout-service", Environment.PRODUCTION));

    return new Tenant(
        organization.getId(),
        slug,
        new Actor(admin.getId(), admin.getEmail(), Role.ADMIN, bearer(admin)),
        new Actor(engineer.getId(), engineer.getEmail(), Role.ENGINEER, bearer(engineer)),
        new Actor(viewer.getId(), viewer.getEmail(), Role.VIEWER, bearer(viewer)),
        service.getId());
  }

  private User createUser(UUID organizationId, String email, Role role) {
    return userRepository.saveAndFlush(
        new User(
            organizationId,
            email,
            passwordEncoder.encode(DEFAULT_PASSWORD),
            role.name().toLowerCase() + " user",
            role));
  }

  public static final String DEFAULT_PASSWORD = "correct-horse-battery-staple";

  private String bearer(User user) {
    return "Bearer " + jwtService.issueFor(user).accessToken();
  }

  public record Tenant(
      UUID organizationId,
      String slug,
      Actor admin,
      Actor engineer,
      Actor viewer,
      UUID serviceId) {}

  public record Actor(UUID userId, String email, Role role, String authorizationHeader) {}
}
