package com.signalforge.telemetry;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signalforge.iam.domain.Organization;
import com.signalforge.iam.repository.OrganizationRepository;
import com.signalforge.platform.tenant.TenantBinder;
import com.signalforge.support.AbstractIntegrationTest;
import com.signalforge.support.TenantFixture;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;

/**
 * Ingestion endpoint behaviour: validation, tenant scoping and rate limiting.
 *
 * <p>These tests stop at the Kafka publish boundary - the template is configured against a broker
 * that is not running in the {@code test} profile, and sends are asynchronous, so a 202 here proves
 * validation and rate limiting rather than end-to-end delivery. Delivery and idempotency are
 * covered by {@link TelemetryIdempotencyIT} against the real write path.
 */
@DisplayName("Telemetry ingestion")
class IngestionIT extends AbstractIntegrationTest {

  @Autowired private TenantFixture tenantFixture;
  @Autowired private TenantBinder tenantBinder;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private StringRedisTemplate redis;

  private TenantFixture.Tenant tenant;

  @BeforeEach
  void setUp() {
    tenant = tenantFixture.createTenant("ingest");
    // The limiter is shared state in Redis; clear it so ordering between tests
    // cannot make one bleed into another.
    redis.delete(redis.keys("sf:ratelimit:ingest:*"));
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("accepts a well-formed batch with 202, not 200")
    void acceptsValidBatch() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/ingest/events")
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(batchJson(3)))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.accepted").value(3))
          .andExpect(jsonPath("$.rejected.length()").value(0))
          .andExpect(header().exists("X-RateLimit-Limit"));
    }

    @Test
    @DisplayName("rejects an event for a service belonging to another organization")
    void rejectsForeignServiceId() throws Exception {
      TenantFixture.Tenant other = tenantFixture.createTenant("ingest-other");

      mockMvc
          .perform(
              post("/api/v1/ingest/events")
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(eventJson(UUID.randomUUID(), other.serviceId(), Instant.now(), 120)))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.accepted").value(0))
          .andExpect(
              jsonPath("$.rejected[0].reason").value("unknown service for this organization"));
    }

    @Test
    @DisplayName("rejects an event whose timestamp is far in the future")
    void rejectsFutureTimestamp() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/ingest/events")
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      eventJson(
                          UUID.randomUUID(),
                          tenant.serviceId(),
                          Instant.now().plusSeconds(3600),
                          120)))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.accepted").value(0));
    }

    @Test
    @DisplayName("rejects a duplicate eventId inside one batch")
    void rejectsIntraBatchDuplicate() throws Exception {
      UUID duplicated = UUID.randomUUID();
      String body =
          """
          {"events":[%s,%s]}
          """
              .formatted(
                  singleEvent(duplicated, tenant.serviceId(), Instant.now(), 100),
                  singleEvent(duplicated, tenant.serviceId(), Instant.now(), 110));

      mockMvc
          .perform(
              post("/api/v1/ingest/events")
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.accepted").value(1))
          .andExpect(jsonPath("$.rejected.length()").value(1));
    }

    @Test
    @DisplayName("a VIEWER cannot ingest")
    void viewerCannotIngest() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/ingest/events")
                  .header("Authorization", tenant.viewer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(batchJson(1)))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("rate limiting")
  class RateLimiting {

    @Test
    @DisplayName("returns 429 once the organization's per-minute quota is spent")
    void returns429WhenQuotaExhausted() throws Exception {
      setQuota(10);

      // Batch cost is the event count, so one 10-event batch spends the quota.
      mockMvc
          .perform(
              post("/api/v1/ingest/events")
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(batchJson(10)))
          .andExpect(status().isAccepted());

      mockMvc
          .perform(
              post("/api/v1/ingest/events")
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(batchJson(1)))
          .andExpect(status().isTooManyRequests())
          .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
          .andExpect(jsonPath("$.details.limitPerMinute").value(10));
    }

    @Test
    @DisplayName("a batch is charged per event, so batching cannot bypass the quota")
    void batchingDoesNotBypassQuota() throws Exception {
      setQuota(5);

      // A single batch larger than the whole quota must be refused outright,
      // not accepted because it is "one request".
      mockMvc
          .perform(
              post("/api/v1/ingest/events")
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(batchJson(50)))
          .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("one organization's traffic does not consume another's quota")
    void quotaIsPerOrganization() throws Exception {
      TenantFixture.Tenant other = tenantFixture.createTenant("ingest-quota");

      setQuota(5);

      mockMvc
          .perform(
              post("/api/v1/ingest/events")
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(batchJson(5)))
          .andExpect(status().isAccepted());

      // tenant is now exhausted; other must be unaffected.
      mockMvc
          .perform(
              post("/api/v1/ingest/events")
                  .header("Authorization", other.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(eventJson(UUID.randomUUID(), other.serviceId(), Instant.now(), 100)))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.accepted").value(1));
    }
  }

  /** Row-level security applies to fixtures too, so the quota change is tenant-bound. */
  private void setQuota(int perMinute) {
    tenantBinder.runAs(
        tenant.organizationId(),
        () -> {
          Organization organization =
              organizationRepository.findById(tenant.organizationId()).orElseThrow();
          organization.setIngestRateLimitPerMinute(perMinute);
          organizationRepository.saveAndFlush(organization);
        });
  }

  // ---- helpers ---------------------------------------------------------------

  private String batchJson(int count) {
    String events =
        IntStream.range(0, count)
            .mapToObj(
                i -> singleEvent(UUID.randomUUID(), tenant.serviceId(), Instant.now(), 100 + i))
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    return "{\"events\":[" + events + "]}";
  }

  private String eventJson(UUID eventId, UUID serviceId, Instant occurredAt, int latencyMs) {
    return "{\"events\":[" + singleEvent(eventId, serviceId, occurredAt, latencyMs) + "]}";
  }

  private static String singleEvent(
      UUID eventId, UUID serviceId, Instant occurredAt, int latencyMs) {
    return """
        {"eventId":"%s","serviceId":"%s","occurredAt":"%s","eventType":"HTTP_REQUEST",
         "httpMethod":"GET","httpPath":"/checkout","statusCode":200,"latencyMs":%d}
        """
        .formatted(eventId, serviceId, occurredAt, latencyMs);
  }
}
