package com.signalforge.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fan-out hub for live incident updates.
 *
 * <p><b>Why SSE rather than WebSockets.</b> Every message here travels server → client; nothing
 * flows the other way, because actions go through the normal REST API where they get the same
 * authorization and audit treatment as everything else. WebSockets would buy bidirectionality
 * nobody needs and cost a second authentication path, a second authorization surface, and a
 * protocol that proxies and corporate middleboxes mangle far more often. SSE is plain HTTP,
 * reconnects automatically in the browser, and needs no extra infrastructure.
 *
 * <p><b>Tenancy.</b> Emitters are keyed by organization and a broadcast only ever touches its own
 * bucket. There is no code path that writes to every emitter — a cross-tenant leak here would be
 * far worse than a normal API leak, because the client never asked for the data.
 *
 * <p><b>Scale limits, stated honestly.</b> This is in-process. Two API replicas each hold their own
 * emitters, so an incident opened on replica A does not reach a browser connected to replica B.
 * Making this correct across replicas needs a shared bus — publishing to the existing {@code
 * notification-events} Kafka topic and having every replica consume it is the natural fix, and the
 * topic already exists for exactly that reason.
 */
@Component
public class StreamHub {

  private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

  /** Long enough to be useful, short enough that dead connections are reaped. */
  private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

  /** Proxies commonly kill an idle connection after 60s; keep it warm well inside that. */
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);

  private final Map<UUID, List<SseEmitter>> emittersByOrganization = new ConcurrentHashMap<>();
  private final AtomicInteger activeConnections = new AtomicInteger();
  private final ObjectMapper objectMapper;

  public StreamHub(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
    this.objectMapper = objectMapper;
    Gauge.builder("signalforge.stream.connections", activeConnections, AtomicInteger::get)
        .description("Open SSE connections")
        .register(meterRegistry);
  }

  /** Registers a new subscriber for one organization. */
  public SseEmitter subscribe(UUID organizationId) {
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());

    emittersByOrganization
        .computeIfAbsent(organizationId, key -> new CopyOnWriteArrayList<>())
        .add(emitter);
    activeConnections.incrementAndGet();

    // All three callbacks must remove the emitter. Missing any one of them leaks
    // a reference per dropped connection, and the leak is invisible until the
    // heap is gone.
    emitter.onCompletion(() -> remove(organizationId, emitter));
    emitter.onTimeout(() -> remove(organizationId, emitter));
    emitter.onError(error -> remove(organizationId, emitter));

    try {
      // An immediate event so the client knows the stream is live rather than
      // merely accepted.
      emitter.send(SseEmitter.event().name("connected").data(Map.of("ok", true)));
    } catch (IOException e) {
      remove(organizationId, emitter);
    }

    return emitter;
  }

  /** Publishes an event to every subscriber of one organization. */
  public void broadcast(UUID organizationId, String eventName, Object payload) {
    List<SseEmitter> emitters = emittersByOrganization.get(organizationId);
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    String json;
    try {
      json = objectMapper.writeValueAsString(payload);
    } catch (Exception e) {
      log.error("Could not serialise SSE payload for event {}", eventName, e);
      return;
    }

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name(eventName).data(json));
      } catch (Exception e) {
        // A client that went away mid-send is normal, not an error worth a stack
        // trace. Drop it and move on - one dead subscriber must not stop the
        // broadcast reaching the others.
        remove(organizationId, emitter);
      }
    }
  }

  /**
   * Keeps connections alive through intermediaries that time out idle streams, and reaps emitters
   * whose client vanished without closing cleanly.
   */
  @Scheduled(fixedDelay = 20_000)
  public void heartbeat() {
    emittersByOrganization.forEach(
        (organizationId, emitters) -> {
          for (SseEmitter emitter : emitters) {
            try {
              // A comment line, which SSE clients ignore. Cheaper than a real event.
              emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
              remove(organizationId, emitter);
            }
          }
        });
  }

  private void remove(UUID organizationId, SseEmitter emitter) {
    List<SseEmitter> emitters = emittersByOrganization.get(organizationId);
    if (emitters != null && emitters.remove(emitter)) {
      activeConnections.decrementAndGet();
      if (emitters.isEmpty()) {
        emittersByOrganization.remove(organizationId, emitters);
      }
    }
  }

  public int activeConnections() {
    return activeConnections.get();
  }

  public int connectionsFor(UUID organizationId) {
    List<SseEmitter> emitters = emittersByOrganization.get(organizationId);
    return emitters == null ? 0 : emitters.size();
  }

  /** Event names the client subscribes to. */
  public static final class Events {
    public static final String INCIDENT_OPENED = "incident.opened";
    public static final String INCIDENT_UPDATED = "incident.updated";
    public static final String INCIDENT_RESOLVED = "incident.resolved";
    public static final String SERVICE_HEALTH_CHANGED = "service.health";

    private Events() {}
  }

  public static Duration heartbeatInterval() {
    return HEARTBEAT_INTERVAL;
  }
}
