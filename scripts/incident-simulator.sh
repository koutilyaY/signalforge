#!/usr/bin/env bash
#
# Deterministic incident simulator.
#
# Drives a complete incident through the real API and measures the one number
# that matters for a detection engine: the wall-clock gap between the telemetry
# that breached a threshold and the incident row appearing.
#
# The scenario mirrors a real deployment-triggered outage:
#
#   1. checkout-service and payment-service register and report healthy traffic
#   2. payment-service deploys version 2.7.4
#   3. payment-service starts returning database errors
#   4. checkout-service starts failing on the same trace ids
#   5. SignalForge detects, opens an incident and correlates the deployment
#   6. an engineer acknowledges, rolls back, and resolves
#
# Nothing here is stubbed. Every step is an HTTP call to a running SignalForge.
#
#   ./scripts/incident-simulator.sh
#
set -euo pipefail

BASE_URL="${SF_BASE_URL:-http://localhost:8099}"
SLUG="sim-$(date +%s)"
PASSWORD="simulator-correct-horse-battery"

say() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
detail() { printf '  %s\n' "$*"; }

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
iso_now() { python3 -c 'import datetime; print(datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00","Z"))'; }
uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }

# ---------------------------------------------------------------------------
say "1/7  Registering organization ${SLUG}"

REGISTER=$(curl -sS -X POST "${BASE_URL}/api/v1/auth/register-organization" \
  -H 'Content-Type: application/json' \
  -d "{\"organizationName\":\"Simulator ${SLUG}\",\"organizationSlug\":\"${SLUG}\",
       \"adminEmail\":\"admin@${SLUG}.test\",\"adminFullName\":\"Sim Admin\",
       \"adminPassword\":\"${PASSWORD}\"}")

TOKEN=$(echo "$REGISTER" | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
AUTH=(-H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json')
detail "organization created, default detection rules seeded"

# ---------------------------------------------------------------------------
say "2/7  Registering services"

register_service() {
  curl -sS -X POST "${BASE_URL}/api/v1/services" "${AUTH[@]}" \
    -d "{\"name\":\"$1\",\"environment\":\"PRODUCTION\",\"criticality\":\"$2\",
         \"expectedP95LatencyMs\":500,\"expectedErrorRate\":0.01}" |
    python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])'
}

CHECKOUT=$(register_service checkout-service CRITICAL)
PAYMENT=$(register_service payment-service HIGH)
detail "checkout-service = ${CHECKOUT}"
detail "payment-service  = ${PAYMENT}"

# ---------------------------------------------------------------------------
say "3/7  Establishing a healthy baseline (200 requests per service)"

emit_batch() {
  local service_id="$1" count="$2" status="$3" latency="$4" type="${5:-HTTP_REQUEST}" errtype="${6:-}"
  python3 - "$service_id" "$count" "$status" "$latency" "$type" "$errtype" <<'PY' > /tmp/sf-sim-batch.json
import json, sys, uuid, datetime, random
service_id, count, status, latency, etype, errtype = sys.argv[1:7]
now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
events = []
for i in range(int(count)):
    e = {
        "eventId": str(uuid.uuid4()),
        "serviceId": service_id,
        "occurredAt": now,
        "eventType": etype,
        "traceId": f"trace-{random.randint(1, 200)}",
        "instanceKey": "instance-1",
    }
    if etype == "HTTP_REQUEST":
        e.update({"httpMethod": "POST", "httpPath": "/checkout",
                  "statusCode": int(status),
                  "latencyMs": int(latency) + random.randint(-15, 15)})
    if errtype:
        e["errorType"] = errtype
        e["errorMessage"] = f"{errtype} while handling request"
    events.append(e)
print(json.dumps({"events": events}))
PY
  curl -sS -X POST "${BASE_URL}/api/v1/ingest/events" "${AUTH[@]}" \
    --data-binary @/tmp/sf-sim-batch.json > /dev/null
}

emit_batch "$CHECKOUT" 200 200 60
emit_batch "$PAYMENT"  200 200 45
detail "baseline traffic accepted"

# ---------------------------------------------------------------------------
say "4/7  Deploying payment-service 2.7.4"

