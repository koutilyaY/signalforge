package com.signalforge;

import com.signalforge.platform.config.SignalForgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SignalForge - AI-assisted production incident and reliability platform.
 *
 * <p>This is a modular monolith, not a distributed set of microservices. The modules under {@code
 * com.signalforge.*} are bounded contexts with their own entities, repositories and services; they
 * talk to each other through published interfaces and Kafka topics rather than by reaching into
 * each other's tables. See docs/adr/ADR-0001-modular-monolith.md for why one deployable was the
 * right call here and what would have to change to split it.
 *
 * <p>The same artifact runs in two roles, selected by Spring profile:
 *
 * <ul>
 *   <li>{@code api} - HTTP endpoints, SSE stream, no Kafka listeners.
 *   <li>{@code worker} - Kafka consumers and the detection scheduler, no HTTP traffic served.
 * </ul>
 *
 * <p>The default profile enables both, which is what local development and the Docker Compose stack
 * use.
 */
@SpringBootApplication
@EnableConfigurationProperties(SignalForgeProperties.class)
@EnableScheduling
@EnableAsync
public class SignalForgeApplication {

  public static void main(String[] args) {
    SpringApplication.run(SignalForgeApplication.class, args);
  }
}
