package com.signalforge.registry.domain;

/**
 * Current health of a service as judged by the detection pipeline.
 *
 * <p>UNKNOWN is a first-class state, not a null substitute: a service that has never reported
 * telemetry is genuinely different from one reporting healthily, and showing it as HEALTHY would be
 * a lie the dashboard tells during an outage where a service stopped emitting entirely.
 */
public enum HealthStatus {
  HEALTHY,
  DEGRADED,
  DOWN,
  UNKNOWN
}
