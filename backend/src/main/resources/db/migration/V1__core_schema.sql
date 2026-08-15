-- =============================================================================
-- V1: Core schema - tenancy, identity, service registry, deployments.
--
-- Conventions used throughout SignalForge:
--   * UUID primary keys (gen_random_uuid(), built into PostgreSQL 13+).
--   * Every tenant-scoped table carries organization_id as a real column, NOT
--     just a join away. Tenant isolation is enforced in the WHERE clause of
--     every query, so the discriminator must be local to the row - otherwise
--     every read needs a join and every index gets less selective.
--   * timestamptz everywhere. Never `timestamp`.
--   * created_at / updated_at on mutable entities; append-only tables get
--     created_at only.
--   * `version` columns where two users can realistically collide (services,
--     incidents) to drive JPA optimistic locking.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Organizations (the tenant boundary)
-- ---------------------------------------------------------------------------
CREATE TABLE organizations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(200) NOT NULL,
    slug                    VARCHAR(80)  NOT NULL,
    -- Per-tenant ingestion budget. Enforced in Redis; persisted here so it
    -- survives a cache flush and is auditable.
    ingest_rate_limit_per_min INTEGER    NOT NULL DEFAULT 6000,
    telemetry_retention_days  INTEGER    NOT NULL DEFAULT 14,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_organizations_slug UNIQUE (slug),
    CONSTRAINT ck_organizations_slug_format CHECK (slug ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'),
    CONSTRAINT ck_organizations_rate_limit CHECK (ingest_rate_limit_per_min > 0),
    CONSTRAINT ck_organizations_retention CHECK (telemetry_retention_days BETWEEN 1 AND 365)
);

-- ---------------------------------------------------------------------------
-- Roles (lookup table; `rank` lets code express "at least ENGINEER")
-- ---------------------------------------------------------------------------
CREATE TABLE roles (
    code        VARCHAR(20) PRIMARY KEY,
    description VARCHAR(300) NOT NULL,
    rank        SMALLINT     NOT NULL,
    CONSTRAINT uq_roles_rank UNIQUE (rank)
);

INSERT INTO roles (code, description, rank) VALUES
    ('VIEWER',   'Read-only access to services, incidents, deployments and analytics.', 10),
    ('ENGINEER', 'Everything VIEWER can do, plus manage services, ingest telemetry and drive incident lifecycle.', 20),
    ('ADMIN',    'Everything ENGINEER can do, plus manage organization settings, users, roles and API keys.', 30);

-- ---------------------------------------------------------------------------
-- Users
--
-- email is globally unique (not per-organization). This makes login a single
-- lookup with no tenant-selection step. See ADR-0008 for the trade-off: a human
-- who belongs to two organizations needs two accounts.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email           VARCHAR(320) NOT NULL,
    password_hash   VARCHAR(120) NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    role_code       VARCHAR(20)  NOT NULL REFERENCES roles(code),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_format CHECK (position('@' IN email) > 1)
);

CREATE INDEX idx_users_org ON users (organization_id, email);

-- ---------------------------------------------------------------------------
-- API keys - how a monitored service authenticates to the ingestion endpoint.
-- Only the hash is stored; the plaintext key is shown once at creation.
-- ---------------------------------------------------------------------------
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    -- SHA-256 of the plaintext key, hex encoded. Lookup is by prefix + hash
    -- compare, so this is indexed.
    key_hash        VARCHAR(64)  NOT NULL,
    key_prefix      VARCHAR(16)  NOT NULL,
    created_by      UUID         REFERENCES users(id) ON DELETE SET NULL,
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_api_keys_hash UNIQUE (key_hash),
    CONSTRAINT uq_api_keys_org_name UNIQUE (organization_id, name)
);

