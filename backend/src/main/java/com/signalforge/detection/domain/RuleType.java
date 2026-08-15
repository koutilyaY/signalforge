package com.signalforge.detection.domain;

/**
 * What a detection rule measures.
 *
 * <p>Each type declares where its measurement comes from, because that determines whether the
 * evaluator can read the cheap pre-aggregated rollups or has to touch raw telemetry.
 */
public enum RuleType {

  /** Ratio of failed requests to total requests in the window. Read from rollups. */
  ERROR_RATE(Source.ROLLUP, "error rate", "%"),

  /**
   * 95th percentile latency in milliseconds, interpolated from the rollup histogram. Read from
   * rollups.
   */
  P95_LATENCY(Source.ROLLUP, "p95 latency", "ms"),

  /** Count of SERVICE_DOWN events. Read from raw telemetry - these are rare and not aggregated. */
  SERVICE_DOWN(Source.RAW_EVENT_COUNT, "service-down events", ""),

  /** Maximum observed consumer lag across the window. Read from raw telemetry. */
  KAFKA_LAG(Source.RAW_MAX, "consumer lag", " messages"),

  /** Count of DATABASE_ERROR events. Read from raw telemetry. */
  DATABASE_ERROR_SPIKE(Source.RAW_EVENT_COUNT, "database errors", ""),

  /**
   * Count of distinct other services reporting errors that share a trace id with this service's
   * errors. Read from raw telemetry.
   */
  CORRELATED_ERRORS(Source.RAW_CORRELATION, "correlated failing services", "");

  public enum Source {
    ROLLUP,
    RAW_EVENT_COUNT,
    RAW_MAX,
    RAW_CORRELATION
  }

  private final Source source;
  private final String label;
  private final String unit;

  RuleType(Source source, String label, String unit) {
    this.source = source;
    this.label = label;
    this.unit = unit;
  }

  public Source source() {
    return source;
  }

  public String label() {
    return label;
  }

  public String unit() {
    return unit;
  }

  /**
   * Whether {@code min_sample_size} is meaningful for this rule.
   *
   * <p>It is only meaningful for ratios. A 100% error rate computed from one request is noise, so
   * ERROR_RATE needs a floor. A single SERVICE_DOWN event, by contrast, is exactly the signal - it
   * would be perverse to require twenty of them before reacting.
   */
  public boolean requiresMinimumSample() {
    return this == ERROR_RATE || this == P95_LATENCY;
  }
}
