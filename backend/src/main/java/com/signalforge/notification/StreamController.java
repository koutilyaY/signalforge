package com.signalforge.notification;

import com.signalforge.iam.auth.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live incident stream.
 *
 * <p>The organization is taken from the authenticated principal, exactly as everywhere else — a
 * client cannot subscribe to another tenant's stream because it has no way to name one.
 *
 * <p>Note the browser's native {@code EventSource} cannot set an {@code Authorization} header, and
 * the usual workaround is to put the token in a query string. That is refused here: URLs end up in
 * proxy logs, browser history and {@code Referer} headers. The frontend instead reads the stream
 * with {@code fetch()} and a {@code ReadableStream} reader, which keeps the bearer token in a
 * header where it belongs. See {@code frontend/lib/stream.ts}.
 */
@RestController
@RequestMapping("/api/v1/stream")
@Tag(name = "Live stream")
public class StreamController {

  private final StreamHub hub;

  public StreamController(StreamHub hub) {
    this.hub = hub;
  }

  @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @PreAuthorize("hasRole('VIEWER')")
  @Operation(summary = "Server-sent event stream of incident and service-health changes")
  public SseEmitter stream(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return hub.subscribe(principal.organizationId());
  }
}
