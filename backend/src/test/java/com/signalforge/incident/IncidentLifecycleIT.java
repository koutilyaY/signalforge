package com.signalforge.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signalforge.detection.domain.IncidentSeverity;
import com.signalforge.incident.domain.Incident;
import com.signalforge.incident.domain.IncidentStatus;
import com.signalforge.incident.repository.IncidentRepository;
import com.signalforge.platform.tenant.TenantBinder;
import com.signalforge.support.AbstractIntegrationTest;
import com.signalforge.support.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Incident lifecycle through the HTTP API. */
@DisplayName("Incident lifecycle")
class IncidentLifecycleIT extends AbstractIntegrationTest {

  @Autowired private IncidentRepository incidentRepository;
  @Autowired private TenantFixture tenantFixture;
  @Autowired private TenantBinder tenantBinder;

  private TenantFixture.Tenant tenant;
  private Incident incident;

  @BeforeEach
  void setUp() {
    tenant = tenantFixture.createTenant("lifecycle");
    incident = persistIncident();
  }

  @Nested
  @DisplayName("transitions")
  class Transitions {

    @Test
    @DisplayName("OPEN → ACKNOWLEDGED records who and when")
    void acknowledge() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/incidents/{id}/transitions", incident.getId())
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"status\":\"ACKNOWLEDGED\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.incident.status").value("ACKNOWLEDGED"))
          .andExpect(jsonPath("$.incident.acknowledgedAt").exists())
          .andExpect(jsonPath("$.incident.timeToAcknowledgeMs").isNumber());

      Incident reloaded = reload();
      assertThat(reloaded.getAcknowledgedBy()).isEqualTo(tenant.engineer().userId());
    }

    @Test
    @DisplayName("the full happy path walks OPEN → ACK → INVESTIGATING → MITIGATED → RESOLVED")
    void fullLifecycle() throws Exception {
      transitionTo("ACKNOWLEDGED", null);
      transitionTo("INVESTIGATING", null);
      transitionTo("MITIGATED", null);
      transitionTo("RESOLVED", "Rolled back payment-service 2.7.4");

      Incident reloaded = reload();
      assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
      assertThat(reloaded.getResolutionNote()).isEqualTo("Rolled back payment-service 2.7.4");
      assertThat(reloaded.timeToResolve()).isNotNull();
    }

    @Test
    @DisplayName("resolving directly from OPEN is allowed - false positives get closed early")
    void resolveDirectlyFromOpen() throws Exception {
      transitionTo("RESOLVED", "False positive - synthetic load test");

      Incident reloaded = reload();
      assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
      assertThat(reloaded.getAcknowledgedAt())
          .as("acknowledgement is backfilled so time-to-acknowledge stays measurable")
          .isNotNull();
    }

    @Test
    @DisplayName("a RESOLVED incident is terminal")
    void resolvedIsTerminal() throws Exception {
      transitionTo("RESOLVED", "done");

      mockMvc
          .perform(
              post("/api/v1/incidents/{id}/transitions", incident.getId())
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"status\":\"INVESTIGATING\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("transitioning to the current status is rejected rather than silently ignored")
    void selfTransitionRejected() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/incidents/{id}/transitions", incident.getId())
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"status\":\"OPEN\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("a stale version token is rejected with 409 rather than clobbering")
    void staleVersionRejected() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/incidents/{id}/transitions", incident.getId())
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"status\":\"ACKNOWLEDGED\",\"version\":999}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }
  }

  @Nested
  @DisplayName("authorization and tenancy")
  class AuthorizationAndTenancy {

    @Test
    @DisplayName("a VIEWER can read an incident but cannot acknowledge it")
    void viewerIsReadOnly() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/incidents/{id}", incident.getId())
                  .header("Authorization", tenant.viewer().authorizationHeader()))
          .andExpect(status().isOk());

      mockMvc
          .perform(
              post("/api/v1/incidents/{id}/transitions", incident.getId())
                  .header("Authorization", tenant.viewer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"status\":\"ACKNOWLEDGED\"}"))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("another organization cannot see or transition this incident")
    void crossTenantAccessIsNotFound() throws Exception {
      TenantFixture.Tenant other = tenantFixture.createTenant("lifecycle-other");

      mockMvc
          .perform(
              get("/api/v1/incidents/{id}", incident.getId())
                  .header("Authorization", other.admin().authorizationHeader()))
          .andExpect(status().isNotFound());

      mockMvc
          .perform(
              post("/api/v1/incidents/{id}/transitions", incident.getId())
                  .header("Authorization", other.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"status\":\"RESOLVED\"}"))
          .andExpect(status().isNotFound());

      assertThat(reload().getStatus())
          .as("the incident must be untouched")
          .isEqualTo(IncidentStatus.OPEN);
    }

    @Test
    @DisplayName("the incident list is scoped to the caller's organization")
    void listIsScoped() throws Exception {
      TenantFixture.Tenant other = tenantFixture.createTenant("lifecycle-list");

      mockMvc
          .perform(
              get("/api/v1/incidents")
                  .header("Authorization", other.viewer().authorizationHeader()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    }
  }

  @Nested
  @DisplayName("detail view")
  class DetailView {

    @Test
    @DisplayName("advertises which transitions are legal next")
    void exposesAllowedTransitions() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/incidents/{id}", incident.getId())
                  .header("Authorization", tenant.viewer().authorizationHeader()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.allowedTransitions").isArray())
          .andExpect(jsonPath("$.allowedTransitions[?(@ == 'ACKNOWLEDGED')]").exists())
          .andExpect(jsonPath("$.allowedTransitions[?(@ == 'OPEN')]").doesNotExist());
    }

    @Test
    @DisplayName("includes comments and records them on the timeline")
    void commentsAppearOnTimeline() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/incidents/{id}/comments", incident.getId())
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"body\":\"Rolling back now\"}"))
          .andExpect(status().isCreated());

      mockMvc
          .perform(
              get("/api/v1/incidents/{id}", incident.getId())
                  .header("Authorization", tenant.viewer().authorizationHeader()))
          .andExpect(jsonPath("$.comments.length()").value(1))
          .andExpect(jsonPath("$.comments[0].body").value("Rolling back now"))
          .andExpect(jsonPath("$.timeline[?(@.kind == 'COMMENT')]").exists());
    }

    @Test
    @DisplayName("records each status change on the timeline")
    void statusChangesAppearOnTimeline() throws Exception {
      transitionTo("ACKNOWLEDGED", null);
      transitionTo("INVESTIGATING", null);

      mockMvc
          .perform(
              get("/api/v1/incidents/{id}", incident.getId())
                  .header("Authorization", tenant.viewer().authorizationHeader()))
          .andExpect(jsonPath("$.timeline[?(@.kind == 'STATUS_CHANGE')]").exists());
    }
  }

  // ---- helpers ---------------------------------------------------------------

  private void transitionTo(String status, String note) throws Exception {
    String body =
        note == null
            ? "{\"status\":\"%s\"}".formatted(status)
            : "{\"status\":\"%s\",\"note\":\"%s\"}".formatted(status, note);
    mockMvc
        .perform(
            post("/api/v1/incidents/{id}/transitions", incident.getId())
                .header("Authorization", tenant.engineer().authorizationHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  private Incident persistIncident() {
    Instant started = Instant.now().minus(5, ChronoUnit.MINUTES);
    Incident created =
        new Incident(
            tenant.organizationId(),
            "INC-1",
            "Elevated error rate on checkout-service",
            IncidentSeverity.HIGH,
            "ERROR_RATE:" + tenant.serviceId() + ":" + UUID.randomUUID(),
            started,
            started.plus(30, ChronoUnit.SECONDS));
    created.setPrimaryServiceId(tenant.serviceId());
    return tenantBinder.callAs(
        tenant.organizationId(), () -> incidentRepository.saveAndFlush(created));
  }

  private Incident reload() {
    return tenantBinder.callAs(
        tenant.organizationId(),
        () ->
            incidentRepository
                .findByIdInOrganization(incident.getId(), tenant.organizationId())
                .orElseThrow());
  }
}
