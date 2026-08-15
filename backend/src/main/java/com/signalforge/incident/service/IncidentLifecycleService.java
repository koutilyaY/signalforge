package com.signalforge.incident.service;

import com.signalforge.correlation.domain.EvidenceBundle;
import com.signalforge.correlation.service.CorrelationService;
import com.signalforge.iam.audit.AuditService;
import com.signalforge.iam.auth.AuthenticatedPrincipal;
import com.signalforge.iam.domain.AuditEvent;
import com.signalforge.incident.api.IncidentDtos;
import com.signalforge.incident.domain.Incident;
import com.signalforge.incident.domain.IncidentComment;
import com.signalforge.incident.domain.IncidentStatus;
import com.signalforge.incident.domain.IncidentTimelineEntry;
import com.signalforge.incident.repository.AlertRepository;
import com.signalforge.incident.repository.IncidentCommentRepository;
import com.signalforge.incident.repository.IncidentRepository;
import com.signalforge.incident.repository.IncidentTimelineRepository;
import com.signalforge.notification.StreamHub;
import com.signalforge.platform.error.ApiException;
import com.signalforge.platform.error.ErrorCode;
import com.signalforge.registry.domain.ServiceEntity;
import com.signalforge.registry.repository.ServiceRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incident lifecycle: transitions, comments and the assembled detail view.
 *
 * <p>Every transition is validated by {@link IncidentStatus}, appended to the timeline and audited.
 * The audit record and the timeline entry serve different readers - the timeline is the operational
 * story an engineer reads during a postmortem, the audit log is the tamper-evident record of who
 * did what.
 */
@Service
public class IncidentLifecycleService {

  private static final Logger log = LoggerFactory.getLogger(IncidentLifecycleService.class);

  private final IncidentRepository incidentRepository;
  private final IncidentTimelineRepository timelineRepository;
  private final IncidentCommentRepository commentRepository;
  private final AlertRepository alertRepository;
  private final ServiceRepository serviceRepository;
  private final AuditService auditService;
  private final JdbcTemplate jdbcTemplate;
  private final CorrelationService correlationService;
  private final StreamHub streamHub;

  public IncidentLifecycleService(
      IncidentRepository incidentRepository,
      IncidentTimelineRepository timelineRepository,
      IncidentCommentRepository commentRepository,
      AlertRepository alertRepository,
      ServiceRepository serviceRepository,
      AuditService auditService,
      JdbcTemplate jdbcTemplate,
      CorrelationService correlationService,
      StreamHub streamHub) {
    this.incidentRepository = incidentRepository;
    this.timelineRepository = timelineRepository;
    this.commentRepository = commentRepository;
    this.alertRepository = alertRepository;
    this.serviceRepository = serviceRepository;
    this.auditService = auditService;
    this.jdbcTemplate = jdbcTemplate;
    this.correlationService = correlationService;
    this.streamHub = streamHub;
  }

