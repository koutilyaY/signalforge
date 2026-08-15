package com.signalforge.incident.repository;

import com.signalforge.incident.domain.IncidentComment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentCommentRepository extends JpaRepository<IncidentComment, UUID> {

  @Query(
      """
      SELECT c FROM IncidentComment c
      WHERE c.incidentId = :incidentId AND c.organizationId = :organizationId
      ORDER BY c.createdAt ASC
      """)
  List<IncidentComment> findForIncident(
      @Param("incidentId") UUID incidentId, @Param("organizationId") UUID organizationId);
}
