package com.signalforge.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signalforge.platform.tenant.TenantBinder;
import com.signalforge.support.AbstractIntegrationTest;
import com.signalforge.support.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The whole pipeline, for real: HTTP -> validation -> Kafka -> consumer -> PostgreSQL.
 *
 * <p>Every hop is genuine. The broker is a real Kafka in KRaft mode, the consumer is the same
 * {@code @KafkaListener} that runs in production, and the assertions read the actual rows and
 * rollups. Nothing here is mocked, which is the only way this test can tell you the wiring works.
 *
 * <p>Assertions poll rather than sleep - ingestion is asynchronous by design, so a fixed sleep
 * would either be flaky or slow, and usually both.
 */
@DisplayName("End-to-end telemetry pipeline")
class PipelineEndToEndIT extends AbstractIntegrationTest {

  private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(45);

  @Autowired private TenantFixture tenantFixture;
  @Autowired private TenantBinder tenantBinder;
  @Autowired private JdbcTemplate jdbcTemplate;

  private TenantFixture.Tenant tenant;

  @BeforeEach
  void setUp() {
    tenant = tenantFixture.createTenant("e2e");
  }

  @Test
  @DisplayName("events posted to the API arrive in PostgreSQL via Kafka")
  void eventsFlowThroughTheWholePipeline() throws Exception {
    int eventCount = 40;

    mockMvc
        .perform(
            post("/api/v1/ingest/events")
                .header("Authorization", tenant.engineer().authorizationHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(batchJson(eventCount)))
        .andExpect(status().isAccepted());

    Awaitility.await("telemetry to be persisted")
        .atMost(PIPELINE_TIMEOUT)
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(() -> assertThat(persistedCount()).isEqualTo(eventCount));

    // The rollup the dashboard reads must agree with the raw rows.
    assertThat(rollupRequestCount())
        .as("per-minute rollup must match the raw event count")
        .isEqualTo(eventCount);
  }

  @Test
  @DisplayName("republishing the same events does not duplicate rows or inflate rollups")
  void replayIsIdempotentThroughTheRealBroker() throws Exception {
    String body = batchJson(15);

    // Post the identical body twice. Same event ids, so the second POST is
    // exactly what a client retry after a timeout looks like.
    mockMvc
        .perform(
            post("/api/v1/ingest/events")
                .header("Authorization", tenant.engineer().authorizationHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted());

    Awaitility.await()
        .atMost(PIPELINE_TIMEOUT)
        .untilAsserted(() -> assertThat(persistedCount()).isEqualTo(15));

    mockMvc
        .perform(
            post("/api/v1/ingest/events")
                .header("Authorization", tenant.engineer().authorizationHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted());

    // Give the consumer time to actually process the replay, then assert
    // nothing changed. Asserting immediately would pass even if the pipeline
    // were about to double-write.
    Awaitility.await()
        .during(Duration.ofSeconds(3))
        .atMost(PIPELINE_TIMEOUT)
        .untilAsserted(
            () -> {
              assertThat(persistedCount()).isEqualTo(15);
              assertThat(rollupRequestCount()).isEqualTo(15);
            });
  }

  // ---- helpers ---------------------------------------------------------------

  private String batchJson(int count) {
    // Deterministic ids derived from the tenant so a replay of this body carries
    // the same event ids, which is the whole point of the idempotency test.
    String events =
        IntStream.range(0, count)
            .mapToObj(
                i ->
                    """
                    {"eventId":"%s","serviceId":"%s","occurredAt":"%s","eventType":"HTTP_REQUEST",
                     "httpMethod":"GET","httpPath":"/checkout","statusCode":200,"latencyMs":%d}
                    """
                        .formatted(
                            UUID.nameUUIDFromBytes((tenant.slug() + ":" + i).getBytes()),
                            tenant.serviceId(),
                            Instant.now().toString(),
                            80 + i))
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    return "{\"events\":[" + events + "]}";
  }

  private long persistedCount() {
    Long count =
        tenantBinder.callAs(
            tenant.organizationId(),
            () ->
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM telemetry_events WHERE organization_id = ?",
                    Long.class,
                    tenant.organizationId()));
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
