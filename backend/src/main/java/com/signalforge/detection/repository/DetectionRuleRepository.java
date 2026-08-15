package com.signalforge.detection.repository;

import com.signalforge.detection.domain.DetectionRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DetectionRuleRepository extends JpaRepository<DetectionRule, UUID> {

  @Query("SELECT r FROM DetectionRule r WHERE r.id = :id AND r.organizationId = :organizationId")
  Optional<DetectionRule> findByIdInOrganization(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);

  @Query(
      """
      SELECT r FROM DetectionRule r
      WHERE r.organizationId = :organizationId AND r.enabled = true
      ORDER BY r.name
      """)
  List<DetectionRule> findEnabled(@Param("organizationId") UUID organizationId);

  @Query("SELECT r FROM DetectionRule r WHERE r.organizationId = :organizationId ORDER BY r.name")
  List<DetectionRule> findAllInOrganization(@Param("organizationId") UUID organizationId);

  /**
   * Organization ids that have at least one enabled rule, for the scheduler's sweep.
   *
   * <p>The single intentionally cross-tenant read in the system, and it returns <em>only ids</em> -
   * no rule bodies, no telemetry, no incidents. It goes through a SECURITY DEFINER function because
   * row-level security would otherwise hide every row from a session with no tenant bound, which is
   * exactly the state the scheduler is in before it picks a tenant.
   *
   * <p>The sweep then binds each id in turn and evaluates that tenant's rules against only that
   * tenant's data, so no tenant boundary is crossed by the evaluation itself.
   */
  @Query(value = "SELECT * FROM sf_organizations_with_rules()", nativeQuery = true)
  List<UUID> organizationsWithEnabledRules();
}
