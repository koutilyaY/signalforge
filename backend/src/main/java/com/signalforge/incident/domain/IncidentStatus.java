package com.signalforge.incident.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Incident lifecycle.
 *
 * <p>The allowed transitions are declared here rather than checked ad hoc at each call site, so
 * there is exactly one place to read to know what the state machine permits.
 *
 * <p>Two deliberate choices:
 *
 * <ul>
 *   <li>An incident can be resolved from any non-resolved state. Real incidents get closed as false
 *       positives from OPEN all the time, and forcing an engineer to walk the happy path first is
 *       the kind of tooling that gets worked around.
 *   <li>RESOLVED is terminal. Re-opening would make "time to resolve" ambiguous and lets one row
 *       represent two distinct outages. A recurrence is a new incident — the fingerprint index is
 *       scoped to non-resolved rows precisely so a new one can be created.
 * </ul>
 */
public enum IncidentStatus {
  OPEN,
  ACKNOWLEDGED,
  INVESTIGATING,
  MITIGATED,
  RESOLVED;

  public Set<IncidentStatus> allowedNext() {
    return switch (this) {
      case OPEN -> EnumSet.of(ACKNOWLEDGED, INVESTIGATING, MITIGATED, RESOLVED);
      case ACKNOWLEDGED -> EnumSet.of(INVESTIGATING, MITIGATED, RESOLVED);
      case INVESTIGATING -> EnumSet.of(MITIGATED, RESOLVED);
      case MITIGATED -> EnumSet.of(INVESTIGATING, RESOLVED);
      case RESOLVED -> EnumSet.noneOf(IncidentStatus.class);
    };
  }

  public boolean canTransitionTo(IncidentStatus target) {
    return allowedNext().contains(target);
  }

  public boolean isOpen() {
    return this != RESOLVED;
  }
}
