package com.signalforge.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need real infrastructure.
 *
 * <p>Uses real PostgreSQL and real Redis via Testcontainers rather than H2 and an embedded fake.
 * That is not gold-plating: this codebase depends on PostgreSQL-specific behaviour that H2 does not
 * reproduce - partial unique indexes, {@code jsonb}, {@code gen_random_uuid()}, the append-only
 * audit trigger, and {@code ON CONFLICT DO NOTHING} semantics. A green H2 suite would tell us
 * almost nothing about whether the real thing works.
 *
 * <p>Containers are {@code static} so a single Postgres and a single Redis are shared across every
 * test class in the JVM. Testcontainers' Ryuk sidecar reaps them at exit. Per-class containers
 * would add roughly 2 seconds of startup per class for no isolation benefit, because each test
 * truncates the tables it uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
public abstract class AbstractIntegrationTest {

  // Deliberately NOT @ServiceConnection. That would point the runtime datasource
  // at the container's superuser, which bypasses row-level security and would
  // make RowLevelSecurityIT pass for the wrong reason - or rather, fail, which
  // is how this was caught. Credentials are wired explicitly below instead.
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("signalforge_test")
          .withUsername("signalforge")
          .withPassword("signalforge")
          .withReuse(true);

  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  /**
   * A real broker rather than {@code EmbeddedKafka}. The embedded broker is a different codebase
   * with different defaults; the behaviour under test here - producer idempotence, consumer
   * rebalancing, dead-letter routing - is precisely where those defaults diverge.
   *
   * <p>KRaft mode, single node, using the same {@code apache/kafka} image the Compose stack runs.
   */
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0")).withReuse(true);

  static {
    // Started manually rather than through the JUnit extension so all three are
    // up before Spring's DynamicPropertySource is evaluated.
    POSTGRES.start();
    REDIS.start();
    KAFKA.start();
  }

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

    // Flyway migrates as the container's owner/superuser; it needs CREATE ROLE.
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("spring.flyway.placeholders.app_role_password", () -> APP_ROLE_PASSWORD);

    // The application connects as the restricted role V4 creates, so RLS
    // actually applies to it.
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "signalforge_app");
    registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
  }

  private static final String APP_ROLE_PASSWORD = "test-app-role-password";

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
}
