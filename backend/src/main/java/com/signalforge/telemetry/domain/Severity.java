package com.signalforge.telemetry.domain;

/** Severity shared by telemetry events, alerts and incidents. */
public enum Severity {
  DEBUG(0),
  INFO(1),
  WARN(2),
  ERROR(3),
  CRITICAL(4);

  private final int weight;

  Severity(int weight) {
    this.weight = weight;
  }

  public int weight() {
    return weight;
  }

  public boolean atLeast(Severity other) {
    return this.weight >= other.weight;
  }
}
