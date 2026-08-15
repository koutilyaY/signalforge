package com.signalforge.iam.repository;

import com.signalforge.iam.domain.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

  /**
   * Keyset pagination rather than OFFSET. The audit log is append-only and grows without bound;
   * OFFSET 50000 makes PostgreSQL walk and discard 50000 rows on every page. Ordering on
   * (created_at DESC, id DESC) makes the cursor total even when many rows share a timestamp.
   */
  @Query(
      """
      SELECT a FROM AuditEvent a
      WHERE a.organizationId = :organizationId
        AND (:beforeCreatedAt IS NULL
             OR a.createdAt < :beforeCreatedAt
             OR (a.createdAt = :beforeCreatedAt AND a.id < :beforeId))
      ORDER BY a.createdAt DESC, a.id DESC
      """)
  List<AuditEvent> findPage(
      @Param("organizationId") UUID organizationId,
      @Param("beforeCreatedAt") Instant beforeCreatedAt,
      @Param("beforeId") UUID beforeId,
      Pageable pageable);

  @Query(
      """
      SELECT a FROM AuditEvent a
      WHERE a.organizationId = :organizationId AND a.action = :action
      ORDER BY a.createdAt DESC, a.id DESC
      """)
  List<AuditEvent> findByAction(
      @Param("organizationId") UUID organizationId,
      @Param("action") String action,
      Pageable pageable);
}
