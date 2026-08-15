package com.signalforge.telemetry.domain;

/**
 * The telemetry vocabulary. Kept small and closed on purpose: a free-text event type would make
 * every detection rule a string comparison against values nobody validates, and the CHECK
 * constraint in {@code telemetry_events} would be impossible to write.
 */
public enum EventType {
  /**
   * A served HTTP request. Carries latency and status code; drives latency and error-rate rules.
   */
  HTTP_REQUEST(true),
  /** An unhandled or logged application-level error. */
  APPLICATION_ERROR(false),
  /** A database failure: timeout, connection refused, deadlock, constraint blowup. */
  DATABASE_ERROR(false),
  /** Consumer lag sample for a Kafka consumer group. Carries {@code consumerLag}. */
  KAFKA_LAG(false),
  /** A health check went from passing to failing. */
  SERVICE_DOWN(false),
  /** A health check recovered. */
  SERVICE_RECOVERED(false),
  DEPLOYMENT_STARTED(false),
  DEPLOYMENT_COMPLETED(false);

  private final boolean countsTowardTraffic;

  EventType(boolean countsTowardTraffic) {
    this.countsTowardTraffic = countsTowardTraffic;
  }

  /**
   * Whether this event contributes to the request-count denominator.
   *
   * <p>This distinction is what stops error rate from being nonsense. If APPLICATION_ERROR events
   * counted as requests, a service emitting one error per request would show a 50% error rate
   * instead of 100%.
   */
  public boolean countsTowardTraffic() {
    return countsTowardTraffic;
  }

  public boolean isError() {
    return this == APPLICATION_ERROR || this == DATABASE_ERROR;
  }
}
