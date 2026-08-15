package com.signalforge.telemetry.query;

import com.signalforge.telemetry.domain.EventType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-side queries for the detection engine.
 *
 * <p>Plain JDBC rather than JPA: these return aggregates, not entities, and there is nothing for an
 * identity map or dirty-checking to do. Every method is tenant-scoped by an explicit {@code
 * organization_id} predicate — see ADR-0003.
 */
@Repository
public class TelemetryQueryRepository {

  private static final String WINDOW_STATS_SQL =
      """
      SELECT COALESCE(SUM(request_count), 0)      AS request_count,
             COALESCE(SUM(error_count), 0)        AS error_count,
             COALESCE(SUM(server_error_count), 0) AS server_error_count,
             COALESCE(SUM(latency_sum_ms), 0)     AS latency_sum_ms,
             COALESCE(MAX(latency_max_ms), 0)     AS latency_max_ms,
             COALESCE(SUM(le_5), 0)    AS le_5,
             COALESCE(SUM(le_10), 0)   AS le_10,
             COALESCE(SUM(le_25), 0)   AS le_25,
             COALESCE(SUM(le_50), 0)   AS le_50,
             COALESCE(SUM(le_100), 0)  AS le_100,
             COALESCE(SUM(le_250), 0)  AS le_250,
             COALESCE(SUM(le_500), 0)  AS le_500,
             COALESCE(SUM(le_1000), 0) AS le_1000,
             COALESCE(SUM(le_2500), 0) AS le_2500,
             COALESCE(SUM(le_5000), 0) AS le_5000,
             COALESCE(SUM(le_inf), 0)  AS le_inf
      FROM telemetry_minute_rollups
      WHERE organization_id = ?
        AND service_id = ?
        AND bucket_start >= ?
        AND bucket_start < ?
      """;

  private final JdbcTemplate jdbcTemplate;

  public TelemetryQueryRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Aggregated traffic and latency for one service over [from, to). */
  public WindowStats windowStats(UUID organizationId, UUID serviceId, Instant from, Instant to) {
    List<WindowStats> rows =
        jdbcTemplate.query(
            WINDOW_STATS_SQL,
            (rs, rowNum) -> {
              long[] buckets = new long[WindowStats.BOUNDARIES.length + 1];
              buckets[0] = rs.getLong("le_5");
              buckets[1] = rs.getLong("le_10");
              buckets[2] = rs.getLong("le_25");
              buckets[3] = rs.getLong("le_50");
              buckets[4] = rs.getLong("le_100");
              buckets[5] = rs.getLong("le_250");
              buckets[6] = rs.getLong("le_500");
              buckets[7] = rs.getLong("le_1000");
              buckets[8] = rs.getLong("le_2500");
              buckets[9] = rs.getLong("le_5000");
              buckets[10] = rs.getLong("le_inf");
              return new WindowStats(
                  rs.getLong("request_count"),
                  rs.getLong("error_count"),
                  rs.getLong("server_error_count"),
                  rs.getLong("latency_sum_ms"),
                  rs.getInt("latency_max_ms"),
                  buckets);
            },
            organizationId,
            serviceId,
            Timestamp.from(from),
            Timestamp.from(to));

    return rows.isEmpty() ? WindowStats.empty() : rows.get(0);
  }

