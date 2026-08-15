package com.signalforge.iam.auth;

import com.signalforge.iam.domain.ApiKey;
import com.signalforge.iam.repository.ApiKeyRepository;
import com.signalforge.platform.tenant.TenantContext;
import com.signalforge.platform.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a monitored service via {@code X-API-Key}.
 *
 * <p>This is the credential the telemetry producers use. It runs before the JWT filter because the
 * ingestion path is the hot path and should not pay for a JWT parse attempt.
 *
 * <p>Performance note: the key hash lookup is a unique-index probe on every ingestion request. The
 * {@code last_used_at} bookkeeping is throttled to at most one UPDATE per key per minute ({@link
 * #LAST_USED_RESOLUTION}) - otherwise a service sending 500 events/second would issue 500 row
 * updates/second against the same row purely for a timestamp nobody reads at that resolution.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-API-Key";

  /** How stale last_used_at is allowed to get before we bother writing it. */
  private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

  private final ApiKeyRepository apiKeyRepository;
  private final TransactionTemplate transactionTemplate;

  public ApiKeyAuthenticationFilter(
      ApiKeyRepository apiKeyRepository, TransactionTemplate transactionTemplate) {
    this.apiKeyRepository = apiKeyRepository;
    this.transactionTemplate = transactionTemplate;
    this.transactionTemplate.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String presented = request.getHeader(HEADER);
    if (presented == null || presented.isBlank()) {
      chain.doFilter(request, response);
      return;
    }

    String hash = ApiKeyHasher.hash(presented.trim());
    Optional<ApiKey> found = apiKeyRepository.findByKeyHashAndRevokedAtIsNull(hash);

    if (found.isEmpty()) {
      // Fall through as unauthenticated rather than 401 here: Spring Security
      // produces the 401 for protected paths, and a bad key on a public path is
      // not an error worth failing the request over.
      chain.doFilter(request, response);
      return;
    }

    ApiKey key = found.get();
    AuthenticatedPrincipal principal =
        AuthenticatedPrincipal.ofApiKey(key.getOrganizationId(), key.getName());

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    TenantContext.set(principal.toTenantPrincipal());
    MDC.put(CorrelationIdFilter.MDC_ORGANIZATION_ID, key.getOrganizationId().toString());

    touchLastUsed(key);

    try {
      chain.doFilter(request, response);
    } finally {
      TenantContext.clear();
      SecurityContextHolder.clearContext();
    }
  }

  /**
   * Best-effort. In its own transaction so it cannot roll back the request's work, and swallowed on
   * failure because a telemetry write must not fail over a usage timestamp.
   */
  private void touchLastUsed(ApiKey key) {
    Instant now = Instant.now();
    Instant lastUsed = key.getLastUsedAt();
    if (lastUsed != null && lastUsed.isAfter(now.minus(LAST_USED_RESOLUTION))) {
      return;
    }
    try {
      transactionTemplate.executeWithoutResult(
          status ->
              apiKeyRepository.touchLastUsedIfStale(
                  key.getId(), now, now.minus(LAST_USED_RESOLUTION)));
    } catch (RuntimeException e) {
      logger.debug("Could not update api key last_used_at", e);
    }
  }
}
