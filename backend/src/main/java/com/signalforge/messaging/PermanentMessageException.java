package com.signalforge.messaging;

import java.io.Serial;

/**
 * Thrown by a consumer when a message can never succeed no matter how many times it is retried - a
 * nonexistent service id, a tenant that has been deleted, a field that violates a CHECK constraint.
 *
 * <p>{@link com.signalforge.platform.config.KafkaConfig} classifies this as non-retryable, so it
 * goes straight to the dead-letter topic instead of blocking its partition. Getting this
 * classification wrong in the other direction is the classic Kafka outage: one poison message
 * retried forever while every message behind it starves.
 */
public class PermanentMessageException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public PermanentMessageException(String message) {
    super(message);
  }

  public PermanentMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}
