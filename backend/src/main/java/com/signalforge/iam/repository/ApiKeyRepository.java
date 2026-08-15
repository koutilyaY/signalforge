package com.signalforge.iam.repository;

import com.signalforge.iam.domain.ApiKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

  /**
   * Authentication lookup. Like the user email lookup this is necessarily not tenant-scoped - the
   * key is what establishes the tenant. The hash is unique, so this returns at most one row.
   *
   * <p>SECURITY DEFINER for the same reason: RLS cannot scope a query whose job is to determine the
   * scope.
   */
  @Query(value = "SELECT * FROM sf_find_active_api_key(:keyHash)", nativeQuery = true)
  Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(@Param("keyHash") String keyHash);

  @Query(
      "SELECT k FROM ApiKey k WHERE k.organizationId = :organizationId ORDER BY k.createdAt DESC")
  List<ApiKey> findAllInOrganization(@Param("organizationId") UUID organizationId);

  @Query("SELECT k FROM ApiKey k WHERE k.id = :id AND k.organizationId = :organizationId")
  Optional<ApiKey> findByIdInOrganization(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);

  /**
   * last_used_at is updated on the ingestion hot path. A plain entity save would take a write lock
   * and flush the whole row on every request; this is a targeted, fire-and-forget UPDATE that is
   * only issued when the stored value is already stale by more than a minute (see
   * ApiKeyAuthenticationFilter).
   */
  @Modifying
  @Query(
      "UPDATE ApiKey k SET k.lastUsedAt = :now WHERE k.id = :id AND (k.lastUsedAt IS NULL OR k.lastUsedAt < :staleBefore)")
  int touchLastUsedIfStale(
      @Param("id") UUID id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
