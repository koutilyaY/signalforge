package com.signalforge.telemetry.query;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Aggregated telemetry for one service over one time window, read from {@code
 * telemetry_minute_rollups}.
 *
 * <p>The latency figures come from a cumulative histogram with fixed boundaries, so percentiles
 * here are <b>interpolated approximations</b>, computed the same way Prometheus' {@code
 * histogram_quantile} does. This is a deliberate accuracy-for-speed trade: an exact {@code
 * percentile_cont} over raw rows has to scan and sort every event in the window, which is the
 * difference between a dashboard that loads and one that times out.
 *
 * <p>The error is bounded by bucket width. Around the 500ms boundary, adjacent buckets are 250ms
 * and 500ms, so p95 is accurate to within a few hundred milliseconds at worst - fine for "did we
 * cross the SLA", and explicitly not fine for billing or for reporting a precise SLO number.
 */
public record WindowStats(
    long requestCount,
    long errorCount,
    long serverErrorCount,
    long latencySumMs,
    int latencyMaxMs,
    long[] cumulativeBuckets) {

  /** Must mirror {@code TelemetryWriter.LATENCY_BOUNDARIES}. */
  public static final int[] BOUNDARIES = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

  public static WindowStats empty() {
    return new WindowStats(0, 0, 0, 0, 0, new long[BOUNDARIES.length + 1]);
  }

  public boolean isEmpty() {
    return requestCount == 0 && errorCount == 0;
  }

  /** Failed requests over total requests. Returns zero rather than NaN for an empty window. */
  public BigDecimal errorRate() {
    if (requestCount == 0) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(errorCount)
        .divide(BigDecimal.valueOf(requestCount), 6, RoundingMode.HALF_UP);
  }

  public BigDecimal meanLatencyMs() {
    if (requestCount == 0) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(latencySumMs)
        .divide(BigDecimal.valueOf(requestCount), 2, RoundingMode.HALF_UP);
  }

  public double p95LatencyMs() {
    return percentile(0.95);
  }

  public double p99LatencyMs() {
    return percentile(0.99);
  }

  public double p50LatencyMs() {
    return percentile(0.50);
  }

  /**
   * Interpolates a percentile from the cumulative histogram.
   *
   * @param quantile between 0 and 1
   */
  public double percentile(double quantile) {
    long total = cumulativeBuckets[cumulativeBuckets.length - 1];
    if (total == 0) {
      return 0;
    }

    double targetRank = quantile * total;
    long countBelow = 0;
    int lowerBoundary = 0;

    for (int i = 0; i < BOUNDARIES.length; i++) {
      long cumulative = cumulativeBuckets[i];
      if (cumulative >= targetRank) {
        long inBucket = cumulative - countBelow;
        if (inBucket <= 0) {
          return lowerBoundary;
        }
        double fraction = (targetRank - countBelow) / inBucket;
        return lowerBoundary + fraction * (BOUNDARIES[i] - lowerBoundary);
      }
      countBelow = cumulative;
      lowerBoundary = BOUNDARIES[i];
    }

    // The percentile falls in the open-ended +Inf bucket, where there is no upper
    // boundary to interpolate against. Report the largest latency actually
    // observed: it is a real measurement rather than a fabricated extrapolation,
    // and it is the honest answer to "how bad did it get".
    return latencyMaxMs;
  }
}
