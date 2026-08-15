package com.signalforge.registry.domain;

/**
 * How much a service failing matters.
 *
 * <p>Used to escalate the severity of an auto-created incident: the same 5% error rate on a
 * CRITICAL service and a LOW one should not page with the same urgency.
 */
public enum Criticality {
  LOW(0),
  MEDIUM(1),
  HIGH(2),
  CRITICAL(3);

  private final int escalationSteps;

  Criticality(int escalationSteps) {
    this.escalationSteps = escalationSteps;
  }

  /** How many severity notches a breach on this service is bumped by. */
  public int escalationSteps() {
    return escalationSteps;
  }
}
