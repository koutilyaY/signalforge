package com.signalforge.telemetry.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalforge.messaging.PermanentMessageException;
import com.signalforge.messaging.TelemetryEventMessage;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable write path for telemetry.
 *
 * <p>Uses JDBC batching rather than JPA. This is a deliberate departure from the ORM used
 * everywhere else, and the reason is measured rather than assumed: persisting 500 events through
 * {@code EntityManager} means 500 managed entities, 500 dirty-checks and a first-level cache that
 * grows for no benefit, because none of these rows is ever read back in the same transaction.
 * Telemetry is write-once, read-by-aggregate; the ORM earns its keep on incidents and services, not
 * here.
 *
 * <p><b>Idempotency.</b> The INSERT carries {@code ON CONFLICT (organization_id, event_id) DO
 * NOTHING}. Combined with the producer-supplied event id, a message delivered twice - which
 * at-least-once delivery guarantees will happen - writes the row once. The database enforces this,
 * not application logic that races with itself across three consumer threads.
 */
@Component
public class TelemetryWriter {

  private static final Logger log = LoggerFactory.getLogger(TelemetryWriter.class);

  private static final String INSERT_EVENT_PREFIX =
      """
      INSERT INTO telemetry_events (
          event_id, organization_id, service_id, occurred_at, received_at,
          event_type, severity, trace_id, span_id, correlation_id,
          http_method, http_path, status_code, latency_ms,
          error_type, error_message, instance_key, consumer_lag, metadata)
      VALUES
      """;

  private static final String INSERT_EVENT_ROW =
      "(?, ?, ?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))";

  /**
   * {@code RETURNING} is the crux of correct aggregation. Rollups must be computed from the rows
   * that were <em>actually inserted</em>, not from the input batch - otherwise a redelivered batch
   * has its rows suppressed by ON CONFLICT while still incrementing the aggregate the dashboard
   * reads, and a service appears to serve twice its real traffic. Covered by {@code
   * TelemetryIdempotencyIT.rollupsAreNotDoubleCounted}.
   */
  private static final String INSERT_EVENT_SUFFIX =
      " ON CONFLICT (organization_id, event_id) DO NOTHING RETURNING event_id";

  /**
   * Rows per statement. PostgreSQL caps bind parameters at 65535; at 18 parameters per row, 200
   * rows is ~3600 - comfortably clear, while still amortising the round trip.
   */
  private static final int INSERT_CHUNK_SIZE = 200;

  /**
   * Rollup upsert. The per-minute aggregate is maintained incrementally as events arrive rather
   * than recomputed on read - see docs/benchmarks/query-optimization.md for the measured difference
   * against percentile_cont over raw rows.
   */
  private static final String UPSERT_ROLLUP =
      """
      INSERT INTO telemetry_minute_rollups (
          organization_id, service_id, bucket_start,
          request_count, error_count, server_error_count,
          latency_sum_ms, latency_max_ms,
          le_5, le_10, le_25, le_50, le_100, le_250, le_500, le_1000, le_2500, le_5000, le_inf)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (organization_id, service_id, bucket_start) DO UPDATE SET
          request_count      = telemetry_minute_rollups.request_count + EXCLUDED.request_count,
          error_count        = telemetry_minute_rollups.error_count + EXCLUDED.error_count,
          server_error_count = telemetry_minute_rollups.server_error_count + EXCLUDED.server_error_count,
          latency_sum_ms     = telemetry_minute_rollups.latency_sum_ms + EXCLUDED.latency_sum_ms,
          latency_max_ms     = GREATEST(telemetry_minute_rollups.latency_max_ms, EXCLUDED.latency_max_ms),
          le_5    = telemetry_minute_rollups.le_5    + EXCLUDED.le_5,
          le_10   = telemetry_minute_rollups.le_10   + EXCLUDED.le_10,
          le_25   = telemetry_minute_rollups.le_25   + EXCLUDED.le_25,
          le_50   = telemetry_minute_rollups.le_50   + EXCLUDED.le_50,
          le_100  = telemetry_minute_rollups.le_100  + EXCLUDED.le_100,
          le_250  = telemetry_minute_rollups.le_250  + EXCLUDED.le_250,
          le_500  = telemetry_minute_rollups.le_500  + EXCLUDED.le_500,
          le_1000 = telemetry_minute_rollups.le_1000 + EXCLUDED.le_1000,
          le_2500 = telemetry_minute_rollups.le_2500 + EXCLUDED.le_2500,
          le_5000 = telemetry_minute_rollups.le_5000 + EXCLUDED.le_5000,
          le_inf  = telemetry_minute_rollups.le_inf  + EXCLUDED.le_inf,
          updated_at = now()
      """;

