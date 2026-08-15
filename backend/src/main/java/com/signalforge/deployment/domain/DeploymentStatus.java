package com.signalforge.deployment.domain;

/** Lifecycle of a deployment. */
public enum DeploymentStatus {
  IN_PROGRESS,
  SUCCEEDED,
  FAILED,
  ROLLED_BACK;

  /**
   * Whether a deployment in this state is a plausible cause of a later incident.
   *
   * <p>A FAILED deployment is if anything <em>more</em> suspicious than a successful one — a
   * half-applied rollout is a classic cause of partial outages. Only ROLLED_BACK is excluded, and
   * even then the rollback itself gets surfaced separately on the timeline.
   */
  public boolean isCorrelationCandidate() {
    return this != ROLLED_BACK;
  }

  public boolean isTerminal() {
    return this != IN_PROGRESS;
  }
}
