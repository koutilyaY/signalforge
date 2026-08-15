package com.signalforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.signalforge.platform.tenant.TenantBinder;
import com.signalforge.support.AbstractIntegrationTest;
import com.signalforge.support.TenantFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The third isolation layer: PostgreSQL row-level security.
 *
 * <p>Layers one and two - tenant from the signed token, {@code organization_id} in every WHERE
 * clause - are application code, and are covered by {@link TenantIsolationIT}. They share a failure
 * mode: they only work while the application code is correct.
 *
 * <p>These tests therefore do the thing the application is not supposed to do. They issue raw SQL
 * with <b>no tenant predicate at all</b> and assert the database returns nothing anyway. If RLS
 * were absent or misconfigured, every one of them would fail by returning another tenant's rows.
 */
@DisplayName("Row-level security")
class RowLevelSecurityIT extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TenantBinder tenantBinder;
  @Autowired private TenantFixture tenantFixture;

  private TenantFixture.Tenant orgA;
  private TenantFixture.Tenant orgB;

  @BeforeEach
  void setUp() {
    orgA = tenantFixture.createTenant("rls-a");
    orgB = tenantFixture.createTenant("rls-b");
  }

  @Nested
  @DisplayName("a query that forgets its tenant predicate")
  class ForgottenPredicate {

    @Test
    @DisplayName("sees only the bound tenant's services, not every tenant's")
    void servicesAreInvisibleWithoutTheBoundTenant() {
      // Deliberately no WHERE organization_id. Without RLS this returns both.
      List<UUID> visible =
          tenantBinder.callAs(
              orgA.organizationId(),
              () ->
                  jdbcTemplate.query(
                      "SELECT organization_id FROM services", (rs, i) -> (UUID) rs.getObject(1)));

      assertThat(visible)
          .as("an unscoped query must still be scoped, by the database")
          .isNotEmpty()
          .containsOnly(orgA.organizationId());
    }

    @Test
    @DisplayName("cannot count another tenant's rows")
    void countsAreScoped() {
      Long fromA =
          tenantBinder.callAs(
              orgA.organizationId(),
              () -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM services", Long.class));
      Long fromB =
          tenantBinder.callAs(
              orgB.organizationId(),
              () -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM services", Long.class));

      // Each tenant has exactly one service from the fixture. A leak would show
      // as 2 (or more, from other tests sharing the container).
      assertThat(fromA).isEqualTo(1);
      assertThat(fromB).isEqualTo(1);
    }

    @Test
    @DisplayName("cannot read another tenant's row even by its exact primary key")
    void directPrimaryKeyLookupIsBlocked() {
      List<UUID> found =
          tenantBinder.callAs(
              orgA.organizationId(),
              () ->
                  jdbcTemplate.query(
                      "SELECT id FROM services WHERE id = ?",
                      (rs, i) -> (UUID) rs.getObject(1),
                      orgB.serviceId()));

      assertThat(found).as("knowing the id of another tenant's row must not be enough").isEmpty();
    }

    @Test
    @DisplayName("cannot read another tenant's users")
    void usersAreScoped() {
      List<String> emails =
          tenantBinder.callAs(
              orgA.organizationId(),
              () -> jdbcTemplate.query("SELECT email FROM users", (rs, i) -> rs.getString(1)));

      assertThat(emails).isNotEmpty();
      assertThat(emails).allSatisfy(email -> assertThat(email).contains("-"));
      assertThat(emails).doesNotContain(orgB.admin().email());
    }

    @Test
    @DisplayName("cannot read another tenant's audit log")
    void auditLogIsScoped() {
      Long count =
          tenantBinder.callAs(
              orgB.organizationId(),
              () ->
                  jdbcTemplate.queryForObject(
                      "SELECT COUNT(*) FROM audit_events WHERE organization_id = ?",
                      Long.class,
                      orgA.organizationId()));

      // Explicitly asking for org A's audit rows while bound to org B: the
      // predicate matches, the policy does not.
      assertThat(count).isZero();
    }
  }

  @Nested
  @DisplayName("with no tenant bound at all")
  class NoTenantBound {

    @Test
    @DisplayName("nothing is visible")
    void unboundSessionSeesNothing() {
      // No TenantBinder wrapper: the DataSource stamps an empty setting and
      // every policy evaluates to NULL, which is not TRUE.
      Long services = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM services", Long.class);
      Long users = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
      Long incidents = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM incidents", Long.class);

      assertThat(services).isZero();
      assertThat(users).isZero();
      assertThat(incidents).isZero();
    }
  }

  @Nested
  @DisplayName("writes")
  class Writes {

    @Test
    @DisplayName("cannot insert a row attributed to another tenant")
    void cannotWriteAcrossTenants() {
      // The WITH CHECK clause is what stops a compromised or buggy write path
      // from planting rows in someone else's tenant.
      assertThatThrownBy(
              () ->
                  tenantBinder.runAs(
                      orgA.organizationId(),
                      () ->
                          jdbcTemplate.update(
                              """
                              INSERT INTO services (id, organization_id, name, environment)
                              VALUES (gen_random_uuid(), ?, 'smuggled-service', 'PRODUCTION')
                              """,
                              orgB.organizationId())))
          .hasStackTraceContaining("row-level security");
    }

    @Test
    @DisplayName("cannot update another tenant's row")
    void cannotUpdateAcrossTenants() {
      int updated =
          tenantBinder.callAs(
              orgA.organizationId(),
              () ->
                  jdbcTemplate.update(
                      "UPDATE services SET team = 'stolen' WHERE id = ?", orgB.serviceId()));

      assertThat(updated).as("the row is not visible, so nothing is updated").isZero();
    }

    @Test
    @DisplayName("cannot delete another tenant's row")
    void cannotDeleteAcrossTenants() {
      int deleted =
          tenantBinder.callAs(
              orgA.organizationId(),
              () -> jdbcTemplate.update("DELETE FROM services WHERE id = ?", orgB.serviceId()));

      assertThat(deleted).isZero();

      Long stillThere =
          tenantBinder.callAs(
              orgB.organizationId(),
              () ->
                  jdbcTemplate.queryForObject(
                      "SELECT COUNT(*) FROM services WHERE id = ?", Long.class, orgB.serviceId()));
      assertThat(stillThere).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("the deliberate escape hatches")
  class EscapeHatches {

    @Test
    @DisplayName("login lookup works with no tenant bound, because it establishes the tenant")
    void loginLookupBypassesRls() {
      List<String> found =
          jdbcTemplate.query(
              "SELECT email FROM sf_find_user_by_email(?)",
              (rs, i) -> rs.getString(1),
              orgA.admin().email());

      assertThat(found).containsExactly(orgA.admin().email());
    }

    @Test
    @DisplayName("the sweep helper returns ids only, never rule bodies")
    void sweepHelperReturnsIdsOnly() {
      List<UUID> ids =
          jdbcTemplate.query(
              "SELECT * FROM sf_organizations_with_rules()", (rs, i) -> (UUID) rs.getObject(1));

      // Fixture tenants have no rules, so this asserts the function is callable
      // and returns UUIDs rather than asserting a particular membership.
      assertThat(ids).allSatisfy(id -> assertThat(id).isNotNull());
    }

    @Test
    @DisplayName("the escape hatches are the only ones - a plain unscoped user query still fails")
    void noOtherBypassExists() {
      Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
      assertThat(count).as("if this is non-zero, someone added a bypass without a test").isZero();
    }
  }
}
