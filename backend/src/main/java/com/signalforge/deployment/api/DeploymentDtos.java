package com.signalforge.deployment.api;

import com.signalforge.deployment.domain.Deployment;
import com.signalforge.deployment.domain.DeploymentStatus;
import com.signalforge.registry.domain.Environment;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class DeploymentDtos {

  private DeploymentDtos() {}

  /**
   * Recorded by CI at rollout time. {@code startedAt} is optional and defaults to now, because the
   * common case is a CI step firing this the moment it begins.
   */
  public record RecordDeploymentRequest(
      @NotNull UUID serviceId,
      @NotNull @Size(max = 80) String version,
      @Size(max = 80) String commitSha,
      @Size(max = 200) String branch,
      @NotNull Environment environment,
      @Size(max = 200) String deployedBy,
      Instant startedAt) {}

  public record CompleteDeploymentRequest(@NotNull DeploymentStatus status, Instant completedAt) {}

  public record DeploymentResponse(
      UUID id,
      UUID serviceId,
      String version,
      String commitSha,
      String branch,
      Environment environment,
      DeploymentStatus status,
      String deployedBy,
      Instant startedAt,
      Instant completedAt,
      long durationSeconds) {

    public static DeploymentResponse from(Deployment deployment) {
      return new DeploymentResponse(
          deployment.getId(),
          deployment.getServiceId(),
          deployment.getVersion(),
          deployment.getCommitSha(),
          deployment.getBranch(),
          deployment.getEnvironment(),
          deployment.getStatus(),
          deployment.getDeployedBy(),
          deployment.getStartedAt(),
          deployment.getCompletedAt(),
          deployment.durationSoFar().toSeconds());
    }
  }
}
