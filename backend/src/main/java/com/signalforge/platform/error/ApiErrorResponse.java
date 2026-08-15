package com.signalforge.platform.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error envelope every non-2xx response uses.
 *
 * <p>Deliberately contains no stack trace, no SQL, no class names and no dependency detail. {@code
 * correlationId} is the bridge: it is echoed here, stamped on every log line for the request, and
 * attached to the trace, so an operator can find the internals from a screenshot a user sent them.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(
    String code,
    String message,
    Instant timestamp,
    String correlationId,
    String path,
    Map<String, Object> details,
    List<FieldViolation> violations) {

  public record FieldViolation(String field, String message, Object rejectedValue) {}

  public static ApiErrorResponse of(
      ErrorCode code, String message, String correlationId, String path) {
    return new ApiErrorResponse(
        code.name(), message, Instant.now(), correlationId, path, Map.of(), List.of());
  }

  public ApiErrorResponse withDetails(Map<String, Object> newDetails) {
    return new ApiErrorResponse(
        code, message, timestamp, correlationId, path, newDetails, violations);
  }

  public ApiErrorResponse withViolations(List<FieldViolation> newViolations) {
    return new ApiErrorResponse(
        code, message, timestamp, correlationId, path, details, newViolations);
  }
}
