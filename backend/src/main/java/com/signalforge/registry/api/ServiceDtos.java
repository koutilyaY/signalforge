package com.signalforge.registry.api;

import com.signalforge.registry.domain.Criticality;
import com.signalforge.registry.domain.Environment;
import com.signalforge.registry.domain.HealthStatus;
import com.signalforge.registry.domain.ServiceEntity;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ServiceDtos {

  private ServiceDtos() {}

  public record CreateServiceRequest(
      @NotBlank
          @Size(max = 120)
          @Pattern(
              regexp = "^[a-zA-Z0-9]([a-zA-Z0-9._-]*[a-zA-Z0-9])?$",
              message = "must be alphanumeric with dots, dashes or underscores")
          String name,
      @Size(max = 1000) String description,
      @NotNull Environment environment,
      @Size(max = 120) String team,
      @Size(max = 500) String repositoryUrl,
      @Size(max = 500) String healthEndpoint,
      Criticality criticality,
      @Min(1) @Max(600000) Integer expectedP95LatencyMs,
      @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal expectedErrorRate) {}

  public record UpdateServiceRequest(
      @Size(max = 1000) String description,
      @Size(max = 120) String team,
      @Size(max = 500) String repositoryUrl,
      @Size(max = 500) String healthEndpoint,
      Criticality criticality,
      @Min(1) @Max(600000) Integer expectedP95LatencyMs,
      @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal expectedErrorRate,
      /**
       * Optimistic-lock token echoed from a previous read. When present it must match the stored
       * version or the update is rejected with 409 rather than silently clobbering a concurrent
       * edit.
       */
      Long version) {}

  public record ServiceResponse(
      UUID id,
      String name,
      String description,
      Environment environment,
      String team,
      String repositoryUrl,
      String healthEndpoint,
      Criticality criticality,
      int expectedP95LatencyMs,
      BigDecimal expectedErrorRate,
      HealthStatus healthStatus,
      Instant healthChangedAt,
      long version,
      Instant createdAt,
      Instant updatedAt) {

    public static ServiceResponse from(ServiceEntity entity) {
      return new ServiceResponse(
          entity.getId(),
          entity.getName(),
          entity.getDescription(),
          entity.getEnvironment(),
          entity.getTeam(),
          entity.getRepositoryUrl(),
          entity.getHealthEndpoint(),
          entity.getCriticality(),
          entity.getExpectedP95LatencyMs(),
          entity.getExpectedErrorRate(),
          entity.getHealthStatus(),
          entity.getHealthChangedAt(),
          entity.getVersion(),
          entity.getCreatedAt(),
          entity.getUpdatedAt());
    }
  }
}
