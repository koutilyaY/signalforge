package com.signalforge.platform.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes.
 *
 * <p>These are part of the API contract: clients branch on the {@code code} string, never on the
 * human-readable message. Adding a code is backwards compatible; changing or removing one is not.
 */
public enum ErrorCode {

  // 400
  VALIDATION_FAILED("Request failed validation", HttpStatus.BAD_REQUEST),
  MALFORMED_REQUEST("Request body could not be parsed", HttpStatus.BAD_REQUEST),
  INVALID_CURSOR("Pagination cursor is not valid", HttpStatus.BAD_REQUEST),
  INVALID_STATE_TRANSITION("The requested state transition is not allowed", HttpStatus.BAD_REQUEST),

  // 401
  AUTHENTICATION_REQUIRED("Authentication is required", HttpStatus.UNAUTHORIZED),
  INVALID_CREDENTIALS("Email or password is incorrect", HttpStatus.UNAUTHORIZED),
  TOKEN_EXPIRED("Access token has expired", HttpStatus.UNAUTHORIZED),
  TOKEN_INVALID("Access token is not valid", HttpStatus.UNAUTHORIZED),

  // 403
  ACCESS_DENIED("You do not have permission to perform this action", HttpStatus.FORBIDDEN),
  TENANT_MISMATCH("Resource does not belong to your organization", HttpStatus.FORBIDDEN),
  ACCOUNT_DISABLED("This account is disabled", HttpStatus.FORBIDDEN),

  // 404
  RESOURCE_NOT_FOUND("Requested resource was not found", HttpStatus.NOT_FOUND),

  // 409
  RESOURCE_CONFLICT("Resource already exists", HttpStatus.CONFLICT),
  CONCURRENT_MODIFICATION("Resource was modified by another request", HttpStatus.CONFLICT),

  // 413 / 422
  PAYLOAD_TOO_LARGE("Request payload exceeds the allowed size", HttpStatus.PAYLOAD_TOO_LARGE),

  // 429
  RATE_LIMIT_EXCEEDED("Rate limit exceeded for this organization", HttpStatus.TOO_MANY_REQUESTS),

  // 500 / 503
  INTERNAL_ERROR("An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR),
  DEPENDENCY_UNAVAILABLE("A downstream dependency is unavailable", HttpStatus.SERVICE_UNAVAILABLE),
  AI_UNAVAILABLE("AI assistant is not available", HttpStatus.SERVICE_UNAVAILABLE);

  private final String defaultMessage;
  private final HttpStatus status;

  ErrorCode(String defaultMessage, HttpStatus status) {
    this.defaultMessage = defaultMessage;
    this.status = status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }

  public HttpStatus status() {
    return status;
  }
}
