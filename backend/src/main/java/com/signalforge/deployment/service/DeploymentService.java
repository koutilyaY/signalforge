package com.signalforge.deployment.service;

import com.signalforge.deployment.api.DeploymentDtos;
import com.signalforge.deployment.domain.Deployment;
import com.signalforge.deployment.domain.DeploymentStatus;
import com.signalforge.deployment.repository.DeploymentRepository;
import com.signalforge.iam.auth.AuthenticatedPrincipal;
import com.signalforge.platform.error.ApiException;
import com.signalforge.registry.domain.ServiceEntity;
import com.signalforge.registry.service.ServiceRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deployment tracking. Written by CI, read by the correlation engine. */
@Service
public class DeploymentService {

  private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);

  private final DeploymentRepository repository;
  private final ServiceRegistry serviceRegistry;

  public DeploymentService(DeploymentRepository repository, ServiceRegistry serviceRegistry) {
    this.repository = repository;
    this.serviceRegistry = serviceRegistry;
  }

  @Transactional
  public DeploymentDtos.DeploymentResponse record(
      AuthenticatedPrincipal principal, DeploymentDtos.RecordDeploymentRequest request) {

    UUID organizationId = principal.organizationId();
    // Validates tenant ownership: a service id from another organization throws 404.
    ServiceEntity service = serviceRegistry.require(organizationId, request.serviceId());

    if (service.getEnvironment() != request.environment()) {
      throw ApiException.validation(
          "Deployment environment does not match the registered service environment",
          java.util.Map.of(
              "serviceEnvironment", service.getEnvironment().name(),
              "requestEnvironment", request.environment().name()));
    }

    Deployment deployment =
        new Deployment(
            organizationId,
            service.getId(),
            request.version(),
            request.environment(),
            request.startedAt() == null ? Instant.now() : request.startedAt());
    deployment.setCommitSha(request.commitSha());
    deployment.setBranch(request.branch());
    deployment.setDeployedBy(
        request.deployedBy() != null ? request.deployedBy() : principal.email());

    Deployment saved = repository.saveAndFlush(deployment);
    log.info(
        "Recorded deployment {} of {} version {}",
        saved.getId(),
        service.getName(),
        saved.getVersion());
    return DeploymentDtos.DeploymentResponse.from(saved);
  }

  @Transactional
  public DeploymentDtos.DeploymentResponse complete(
      AuthenticatedPrincipal principal,
      UUID deploymentId,
      DeploymentDtos.CompleteDeploymentRequest request) {

    Deployment deployment = require(principal.organizationId(), deploymentId);

    if (deployment.getStatus().isTerminal()) {
      throw ApiException.invalidTransition(deployment.getStatus().name(), request.status().name());
    }
    if (request.status() == DeploymentStatus.IN_PROGRESS) {
      throw ApiException.validation(
          "Cannot complete a deployment as IN_PROGRESS", java.util.Map.of());
    }

    deployment.markCompleted(
        request.status(), request.completedAt() == null ? Instant.now() : request.completedAt());

    return DeploymentDtos.DeploymentResponse.from(repository.saveAndFlush(deployment));
  }

  @Transactional(readOnly = true)
  public List<DeploymentDtos.DeploymentResponse> list(
      UUID organizationId, UUID serviceId, int page, int size) {
    return repository.findPage(organizationId, serviceId, PageRequest.of(page, size)).stream()
        .map(DeploymentDtos.DeploymentResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public DeploymentDtos.DeploymentResponse get(UUID organizationId, UUID deploymentId) {
    return DeploymentDtos.DeploymentResponse.from(require(organizationId, deploymentId));
  }

  private Deployment require(UUID organizationId, UUID deploymentId) {
    return repository
        .findByIdInOrganization(deploymentId, organizationId)
        .orElseThrow(() -> ApiException.notFound("Deployment", deploymentId));
  }
}
