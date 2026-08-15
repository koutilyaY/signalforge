package com.signalforge.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.signalforge.telemetry.query.WindowStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for histogram percentile interpolation.
 *
 * <p>No Spring, no containers - this is arithmetic, and arithmetic deserves a test that runs in
 * microseconds. The interpolation is the one place where SignalForge trades accuracy for speed, so
 * the size and shape of that error needs to be pinned down rather than assumed.
 */
@DisplayName("WindowStats histogram percentiles")
class WindowStatsTest {

  /** Boundaries: 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, +Inf */
  private static WindowStats withBuckets(long... cumulative) {
    long total = cumulative[cumulative.length - 1];
    return new WindowStats(total, 0, 0, 0, 9999, cumulative);
  }

  @Nested
  @DisplayName("percentile interpolation")
  class Interpolation {

    @Test
    @DisplayName("returns zero for an empty window rather than NaN or an exception")
    void emptyWindow() {
      assertThat(WindowStats.empty().p95LatencyMs()).isZero();
      assertThat(WindowStats.empty().errorRate().doubleValue()).isZero();
    }

    @Test
    @DisplayName("every observation in one bucket puts the percentile inside that bucket")
    void singleBucket() {
      // 100 requests, all <= 100ms (so they appear in le_100 and every wider bucket).
      WindowStats stats = withBuckets(0, 0, 0, 0, 100, 100, 100, 100, 100, 100, 100);

      // Target rank 95 falls in the (50, 100] bucket, 95% of the way through it.
      assertThat(stats.p95LatencyMs()).isCloseTo(97.5, within(0.5));
    }

    @Test
    @DisplayName("interpolates linearly within the bucket that contains the target rank")
    void interpolatesWithinBucket() {
      // 90 requests <= 50ms, 10 more between 50 and 100ms. Total 100.
      WindowStats stats = withBuckets(0, 0, 0, 90, 100, 100, 100, 100, 100, 100, 100);

      // Rank 95 sits 5 observations into the 10 that fall in (50, 100],
      // so halfway: 50 + 0.5 * (100 - 50) = 75.
      assertThat(stats.p95LatencyMs()).isCloseTo(75.0, within(0.1));
    }

    @Test
    @DisplayName("p50, p95 and p99 are ordered")
    void percentilesAreOrdered() {
      WindowStats stats = withBuckets(10, 25, 50, 100, 200, 400, 700, 900, 980, 998, 1000);

      assertThat(stats.p50LatencyMs()).isLessThan(stats.p95LatencyMs());
      assertThat(stats.p95LatencyMs()).isLessThanOrEqualTo(stats.p99LatencyMs());
    }

    @Test
    @DisplayName("falls back to the observed maximum when the percentile lands in the +Inf bucket")
    void openEndedBucketFallsBackToMax() {
      // 90 requests under 5s, 10 above it - so p95 is in the unbounded bucket
      // where there is no upper boundary to interpolate against.
      long[] buckets = {0, 0, 0, 0, 0, 0, 0, 0, 0, 90, 100};
      WindowStats stats = new WindowStats(100, 0, 0, 0, 8123, buckets);

      // Reports a real measurement rather than extrapolating a number nobody observed.
      assertThat(stats.p95LatencyMs()).isEqualTo(8123.0);
    }

    @Test
    @DisplayName("a uniform distribution interpolates close to the true percentile")
    void boundedErrorAgainstKnownDistribution() {
      // 1000 requests spread uniformly over 0-1000ms. True p95 is 950ms.
      // Cumulative counts at each boundary are just the boundary value.
      long[] buckets = {5, 10, 25, 50, 100, 250, 500, 1000, 1000, 1000, 1000};
      WindowStats stats = new WindowStats(1000, 0, 0, 0, 1000, buckets);

      // The (500, 1000] bucket is 500ms wide, so this is the worst case for
      // interpolation error - and it still lands within that bucket's width.
      assertThat(stats.p95LatencyMs()).isBetween(900.0, 1000.0);
    }
  }

  @Nested
  @DisplayName("error rate")
  class ErrorRate {

    @Test
    @DisplayName("is errors over requests")
    void computesRatio() {
      WindowStats stats = new WindowStats(200, 10, 8, 20_000, 500, new long[11]);
      assertThat(stats.errorRate().doubleValue()).isCloseTo(0.05, within(0.0001));
    }

    @Test
    @DisplayName("is zero, not a division by zero, when there was no traffic")
    void handlesZeroTraffic() {
      WindowStats stats = new WindowStats(0, 0, 0, 0, 0, new long[11]);
      assertThat(stats.errorRate().doubleValue()).isZero();
      assertThat(stats.meanLatencyMs().doubleValue()).isZero();
    }
  }
}
