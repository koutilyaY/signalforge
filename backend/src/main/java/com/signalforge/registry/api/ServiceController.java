package com.signalforge.registry.api;

import com.signalforge.iam.auth.AuthenticatedPrincipal;
import com.signalforge.registry.domain.Environment;
import com.signalforge.registry.service.ServiceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service registry API.
 *
 * <p>Reads need VIEWER, writes need ENGINEER. Note that no method takes an organization id - it
 * always comes from {@code principal}, which came from a signed token.
 */
@RestController
@RequestMapping("/api/v1/services")
@Tag(name = "Service registry")
public class ServiceController {

  private final ServiceRegistry registry;

  public ServiceController(ServiceRegistry registry) {
    this.registry = registry;
  }

  @GetMapping
  @PreAuthorize("hasRole('VIEWER')")
  @Operation(summary = "List services in the caller's organization")
  public List<ServiceDtos.ServiceResponse> list(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @RequestParam(required = false) Environment environment) {
    return registry.list(principal.organizationId(), environment);
  }

  @GetMapping("/{serviceId}")
  @PreAuthorize("hasRole('VIEWER')")
  @Operation(summary = "Fetch one service")
  public ServiceDtos.ServiceResponse get(
      @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID serviceId) {
    return registry.get(principal.organizationId(), serviceId);
  }

  @PostMapping
  @PreAuthorize("hasRole('ENGINEER')")
  @Operation(summary = "Register a new service")
  public ResponseEntity<ServiceDtos.ServiceResponse> create(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @Valid @RequestBody ServiceDtos.CreateServiceRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(registry.create(principal, request));
  }

  @PatchMapping("/{serviceId}")
  @PreAuthorize("hasRole('ENGINEER')")
  @Operation(summary = "Update service metadata and SLA targets")
  public ServiceDtos.ServiceResponse update(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @PathVariable UUID serviceId,
      @Valid @RequestBody ServiceDtos.UpdateServiceRequest request) {
    return registry.update(principal, serviceId, request);
  }

  @DeleteMapping("/{serviceId}")
  @PreAuthorize("hasRole('ENGINEER')")
  @Operation(summary = "Archive a service (soft delete, preserves incident history)")
  public ResponseEntity<Void> archive(
      @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID serviceId) {
    registry.archive(principal, serviceId);
    return ResponseEntity.noContent().build();
  }
}
