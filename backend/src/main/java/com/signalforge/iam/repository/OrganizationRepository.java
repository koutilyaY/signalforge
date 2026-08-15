package com.signalforge.iam.repository;

import com.signalforge.iam.domain.Organization;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

  Optional<Organization> findBySlug(String slug);

  /**
   * Slug uniqueness is checked before any tenant exists, so it cannot be tenant-scoped. Returns a
   * boolean only - it never exposes another organization's row.
   */
  @Query(value = "SELECT sf_slug_exists(:slug)", nativeQuery = true)
  boolean existsBySlug(@Param("slug") String slug);
}
