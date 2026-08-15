package com.signalforge.platform.error;

import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base exception for every deliberately-signalled API failure.
 *
 * <p>Anything thrown as an {@code ApiException} is considered an expected outcome and is rendered
 * with its own status and code. Anything else that escapes to {@link GlobalExceptionHandler} is a
 * bug, is logged at ERROR with a stack trace, and is rendered as an opaque 500 - the client never
 * sees internals.
 */
public class ApiException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final ErrorCode code;
  private final transient Map<String, Object> details;

  public ApiException(ErrorCode code) {
    this(code, code.defaultMessage(), null, Map.of());
  }

  public ApiException(ErrorCode code, String message) {
    this(code, message, null, Map.of());
  }

  public ApiException(ErrorCode code, String message, Throwable cause) {
    this(code, message, cause, Map.of());
  }

  public ApiException(
      ErrorCode code, String message, Throwable cause, Map<String, Object> details) {
    super(message, cause);
    this.code = code;
    this.details = new LinkedHashMap<>(details == null ? Map.of() : details);
  }

  public ErrorCode code() {
    return code;
  }

  public Map<String, Object> details() {
    return Map.copyOf(details);
  }

  // ---- Convenience factories -------------------------------------------------

  public static ApiException notFound(String resourceType, Object id) {
    return new ApiException(
        ErrorCode.RESOURCE_NOT_FOUND,
        resourceType + " not found",
        null,
        Map.of("resourceType", resourceType, "resourceId", String.valueOf(id)));
  }

  public static ApiException conflict(String message) {
    return new ApiException(ErrorCode.RESOURCE_CONFLICT, message);
  }

  public static ApiException accessDenied(String message) {
    return new ApiException(ErrorCode.ACCESS_DENIED, message);
  }

  public static ApiException validation(String message, Map<String, Object> details) {
    return new ApiException(ErrorCode.VALIDATION_FAILED, message, null, details);
  }

  public static ApiException invalidTransition(String from, String to) {
    return new ApiException(
        ErrorCode.INVALID_STATE_TRANSITION,
        "Cannot move from %s to %s".formatted(from, to),
        null,
        Map.of("from", from, "to", to));
  }
}
