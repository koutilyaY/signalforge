-- =============================================================================
-- V4: PostgreSQL row-level security as the third tenant-isolation layer.
--
-- Layers 1 and 2 (token-derived tenant, query-level scoping) are application
-- code, and application code has bugs. This layer means a query that *forgets*
-- its organization_id predicate returns zero rows instead of another tenant's
-- data.
--
-- HOW IT WORKS
--   The application sets `signalforge.current_org` on the connection at the
--   start of every unit of work (see TenantAwareDataSource). Every policy below
--   compares organization_id against it. With the setting absent, the policy
--   evaluates to NULL, which is not TRUE, so nothing is visible.
--
-- WHY A SEPARATE ROLE IS NOT OPTIONAL
--   PostgreSQL exempts a table's owner from its own policies, and exempts
--   SUPERUSERS unconditionally - `FORCE ROW LEVEL SECURITY` does not change
--   that. The `postgres` Docker image creates POSTGRES_USER as a superuser, so
--   an application connecting with those credentials would bypass every policy
--   below and this migration would be decorative.
--
--   That was not a theoretical concern: the first version of this migration was
--   written that way, and RowLevelSecurityIT failed every assertion because the
--   database cheerfully returned other tenants' rows.
--
--   So: `signalforge_app` is a plain LOGIN role with DML grants and nothing
--   else. It owns no tables, so it cannot ALTER them to disable RLS, and it is
--   not a superuser, so it cannot bypass it. Flyway continues to connect as the
--   owner; the runtime connects as this role.
--
-- THE ESCAPE HATCH
--   The detection sweep must enumerate which organizations have enabled rules
--   before it can scope itself to any one of them. `sf_organizations_with_rules()`
--   is a SECURITY DEFINER function returning *only* organization ids - no
--   telemetry, no incidents, no user data. It is the single, narrow, auditable
--   bypass in the system.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- The runtime role.
--
-- CREATE ROLE has no IF NOT EXISTS, and roles are cluster-wide rather than
-- per-database, so this has to be conditional to stay re-runnable against a
-- cluster where a previous database already created it.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'signalforge_app') THEN
        EXECUTE format('CREATE ROLE signalforge_app LOGIN PASSWORD %L', '${app_role_password}');
    ELSE
        EXECUTE format('ALTER ROLE signalforge_app LOGIN PASSWORD %L', '${app_role_password}');
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO signalforge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO signalforge_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO signalforge_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO signalforge_app;

-- Any table added by a later migration is granted automatically. Without this,
-- V5 would create a table the application cannot read and the failure would
-- only show up at runtime.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO signalforge_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO signalforge_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO signalforge_app;

-- ---------------------------------------------------------------------------
-- Helper: the current tenant, or NULL when unset.
-- STABLE, so the planner evaluates it once per query rather than per row.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sf_current_org() RETURNS UUID AS $$
    SELECT NULLIF(current_setting('signalforge.current_org', true), '')::uuid;
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------------
-- Narrow escape hatch for the detection scheduler.
-- SECURITY DEFINER runs as the function owner, bypassing RLS - which is exactly
-- why it returns nothing but ids.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sf_organizations_with_rules() RETURNS SETOF UUID AS $$
    SELECT DISTINCT organization_id FROM detection_rules WHERE enabled = true;
$$ LANGUAGE sql STABLE SECURITY DEFINER;

COMMENT ON FUNCTION sf_organizations_with_rules() IS
    'RLS bypass, deliberately minimal: returns organization ids only, so the detection sweep can iterate tenants and then scope itself to each one.';

-- ---------------------------------------------------------------------------
-- Authentication lookups.
--
-- Two queries in the system are legitimately cross-tenant, because they are what
-- *establishes* the tenant: finding a user by email at login, and finding an API
-- key by its hash. Neither can be scoped, because at that moment nothing is
-- known about which tenant is being addressed.
--
-- Rather than punch a broad hole in the users/api_keys policies with a
-- "currently authenticating" flag - which would be set by a filter and could be
-- left set - each gets its own SECURITY DEFINER function. The bypass is then
-- exactly one query shape wide, and it is visible in the schema.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sf_find_user_by_email(p_email TEXT) RETURNS SETOF users AS $$
    SELECT * FROM users WHERE email = p_email;
$$ LANGUAGE sql STABLE SECURITY DEFINER;

COMMENT ON FUNCTION sf_find_user_by_email(TEXT) IS
    'Login lookup. Necessarily cross-tenant: the account is what determines the tenant. Single-purpose RLS bypass.';

CREATE OR REPLACE FUNCTION sf_find_active_api_key(p_hash TEXT) RETURNS SETOF api_keys AS $$
    SELECT * FROM api_keys WHERE key_hash = p_hash AND revoked_at IS NULL;
$$ LANGUAGE sql STABLE SECURITY DEFINER;

COMMENT ON FUNCTION sf_find_active_api_key(TEXT) IS
    'Ingestion authentication lookup. Necessarily cross-tenant: the key determines the tenant. Single-purpose RLS bypass.';

CREATE OR REPLACE FUNCTION sf_email_exists(p_email TEXT) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1 FROM users WHERE email = p_email);
$$ LANGUAGE sql STABLE SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sf_slug_exists(p_slug TEXT) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1 FROM organizations WHERE slug = p_slug);
$$ LANGUAGE sql STABLE SECURITY DEFINER;

-- ---------------------------------------------------------------------------
-- Policies.
--
-- `organizations` is the tenant table itself, so its discriminator is `id`.
-- Every other table carries `organization_id`.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
    tenant_tables TEXT[] := ARRAY[
        'users', 'api_keys', 'services', 'service_instances', 'service_dependencies',
        'deployments', 'telemetry_events', 'telemetry_minute_rollups', 'detection_rules',
        'incidents', 'incident_events', 'incident_services', 'incident_comments',
        'alerts', 'incident_ai_summaries', 'notification_preferences', 'audit_events'
    ];
BEGIN
    FOREACH t IN ARRAY tenant_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        -- FORCE is belt-and-braces: the runtime role is not the owner, so
        -- ENABLE alone would suffice, but this also constrains anything that
        -- mistakenly connects as the owner.
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY sf_tenant_isolation ON %I
                 USING (organization_id = sf_current_org())
                 WITH CHECK (organization_id = sf_current_org())', t);
    END LOOP;
END $$;

-- The tenant table itself.
ALTER TABLE organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations FORCE ROW LEVEL SECURITY;
CREATE POLICY sf_tenant_isolation ON organizations
    USING (id = sf_current_org())
    WITH CHECK (id = sf_current_org());

-- ---------------------------------------------------------------------------
-- Deliberately NOT protected:
--
--   roles                — a static lookup table with no tenant data
--   incident_counters    — keyed by organization_id but read only via an
--                          INSERT ... ON CONFLICT RETURNING that already carries
--                          the id; adding a policy here would break reference
--                          allocation during tenant bootstrap, when the org row
--                          exists but the session GUC is not yet set
--   processed_messages   — Kafka idempotency ledger, keyed by consumer group and
--                          message id; contains no tenant-readable content
--   flyway_schema_history
-- ---------------------------------------------------------------------------
