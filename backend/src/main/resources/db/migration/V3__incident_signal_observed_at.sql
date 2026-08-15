-- =============================================================================
-- V3: Fix the detection-latency metric.
--
-- The defect this corrects was found by the end-to-end incident simulator, not
-- by a unit test: it reported timeToDetectMs = 300,023 ms for an incident that
-- was genuinely detected in under eleven seconds.
--
-- The cause: `started_at` holds the *detection window start*, which is correct
-- for evidence gathering - the incident's supporting data really does begin
-- there - but a metric derived as (detected_at - started_at) is then dominated
-- by the rule's window length and says nothing about detection speed.
--
-- `signal_observed_at` records the timestamp of the most recent telemetry the
-- breaching evaluation actually saw. (detected_at - signal_observed_at) is
-- therefore "how long after the data existed did we open an incident", which is
-- the number an SRE means by detection latency.
--
-- Nullable because incidents created before this migration have no value for
-- it, and backfilling a number we never measured would be fabrication.
-- =============================================================================

ALTER TABLE incidents
    ADD COLUMN signal_observed_at TIMESTAMPTZ;

COMMENT ON COLUMN incidents.started_at IS
    'Start of the detection window that produced this incident. Evidence boundary, NOT a detection-speed input.';

COMMENT ON COLUMN incidents.signal_observed_at IS
    'Timestamp of the most recent telemetry the breaching evaluation observed. Detection latency = detected_at - signal_observed_at.';

CREATE INDEX idx_incidents_org_signal_observed
    ON incidents (organization_id, signal_observed_at DESC)
    WHERE signal_observed_at IS NOT NULL;