CREATE INDEX idx_api_keys_org ON api_keys (organization_id) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- Services (the registry)
-- ---------------------------------------------------------------------------
CREATE TABLE services (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name                     VARCHAR(120) NOT NULL,
    description              VARCHAR(1000),
    environment              VARCHAR(20)  NOT NULL,
    team                     VARCHAR(120),
    repository_url           VARCHAR(500),
    health_endpoint          VARCHAR(500),
    criticality              VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',
    -- SLA targets that detection rules compare against.
    expected_p95_latency_ms  INTEGER      NOT NULL DEFAULT 500,
    expected_error_rate      NUMERIC(5,4) NOT NULL DEFAULT 0.0100,
    -- Denormalised current health, maintained by the detection pipeline. Redis
    -- holds the hot copy; this column is the durable fallback.
    health_status            VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    health_changed_at        TIMESTAMPTZ,
    archived_at              TIMESTAMPTZ,
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_services_org_name_env UNIQUE (organization_id, name, environment),
    CONSTRAINT ck_services_env CHECK (environment IN ('PRODUCTION','STAGING','DEVELOPMENT')),
    CONSTRAINT ck_services_criticality CHECK (criticality IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_services_health CHECK (health_status IN ('HEALTHY','DEGRADED','DOWN','UNKNOWN')),
    CONSTRAINT ck_services_latency CHECK (expected_p95_latency_ms BETWEEN 1 AND 600000),
    CONSTRAINT ck_services_error_rate CHECK (expected_error_rate >= 0 AND expected_error_rate <= 1),
    CONSTRAINT ck_services_name_format CHECK (name ~ '^[a-zA-Z0-9]([a-zA-Z0-9._-]*[a-zA-Z0-9])?$')
);

-- Tenant-first composite: every listing query starts with organization_id.
CREATE INDEX idx_services_org_env ON services (organization_id, environment, name)
    WHERE archived_at IS NULL;
CREATE INDEX idx_services_org_health ON services (organization_id, health_status)
    WHERE archived_at IS NULL;

-- ---------------------------------------------------------------------------
-- Service instances (a single running replica of a service)
-- ---------------------------------------------------------------------------
CREATE TABLE service_instances (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id        UUID         NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    instance_key      VARCHAR(200) NOT NULL,
    host              VARCHAR(255),
    region            VARCHAR(60),
    version           VARCHAR(80),
    status            VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    last_heartbeat_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_instances UNIQUE (service_id, instance_key),
    CONSTRAINT ck_service_instances_status CHECK (status IN ('UP','DOWN','UNKNOWN'))
);

CREATE INDEX idx_service_instances_org_service ON service_instances (organization_id, service_id);

-- ---------------------------------------------------------------------------
-- Service dependencies. `discovered` distinguishes operator-declared edges from
-- edges inferred by observing shared trace ids.
-- ---------------------------------------------------------------------------
CREATE TABLE service_dependencies (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id             UUID        NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    depends_on_service_id  UUID        NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    discovered             BOOLEAN     NOT NULL DEFAULT FALSE,
    call_count             BIGINT      NOT NULL DEFAULT 0,
    last_seen_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_dependencies UNIQUE (service_id, depends_on_service_id),
    CONSTRAINT ck_service_dependencies_no_self CHECK (service_id <> depends_on_service_id)
);

CREATE INDEX idx_service_dependencies_org ON service_dependencies (organization_id, service_id);

-- ---------------------------------------------------------------------------
-- Deployments
-- ---------------------------------------------------------------------------
CREATE TABLE deployments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id       UUID         NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    version          VARCHAR(80)  NOT NULL,
    commit_sha       VARCHAR(80),
    branch           VARCHAR(200),
    environment      VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    deployed_by      VARCHAR(200),
    started_at       TIMESTAMPTZ  NOT NULL,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_deployments_status CHECK (status IN ('IN_PROGRESS','SUCCEEDED','FAILED','ROLLED_BACK')),
    CONSTRAINT ck_deployments_env CHECK (environment IN ('PRODUCTION','STAGING','DEVELOPMENT')),
    CONSTRAINT ck_deployments_times CHECK (completed_at IS NULL OR completed_at >= started_at)
);

-- Deployment correlation asks: "which deployments for this org landed in the
-- window [incidentStart - 60min, incidentStart]?". DESC ordering on the
-- timestamp makes that a backwards index scan with no sort.
CREATE INDEX idx_deployments_org_started ON deployments (organization_id, started_at DESC);
CREATE INDEX idx_deployments_org_service_started ON deployments (organization_id, service_id, started_at DESC);

-- ---------------------------------------------------------------------------
-- Shared trigger: keep updated_at honest without relying on the ORM.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sf_touch_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_organizations_touch BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
CREATE TRIGGER trg_users_touch BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
CREATE TRIGGER trg_api_keys_touch BEFORE UPDATE ON api_keys
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
CREATE TRIGGER trg_services_touch BEFORE UPDATE ON services
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
CREATE TRIGGER trg_service_instances_touch BEFORE UPDATE ON service_instances
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
CREATE TRIGGER trg_service_dependencies_touch BEFORE UPDATE ON service_dependencies
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
CREATE TRIGGER trg_deployments_touch BEFORE UPDATE ON deployments
    FOR EACH ROW EXECUTE FUNCTION sf_touch_updated_at();
