package com.signalforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signalforge.platform.tenant.TenantBinder;
import com.signalforge.registry.repository.ServiceRepository;
import com.signalforge.support.AbstractIntegrationTest;
import com.signalforge.support.TenantFixture;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Negative tests proving organization A cannot reach organization B's data.
 *
 * <p>These are the tests that matter most in a multi-tenant system, and the reason they are written
 * as negatives is that a positive test ("A can read A's service") passes just as happily on a
 * system with no isolation at all.
 *
 * <p>Coverage is deliberately layered so that a regression at any one layer is caught:
 *
 * <ul>
 *   <li>HTTP: a valid token for A used against B's resource ids.
 *   <li>Repository: the tenant-scoped finders return empty rather than the row.
 *   <li>Error shape: cross-tenant access is reported as 404, never 403 - a 403 would confirm the
 *       resource exists.
 * </ul>
 */
@DisplayName("Tenant isolation")
class TenantIsolationIT extends AbstractIntegrationTest {

  @Autowired private TenantFixture tenantFixture;
  @Autowired private ServiceRepository serviceRepository;
  @Autowired private TenantBinder tenantBinder;

  private TenantFixture.Tenant orgA;
  private TenantFixture.Tenant orgB;

  @BeforeEach
  void setUp() {
    orgA = tenantFixture.createTenant("alpha");
    orgB = tenantFixture.createTenant("bravo");
  }

  @Nested
  @DisplayName("through the HTTP API")
  class ThroughHttpApi {

    @Test
    @DisplayName("A cannot read B's service by id")
    void cannotReadForeignService() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/services/{id}", orgB.serviceId())
                  .header("Authorization", orgA.engineer().authorizationHeader()))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("A cannot modify B's service")
    void cannotUpdateForeignService() throws Exception {
      mockMvc
          .perform(
              patch("/api/v1/services/{id}", orgB.serviceId())
                  .header("Authorization", orgA.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"team\":\"stolen-by-org-a\"}"))
          .andExpect(status().isNotFound());

      // And prove nothing actually changed.
      assertThat(
              tenantBinder.callAs(
                  orgB.organizationId(),
                  () ->
                      serviceRepository
                          .findByIdInOrganization(orgB.serviceId(), orgB.organizationId())
                          .orElseThrow()
                          .getTeam()))
          .isNull();
    }

    @Test
    @DisplayName("A cannot archive B's service")
    void cannotArchiveForeignService() throws Exception {
      mockMvc
          .perform(
              delete("/api/v1/services/{id}", orgB.serviceId())
                  .header("Authorization", orgA.engineer().authorizationHeader()))
          .andExpect(status().isNotFound());

      assertThat(
              tenantBinder.callAs(
                  orgB.organizationId(),
                  () ->
                      serviceRepository.findByIdInOrganization(
                          orgB.serviceId(), orgB.organizationId())))
          .as("B's service must still be active")
          .isPresent();
    }

    @Test
    @DisplayName("A's service listing contains only A's services")
    void listingIsScopedToCallerOrganization() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/services")
                  .header("Authorization", orgA.engineer().authorizationHeader()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].id").value(orgA.serviceId().toString()));
    }

    @Test
    @DisplayName(
        "even an ADMIN of A gets nothing from B - privilege does not cross the tenant line")
    void adminPrivilegeDoesNotCrossTenants() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/services/{id}", orgB.serviceId())
                  .header("Authorization", orgA.admin().authorizationHeader()))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("cross-tenant access reports 404, not 403, so resource existence is not disclosed")
    void doesNotDiscloseExistence() throws Exception {
      // A completely made-up id and a real-but-foreign id must be indistinguishable.
      String responseForForeign =
          mockMvc
              .perform(
                  get("/api/v1/services/{id}", orgB.serviceId())
                      .header("Authorization", orgA.viewer().authorizationHeader()))
              .andExpect(status().isNotFound())
              .andReturn()
              .getResponse()
              .getContentAsString();

      String responseForNonexistent =
          mockMvc
              .perform(
                  get("/api/v1/services/{id}", java.util.UUID.randomUUID())
                      .header("Authorization", orgA.viewer().authorizationHeader()))
              .andExpect(status().isNotFound())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(extractCode(responseForForeign))
          .as("both cases must produce the same error code")
          .isEqualTo(extractCode(responseForNonexistent));
    }

    private String extractCode(String json) throws Exception {
      return objectMapper.readTree(json).get("code").asText();
    }
  }

  @Nested
  @DisplayName("at the repository layer")
  class AtRepositoryLayer {

    @Test
    @DisplayName("tenant-scoped finder returns empty for a foreign id")
    void scopedFinderRejectsForeignId() {
      Optional<?> found =
          tenantBinder.callAs(
              orgA.organizationId(),
              () ->
                  serviceRepository.findByIdInOrganization(
                      orgB.serviceId(), orgA.organizationId()));
      assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("the same id resolves for its own organization - proving the id itself is valid")
    void sameIdResolvesForOwner() {
      // Without this control, the test above would also pass if the id were simply wrong.
      assertThat(
              tenantBinder.callAs(
                  orgB.organizationId(),
                  () ->
                      serviceRepository.findByIdInOrganization(
                          orgB.serviceId(), orgB.organizationId())))
          .isPresent();
    }

    @Test
    @DisplayName("listing is scoped")
    void listingIsScoped() {
      assertThat(
              tenantBinder.callAs(
                  orgA.organizationId(), () -> serviceRepository.findActive(orgA.organizationId())))
          .extracting(s -> s.getOrganizationId())
          .containsOnly(orgA.organizationId());
    }

    @Test
    @DisplayName("counts do not leak across tenants")
    void countsAreScoped() {
      assertThat(
              tenantBinder.callAs(
                  orgA.organizationId(),
                  () -> serviceRepository.countActive(orgA.organizationId())))
          .isEqualTo(1);
      assertThat(
              tenantBinder.callAs(
                  orgB.organizationId(),
                  () -> serviceRepository.countActive(orgB.organizationId())))
          .isEqualTo(1);
    }
  }
}
