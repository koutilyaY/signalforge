package com.signalforge.incident.repository;

import com.signalforge.incident.domain.IncidentTimelineEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentTimelineRepository extends JpaRepository<IncidentTimelineEntry, UUID> {

  @Query(
      """
      SELECT e FROM IncidentTimelineEntry e
      WHERE e.incidentId = :incidentId AND e.organizationId = :organizationId
      ORDER BY e.occurredAt ASC, e.createdAt ASC
      """)
  List<IncidentTimelineEntry> findTimeline(
      @Param("incidentId") UUID incidentId, @Param("organizationId") UUID organizationId);
}
