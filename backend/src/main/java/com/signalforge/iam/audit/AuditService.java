package com.signalforge.iam.audit;

import com.signalforge.iam.domain.AuditEvent;
import com.signalforge.iam.repository.AuditEventRepository;
import com.signalforge.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes the audit trail.
 *
 * <p>Every write runs in {@link Propagation#REQUIRES_NEW}. That is the important design choice: an
 * audit record for a <em>denied</em> or <em>failed</em> action must survive the rollback of the
 * transaction that failed. If auditing joined the caller's transaction, the most security-relevant
 * events - the ones that blew up - would be the exact ones that never got recorded.
 *
 * <p>The converse risk is that a failing audit write breaks a working request. Audit failures are
 * therefore caught and logged rather than propagated, except for {@link #record} calls the caller
 * explicitly marks as required.
 */
@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  // Action constants: string literals scattered across call sites become typos
  // that silently produce an unqueryable audit log.
  public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
  public static final String LOGIN_FAILED = "LOGIN_FAILED";
  public static final String LOGIN_LOCKED_OUT = "LOGIN_LOCKED_OUT";
  public static final String ORGANIZATION_CREATED = "ORGANIZATION_CREATED";
  public static final String ORGANIZATION_UPDATED = "ORGANIZATION_UPDATED";
  public static final String USER_CREATED = "USER_CREATED";
  public static final String USER_ROLE_CHANGED = "USER_ROLE_CHANGED";
  public static final String USER_DISABLED = "USER_DISABLED";
  public static final String API_KEY_CREATED = "API_KEY_CREATED";
  public static final String API_KEY_REVOKED = "API_KEY_REVOKED";
  public static final String SERVICE_CREATED = "SERVICE_CREATED";
  public static final String SERVICE_UPDATED = "SERVICE_UPDATED";
  public static final String SERVICE_ARCHIVED = "SERVICE_ARCHIVED";
  public static final String DETECTION_RULE_CREATED = "DETECTION_RULE_CREATED";
  public static final String DETECTION_RULE_UPDATED = "DETECTION_RULE_UPDATED";
  public static final String INCIDENT_CREATED = "INCIDENT_CREATED";
  public static final String INCIDENT_ACKNOWLEDGED = "INCIDENT_ACKNOWLEDGED";
  public static final String INCIDENT_STATUS_CHANGED = "INCIDENT_STATUS_CHANGED";
  public static final String INCIDENT_RESOLVED = "INCIDENT_RESOLVED";

  private final AuditEventRepository repository;

  public AuditService(AuditEventRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      UUID organizationId,
      UUID actorUserId,
      String actorEmail,
      String action,
      String resourceType,
      String resourceId,
      AuditEvent.Outcome outcome,
      Map<String, Object> metadata) {

    RequestMetadata request = currentRequestMetadata();
    AuditEvent event =
        new AuditEvent(
            organizationId,
            actorUserId,
            actorEmail,
            action,
            resourceType,
            resourceId,
            outcome,
            request.ip(),
            request.userAgent(),
            CorrelationIdFilter.currentCorrelationId(),
            metadata);
    repository.save(event);
  }

  /**
   * Same as {@link #record} but never throws - for paths where auditing must not break the request.
   */
  public void recordQuietly(
      UUID organizationId,
      UUID actorUserId,
      String actorEmail,
      String action,
      String resourceType,
      String resourceId,
      AuditEvent.Outcome outcome,
      Map<String, Object> metadata) {
    try {
      record(
          organizationId,
          actorUserId,
          actorEmail,
          action,
          resourceType,
          resourceId,
          outcome,
          metadata);
    } catch (RuntimeException e) {
      log.error(
          "Failed to write audit event action={} org={} resource={}:{}",
          action,
          organizationId,
          resourceType,
          resourceId,
          e);
    }
  }

  private record RequestMetadata(String ip, String userAgent) {}

  private RequestMetadata currentRequestMetadata() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
      // Kafka consumer or scheduled job - no HTTP request in scope.
      return new RequestMetadata(null, null);
    }
    HttpServletRequest request = attrs.getRequest();
    return new RequestMetadata(clientIp(request), truncate(request.getHeader("User-Agent"), 400));
  }

  /**
   * Behind the compose network the peer address is the proxy, so X-Forwarded-For is honoured. Only
   * the left-most entry is taken and it is length-capped: the header is attacker-controlled.
   */
  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      String first = forwarded.split(",", 2)[0].trim();
      return truncate(first, 60);
    }
    return truncate(request.getRemoteAddr(), 60);
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