  /** Cumulative histogram boundaries in milliseconds. Mirrors the le_* columns. */
  static final int[] LATENCY_BOUNDARIES = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public TelemetryWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Persists a batch and updates the affected rollup buckets, in one transaction.
   *
   * @return how many event rows were actually inserted; the difference from {@code batch.size()} is
   *     the number of duplicates suppressed, which the caller reports as a metric.
   */
  @Transactional
  public int write(List<TelemetryEventMessage> batch) {
    if (batch.isEmpty()) {
      return 0;
    }

    // Collapse duplicates inside the batch itself before touching the database.
    // A batch containing the same event id twice would otherwise have its second
    // copy silently dropped by ON CONFLICT, which is correct, but it is cheaper
    // and clearer to notice it here.
    Map<UUID, TelemetryEventMessage> deduplicated = new LinkedHashMap<>(batch.size());
    for (TelemetryEventMessage message : batch) {
      deduplicated.putIfAbsent(message.eventId(), message);
    }
    List<TelemetryEventMessage> candidates = List.copyOf(deduplicated.values());

    Set<UUID> insertedIds = new HashSet<>(candidates.size());
    for (int start = 0; start < candidates.size(); start += INSERT_CHUNK_SIZE) {
      int end = Math.min(start + INSERT_CHUNK_SIZE, candidates.size());
      insertedIds.addAll(insertChunk(candidates.subList(start, end)));
    }

    // Aggregate only what was genuinely new. This is what makes redelivery a
    // no-op end to end rather than merely at the row level.
    List<TelemetryEventMessage> newlyPersisted =
        candidates.stream().filter(m -> insertedIds.contains(m.eventId())).toList();

    updateRollups(newlyPersisted);
    return newlyPersisted.size();
  }

  /**
   * One multi-row INSERT ... ON CONFLICT DO NOTHING RETURNING; returns the ids actually written.
   */
  private Set<UUID> insertChunk(List<TelemetryEventMessage> chunk) {
    StringBuilder sql = new StringBuilder(INSERT_EVENT_PREFIX);
    for (int i = 0; i < chunk.size(); i++) {
      sql.append(i == 0 ? "" : ",").append(INSERT_EVENT_ROW);
    }
    sql.append(INSERT_EVENT_SUFFIX);

    List<UUID> returned =
        jdbcTemplate.query(
            sql.toString(),
            (PreparedStatement ps) -> {
              int p = 1;
              for (TelemetryEventMessage m : chunk) {
                ps.setObject(p++, m.eventId());
                ps.setObject(p++, m.organizationId());
                ps.setObject(p++, m.serviceId());
                ps.setTimestamp(p++, Timestamp.from(m.occurredAt()));
                ps.setString(p++, m.eventType().name());
                ps.setString(p++, m.severity().name());
                p = setNullable(ps, p, m.traceId());
                p = setNullable(ps, p, m.spanId());
                p = setNullable(ps, p, m.correlationId());
                p = setNullable(ps, p, m.httpMethod());
                p = setNullable(ps, p, m.httpPath());
                p = setNullableInt(ps, p, m.statusCode());
                p = setNullableInt(ps, p, m.latencyMs());
                p = setNullable(ps, p, m.errorType());
                p = setNullable(ps, p, truncate(m.errorMessage(), 2000));
                p = setNullable(ps, p, m.instanceKey());
                if (m.consumerLag() == null) {
                  ps.setNull(p++, Types.BIGINT);
                } else {
                  ps.setLong(p++, m.consumerLag());
                }
                // Bound as text and CAST to jsonb in SQL, so the JDBC driver
                // stays a runtime-scope dependency rather than leaking
                // org.postgresql.util.PGobject onto the compile classpath.
                ps.setString(p++, toJsonb(m.metadata()));
              }
            },
            (rs, rowNum) -> (UUID) rs.getObject("event_id"));

    return new HashSet<>(returned);
  }