  /** Number of events of one type for a service in [from, to). */
  public long countEvents(
      UUID organizationId, UUID serviceId, EventType eventType, Instant from, Instant to) {
    Long count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM telemetry_events
            WHERE organization_id = ? AND service_id = ? AND event_type = ?
              AND occurred_at >= ? AND occurred_at < ?
            """,
            Long.class,
            organizationId,
            serviceId,
            eventType.name(),
            Timestamp.from(from),
            Timestamp.from(to));
    return count == null ? 0 : count;
  }

  /** Highest consumer lag reported for a service in [from, to). */
  public long maxConsumerLag(UUID organizationId, UUID serviceId, Instant from, Instant to) {
    Long lag =
        jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(consumer_lag), 0) FROM telemetry_events
            WHERE organization_id = ? AND service_id = ? AND event_type = 'KAFKA_LAG'
              AND occurred_at >= ? AND occurred_at < ?
            """,
            Long.class,
            organizationId,
            serviceId,
            Timestamp.from(from),
            Timestamp.from(to));
    return lag == null ? 0 : lag;
  }

  /**
   * Other services that reported an error sharing a trace id with this service's errors in the
   * window.
   *
   * <p>This is the cheap, deterministic core of failure correlation: if checkout-service and
   * payment-service both errored inside the same distributed trace, they are involved in the same
   * failure, and no statistics are needed to say so.
   */
  public List<UUID> correlatedFailingServices(
      UUID organizationId, UUID serviceId, Instant from, Instant to, int limit) {
    return jdbcTemplate.query(
        """
        SELECT DISTINCT other.service_id
        FROM telemetry_events self
        JOIN telemetry_events other
          ON other.organization_id = self.organization_id
         AND other.trace_id = self.trace_id
         AND other.service_id <> self.service_id
        WHERE self.organization_id = ?
          AND self.service_id = ?
          AND self.trace_id IS NOT NULL
          AND self.occurred_at >= ? AND self.occurred_at < ?
          AND (self.severity IN ('ERROR','CRITICAL') OR self.status_code >= 500)
          AND other.occurred_at >= ? AND other.occurred_at < ?
          AND (other.severity IN ('ERROR','CRITICAL') OR other.status_code >= 500)
        LIMIT ?
        """,
        (rs, rowNum) -> (UUID) rs.getObject(1),
        organizationId,
        serviceId,
        Timestamp.from(from),
        Timestamp.from(to),
        Timestamp.from(from),
        Timestamp.from(to),
        limit);
  }

  /** Distinct trace ids involved in this service's errors, for incident evidence. */
  public List<String> failingTraceIds(
      UUID organizationId, UUID serviceId, Instant from, Instant to, int limit) {
    return jdbcTemplate.query(
        """
        SELECT DISTINCT trace_id FROM telemetry_events
        WHERE organization_id = ? AND service_id = ?
          AND trace_id IS NOT NULL
          AND occurred_at >= ? AND occurred_at < ?
          AND (severity IN ('ERROR','CRITICAL') OR status_code >= 500)
        LIMIT ?
        """,
        (rs, rowNum) -> rs.getString(1),
        organizationId,
        serviceId,
        Timestamp.from(from),
        Timestamp.from(to),
        limit);
  }

  /** Most frequent error signatures for a service in the window. */
  public List<ErrorSignature> topErrorSignatures(
      UUID organizationId, UUID serviceId, Instant from, Instant to, int limit) {
    return jdbcTemplate.query(
        """
        SELECT error_type, COUNT(*) AS occurrences
        FROM telemetry_events
        WHERE organization_id = ? AND service_id = ?
          AND error_type IS NOT NULL
          AND occurred_at >= ? AND occurred_at < ?
        GROUP BY error_type
        ORDER BY occurrences DESC
        LIMIT ?
        """,
        (rs, rowNum) -> new ErrorSignature(rs.getString("error_type"), rs.getLong("occurrences")),
        organizationId,
        serviceId,
        Timestamp.from(from),
        Timestamp.from(to),
        limit);
  }

  /** True when the service reported nothing at all in the window. */
  public boolean hasTelemetry(UUID organizationId, UUID serviceId, Instant from, Instant to) {
    Boolean exists =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM telemetry_events
              WHERE organization_id = ? AND service_id = ?
                AND occurred_at >= ? AND occurred_at < ?
            )
            """,
            Boolean.class,
            organizationId,
            serviceId,
            Timestamp.from(from),
            Timestamp.from(to));
    return Boolean.TRUE.equals(exists);
  }

  /**
   * Timestamp of the most recent event for a service inside a window.
   *
   * <p>This is what makes detection latency measurable: an incident's latency is {@code detected_at
   * - this}, i.e. how long after the data existed did we react.
   */
  public Instant latestEventAt(UUID organizationId, UUID serviceId, Instant from, Instant to) {
    return jdbcTemplate.queryForObject(
        """
        SELECT MAX(occurred_at) FROM telemetry_events
        WHERE organization_id = ? AND service_id = ?
          AND occurred_at >= ? AND occurred_at < ?
        """,
        Instant.class,
        organizationId,
        serviceId,
        Timestamp.from(from),
        Timestamp.from(to));
  }

  public record ErrorSignature(String errorType, long occurrences) {}
}
