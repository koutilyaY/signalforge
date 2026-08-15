package com.signalforge.incident.api;

import com.signalforge.ai.IncidentAiService;
import com.signalforge.correlation.domain.EvidenceBundle;
import com.signalforge.iam.auth.AuthenticatedPrincipal;
import com.signalforge.incident.domain.IncidentStatus;
import com.signalforge.incident.service.IncidentLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident management API.
 *
 * <p>Reading needs VIEWER; driving the lifecycle needs ENGINEER. A read-only stakeholder can watch
 * an incident unfold without being able to acknowledge it on someone else's behalf.
 */
@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incidents")
@Validated
public class IncidentController {

  private final IncidentLifecycleService lifecycleService;
  private final IncidentAiService aiService;

  public IncidentController(
      IncidentLifecycleService lifecycleService, IncidentAiService aiService) {
    this.lifecycleService = lifecycleService;
    this.aiService = aiService;
  }

  @GetMapping
  @PreAuthorize("hasRole('VIEWER')")
  @Operation(summary = "List incidents, most recently detected first")
  public List<IncidentDtos.IncidentSummary> list(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @RequestParam(required = false) IncidentStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
    return lifecycleService.list(principal.organizationId(), status, page, size);
  }

  @GetMapping("/{incidentId}")
  @PreAuthorize("hasRole('VIEWER')")
  @Operation(summary = "Full incident detail: timeline, alerts, affected services and comments")
  public IncidentDtos.IncidentDetail detail(
      @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID incidentId) {
    return lifecycleService.detail(principal.organizationId(), incidentId);
  }

  /**
   * One endpoint for every transition rather than separate /acknowledge, /resolve and /mitigate
   * routes. The state machine already knows which moves are legal; splitting it across five URLs
   * would duplicate those rules in the routing table and let the two drift apart.
   */
  @PostMapping("/{incidentId}/transitions")
  @PreAuthorize("hasRole('ENGINEER')")
  @Operation(summary = "Move an incident to a new status")
  public IncidentDtos.IncidentDetail transition(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @PathVariable UUID incidentId,
      @Valid @RequestBody IncidentDtos.TransitionRequest request) {
    return lifecycleService.transition(principal, incidentId, request);
  }

  /**
   * Deterministic root-cause evidence for an incident.
   *
   * <p>A separate endpoint rather than part of the detail payload: correlation runs several
   * aggregate queries over telemetry, and the incident list and detail views are polled far more
   * often than anyone actually opens the RCA panel.
   */
  @GetMapping("/{incidentId}/correlation")
  @PreAuthorize("hasRole('VIEWER')")
  @Operation(summary = "Ranked contributing factors with supporting evidence")
  public EvidenceBundle correlation(
      @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID incidentId) {
    return lifecycleService.correlation(principal.organizationId(), incidentId);
  }

  /**
   * Optional AI narrative over the same evidence the correlation endpoint returns.
   *
   * <p>Always 200. When the assistant is disabled, unreachable or produced nothing usable, the
   * response carries {@code available: false} and a reason. Returning 503 would make a deliberately
   * optional feature look like a platform outage.
   */
  @GetMapping("/{incidentId}/ai-summary")
  @PreAuthorize("hasRole('VIEWER')")
  @Operation(summary = "Evidence-grounded AI summary (optional; degrades to unavailable)")
  public IncidentAiService.AiSummaryResponse aiSummary(
      @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID incidentId) {
    return aiService.summarise(principal.organizationId(), incidentId);
  }

  @PostMapping("/{incidentId}/comments")
  @PreAuthorize("hasRole('ENGINEER')")
  @Operation(summary = "Add a comment to an incident")
  public ResponseEntity<IncidentDtos.CommentResponse> comment(
      @AuthenticationPrincipal AuthenticatedPrincipal principal,
      @PathVariable UUID incidentId,
      @Valid @RequestBody IncidentDtos.CommentRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(lifecycleService.addComment(principal, incidentId, request));
  }
}
