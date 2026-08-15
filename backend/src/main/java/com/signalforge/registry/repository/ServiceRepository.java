package com.signalforge.registry.repository;

import com.signalforge.registry.domain.ServiceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every method here takes {@code organizationId}. There is deliberately no {@code findById(UUID)}
 * exposed to callers - inheriting {@link JpaRepository#findById} is unavoidable, so the coding
 * standard is that service classes call {@link #findByIdInOrganization} and the tenant-isolation
 * test suite asserts the behaviour.
 */
public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {

  @Query(
      "SELECT s FROM ServiceEntity s WHERE s.id = :id AND s.organizationId = :organizationId AND s.archivedAt IS NULL")
  Optional<ServiceEntity> findByIdInOrganization(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);

  @Query(
      """
      SELECT s FROM ServiceEntity s
      WHERE s.organizationId = :organizationId
        AND s.archivedAt IS NULL
        AND (:environment IS NULL OR s.environment = :environment)
      ORDER BY s.name
      """)
  List<ServiceEntity> findAllInOrganization(
      @Param("organizationId") UUID organizationId, @Param("environment") String environment);

  @Query(
      "SELECT s FROM ServiceEntity s WHERE s.organizationId = :organizationId AND s.archivedAt IS NULL")
  List<ServiceEntity> findActive(@Param("organizationId") UUID organizationId);

  @Query(
      """
      SELECT s FROM ServiceEntity s
      WHERE s.organizationId = :organizationId AND s.name = :name AND s.environment = :environment
      """)
  Optional<ServiceEntity> findByName(
      @Param("organizationId") UUID organizationId,
      @Param("name") String name,
      @Param("environment") String environment);

  @Query(
      "SELECT COUNT(s) FROM ServiceEntity s WHERE s.organizationId = :organizationId AND s.archivedAt IS NULL")
  long countActive(@Param("organizationId") UUID organizationId);
}