  @Transactional(readOnly = true)
  public List<IncidentDtos.IncidentSummary> list(
      UUID organizationId, IncidentStatus status, int page, int size) {
    return incidentRepository
        .findPage(organizationId, status == null ? null : status.name(), PageRequest.of(page, size))
        .stream()
        .map(IncidentDtos.IncidentSummary::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public IncidentDtos.IncidentDetail detail(UUID organizationId, UUID incidentId) {
    Incident incident = require(organizationId, incidentId);

    List<IncidentDtos.AffectedService> affected = affectedServices(organizationId, incidentId);

    return new IncidentDtos.IncidentDetail(
        IncidentDtos.IncidentSummary.from(incident),
        incident.getDescription(),
        incident.getResolutionNote(),
        incident.getVersion(),
        affected,
        timelineRepository.findTimeline(incidentId, organizationId).stream()
            .map(IncidentDtos.TimelineEntry::from)
            .toList(),
        alertRepository.findForIncident(organizationId, incidentId).stream()
            .map(IncidentDtos.AlertSummary::from)
            .toList(),
        commentRepository.findForIncident(incidentId, organizationId).stream()
            .map(IncidentDtos.CommentResponse::from)
            .toList(),
        incident.getStatus().allowedNext());
  }

  /** Ranked contributing factors for an incident. Read-only and side-effect free. */
  @Transactional(readOnly = true)
  public EvidenceBundle correlation(UUID organizationId, UUID incidentId) {
    return correlationService.buildFor(require(organizationId, incidentId));
  }

  @Transactional
  public IncidentDtos.IncidentDetail transition(
      AuthenticatedPrincipal principal, UUID incidentId, IncidentDtos.TransitionRequest request) {

    UUID organizationId = principal.organizationId();
    Incident incident = require(organizationId, incidentId);

    if (request.version() != null && request.version() != incident.getVersion()) {
      // Two engineers acting on the same incident is normal during an outage.
      // Last-write-wins would let one silently overwrite the other's resolution.
      throw new ApiException(
          ErrorCode.CONCURRENT_MODIFICATION,
          "This incident changed since you loaded it. Reload and try again.",
          null,
          Map.of("expectedVersion", request.version(), "actualVersion", incident.getVersion()));
    }

    Instant now = Instant.now();
    IncidentStatus target = request.status();
    IncidentStatus previous = incident.transitionTo(target, principal.userId(), now);

    if (target == IncidentStatus.RESOLVED && request.note() != null) {
      incident.setResolutionNote(request.note());
    }

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("from", previous.name());
    metadata.put("to", target.name());
    if (target == IncidentStatus.ACKNOWLEDGED && incident.timeToAcknowledge() != null) {
      metadata.put("timeToAcknowledgeMs", incident.timeToAcknowledge().toMillis());
    }
    if (target == IncidentStatus.RESOLVED && incident.timeToResolve() != null) {
      metadata.put("timeToResolveMs", incident.timeToResolve().toMillis());
    }

    timelineRepository.save(
        new IncidentTimelineEntry(
            organizationId,
            incidentId,
            now,
            IncidentTimelineEntry.Kind.STATUS_CHANGE,
            "%s → %s".formatted(previous, target),
            request.note(),
            principal.userId(),
            metadata));

    incidentRepository.saveAndFlush(incident);

    auditService.recordQuietly(
        organizationId,
        principal.userId(),
        principal.email(),
        auditActionFor(target),
        "INCIDENT",
        incidentId.toString(),
        AuditEvent.Outcome.SUCCESS,
        metadata);

    log.info(
        "Incident {} {} -> {} by {}", incident.getReference(), previous, target, principal.email());

    try {
      streamHub.broadcast(
          organizationId,
          target == IncidentStatus.RESOLVED
              ? StreamHub.Events.INCIDENT_RESOLVED
              : StreamHub.Events.INCIDENT_UPDATED,
          Map.of(
              "incidentId", incidentId.toString(),
              "reference", incident.getReference(),
              "status", target.name(),
              "severity", incident.getSeverity().name(),
              "actor", principal.email()));
    } catch (RuntimeException broadcastFailure) {
      // A dashboard that misses an update is a nuisance; a failed transition is
      // an outage. Never let the former cause the latter.
      log.warn("Failed to broadcast transition for {}", incident.getReference(), broadcastFailure);
    }

    return detail(organizationId, incidentId);
  }

  @Transactional
  public IncidentDtos.CommentResponse addComment(
      AuthenticatedPrincipal principal, UUID incidentId, IncidentDtos.CommentRequest request) {

    UUID organizationId = principal.organizationId();
    // Loaded purely to enforce that the incident exists in this tenant before
    // writing a child row against its id.
    require(organizationId, incidentId);

    IncidentComment comment =
        commentRepository.save(
            new IncidentComment(
                organizationId, incidentId, principal.userId(), request.body().trim()));

    timelineRepository.save(
        new IncidentTimelineEntry(
            organizationId,
            incidentId,
            comment.getCreatedAt() == null ? Instant.now() : comment.getCreatedAt(),
            IncidentTimelineEntry.Kind.COMMENT,
            "Comment added",
            request.body().trim(),
            principal.userId(),
            Map.of("commentId", String.valueOf(comment.getId()))));

    return IncidentDtos.CommentResponse.from(comment);
  }

  /** 404 rather than 403 for a foreign incident - see ADR-0003. */
  private Incident require(UUID organizationId, UUID incidentId) {
    return incidentRepository
        .findByIdInOrganization(incidentId, organizationId)
        .orElseThrow(() -> ApiException.notFound("Incident", incidentId));
  }

  private List<IncidentDtos.AffectedService> affectedServices(
      UUID organizationId, UUID incidentId) {

    List<ServiceLink> links =
        jdbcTemplate.query(
            """
            SELECT service_id, role FROM incident_services
            WHERE organization_id = ? AND incident_id = ?
            ORDER BY CASE role WHEN 'PRIMARY' THEN 0 WHEN 'SUSPECTED' THEN 1 ELSE 2 END
            """,
            (rs, rowNum) ->
                new ServiceLink((UUID) rs.getObject("service_id"), rs.getString("role")),
            organizationId,
            incidentId);

    return links.stream()
        .map(
            link -> {
              ServiceEntity service =
                  serviceRepository
                      .findByIdInOrganization(link.serviceId(), organizationId)
                      .orElse(null);
              return new IncidentDtos.AffectedService(
                  link.serviceId(),
                  service == null ? "(archived service)" : service.getName(),
                  link.role(),
                  service == null ? "UNKNOWN" : service.getHealthStatus().name());
            })
        .toList();
  }

  private static String auditActionFor(IncidentStatus target) {
    return switch (target) {
      case ACKNOWLEDGED -> AuditService.INCIDENT_ACKNOWLEDGED;
      case RESOLVED -> AuditService.INCIDENT_RESOLVED;
      default -> AuditService.INCIDENT_STATUS_CHANGED;
    };
  }

  private record ServiceLink(UUID serviceId, String role) {}
}
