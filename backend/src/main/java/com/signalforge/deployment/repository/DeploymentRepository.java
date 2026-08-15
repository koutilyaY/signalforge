package com.signalforge.deployment.repository;

import com.signalforge.deployment.domain.Deployment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

  @Query("SELECT d FROM Deployment d WHERE d.id = :id AND d.organizationId = :organizationId")
  Optional<Deployment> findByIdInOrganization(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);

  @Query(
      """
      SELECT d FROM Deployment d
      WHERE d.organizationId = :organizationId
        AND (:serviceId IS NULL OR d.serviceId = :serviceId)
      ORDER BY d.startedAt DESC
      """)
  List<Deployment> findPage(
      @Param("organizationId") UUID organizationId,
      @Param("serviceId") UUID serviceId,
      Pageable pageable);

  /**
   * Deployments that could have caused an incident starting at {@code incidentStart}.
   *
   * <p>Ordered newest-first because the most recent change before a failure is the most likely
   * culprit, and because {@code idx_deployments_org_started} is a DESC index — this is a backwards
   * index scan with no sort step.
   *
   * <p>Note the filter is on {@code COALESCE(completed_at, started_at)}: a rollout is judged by
   * when it could first have affected traffic, not by when someone pressed the button.
   */
  @Query(
      """
      SELECT d FROM Deployment d
      WHERE d.organizationId = :organizationId
        AND d.status <> 'ROLLED_BACK'
        AND COALESCE(d.completedAt, d.startedAt) BETWEEN :from AND :to
      ORDER BY COALESCE(d.completedAt, d.startedAt) DESC
      """)
  List<Deployment> findInWindow(
      @Param("organizationId") UUID organizationId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  @Query(
      """
      SELECT d FROM Deployment d
      WHERE d.organizationId = :organizationId AND d.serviceId = :serviceId
      ORDER BY d.startedAt DESC
      LIMIT 1
      """)
  Optional<Deployment> findLatestForService(
      @Param("organizationId") UUID organizationId, @Param("serviceId") UUID serviceId);
}
