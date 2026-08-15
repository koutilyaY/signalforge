package com.signalforge.incident.repository;

import com.signalforge.incident.domain.Incident;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

  @Query("SELECT i FROM Incident i WHERE i.id = :id AND i.organizationId = :organizationId")
  Optional<Incident> findByIdInOrganization(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);

  /**
   * The currently-open incident for a fingerprint, if any.
   *
   * <p>This is a read used to <em>attach</em> a new alert to an existing incident. It is
   * deliberately not used to decide whether to create one — that decision is left to the partial
   * unique index {@code uq_incidents_active_fingerprint}, because a check-then-insert here would
   * race with a concurrent evaluator and produce two incidents for one problem.
   */
  @Query(
      """
      SELECT i FROM Incident i
      WHERE i.organizationId = :organizationId
        AND i.fingerprint = :fingerprint
        AND i.status <> 'RESOLVED'
      """)
  Optional<Incident> findActiveByFingerprint(
      @Param("organizationId") UUID organizationId, @Param("fingerprint") String fingerprint);

  /** Most recently resolved incident for a fingerprint — drives the cooldown check. */
  @Query(
      """
      SELECT i FROM Incident i
      WHERE i.organizationId = :organizationId
        AND i.fingerprint = :fingerprint
        AND i.status = 'RESOLVED'
      ORDER BY i.resolvedAt DESC
      LIMIT 1
      """)
  Optional<Incident> findMostRecentlyResolved(
      @Param("organizationId") UUID organizationId, @Param("fingerprint") String fingerprint);

  @Query(
      """
      SELECT i FROM Incident i
      WHERE i.organizationId = :organizationId
        AND (:status IS NULL OR i.status = :status)
      ORDER BY i.detectedAt DESC, i.id DESC
      """)
  List<Incident> findPage(
      @Param("organizationId") UUID organizationId,
      @Param("status") String status,
      Pageable pageable);

  @Query(
      "SELECT COUNT(i) FROM Incident i WHERE i.organizationId = :organizationId AND i.status <> 'RESOLVED'")
  long countOpen(@Param("organizationId") UUID organizationId);

  @Query(
      """
      SELECT i FROM Incident i
      WHERE i.organizationId = :organizationId
        AND i.detectedAt >= :since
      ORDER BY i.detectedAt DESC
      """)
  List<Incident> findSince(
      @Param("organizationId") UUID organizationId, @Param("since") Instant since);
}
