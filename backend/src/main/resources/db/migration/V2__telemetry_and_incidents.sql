-- =============================================================================
-- V2: Telemetry, detection rules, alerts, incidents, audit, idempotency.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Telemetry events - the highest-volume table in the system.
--
-- Design notes:
--   * `event_id` is supplied by the producer. UNIQUE (organization_id, event_id)
--     is what turns at-least-once Kafka delivery into effectively-once business
--     behaviour: a redelivered message hits this constraint and is swallowed.
--   * Hot query columns (latency_ms, status_code, error_type) are promoted out
--     of the jsonb payload so they can be indexed and aggregated without
--     per-row JSON parsing.
--   * `metadata` keeps whatever else the producer sent, so the schema does not
--     have to grow a column per integration.
-- ---------------------------------------------------------------------------
CREATE TABLE telemetry_events (
    id               BIGSERIAL PRIMARY KEY,
    event_id         UUID         NOT NULL,
    organization_id  UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id       UUID         NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    occurred_at      TIMESTAMPTZ  NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    event_type       VARCHAR(30)  NOT NULL,
    severity         VARCHAR(10)  NOT NULL DEFAULT 'INFO',
    trace_id         VARCHAR(64),
    span_id          VARCHAR(32),
    correlation_id   VARCHAR(120),
    -- Promoted hot fields
    http_method      VARCHAR(10),
    http_path        VARCHAR(500),
    status_code      SMALLINT,
    latency_ms       INTEGER,
    error_type       VARCHAR(200),
    error_message    VARCHAR(2000),
    instance_key     VARCHAR(200),
    consumer_lag     BIGINT,
    metadata         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_telemetry_org_event UNIQUE (organization_id, event_id),
    CONSTRAINT ck_telemetry_type CHECK (event_type IN (
        'HTTP_REQUEST','APPLICATION_ERROR','DATABASE_ERROR','KAFKA_LAG',
        'SERVICE_DOWN','SERVICE_RECOVERED','DEPLOYMENT_STARTED','DEPLOYMENT_COMPLETED')),
    CONSTRAINT ck_telemetry_severity CHECK (severity IN ('DEBUG','INFO','WARN','ERROR','CRITICAL')),
    CONSTRAINT ck_telemetry_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

-- Primary read pattern: "recent events for one service in this org".
-- occurred_at DESC so ORDER BY occurred_at DESC is a backwards index scan.
CREATE INDEX idx_telemetry_org_service_time
    ON telemetry_events (organization_id, service_id, occurred_at DESC);

-- Detection windows filter by type as well: "ERRORs for service X in last 5m".
CREATE INDEX idx_telemetry_org_service_type_time
    ON telemetry_events (organization_id, service_id, event_type, occurred_at DESC);

-- Org-wide feeds and the dashboard's "recent activity" panel.
CREATE INDEX idx_telemetry_org_time
    ON telemetry_events (organization_id, occurred_at DESC);

-- Trace correlation. Partial: the vast majority of rows in a real system do
-- carry a trace id, but rows without one are useless for this lookup and
-- excluding them keeps the index smaller.
CREATE INDEX idx_telemetry_org_trace
    ON telemetry_events (organization_id, trace_id, occurred_at)
    WHERE trace_id IS NOT NULL;

-- Error-signature clustering during correlation.
CREATE INDEX idx_telemetry_org_error_type
    ON telemetry_events (organization_id, error_type, occurred_at DESC)
    WHERE error_type IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Per-minute rollups.
--
-- This table exists purely as a query-performance optimisation for the
-- dashboard and analytics pages. Computing p95 with percentile_cont() over raw
-- telemetry is exact but scans every row in the window; that cost is measured
-- in docs/benchmarks/query-optimization.md.
--
-- Latency is stored as a cumulative histogram with fixed boundaries, so p95 is
-- interpolated the same way Prometheus' histogram_quantile does. This is an
-- APPROXIMATION and is documented as such - it is not claimed to be exact.
-- Boundaries (ms): 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, +Inf
-- ---------------------------------------------------------------------------
CREATE TABLE telemetry_minute_rollups (
    organization_id  UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id       UUID        NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    bucket_start     TIMESTAMPTZ NOT NULL,
    request_count    BIGINT      NOT NULL DEFAULT 0,
    error_count      BIGINT      NOT NULL DEFAULT 0,
    server_error_count BIGINT    NOT NULL DEFAULT 0,
    latency_sum_ms   BIGINT      NOT NULL DEFAULT 0,
    latency_max_ms   INTEGER     NOT NULL DEFAULT 0,
    -- cumulative "less than or equal to" counters
    le_5             BIGINT      NOT NULL DEFAULT 0,
    le_10            BIGINT      NOT NULL DEFAULT 0,
    le_25            BIGINT      NOT NULL DEFAULT 0,
    le_50            BIGINT      NOT NULL DEFAULT 0,
    le_100           BIGINT      NOT NULL DEFAULT 0,
    le_250           BIGINT      NOT NULL DEFAULT 0,
    le_500           BIGINT      NOT NULL DEFAULT 0,
    le_1000          BIGINT      NOT NULL DEFAULT 0,
    le_2500          BIGINT      NOT NULL DEFAULT 0,
    le_5000          BIGINT      NOT NULL DEFAULT 0,
    le_inf           BIGINT      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_telemetry_minute_rollups PRIMARY KEY (organization_id, service_id, bucket_start)
);

CREATE INDEX idx_rollups_org_time ON telemetry_minute_rollups (organization_id, bucket_start DESC);

-- ---------------------------------------------------------------------------
-- Detection rules
-- ---------------------------------------------------------------------------
CREATE TABLE detection_rules (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    -- NULL service_id means "applies to every service in this organization".
    service_id        UUID         REFERENCES services(id) ON DELETE CASCADE,
    name              VARCHAR(160) NOT NULL,
    description       VARCHAR(1000),
    rule_type         VARCHAR(30)  NOT NULL,
    -- Interpretation depends on rule_type: a ratio for ERROR_RATE, milliseconds
    -- for P95_LATENCY, a message count for KAFKA_LAG, an event count for the
    -- spike rules. NULL means "use the service's configured SLA".
    threshold         NUMERIC(12,4),
    window_seconds    INTEGER      NOT NULL DEFAULT 300,
    -- Guards against a 1-request window with a single failure reading as 100%.
    min_sample_size   INTEGER      NOT NULL DEFAULT 20,
    severity          VARCHAR(10)  NOT NULL DEFAULT 'HIGH',
    -- After firing, this rule is muted for this long for the same fingerprint.
    cooldown_seconds  INTEGER      NOT NULL DEFAULT 900,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_detection_rules_org_name UNIQUE (organization_id, name),
    CONSTRAINT ck_detection_rules_type CHECK (rule_type IN (
        'ERROR_RATE','P95_LATENCY','SERVICE_DOWN','KAFKA_LAG','DATABASE_ERROR_SPIKE','CORRELATED_ERRORS')),
    CONSTRAINT ck_detection_rules_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_detection_rules_window CHECK (window_seconds BETWEEN 30 AND 86400),
    CONSTRAINT ck_detection_rules_cooldown CHECK (cooldown_seconds BETWEEN 0 AND 86400),
    CONSTRAINT ck_detection_rules_sample CHECK (min_sample_size >= 1)
);

CREATE INDEX idx_detection_rules_org_enabled ON detection_rules (organization_id) WHERE enabled;

-- ---------------------------------------------------------------------------
-- Incidents
--
-- `fingerprint` identifies "the same problem". The partial unique index below
-- is the real guarantee that a flapping service produces one incident rather
-- than one per evaluation tick - enforced by the database, not by application
-- logic that races with itself across consumer threads.
-- ---------------------------------------------------------------------------
CREATE TABLE incidents (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    reference             VARCHAR(20)  NOT NULL,
    title                 VARCHAR(300) NOT NULL,
    description           VARCHAR(4000),
    severity              VARCHAR(10)  NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    source                VARCHAR(10)  NOT NULL DEFAULT 'AUTO',
    fingerprint           VARCHAR(200) NOT NULL,
    detection_rule_id     UUID         REFERENCES detection_rules(id) ON DELETE SET NULL,
    primary_service_id    UUID         REFERENCES services(id) ON DELETE SET NULL,
    -- started_at = when the offending signal began (window start).
    -- detected_at = when SignalForge created the incident.
    -- The difference is the detection latency benchmark.
    started_at            TIMESTAMPTZ  NOT NULL,
    detected_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    acknowledged_at       TIMESTAMPTZ,
    acknowledged_by       UUID         REFERENCES users(id) ON DELETE SET NULL,
    mitigated_at          TIMESTAMPTZ,
    resolved_at           TIMESTAMPTZ,
    resolved_by           UUID         REFERENCES users(id) ON DELETE SET NULL,
    resolution_note       VARCHAR(4000),
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_incidents_org_reference UNIQUE (organization_id, reference),
    CONSTRAINT ck_incidents_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_incidents_status CHECK (status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MITIGATED','RESOLVED')),
    CONSTRAINT ck_incidents_source CHECK (source IN ('AUTO','MANUAL')),
    CONSTRAINT ck_incidents_resolved_consistency
        CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

-- THE dedup guarantee: at most one non-resolved incident per fingerprint per
-- organization. Concurrent detectors race into this and exactly one wins.
CREATE UNIQUE INDEX uq_incidents_active_fingerprint
    ON incidents (organization_id, fingerprint)
    WHERE status <> 'RESOLVED';

CREATE INDEX idx_incidents_org_status_detected
    ON incidents (organization_id, status, detected_at DESC);
CREATE INDEX idx_incidents_org_detected
    ON incidents (organization_id, detected_at DESC);
CREATE INDEX idx_incidents_org_service
    ON incidents (organization_id, primary_service_id, detected_at DESC);

-- Per-organization human-friendly counter: INC-1, INC-2, ...
CREATE TABLE incident_counters (
    organization_id UUID   PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    last_value      BIGINT NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- Incident timeline. Append-only.
-- ---------------------------------------------------------------------------
CREATE TABLE incident_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id     UUID         NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    kind            VARCHAR(30)  NOT NULL,
    title           VARCHAR(300) NOT NULL,
    detail          VARCHAR(4000),
    actor_user_id   UUID         REFERENCES users(id) ON DELETE SET NULL,
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_incident_events_kind CHECK (kind IN (
        'DETECTED','STATUS_CHANGE','COMMENT','DEPLOYMENT','EVIDENCE',
        'ALERT','RECOVERY','AI_SUMMARY','SEVERITY_CHANGE'))
);

CREATE INDEX idx_incident_events_incident ON incident_events (incident_id, occurred_at);

-- ---------------------------------------------------------------------------
-- Incident <-> service association
-- ---------------------------------------------------------------------------
CREATE TABLE incident_services (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id     UUID        NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    service_id      UUID        NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL DEFAULT 'AFFECTED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_incident_services UNIQUE (incident_id, service_id),
    CONSTRAINT ck_incident_services_role CHECK (role IN ('PRIMARY','AFFECTED','SUSPECTED'))
);

CREATE INDEX idx_incident_services_service ON incident_services (organization_id, service_id);

-- ---------------------------------------------------------------------------
-- Incident comments
-- ---------------------------------------------------------------------------
CREATE TABLE incident_comments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id     UUID         NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    author_user_id  UUID         REFERENCES users(id) ON DELETE SET NULL,
    body            VARCHAR(4000) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_incident_comments_incident ON incident_comments (incident_id, created_at);

-- ---------------------------------------------------------------------------
-- Alerts - a rule firing. An alert may or may not open an incident (cooldown).
-- ---------------------------------------------------------------------------
CREATE TABLE alerts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id        UUID         NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    detection_rule_id UUID         REFERENCES detection_rules(id) ON DELETE SET NULL,
    incident_id       UUID         REFERENCES incidents(id) ON DELETE SET NULL,
    fingerprint       VARCHAR(200) NOT NULL,
    severity          VARCHAR(10)  NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'FIRING',
    summary           VARCHAR(500) NOT NULL,
    observed_value    NUMERIC(14,4),
    threshold_value   NUMERIC(14,4),
    sample_size       INTEGER,
    window_start      TIMESTAMPTZ  NOT NULL,
    window_end        TIMESTAMPTZ  NOT NULL,
    triggered_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_alerts_status CHECK (status IN ('FIRING','RESOLVED','SUPPRESSED')),
    CONSTRAINT ck_alerts_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL'))
);

CREATE INDEX idx_alerts_org_triggered ON alerts (organization_id, triggered_at DESC);
CREATE INDEX idx_alerts_incident ON alerts (incident_id) WHERE incident_id IS NOT NULL;
CREATE INDEX idx_alerts_org_service_triggered ON alerts (organization_id, service_id, triggered_at DESC);

-- ---------------------------------------------------------------------------
-- AI summaries. Kept separate from `incidents` so that a missing/failed LLM is
-- structurally incapable of blocking incident creation - there is simply no
-- row here.
-- ---------------------------------------------------------------------------
CREATE TABLE incident_ai_summaries (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id        UUID         NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    model              VARCHAR(120) NOT NULL,
    summary            TEXT         NOT NULL,
    likely_causes      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    recommended_steps  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    -- The exact evidence bundle handed to the model, so any claim can be
    -- audited after the fact.
    evidence_snapshot  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    generation_ms      INTEGER,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_incident_ai_summaries UNIQUE (incident_id)
);

-- ---------------------------------------------------------------------------
-- Notification preferences
-- ---------------------------------------------------------------------------
CREATE TABLE notification_preferences (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel         VARCHAR(20) NOT NULL DEFAULT 'IN_APP',
    min_severity    VARCHAR(10) NOT NULL DEFAULT 'MEDIUM',
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    webhook_url     VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_prefs UNIQUE (user_id, channel),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('IN_APP','WEBHOOK')),
    CONSTRAINT ck_notification_severity CHECK (min_severity IN ('LOW','MEDIUM','HIGH','CRITICAL'))
);

-- ---------------------------------------------------------------------------
-- Audit log. Immutable through the application: the trigger below rejects any
-- UPDATE or DELETE regardless of what the ORM tries to do.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    actor_user_id   UUID,
    actor_email     VARCHAR(320),
    action          VARCHAR(80)  NOT NULL,
    resource_type   VARCHAR(60)  NOT NULL,
    resource_id     VARCHAR(120),
    outcome         VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    ip_address      VARCHAR(60),
    user_agent      VARCHAR(400),
    correlation_id  VARCHAR(120),
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS','FAILURE','DENIED'))
);

