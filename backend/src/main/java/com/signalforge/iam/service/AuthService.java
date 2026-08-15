package com.signalforge.iam.service;

import com.signalforge.detection.service.DefaultRuleFactory;
import com.signalforge.iam.api.AuthDtos;
import com.signalforge.iam.audit.AuditService;
import com.signalforge.iam.auth.JwtService;
import com.signalforge.iam.auth.LoginThrottle;
import com.signalforge.iam.domain.AuditEvent;
import com.signalforge.iam.domain.Organization;
import com.signalforge.iam.domain.Role;
import com.signalforge.iam.domain.User;
import com.signalforge.iam.repository.OrganizationRepository;
import com.signalforge.iam.repository.UserRepository;
import com.signalforge.platform.error.ApiException;
import com.signalforge.platform.error.ErrorCode;
import com.signalforge.platform.tenant.TenantBinder;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login, refresh and tenant bootstrap. */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  /**
   * A bcrypt hash of a throwaway value, verified when the email is unknown so that a request for a
   * non-existent account costs the same wall-clock time as one for a real account. Without this the
   * response time is a user-enumeration oracle.
   */
  private final String dummyHash;

  private final UserRepository userRepository;
  private final OrganizationRepository organizationRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final LoginThrottle loginThrottle;
  private final AuditService auditService;
  private final DefaultRuleFactory defaultRuleFactory;
  private final TenantBinder tenantBinder;

  public AuthService(
      UserRepository userRepository,
      OrganizationRepository organizationRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      LoginThrottle loginThrottle,
      AuditService auditService,
      DefaultRuleFactory defaultRuleFactory,
      TenantBinder tenantBinder) {
    this.userRepository = userRepository;
    this.organizationRepository = organizationRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.loginThrottle = loginThrottle;
    this.auditService = auditService;
    this.defaultRuleFactory = defaultRuleFactory;
    this.tenantBinder = tenantBinder;
    this.dummyHash = passwordEncoder.encode("signalforge-timing-equalizer");
  }

  @Transactional
  public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
    String email = User.normalizeEmail(request.email());

    if (loginThrottle.isLockedOut(email)) {
      log.warn("Login blocked by throttle for {}", email);
      // Audited without an organization id when the account is unknown; we use a
      // nil UUID rather than skipping the record, because repeated lockouts for a
      // non-existent email is itself a signal worth keeping.
      userRepository
          .findByEmail(email)
          .ifPresent(
              u ->
                  auditService.recordQuietly(
                      u.getOrganizationId(),
                      u.getId(),
                      email,
                      AuditService.LOGIN_LOCKED_OUT,
                      "USER",
                      u.getId().toString(),
                      AuditEvent.Outcome.DENIED,
                      Map.of()));
      throw new ApiException(
          ErrorCode.RATE_LIMIT_EXCEEDED, "Too many failed attempts. Try again shortly.");
    }

    Optional<User> found = userRepository.findByEmail(email);

    if (found.isEmpty()) {
      passwordEncoder.matches(request.password(), dummyHash); // constant-ish time
      loginThrottle.recordFailure(email);
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }

    User user = found.get();

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      loginThrottle.recordFailure(email);
      auditService.recordQuietly(
          user.getOrganizationId(),
          user.getId(),
          email,
          AuditService.LOGIN_FAILED,
          "USER",
          user.getId().toString(),
          AuditEvent.Outcome.FAILURE,
          Map.of());
      // Same code and message as "no such user" - see above.
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }

    if (!user.isEnabled()) {
      auditService.recordQuietly(
          user.getOrganizationId(),
          user.getId(),
          email,
          AuditService.LOGIN_FAILED,
          "USER",
          user.getId().toString(),
          AuditEvent.Outcome.DENIED,
          Map.of("reason", "account_disabled"));
      throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
    }

    loginThrottle.reset(email);
    user.setLastLoginAt(Instant.now());

    auditService.recordQuietly(
        user.getOrganizationId(),
        user.getId(),
        email,
        AuditService.LOGIN_SUCCEEDED,
        "USER",
        user.getId().toString(),
        AuditEvent.Outcome.SUCCESS,
        Map.of());

    return buildTokenResponse(user);
  }

  @Transactional(readOnly = true)
  public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest request) {
    JwtService.ParsedToken parsed = jwtService.parseRefreshToken(request.refreshToken());

    // Re-read the user rather than trusting the token's claims. A token minted
    // before a role change or a disable must not keep working.
    User user =
        userRepository
            .findByIdInOrganization(parsed.userId(), parsed.organizationId())
            .orElseThrow(
                () -> new ApiException(ErrorCode.TOKEN_INVALID, "Account no longer exists"));

    if (!user.isEnabled()) {
      throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
    }
    return buildTokenResponse(user);
  }

  /**
   * Creates a tenant and its first ADMIN in one transaction. Either both exist or neither does - an
   * organization with no way to log into it would be unrecoverable through the API.
   */
  @Transactional
  public AuthDtos.TokenResponse registerOrganization(AuthDtos.RegisterOrganizationRequest request) {
    String email = User.normalizeEmail(request.adminEmail());
    String slug = request.organizationSlug().trim();

    if (organizationRepository.existsBySlug(slug)) {
      throw ApiException.conflict("An organization with that slug already exists");
    }
    if (userRepository.existsByEmail(email)) {
      throw ApiException.conflict("An account with that email already exists");
    }

    // The tenant id is minted here rather than by the database, because
    // row-level security must be able to bind it to the connection BEFORE the
    // organization row is written - the WITH CHECK clause on every policy
    // compares against signalforge.current_org, and it has to already be set.
    UUID organizationId = UUID.randomUUID();

    Organization organization =
        tenantBinder.callAs(
            organizationId,
            () -> {
              Organization created =
                  organizationRepository.saveAndFlush(
                      new Organization(organizationId, request.organizationName().trim(), slug));

              userRepository.saveAndFlush(
                  new User(
                      organizationId,
                      email,
                      passwordEncoder.encode(request.adminPassword()),
                      request.adminFullName().trim(),
                      Role.ADMIN));

              // A monitoring platform that detects nothing until someone
              // configures it is a platform nobody configures. Seed a working
              // default rule set so a new tenant has coverage from its first
              // telemetry event.
              defaultRuleFactory.seedFor(organizationId);
              return created;
            });

    User admin = userRepository.findByEmail(email).orElseThrow();

    auditService.recordQuietly(
        organization.getId(),
        admin.getId(),
        email,
        AuditService.ORGANIZATION_CREATED,
        "ORGANIZATION",
        organization.getId().toString(),
        AuditEvent.Outcome.SUCCESS,
        Map.of("slug", slug));

    log.info("Registered organization slug={} id={}", slug, organization.getId());
    return buildTokenResponse(admin, organization);
  }

  @Transactional(readOnly = true)
  public AuthDtos.CurrentUserResponse currentUser(java.util.UUID userId, java.util.UUID orgId) {
    User user =
        userRepository
            .findByIdInOrganization(userId, orgId)
            .orElseThrow(() -> ApiException.notFound("User", userId));
    Organization organization =
        organizationRepository
            .findById(orgId)
            .orElseThrow(() -> ApiException.notFound("Organization", orgId));
    return toCurrentUser(user, organization);
  }

  private AuthDtos.TokenResponse buildTokenResponse(User user) {
    Organization organization =
        organizationRepository
            .findById(user.getOrganizationId())
            .orElseThrow(() -> ApiException.notFound("Organization", user.getOrganizationId()));
    return buildTokenResponse(user, organization);
  }

  private AuthDtos.TokenResponse buildTokenResponse(User user, Organization organization) {
    JwtService.IssuedTokens tokens = jwtService.issueFor(user);
    return AuthDtos.TokenResponse.of(
        tokens.accessToken(),
        tokens.refreshToken(),
        tokens.expiresInSeconds(),
        tokens.accessTokenExpiresAt(),
        toCurrentUser(user, organization));
  }

  private static AuthDtos.CurrentUserResponse toCurrentUser(User user, Organization organization) {
    return new AuthDtos.CurrentUserResponse(
        user.getId(),
        user.getEmail(),
        user.getFullName(),
        user.getRole().name(),
        organization.getId(),
        organization.getName(),
        organization.getSlug());
  }
}
