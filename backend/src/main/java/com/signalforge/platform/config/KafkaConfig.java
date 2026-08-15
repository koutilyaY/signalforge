package com.signalforge.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalforge.messaging.KafkaTopics;
import com.signalforge.messaging.TelemetryEventMessage;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka wiring: delivery semantics, retry classification and dead-lettering.
 *
 * <p><b>Delivery semantics.</b> This system assumes <em>at-least-once</em> delivery and does not
 * claim exactly-once. Exactly-once across a Kafka consume and a PostgreSQL write would require
 * either a distributed transaction or an outbox on both sides; neither is here. Instead the
 * consumers are idempotent, so duplicate delivery produces the same end state - "effectively once"
 * business behaviour, which is what actually matters. See ADR-0007.
 *
 * <p><b>Producer.</b> {@code acks=all} plus {@code enable.idempotence=true} means a record is
 * acknowledged only once it is on every in-sync replica, and a producer-side retry cannot write the
 * record twice. Combined with a bounded in-flight count this preserves per-partition ordering.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

  private final KafkaProperties kafkaProperties;

  public KafkaConfig(KafkaProperties kafkaProperties) {
    this.kafkaProperties = kafkaProperties;
  }

  @Bean
  public ProducerFactory<String, Object> producerFactory(ObjectMapper objectMapper) {
    Map<String, Object> config =
        new java.util.HashMap<>(kafkaProperties.buildProducerProperties(null));
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

    // Durability: wait for all in-sync replicas.
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    // Producer-side dedup - a retried send does not append the record twice.
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    // >1 would let a retried batch overtake a later one and reorder the partition.
    // 5 is the maximum the idempotent producer can still order correctly.
    config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
    config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
    config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
    // The default is 60 seconds. send() blocks for this long fetching metadata
    // when the broker is unreachable, so the default would tie up a request
    // thread for a full minute per ingestion call during a Kafka outage - the
    // API would stop responding well before Kafka came back. 5 seconds turns a
    // broker outage into a fast, honest 503 instead of a hang.
    config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
    // Batching: 10ms of linger buys much better throughput on the ingestion path
    // for latency nobody can perceive.
    config.put(ProducerConfig.LINGER_MS_CONFIG, 10);
    config.put(ProducerConfig.BATCH_SIZE_CONFIG, 32 * 1024);
    config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

    DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(config);
    factory.setValueSerializer(new JsonSerializer<>(objectMapper));
    return factory;
  }

  @Bean
  public KafkaTemplate<String, Object> kafkaTemplate(
      ProducerFactory<String, Object> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }

  @Bean
  public ConsumerFactory<String, Object> consumerFactory(ObjectMapper objectMapper) {
    Map<String, Object> config =
        new java.util.HashMap<>(kafkaProperties.buildConsumerProperties(null));
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    // ErrorHandlingDeserializer wrapping JsonDeserializer, and this matters more
    // than it looks. Deserialization happens inside poll(), BEFORE the listener
    // is invoked, so a DefaultErrorHandler cannot see or route the failure - the
    // container just throws, seeks back, and re-fetches the same bad record
    // forever. That is the classic poison-message partition stall.
    //
    // Wrapping it converts the failure into a DeserializationException carried
    // on a well-formed record, which the error handler CAN classify as
    // non-retryable and publish to the dead-letter topic.
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
    config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
    // Offsets are committed by the container after the listener returns, never
    // automatically on a timer - auto-commit would acknowledge messages the
    // listener has not actually processed yet.
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    // Bounded poll size keeps max.poll.interval achievable; a huge batch that
    // takes too long to process triggers a rebalance and reprocessing.
    config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
    config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
    // The consumer decides the target type, not the producer. Trusting a
    // producer-supplied __TypeId__ header means a malicious or simply mistaken
    // publisher can name any class on our classpath for us to instantiate -
    // that is the shape of a deserialization gadget attack. Turning the headers
    // off and pinning the type here removes the choice entirely.
    config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
    config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TelemetryEventMessage.class.getName());
    config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.signalforge.*");

    return new DefaultKafkaConsumerFactory<>(config);
  }

  /**
   * Topics are declared, not left to broker auto-creation.
   *
   * <p>KafkaAdmin applies these at startup. Auto-created topics inherit the broker's default
   * partition count (usually 1), which silently caps consumer parallelism at one thread no matter
   * what {@code concurrency} says - a performance bug that only shows up under load.
   *
   * <p>Increasing partitions here is applied; decreasing them is not possible in Kafka and is
   * ignored.
   */
  @Bean
  public KafkaAdmin.NewTopics signalForgeTopics() {
    return new KafkaAdmin.NewTopics(
        TopicBuilder.name(KafkaTopics.TELEMETRY_EVENTS).partitions(6).replicas(1).build(),
        TopicBuilder.name(KafkaTopics.TELEMETRY_EVENTS_DLT).partitions(1).replicas(1).build(),
        TopicBuilder.name(KafkaTopics.INCIDENT_EVENTS).partitions(3).replicas(1).build(),
        TopicBuilder.name(KafkaTopics.INCIDENT_EVENTS_DLT).partitions(1).replicas(1).build(),
        TopicBuilder.name(KafkaTopics.NOTIFICATION_EVENTS).partitions(3).replicas(1).build());
  }

  /**
   * Retry classification.
   *
   * <p>The distinction that matters: a <em>transient</em> failure (database briefly unreachable)
   * should be retried, while a <em>permanent</em> one (a malformed message that will never
   * deserialize) must not be - retrying it forever blocks the partition and stalls every other
   * message behind it. Permanent failures go straight to the dead-letter topic.
   */
  @Bean
  public DefaultErrorHandler telemetryErrorHandler(KafkaTemplate<String, Object> template) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, exception) ->
                // Route to <topic>-dlt, partition 0 - the DLT has one partition
                // and ordering there is irrelevant.
                new org.apache.kafka.common.TopicPartition(record.topic() + "-dlt", 0));

    // 5 attempts total (initial + 4 retries): 500ms, 1s, 2s, 4s. Beyond ~8
    // seconds a genuinely transient fault is unlikely to clear, and the message
    // is better off in the DLT where it can be inspected than blocking the
    // partition for every message behind it.
    ExponentialBackOff backOff = new ExponentialBackOff();
    backOff.setInitialInterval(500L);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(5_000L);
    backOff.setMaxAttempts(5);

    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

    // Permanent - no amount of retrying fixes these.
    handler.addNotRetryableExceptions(
        SerializationException.class,
        org.springframework.kafka.support.serializer.DeserializationException.class,
        org.springframework.messaging.converter.MessageConversionException.class,
        IllegalArgumentException.class,
        com.signalforge.messaging.PermanentMessageException.class);

    // Transient - worth retrying.
    handler.addRetryableExceptions(
        org.springframework.dao.TransientDataAccessException.class,
        org.springframework.dao.RecoverableDataAccessException.class,
        java.net.SocketTimeoutException.class);

    handler.setCommitRecovered(true);
    return handler;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
      ConsumerFactory<String, Object> consumerFactory, DefaultErrorHandler errorHandler) {

    ConcurrentKafkaListenerContainerFactory<String, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(errorHandler);
    // 3 threads against a 6-partition topic: each thread owns 2 partitions.
    // Going above the partition count just leaves threads idle.
    factory.setConcurrency(3);
    factory.setBatchListener(true);
    factory.getContainerProperties().setObservationEnabled(true);
    return factory;
  }

  /** Exposed for tests and health checks that need to know which topics must exist. */
  public static java.util.List<String> requiredTopics() {
    return java.util.List.of(
        KafkaTopics.TELEMETRY_EVENTS,
        KafkaTopics.TELEMETRY_EVENTS_DLT,
        KafkaTopics.INCIDENT_EVENTS,
        KafkaTopics.INCIDENT_EVENTS_DLT,
        KafkaTopics.NOTIFICATION_EVENTS);
  }
}
