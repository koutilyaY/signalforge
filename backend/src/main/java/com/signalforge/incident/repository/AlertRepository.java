package com.signalforge.incident.repository;

import com.signalforge.incident.domain.Alert;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

  @Query(
      """
      SELECT a FROM Alert a
      WHERE a.organizationId = :organizationId AND a.incidentId = :incidentId
      ORDER BY a.triggeredAt ASC
      """)
  List<Alert> findForIncident(
      @Param("organizationId") UUID organizationId, @Param("incidentId") UUID incidentId);

  @Query(
      """
      SELECT a FROM Alert a
      WHERE a.organizationId = :organizationId
      ORDER BY a.triggeredAt DESC
      """)
  List<Alert> findRecent(@Param("organizationId") UUID organizationId, Pageable pageable);

  @Query(
      "SELECT COUNT(a) FROM Alert a WHERE a.organizationId = :organizationId AND a.fingerprint = :fingerprint")
  long countByFingerprint(
      @Param("organizationId") UUID organizationId, @Param("fingerprint") String fingerprint);
}
