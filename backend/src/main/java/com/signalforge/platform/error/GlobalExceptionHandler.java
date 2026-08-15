package com.signalforge.platform.error;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.signalforge.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Translates every exception into the single {@link ApiErrorResponse} envelope.
 *
 * <p>Two rules, both of which exist because leaking internals is a real vulnerability and not a
 * style preference:
 *
 * <ul>
 *   <li>Known failures ({@link ApiException} and Spring's own well-understood exceptions) are
 *       reported with a specific code and a message safe to show a user.
 *   <li>Everything else is logged in full with its stack trace and reported as an opaque {@code
 *       INTERNAL_ERROR}. The response carries only the correlation id, which is the operator's key
 *       back into the logs.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiErrorResponse> handleApiException(
      ApiException ex, HttpServletRequest request) {
    ErrorCode code = ex.code();
    // 5xx signalled deliberately still deserves a stack trace in the log.
    if (code.status().is5xxServerError()) {
      log.error("Handled server-side failure code={} message={}", code, ex.getMessage(), ex);
    } else {
      log.debug("Handled client failure code={} message={}", code, ex.getMessage());
    }
    ApiErrorResponse body =
        ApiErrorResponse.of(code, ex.getMessage(), correlationId(), path(request))
            .withDetails(ex.details());
    return ResponseEntity.status(code.status()).body(body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleBeanValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<ApiErrorResponse.FieldViolation> violations =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                fe ->
                    new ApiErrorResponse.FieldViolation(
                        fe.getField(),
                        fe.getDefaultMessage(),
                        sanitizeRejected(fe.getRejectedValue())))
            .toList();
    ApiErrorResponse body =
        ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                correlationId(),
                path(request))
            .withViolations(violations);
    return ResponseEntity.badRequest().body(body);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<ApiErrorResponse.FieldViolation> violations =
        ex.getConstraintViolations().stream()
            .map(
                cv ->
                    new ApiErrorResponse.FieldViolation(
                        String.valueOf(cv.getPropertyPath()),
                        cv.getMessage(),
                        sanitizeRejected(cv.getInvalidValue())))
            .toList();
    ApiErrorResponse body =
        ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                correlationId(),
                path(request))
            .withViolations(violations);
    return ResponseEntity.badRequest().body(body);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleUnreadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    // Report which field failed to bind, but never echo the parser's message -
    // Jackson's text can include the offending class name and source snippet.
    String message = ErrorCode.MALFORMED_REQUEST.defaultMessage();
    Map<String, Object> details = Map.of();
    if (ex.getCause() instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
      String field =
          ife.getPath().stream()
              .map(ref -> ref.getFieldName() == null ? "[]" : ref.getFieldName())
              .reduce((a, b) -> a + "." + b)
              .orElse("body");
      details = Map.of("field", field);
    }
    log.debug("Malformed request body on {}", path(request));
    return ResponseEntity.badRequest()
        .body(
            ApiErrorResponse.of(
                    ErrorCode.MALFORMED_REQUEST, message, correlationId(), path(request))
                .withDetails(details));
  }

  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ApiErrorResponse> handleBadParameter(
      Exception ex, HttpServletRequest request) {
    String message =
        ex instanceof MissingServletRequestParameterException missing
            ? "Missing required parameter: " + missing.getParameterName()
            : "Parameter has the wrong type";
    return ResponseEntity.badRequest()
        .body(
            ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED, message, correlationId(), path(request)));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiErrorResponse> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    log.warn("Access denied on {} correlationId={}", path(request), correlationId());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            ApiErrorResponse.of(
                ErrorCode.ACCESS_DENIED,
                ErrorCode.ACCESS_DENIED.defaultMessage(),
                correlationId(),
                path(request)));
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiErrorResponse> handleAuthentication(
      AuthenticationException ex, HttpServletRequest request) {
    // Never distinguish "no such user" from "wrong password" - that is a user
    // enumeration oracle.
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            ApiErrorResponse.of(
                ErrorCode.AUTHENTICATION_REQUIRED,
                ErrorCode.AUTHENTICATION_REQUIRED.defaultMessage(),
                correlationId(),
                path(request)));
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ApiErrorResponse> handleOptimisticLock(
      OptimisticLockingFailureException ex, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ApiErrorResponse.of(
                ErrorCode.CONCURRENT_MODIFICATION,
                "This record changed while you were editing it. Reload and try again.",
                correlationId(),
                path(request)));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiErrorResponse> handleIntegrity(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    // The constraint name would tell a client about our schema. Log it, do not send it.
    log.warn("Data integrity violation on {}: {}", path(request), rootMessage(ex));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ApiErrorResponse.of(
                ErrorCode.RESOURCE_CONFLICT,
                "The request conflicts with existing data",
                correlationId(),
                path(request)));
  }

  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNoHandler(
      NoHandlerFoundException ex, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            ApiErrorResponse.of(
                ErrorCode.RESOURCE_NOT_FOUND, "No such endpoint", correlationId(), path(request)));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(
      Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception on {} correlationId={}", path(request), correlationId(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ApiErrorResponse.of(
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.defaultMessage(),
                correlationId(),
                path(request)));
  }

  // ---- helpers ---------------------------------------------------------------

  private static String correlationId() {
    return CorrelationIdFilter.currentCorrelationId();
  }

  private static String path(HttpServletRequest request) {
    return request == null ? null : request.getRequestURI();
  }

  /** Echo back small scalars only; never a whole object graph or an oversized string. */
  private static Object sanitizeRejected(Object rejected) {
    if (rejected == null) {
      return null;
    }
    if (rejected instanceof CharSequence cs) {
      return cs.length() > 120 ? cs.subSequence(0, 120) + "..." : cs.toString();
    }
    if (rejected instanceof Number || rejected instanceof Boolean || rejected instanceof Enum<?>) {
      return rejected;
    }
    return rejected.getClass().getSimpleName();
  }

  private static String rootMessage(Throwable t) {
    Throwable cursor = t;
    while (cursor.getCause() != null && cursor.getCause() != cursor) {
      cursor = cursor.getCause();
    }
    return cursor.getMessage();
  }
}