CREATE INDEX idx_audit_org_created ON audit_events (organization_id, created_at DESC);
CREATE INDEX idx_audit_org_action ON audit_events (organization_id, action, created_at DESC);

CREATE OR REPLACE FUNCTION sf_reject_audit_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only (attempted %)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION sf_reject_audit_mutation();

-- ---------------------------------------------------------------------------
-- Kafka idempotency ledger.
--
-- Consumers record (consumer_group, message_id) before committing their work in
-- the same transaction. A redelivery hits the primary key and is skipped.
-- ---------------------------------------------------------------------------
CREATE TABLE processed_messages (
    consumer_group  VARCHAR(120) NOT NULL,
    message_id      UUID         NOT NULL,
    organization_id UUID,
    topic           VARCHAR(120) NOT NULL,
    partition_id    INTEGER      NOT NULL,
    record_offset   BIGINT       NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_messages PRIMARY KEY (consumer_group, message_id)
);

-- Supports periodic pruning of the ledger.
CREATE INDEX idx_processed_messages_processed_at ON processed_messages (processed_at);

-- ---------------------------------------------------------------------------
-- Touch triggers
-- ---------------------------------------------------------------------------
CREATE TRIGGER trg_detection_rules_touch BEFORE UPDATE ON detection_rules
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
CREATE TRIGGER trg_incidents_touch BEFORE UPDATE ON incidents
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
CREATE TRIGGER trg_incident_comments_touch BEFORE UPDATE ON incident_comments
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
CREATE TRIGGER trg_notification_prefs_touch BEFORE UPDATE ON notification_preferences
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
CREATE TRIGGER trg_rollups_touch BEFORE UPDATE ON telemetry_minute_rollups
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