DEPLOY_START=$(iso_now)
DEPLOYMENT=$(curl -sS -X POST "${BASE_URL}/api/v1/deployments" "${AUTH[@]}" \
  -d "{\"serviceId\":\"${PAYMENT}\",\"version\":\"2.7.4\",\"commitSha\":\"9f2c1ab7d4e8\",
       \"branch\":\"main\",\"environment\":\"PRODUCTION\",\"deployedBy\":\"ci-pipeline\",
       \"startedAt\":\"${DEPLOY_START}\"}" |
  python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

curl -sS -X POST "${BASE_URL}/api/v1/deployments/${DEPLOYMENT}/completion" "${AUTH[@]}" \
  -d '{"status":"SUCCEEDED"}' > /dev/null
detail "deployment ${DEPLOYMENT} completed"

# ---------------------------------------------------------------------------
say "5/7  Injecting the failure and waiting for detection"

BREACH_MS=$(now_ms)

# payment-service starts timing out against its database; checkout-service
# fails on the same traces. 300 failures against a 200-request baseline puts
# the error rate far above the 1% SLA.
emit_batch "$PAYMENT"  300 500 2400 HTTP_REQUEST PaymentGatewayTimeout
emit_batch "$PAYMENT"   60 000 0    DATABASE_ERROR ConnectionPoolExhausted
emit_batch "$CHECKOUT" 300 500 2600 HTTP_REQUEST DownstreamUnavailable

detail "failure injected at $(date -r $((BREACH_MS/1000)) '+%H:%M:%S')"
detail "polling for an auto-created incident..."

INCIDENT_ID=""
DETECTED_MS=0
DEADLINE=$(( $(now_ms) + 180000 ))

while [ "$(now_ms)" -lt "$DEADLINE" ]; do
  RESPONSE=$(curl -sS "${BASE_URL}/api/v1/incidents?status=OPEN" "${AUTH[@]}")
  COUNT=$(echo "$RESPONSE" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')
  if [ "$COUNT" -gt 0 ]; then
    DETECTED_MS=$(now_ms)
    INCIDENT_ID=$(echo "$RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])')
    break
  fi
  sleep 1
done

if [ -z "$INCIDENT_ID" ]; then
  echo "FAILED: no incident was created within 180 seconds" >&2
  exit 1
fi

WALL_CLOCK_MS=$(( DETECTED_MS - BREACH_MS ))

INCIDENT=$(curl -sS "${BASE_URL}/api/v1/incidents/${INCIDENT_ID}" "${AUTH[@]}")
echo "$INCIDENT" | python3 -c '
import sys, json
d = json.load(sys.stdin)["incident"]
print("  reference        " + str(d["reference"]))
print("  title            " + str(d["title"]))
print("  severity         " + str(d["severity"]))
print("  server-measured  " + str(d["timeToDetectMs"]) + " ms  (window start -> detected_at)")
'
detail "client-observed  ${WALL_CLOCK_MS} ms  (failure injected -> incident visible via API)"

# ---------------------------------------------------------------------------
say "6/7  Correlation output"

curl -sS "${BASE_URL}/api/v1/incidents/${INCIDENT_ID}/correlation" "${AUTH[@]}" | python3 -c '
import sys, json
b = json.load(sys.stdin)
if not b["factors"]:
    print("  Insufficient evidence to determine root cause.")
for f in b["factors"]:
    print("  [" + str(f["confidence"]) + "%] " + f["kind"] + ": " + f["summary"])
    for e in f["evidence"][:3]:
        print("          - " + e)
'

# ---------------------------------------------------------------------------
say "7/7  Acknowledge, roll back, resolve"

transition() {
  curl -sS -X POST "${BASE_URL}/api/v1/incidents/${INCIDENT_ID}/transitions" "${AUTH[@]}" \
    -d "{\"status\":\"$1\"$2}" > /dev/null
}

transition ACKNOWLEDGED ""
transition INVESTIGATING ""

curl -sS -X POST "${BASE_URL}/api/v1/deployments" "${AUTH[@]}" \
  -d "{\"serviceId\":\"${PAYMENT}\",\"version\":\"2.7.3\",\"commitSha\":\"7a1b4cc02f19\",
       \"branch\":\"main\",\"environment\":\"PRODUCTION\",\"deployedBy\":\"rollback\"}" > /dev/null

emit_batch "$PAYMENT"  200 200 50
emit_batch "$CHECKOUT" 200 200 65

transition MITIGATED ""
transition RESOLVED ',"note":"Rolled back payment-service to 2.7.3; error rate recovered."'

curl -sS "${BASE_URL}/api/v1/incidents/${INCIDENT_ID}" "${AUTH[@]}" | python3 -c '
import sys, json
d = json.load(sys.stdin)
i = d["incident"]
print("  final status     " + str(i["status"]))
print("  time to detect   " + str(i["timeToDetectMs"]) + " ms")
print("  time to ack      " + str(i["timeToAcknowledgeMs"]) + " ms")
print("  time to resolve  " + str(i["timeToResolveMs"]) + " ms")
print("  timeline entries " + str(len(d["timeline"])))
'

say "Simulation complete"
echo "  organization slug: ${SLUG}"
echo "  incident:          ${BASE_URL}/api/v1/incidents/${INCIDENT_ID}"
echo
echo "  DETECTION LATENCY (client-observed): ${WALL_CLOCK_MS} ms"
echo "  Note: bounded below by signalforge.detection.evaluation-interval (default 15s)."
