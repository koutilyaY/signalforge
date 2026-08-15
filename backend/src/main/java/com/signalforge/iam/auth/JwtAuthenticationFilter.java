package com.signalforge.iam.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalforge.platform.error.ApiErrorResponse;
import com.signalforge.platform.error.ApiException;
import com.signalforge.platform.error.ErrorCode;
import com.signalforge.platform.tenant.TenantContext;
import com.signalforge.platform.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the authenticated principal from a {@code Authorization: Bearer <jwt>} header, and
 * binds the tenant to the thread for the duration of the request.
 *
 * <p>Requests without a bearer token pass through untouched - it is Spring Security's job, not this
 * filter's, to decide whether an anonymous request is acceptable for a given path. A token that is
 * present but bad is rejected here with a 401, because silently continuing as anonymous would turn
 * an expired token into a confusing 403 later.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String HEADER = "Authorization";
  private static final String PREFIX = "Bearer ";

  private final JwtService jwtService;
  private final ObjectMapper objectMapper;

  public JwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper) {
    this.jwtService = jwtService;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String header = request.getHeader(HEADER);
    if (header == null || !header.startsWith(PREFIX)) {
      chain.doFilter(request, response);
      return;
    }

    // An API key filter earlier in the chain may already have authenticated this
    // request; do not overwrite it.
    if (SecurityContextHolder.getContext().getAuthentication() != null) {
      chain.doFilter(request, response);
      return;
    }

    String token = header.substring(PREFIX.length()).trim();
    JwtService.ParsedToken parsed;
    try {
      parsed = jwtService.parseAccessToken(token);
    } catch (ApiException e) {
      writeError(response, request, e);
      return;
    }

    AuthenticatedPrincipal principal =
        AuthenticatedPrincipal.ofUser(
            parsed.userId(), parsed.organizationId(), parsed.email(), parsed.role());

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);

    TenantContext.set(principal.toTenantPrincipal());
    MDC.put(CorrelationIdFilter.MDC_ORGANIZATION_ID, parsed.organizationId().toString());
    MDC.put(CorrelationIdFilter.MDC_USER_ID, parsed.userId().toString());

    try {
      chain.doFilter(request, response);
    } finally {
      // Servlet containers pool threads; a leaked ThreadLocal would hand the next
      // request the previous tenant.
      TenantContext.clear();
      SecurityContextHolder.clearContext();
    }
  }

  private void writeError(HttpServletResponse response, HttpServletRequest request, ApiException e)
      throws IOException {
    ErrorCode code = e.code();
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    ApiErrorResponse body =
        ApiErrorResponse.of(
            code,
            e.getMessage(),
            CorrelationIdFilter.currentCorrelationId(),
            request.getRequestURI());
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
