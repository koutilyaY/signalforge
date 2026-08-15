#!/usr/bin/env bash
# Explicit topic creation. Auto-create is disabled on the broker so that
# partition count, replication and retention are deliberate choices rather than
# broker defaults.
#
# Partition rationale (single-broker local dev, but sized as it would be in prod):
#   telemetry-events    : 6 partitions - highest volume topic, keyed by service_id
#                          so all events for a service land on one partition and
#                          per-service ordering is preserved for detection windows.
#   telemetry-events-dlt: 1 partition  - low volume, no ordering requirement.
#   incident-events     : 3 partitions - keyed by incident_id so lifecycle
#                          transitions for one incident stay ordered.
#   notification-events : 3 partitions - keyed by organization_id.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
KCMD="/opt/kafka/bin/kafka-topics.sh --bootstrap-server ${BOOTSTRAP}"

echo "[kafka-init] waiting for broker at ${BOOTSTRAP}..."
for i in $(seq 1 60); do
  if ${KCMD} --list >/dev/null 2>&1; then
    echo "[kafka-init] broker is up"
    break
  fi
  sleep 2
done

create_topic() {
  local name="$1" partitions="$2" retention_ms="$3"
  if ${KCMD} --list 2>/dev/null | grep -qx "${name}"; then
    echo "[kafka-init] topic ${name} already exists"
  else
    echo "[kafka-init] creating topic ${name} (partitions=${partitions})"
    ${KCMD} --create \
      --topic "${name}" \
      --partitions "${partitions}" \
      --replication-factor 1 \
      --config "retention.ms=${retention_ms}" \
      --config "cleanup.policy=delete"
  fi
}

# 3 days retention on telemetry, 7 days on the lower-volume control topics.
create_topic "telemetry-events"      6 259200000
create_topic "telemetry-events-dlt"  1 604800000
create_topic "incident-events"       3 604800000
create_topic "incident-events-dlt"   1 604800000
create_topic "notification-events"   3 604800000

echo "[kafka-init] final topic list:"
${KCMD} --list
echo "[kafka-init] done"
