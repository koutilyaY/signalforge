package com.signalforge.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.signalforge.messaging.TelemetryEventMessage;
import com.signalforge.platform.tenant.TenantBinder;
import com.signalforge.support.AbstractIntegrationTest;
import com.signalforge.support.TenantFixture;
import com.signalforge.telemetry.domain.EventType;
import com.signalforge.telemetry.domain.Severity;
import com.signalforge.telemetry.persistence.TelemetryWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves that redelivering a telemetry record produces the same end state.
 *
 * <p>This is the test behind the claim that SignalForge tolerates at-least-once delivery. It
 * exercises the real guarantee - the {@code (organization_id, event_id)} unique index and {@code ON
 * CONFLICT DO NOTHING} - against real PostgreSQL, because the behaviour under test is the
 * database's, not the application's.
 */
@DisplayName("Telemetry idempotency under duplicate delivery")
class TelemetryIdempotencyIT extends AbstractIntegrationTest {

  @Autowired private TelemetryWriter writer;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TenantFixture tenantFixture;
  @Autowired private TenantBinder tenantBinder;

  private TenantFixture.Tenant tenant;
  private Instant occurredAt;

  @BeforeEach
  void setUp() {
    tenant = tenantFixture.createTenant("idem");
    occurredAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  }

  @Test
  @DisplayName("the same batch delivered twice inserts each event exactly once")
  void duplicateBatchIsSuppressed() {
    List<TelemetryEventMessage> batch = batchOf(25);

    int firstDelivery = write(batch);
    int secondDelivery = write(batch);

    assertThat(firstDelivery).as("first delivery persists everything").isEqualTo(25);
    assertThat(secondDelivery).as("redelivery persists nothing").isZero();
    assertThat(countEvents()).isEqualTo(25);
  }

  @Test
  @DisplayName("a partially overlapping redelivery inserts only the new events")
  void partialOverlapInsertsOnlyNewEvents() {
    List<TelemetryEventMessage> first = batchOf(10);
    write(first);

    // Simulates a consumer restarting mid-batch: it replays from an earlier
    // offset, so it re-sends 5 already-applied records plus 5 new ones.
    List<TelemetryEventMessage> overlapping =
        java.util.stream.Stream.concat(first.subList(5, 10).stream(), batchOf(5).stream()).toList();

    int inserted = write(overlapping);

    assertThat(inserted).as("only the 5 genuinely new events are written").isEqualTo(5);
    assertThat(countEvents()).isEqualTo(15);
  }

  @Test
  @DisplayName("rollup counters are not double counted on redelivery")
  void rollupsAreNotDoubleCounted() {
    // This is the assertion that actually matters for correctness of the
    // product. Suppressing the duplicate row is useless if the aggregate the
    // dashboard reads has still been incremented twice - the service would
    // appear to be serving twice its real traffic.
    List<TelemetryEventMessage> batch = batchOf(20);

    write(batch);
    long afterFirst = rollupRequestCount();

    write(batch);
    long afterSecond = rollupRequestCount();

    assertThat(afterFirst).isEqualTo(20);
    assertThat(afterSecond)
        .as("redelivery must not inflate the rollup the dashboard reads")
        .isEqualTo(20);
  }

  @Test
  @DisplayName("concurrent delivery of the same batch on three threads still inserts once")
  void concurrentDuplicateDeliveryIsSafe() throws Exception {
    // The consumer runs with concurrency=3. Two threads can be handed the same
    // record after a rebalance, so the guarantee has to hold under a genuine
    // race, not just sequential redelivery.
    List<TelemetryEventMessage> batch = batchOf(50);

    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<Integer>> futures =
          IntStream.range(0, 3)
              .mapToObj(i -> pool.submit(() -> write(batch)))
              .map(f -> (Future<Integer>) f)
              .toList();

      int total = 0;
      for (Future<Integer> future : futures) {
        total += future.get(30, TimeUnit.SECONDS);
      }

      assertThat(total).as("across all three threads exactly 50 rows are inserted").isEqualTo(50);
      assertThat(countEvents()).isEqualTo(50);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("the same event id in a different organization is a different event")
  void eventIdIsScopedToOrganization() {
    // The unique constraint is (organization_id, event_id), not event_id alone.
    // Two tenants generating the same UUID must not collide - one tenant could
    // otherwise suppress another's telemetry.
    TenantFixture.Tenant other = tenantFixture.createTenant("idem-other");
    UUID sharedEventId = UUID.randomUUID();

    tenantBinder.runAs(
        tenant.organizationId(),
        () ->
            writer.write(
                List.of(event(sharedEventId, tenant.organizationId(), tenant.serviceId()))));
    tenantBinder.runAs(
        other.organizationId(),
        () ->
            writer.write(List.of(event(sharedEventId, other.organizationId(), other.serviceId()))));

    assertThat(countEventsFor(tenant.organizationId())).isEqualTo(1);
    assertThat(countEventsFor(other.organizationId())).isEqualTo(1);
  }

  /** All writes go through a bound tenant - RLS applies to fixtures too. */
  private int write(List<TelemetryEventMessage> batch) {
    return tenantBinder.callAs(tenant.organizationId(), () -> writer.write(batch));
  }

  // ---- helpers ---------------------------------------------------------------

  private List<TelemetryEventMessage> batchOf(int size) {
    return IntStream.range(0, size)
        .mapToObj(i -> event(UUID.randomUUID(), tenant.organizationId(), tenant.serviceId()))
        .toList();
  }

  private TelemetryEventMessage event(UUID eventId, UUID organizationId, UUID serviceId) {
    return new TelemetryEventMessage(
        eventId,
        organizationId,
        serviceId,
        occurredAt,
        EventType.HTTP_REQUEST,
        Severity.INFO,
        "trace-" + eventId,
        null,
        null,
        "GET",
        "/checkout",
        200,
        120,
        null,
        null,
        "instance-1",
        null,
        Map.of("region", "us-east-1"),
        Instant.now());
  }

  private long countEvents() {
    return countEventsFor(tenant.organizationId());
  }

  private long countEventsFor(UUID organizationId) {
    // Bound explicitly: row-level security applies to raw JDBC in a test exactly
    // as it does to the application, which is the point of having it.
    Long count =
        tenantBinder.callAs(
            organizationId,
            () ->
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM telemetry_events WHERE organization_id = ?",
                    Long.class,
                    organizationId));
    return count == null ? 0 : count;
  }

  private long rollupRequestCount() {
    Long count =
        tenantBinder.callAs(
            tenant.organizationId(),
            () ->
                jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(request_count), 0) FROM telemetry_minute_rollups WHERE organization_id = ?",
                    Long.class,
                    tenant.organizationId()));
    return count == null ? 0 : count;
  }
}
