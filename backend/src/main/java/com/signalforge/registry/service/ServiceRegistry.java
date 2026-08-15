package com.signalforge.registry.service;

import com.signalforge.iam.audit.AuditService;
import com.signalforge.iam.auth.AuthenticatedPrincipal;
import com.signalforge.iam.domain.AuditEvent;
import com.signalforge.platform.error.ApiException;
import com.signalforge.platform.error.ErrorCode;
import com.signalforge.registry.api.ServiceDtos;
import com.signalforge.registry.domain.Criticality;
import com.signalforge.registry.domain.Environment;
import com.signalforge.registry.domain.ServiceEntity;
import com.signalforge.registry.repository.ServiceRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service registry CRUD. Every read and write is scoped to the caller's organization. */
@Service
public class ServiceRegistry {

  private final ServiceRepository repository;
  private final AuditService auditService;

  public ServiceRegistry(ServiceRepository repository, AuditService auditService) {
    this.repository = repository;
    this.auditService = auditService;
  }

  @Transactional(readOnly = true)
  public List<ServiceDtos.ServiceResponse> list(UUID organizationId, Environment environment) {
    return repository
        .findAllInOrganization(organizationId, environment == null ? null : environment.name())
        .stream()
        .map(ServiceDtos.ServiceResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public ServiceDtos.ServiceResponse get(UUID organizationId, UUID serviceId) {
    return ServiceDtos.ServiceResponse.from(require(organizationId, serviceId));
  }

  /**
   * Loads a service or throws 404.
   *
   * <p>Note this returns RESOURCE_NOT_FOUND, not TENANT_MISMATCH, when the id belongs to another
   * organization. Distinguishing the two would confirm to an attacker that a given UUID exists
   * somewhere in the system - the two cases must be indistinguishable from outside.
   */
  @Transactional(readOnly = true)
  public ServiceEntity require(UUID organizationId, UUID serviceId) {
    return repository
        .findByIdInOrganization(serviceId, organizationId)
        .orElseThrow(() -> ApiException.notFound("Service", serviceId));
  }

  @Transactional
  public ServiceDtos.ServiceResponse create(
      AuthenticatedPrincipal principal, ServiceDtos.CreateServiceRequest request) {
    UUID organizationId = principal.organizationId();

    ServiceEntity entity =
        new ServiceEntity(organizationId, request.name().trim(), request.environment());
    entity.setDescription(request.description());
    entity.setTeam(request.team());
    entity.setRepositoryUrl(request.repositoryUrl());
    entity.setHealthEndpoint(request.healthEndpoint());
    entity.setCriticality(
        request.criticality() == null ? Criticality.MEDIUM : request.criticality());
    if (request.expectedP95LatencyMs() != null) {
      entity.setExpectedP95LatencyMs(request.expectedP95LatencyMs());
    }
    if (request.expectedErrorRate() != null) {
      entity.setExpectedErrorRate(request.expectedErrorRate());
    }

    ServiceEntity saved;
    try {
      saved = repository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      // Relies on uq_services_org_name_env rather than a read-then-write check,
      // which would race under concurrent creates.
      throw ApiException.conflict(
          "A service named '%s' already exists in %s"
              .formatted(request.name(), request.environment()));
    }

    auditService.recordQuietly(
        organizationId,
        principal.userId(),
        principal.email(),
        AuditService.SERVICE_CREATED,
        "SERVICE",
        saved.getId().toString(),
        AuditEvent.Outcome.SUCCESS,
        Map.of("name", saved.getName(), "environment", saved.getEnvironment().name()));

    return ServiceDtos.ServiceResponse.from(saved);
  }

  @Transactional
  public ServiceDtos.ServiceResponse update(
      AuthenticatedPrincipal principal, UUID serviceId, ServiceDtos.UpdateServiceRequest request) {
    UUID organizationId = principal.organizationId();
    ServiceEntity entity = require(organizationId, serviceId);

    if (request.version() != null && request.version() != entity.getVersion()) {
      throw new ApiException(
          ErrorCode.CONCURRENT_MODIFICATION,
          "This service changed since you loaded it. Reload and try again.",
          null,
          Map.of("expectedVersion", request.version(), "actualVersion", entity.getVersion()));
    }

    if (request.description() != null) {
      entity.setDescription(request.description());
    }
    if (request.team() != null) {
      entity.setTeam(request.team());
    }
    if (request.repositoryUrl() != null) {
      entity.setRepositoryUrl(request.repositoryUrl());
    }
    if (request.healthEndpoint() != null) {
      entity.setHealthEndpoint(request.healthEndpoint());
    }
    if (request.criticality() != null) {
      entity.setCriticality(request.criticality());
    }
    if (request.expectedP95LatencyMs() != null) {
      entity.setExpectedP95LatencyMs(request.expectedP95LatencyMs());
    }
    if (request.expectedErrorRate() != null) {
      entity.setExpectedErrorRate(request.expectedErrorRate());
    }

    ServiceEntity saved = repository.saveAndFlush(entity);

    auditService.recordQuietly(
        organizationId,
        principal.userId(),
        principal.email(),
        AuditService.SERVICE_UPDATED,
        "SERVICE",
        serviceId.toString(),
        AuditEvent.Outcome.SUCCESS,
        Map.of("name", saved.getName()));

    return ServiceDtos.ServiceResponse.from(saved);
  }

  /**
   * Soft delete. Telemetry, incidents and deployments reference this row; a hard delete would
   * cascade away historical incident records, which is exactly the data you most want to keep.
   */
  @Transactional
  public void archive(AuthenticatedPrincipal principal, UUID serviceId) {
    UUID organizationId = principal.organizationId();
    ServiceEntity entity = require(organizationId, serviceId);
    entity.archive();
    repository.save(entity);

    auditService.recordQuietly(
        organizationId,
        principal.userId(),
        principal.email(),
        AuditService.SERVICE_ARCHIVED,
        "SERVICE",
        serviceId.toString(),
        AuditEvent.Outcome.SUCCESS,
        Map.of("name", entity.getName()));
  }
}
