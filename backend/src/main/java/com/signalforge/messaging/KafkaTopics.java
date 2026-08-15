package com.signalforge.messaging;

/** Topic names in one place so a typo is a compile error rather than a silently dead consumer. */
public final class KafkaTopics {

  public static final String TELEMETRY_EVENTS = "telemetry-events";
  public static final String TELEMETRY_EVENTS_DLT = "telemetry-events-dlt";
  public static final String INCIDENT_EVENTS = "incident-events";
  public static final String INCIDENT_EVENTS_DLT = "incident-events-dlt";
  public static final String NOTIFICATION_EVENTS = "notification-events";

  /** Consumer group ids. Changing one of these replays the topic from the configured offset. */
  public static final String GROUP_TELEMETRY_PERSIST = "sf-telemetry-persist";

  public static final String GROUP_INCIDENT_NOTIFY = "sf-incident-notify";

  private KafkaTopics() {}
}