  /**
   * Aggregates the batch in memory first, so a 500-event batch touching 3 minute-buckets issues 3
   * upserts rather than 500. This is the single biggest win in the write path.
   */
  private void updateRollups(List<TelemetryEventMessage> batch) {
    Map<RollupKey, RollupAccumulator> buckets = new HashMap<>();

    for (TelemetryEventMessage m : batch) {
      if (!m.eventType().countsTowardTraffic() && !m.eventType().isError()) {
        continue; // deployment/health signals do not belong in traffic aggregates
      }
      Instant bucket = m.occurredAt().truncatedTo(ChronoUnit.MINUTES);
      RollupKey key = new RollupKey(m.organizationId(), m.serviceId(), bucket);
      buckets.computeIfAbsent(key, k -> new RollupAccumulator()).add(m);
    }

    if (buckets.isEmpty()) {
      return;
    }

    List<Map.Entry<RollupKey, RollupAccumulator>> entries = List.copyOf(buckets.entrySet());
    jdbcTemplate.batchUpdate(
        UPSERT_ROLLUP,
        entries,
        entries.size(),
        (PreparedStatement ps, Map.Entry<RollupKey, RollupAccumulator> entry) -> {
          RollupKey key = entry.getKey();
          RollupAccumulator acc = entry.getValue();
          ps.setObject(1, key.organizationId());
          ps.setObject(2, key.serviceId());
          ps.setTimestamp(3, Timestamp.from(key.bucketStart()));
          ps.setLong(4, acc.requestCount);
          ps.setLong(5, acc.errorCount);
          ps.setLong(6, acc.serverErrorCount);
          ps.setLong(7, acc.latencySum);
          ps.setInt(8, acc.latencyMax);
          for (int i = 0; i < acc.cumulative.length; i++) {
            ps.setLong(9 + i, acc.cumulative[i]);
          }
        });
  }

  /** Binds a possibly-null string and returns the next parameter index. */
  private static int setNullable(PreparedStatement ps, int index, String value)
      throws java.sql.SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
    return index + 1;
  }

  private static int setNullableInt(PreparedStatement ps, int index, Integer value)
      throws java.sql.SQLException {
    if (value == null) {
      ps.setNull(index, Types.INTEGER);
    } else {
      ps.setInt(index, value);
    }
    return index + 1;
  }

  private static String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  private String toJsonb(Map<String, Object> metadata) {
    try {
      return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
    } catch (JsonProcessingException e) {
      // Unserialisable metadata will never become serialisable on retry, so this
      // is permanent by definition and must not be retried.
      throw new PermanentMessageException("Event metadata could not be serialised to jsonb", e);
    }
  }

  private record RollupKey(UUID organizationId, UUID serviceId, Instant bucketStart) {}

  /** Mutable per-bucket accumulator. Not thread safe; used only within one write() call. */
  private static final class RollupAccumulator {
    long requestCount;
    long errorCount;
    long serverErrorCount;
    long latencySum;
    int latencyMax;

    /** 11 counters: one per boundary plus +Inf. Cumulative, so le_10 includes everything <= 5. */
    final long[] cumulative = new long[LATENCY_BOUNDARIES.length + 1];

    void add(TelemetryEventMessage m) {
      if (m.eventType().countsTowardTraffic()) {
        requestCount++;
        if (m.statusCode() != null && m.statusCode() >= 500) {
          serverErrorCount++;
          errorCount++;
        } else if (m.statusCode() != null && m.statusCode() >= 400) {
          errorCount++;
        }
        if (m.latencyMs() != null) {
          latencySum += m.latencyMs();
          latencyMax = Math.max(latencyMax, m.latencyMs());
          recordLatency(m.latencyMs());
        }
      } else if (m.eventType().isError()) {
        errorCount++;
      }
    }

    private void recordLatency(int latencyMs) {
      // Cumulative: increment this bucket and every wider one.
      for (int i = 0; i < LATENCY_BOUNDARIES.length; i++) {
        if (latencyMs <= LATENCY_BOUNDARIES[i]) {
          for (int j = i; j < cumulative.length; j++) {
            cumulative[j]++;
          }
          return;
        }
      }
      cumulative[cumulative.length - 1]++; // +Inf only
    }
  }
}
