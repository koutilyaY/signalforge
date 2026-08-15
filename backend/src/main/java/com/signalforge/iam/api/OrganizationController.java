package com.signalforge.iam.api;

import com.signalforge.iam.audit.AuditService;
import com.signalforge.iam.auth.AuthenticatedPrincipal;
import com.signalforge.iam.domain.AuditEvent;
import com.signalforge.iam.domain.Organization;
import com.signalforge.iam.repository.OrganizationRepository;
import com.signalforge.iam.repository.UserRepository;
import com.signalforge.platform.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organization settings. ADMIN only — the path rule in {@code SecurityConfig} already enforces
 * that, and the method annotation restates it so the requirement survives a routing change.
 */
@RestController
@RequestMapping("/api/v1/organization")
@Tag(name = "Organization")
public class OrganizationController {

  private final OrganizationRepository organizationRepository;
  private final UserRepository userRepository;
  private final AuditService auditService;

  public OrganizationController(
      OrganizationRepository organizationRepository,
      UserRepository userRepository,
      AuditService auditService) {
    this.organizationRepository = organizationRepository;
    this.userRepository = userRepository;
    this.auditService = auditService;
  }

  public record OrganizationResponse(
      UUID id,
      String name,
      String slug,
      int ingestRateLimitPerMinute,
      int telemetryRetentionDays,
      long userCount,
      Instant createdAt) {}

  public record UpdateOrganizationRequest(
      @Size(max = 200) String name,
      @Min(1) @Max(10_000_000) Integer ingestRateLimitPerMinute,
      @Min(1) @Max(365) Integer telemetryRetentionDays) {}

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read the caller's organization settings")
  @Transactional(readOnly = true)
  public OrganizationResponse get(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return toResponse(require(principal.organizationId()));
  }

  @PatchMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Update organization settings")
  @Transactional
  public OrganizationResponse update(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @Valid @RequestBody UpdateOrganizationRequest request) {

    // Loaded by the principal's own organization id, never by one supplied in
    // the request - an ADMIN of A must not be able to edit B by guessing an id.
    Organization organization = require(principal.organizationId());

    Map<String, Object> changes = new LinkedHashMap<>();
    if (request.name() != null && !request.name().isBlank()) {
      changes.put("name", request.name());
      organization.setName(request.name().trim());
    }
    if (request.ingestRateLimitPerMinute() != null) {
      changes.put("ingestRateLimitPerMinute", request.ingestRateLimitPerMinute());
      organization.setIngestRateLimitPerMinute(request.ingestRateLimitPerMinute());
    }
    if (request.telemetryRetentionDays() != null) {
      changes.put("telemetryRetentionDays", request.telemetryRetentionDays());
      organization.setTelemetryRetentionDays(request.telemetryRetentionDays());
    }

    Organization saved = organizationRepository.saveAndFlush(organization);

    if (!changes.isEmpty()) {
      auditService.recordQuietly(
          saved.getId(),
          principal.userId(),
          principal.email(),
          AuditService.ORGANIZATION_UPDATED,
          "ORGANIZATION",
          saved.getId().toString(),
          AuditEvent.Outcome.SUCCESS,
          changes);
    }

    return toResponse(saved);
  }

  private Organization require(UUID organizationId) {
    return organizationRepository
        .findById(organizationId)
        .orElseThrow(() -> ApiException.notFound("Organization", organizationId));
  }

  private OrganizationResponse toResponse(Organization organization) {
    return new OrganizationResponse(
        organization.getId(),
        organization.getName(),
        organization.getSlug(),
        organization.getIngestRateLimitPerMinute(),
        organization.getTelemetryRetentionDays(),
        userRepository.findAllInOrganization(organization.getId()).size(),
        organization.getCreatedAt());
  }
}
