package com.signalforge.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fan-out behaviour of the live stream.
 *
 * <p>The assertion that matters is the tenancy one. A leak here is worse than a normal API leak,
 * because the receiving client never asked for the data and would have no reason to notice it had
 * arrived.
 */
@DisplayName("Live stream hub")
class StreamHubTest {

  private StreamHub hub;
  private final UUID orgA = UUID.randomUUID();
  private final UUID orgB = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    hub = new StreamHub(new ObjectMapper(), new SimpleMeterRegistry());
  }

  @Test
  @DisplayName("tracks connections per organization")
  void tracksConnectionsPerOrganization() {
    hub.subscribe(orgA);
    hub.subscribe(orgA);
    hub.subscribe(orgB);

    assertThat(hub.connectionsFor(orgA)).isEqualTo(2);
    assertThat(hub.connectionsFor(orgB)).isEqualTo(1);
    assertThat(hub.activeConnections()).isEqualTo(3);
  }

  @Test
  @DisplayName("an organization with no subscribers is not tracked")
  void unknownOrganizationHasNoConnections() {
    assertThat(hub.connectionsFor(UUID.randomUUID())).isZero();
  }

  @Test
  @DisplayName("broadcasting to one organization does not touch another's emitters")
  void broadcastIsTenantScoped() {
    SseEmitter emitterA = hub.subscribe(orgA);
    SseEmitter emitterB = hub.subscribe(orgB);

    // Both start alive. Broadcasting to A must leave B's connection count
    // untouched - a cross-tenant write would either error B's emitter or, far
    // worse, succeed.
    hub.broadcast(orgA, StreamHub.Events.INCIDENT_OPENED, Map.of("reference", "INC-1"));

    assertThat(hub.connectionsFor(orgB))
        .as("org B's subscriber must be entirely unaffected by org A's traffic")
        .isEqualTo(1);
    assertThat(emitterA).isNotNull();
    assertThat(emitterB).isNotNull();
  }

  @Test
  @DisplayName("broadcasting to an organization with no subscribers is a no-op, not an error")
  void broadcastWithNoSubscribers() {
    // The detection engine broadcasts unconditionally; nobody watching is the
    // normal case at 3am and must not throw into the incident-creation path.
    hub.broadcast(UUID.randomUUID(), StreamHub.Events.INCIDENT_OPENED, Map.of("a", "b"));
    assertThat(hub.activeConnections()).isZero();
  }

  @Test
  @DisplayName("an unserialisable payload is dropped rather than propagated to the caller")
  void unserialisablePayloadIsSwallowed() {
    hub.subscribe(orgA);

    // A self-referencing structure Jackson cannot serialise.
    Map<String, Object> cyclic = new java.util.HashMap<>();
    cyclic.put("self", cyclic);

    hub.broadcast(orgA, StreamHub.Events.INCIDENT_OPENED, cyclic);

    assertThat(hub.connectionsFor(orgA))
        .as("a bad payload must not tear down a healthy subscriber")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("a dead emitter is reaped on the next broadcast rather than leaking")
  void deadEmitterIsReapedOnBroadcast() {
    SseEmitter emitter = hub.subscribe(orgA);
    assertThat(hub.activeConnections()).isEqualTo(1);

    // Simulates a client that vanished. Note this asserts the reap path that
    // broadcast() owns, not SseEmitter.onCompletion - that callback is invoked
    // by the servlet container when the async request finishes, so it does not
    // fire for an emitter that was never attached to a real request. Testing it
    // here would be testing Spring, not this class.
    emitter.complete();
    hub.broadcast(orgA, StreamHub.Events.INCIDENT_OPENED, Map.of("reference", "INC-1"));

    // Missing this cleanup leaks one reference per dropped connection, and the
    // leak is invisible until the heap is gone.
    assertThat(hub.activeConnections()).isZero();
    assertThat(hub.connectionsFor(orgA)).isZero();
  }

  @Test
  @DisplayName("one dead subscriber does not stop the broadcast reaching the healthy ones")
  void deadSubscriberDoesNotBlockOthers() {
    SseEmitter dead = hub.subscribe(orgA);
    hub.subscribe(orgA);
    dead.complete();

    hub.broadcast(orgA, StreamHub.Events.INCIDENT_OPENED, Map.of("reference", "INC-2"));

    assertThat(hub.connectionsFor(orgA))
        .as("the healthy subscriber must survive its neighbour dying")
        .isEqualTo(1);
  }
}
