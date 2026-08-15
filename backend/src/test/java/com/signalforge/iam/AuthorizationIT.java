package com.signalforge.iam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signalforge.support.AbstractIntegrationTest;
import com.signalforge.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Role-based authorization, tested from the outside.
 *
 * <p>The interesting assertions here are the denials. A suite that only checks "ADMIN can do X"
 * cannot tell the difference between a working authorization model and one that lets everyone do
 * everything.
 */
@DisplayName("Role-based authorization")
class AuthorizationIT extends AbstractIntegrationTest {

  private static final String NEW_SERVICE_JSON =
      """
      {"name":"payment-service","environment":"PRODUCTION","criticality":"HIGH"}
      """;

  @Autowired private TenantFixture tenantFixture;

  private TenantFixture.Tenant tenant;

  @BeforeEach
  void setUp() {
    tenant = tenantFixture.createTenant("rbac");
  }

  @Nested
  @DisplayName("unauthenticated requests")
  class Unauthenticated {

    @Test
    @DisplayName("are rejected with 401 on protected endpoints")
    void rejectedWithoutToken() throws Exception {
      mockMvc
          .perform(get("/api/v1/services"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("a garbage bearer token is rejected, not silently treated as anonymous")
    void rejectsMalformedToken() throws Exception {
      mockMvc
          .perform(get("/api/v1/services").header("Authorization", "Bearer not-a-real-jwt"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("a token signed with the wrong key is rejected")
    void rejectsForeignSignature() throws Exception {
      // Structurally valid JWT, signed with a different secret.
      String forged =
          "eyJhbGciOiJIUzI1NiJ9."
              + "eyJpc3MiOiJzaWduYWxmb3JnZS10ZXN0Iiwic3ViIjoiMDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAwIn0."
              + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
      mockMvc
          .perform(get("/api/v1/services").header("Authorization", "Bearer " + forged))
          .andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("VIEWER")
  class Viewer {

    @Test
    @DisplayName("can read services")
    void canRead() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/services")
                  .header("Authorization", tenant.viewer().authorizationHeader()))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("cannot create a service")
    void cannotCreate() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/services")
                  .header("Authorization", tenant.viewer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(NEW_SERVICE_JSON))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("cannot update a service")
    void cannotUpdate() throws Exception {
      mockMvc
          .perform(
              patch("/api/v1/services/{id}", tenant.serviceId())
                  .header("Authorization", tenant.viewer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"team\":\"platform\"}"))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("cannot archive a service")
    void cannotArchive() throws Exception {
      mockMvc
          .perform(
              delete("/api/v1/services/{id}", tenant.serviceId())
                  .header("Authorization", tenant.viewer().authorizationHeader()))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("cannot read the audit log")
    void cannotReadAudit() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/audit").header("Authorization", tenant.viewer().authorizationHeader()))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("ENGINEER")
  class Engineer {

    @Test
    @DisplayName("can create a service")
    void canCreate() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/services")
                  .header("Authorization", tenant.engineer().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(NEW_SERVICE_JSON))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.name").value("payment-service"));
    }

    @Test
    @DisplayName("cannot administer the organization")
    void cannotAdminister() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/organization")
                  .header("Authorization", tenant.engineer().authorizationHeader()))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("cannot manage API keys")
    void cannotManageApiKeys() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/api-keys")
                  .header("Authorization", tenant.engineer().authorizationHeader()))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("ADMIN")
  class Admin {

    @Test
    @DisplayName("inherits ENGINEER permissions rather than needing them granted separately")
    void inheritsEngineerPermissions() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/services")
                  .header("Authorization", tenant.admin().authorizationHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(NEW_SERVICE_JSON))
          .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("inherits VIEWER permissions")
    void inheritsViewerPermissions() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/services").header("Authorization", tenant.admin().authorizationHeader()))
          .andExpect(status().isOk());
    }
  }
}
