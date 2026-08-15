package com.signalforge.detection.domain;

import com.signalforge.registry.domain.Criticality;

/** Severity of an alert or incident, ordered. */
public enum IncidentSeverity {
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL;

  /**
   * Raises this severity by the escalation weight of the affected service's criticality, capped at
   * CRITICAL.
   *
   * <p>The same 5% error rate is not equally urgent on a payment service and on an internal
   * reporting job. Encoding that here means the rule expresses "what is abnormal" and the service
   * registry expresses "how much it matters" — one rule can then cover a whole organization without
   * flattening that distinction.
   */
  public IncidentSeverity escalateFor(Criticality criticality) {
    int raised = Math.min(ordinal() + criticality.escalationSteps(), values().length - 1);
    return values()[raised];
  }

  public boolean atLeast(IncidentSeverity other) {
    return ordinal() >= other.ordinal();
  }
}
