package com.signalforge.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns (or adopts) a correlation id for every request and publishes it to SLF4J's MDC so the
 * JSON log encoder stamps it on every line, and to the response header so a client can quote it.
 *
 * <p>Runs before Spring Security so that authentication failures are correlated too - those are
 * exactly the requests you most want to trace.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_CORRELATION_ID = "correlationId";
  public static final String MDC_ORGANIZATION_ID = "organizationId";
  public static final String MDC_USER_ID = "userId";
  public static final String REQUEST_ATTRIBUTE = "sf.correlationId";

  /**
   * Client-supplied ids are accepted only if they look sane. Without this, a caller could inject
   * newlines or a multi-kilobyte string into every log line for the request.
   */
  private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_.:-]{8,120}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String supplied = request.getHeader(HEADER);
    String correlationId =
        (supplied != null && SAFE_ID.matcher(supplied).matches())
            ? supplied
            : UUID.randomUUID().toString();

    MDC.put(MDC_CORRELATION_ID, correlationId);
    request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
    response.setHeader(HEADER, correlationId);

    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_CORRELATION_ID);
      MDC.remove(MDC_ORGANIZATION_ID);
      MDC.remove(MDC_USER_ID);
    }
  }

  /** Reads the correlation id for the current request, falling back to a fresh one. */
  public static String currentCorrelationId() {
    String fromMdc = MDC.get(MDC_CORRELATION_ID);
    return fromMdc != null ? fromMdc : "unknown";
  }
}
