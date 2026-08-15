package com.signalforge.deployment.api;

import com.signalforge.deployment.service.DeploymentService;
import com.signalforge.iam.auth.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deployment tracking.
 *
 * <p>Writes need ENGINEER, which an ingestion API key also satisfies — the intended caller is a CI
 * pipeline, not a human clicking a button.
 */
@RestController
@RequestMapping("/api/v1/deployments")
@Tag(name = "Deployments")
@Validated
public class DeploymentController {

  private final DeploymentService deploymentService;

  public DeploymentController(DeploymentService deploymentService) {
    this.deploymentService = deploymentService;
  }

  @GetMapping
  @PreAuthorize("hasRole('VIEWER')")
  @Operation(summary = "List deployments, most recent first")
  public List<DeploymentDtos.DeploymentResponse> list(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @RequestParam(required = false) UUID serviceId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
    return deploymentService.list(principal.organizationId(), serviceId, page, size);
  }

  @GetMapping("/{deploymentId}")
  @PreAuthorize("hasRole('VIEWER')")
  @Operation(summary = "Fetch one deployment")
  public DeploymentDtos.DeploymentResponse get(
      @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID deploymentId) {
    return deploymentService.get(principal.organizationId(), deploymentId);
  }

  @PostMapping
  @PreAuthorize("hasRole('ENGINEER')")
  @Operation(summary = "Record the start of a deployment (called from CI)")
  public ResponseEntity<DeploymentDtos.DeploymentResponse> record(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @Valid @RequestBody DeploymentDtos.RecordDeploymentRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(deploymentService.record(principal, request));
  }

  @PostMapping("/{deploymentId}/completion")
  @PreAuthorize("hasRole('ENGINEER')")
  @Operation(summary = "Mark a deployment succeeded, failed or rolled back")
  public DeploymentDtos.DeploymentResponse complete(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @PathVariable UUID deploymentId,
      @Valid @RequestBody DeploymentDtos.CompleteDeploymentRequest request) {
    return deploymentService.complete(principal, deploymentId, request);
  }
}
