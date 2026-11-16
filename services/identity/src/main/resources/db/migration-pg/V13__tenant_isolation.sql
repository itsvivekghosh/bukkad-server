-- ============================================================================
-- V13: Multi-tenancy data isolation — tenant_id on core domain tables.
-- ============================================================================
--
-- Adds a nullable tenant_id (UUID) column + btree index to every core domain
-- table in the identity service. The column is deliberately NULLABLE here:
-- existing rows are NOT touched automatically. Populate the column and enforce
-- NOT NULL with scripts/db/backfill_tenant_id.sql (see docs/multi-tenancy-migration.md).
--
-- Row-level security policies are also created (not FORCED). They take effect
-- for the application once (a) rows are backfilled, (b) NOT NULL is enforced,
-- and (c) each application connection runs
--   SET app.current_tenant = <tenant-uuid>
-- for the request. Until then the table owner still bypasses RLS, so existing
-- behavior is unchanged.
-- ============================================================================

ALTER TABLE public.customers
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_customers_tenant_id
    ON public.customers USING btree (tenant_id);
ALTER TABLE public.customers ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.customers
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.customers.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.addresses
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_addresses_tenant_id
    ON public.addresses USING btree (tenant_id);
ALTER TABLE public.addresses ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.addresses
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.addresses.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.admins
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_admins_tenant_id
    ON public.admins USING btree (tenant_id);
ALTER TABLE public.admins ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.admins
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.admins.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_users_tenant_id
    ON public.users USING btree (tenant_id);
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.users
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.users.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.delivery_agents
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_delivery_agents_tenant_id
    ON public.delivery_agents USING btree (tenant_id);
ALTER TABLE public.delivery_agents ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.delivery_agents
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.delivery_agents.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.restaurant_owners
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_restaurant_owners_tenant_id
    ON public.restaurant_owners USING btree (tenant_id);
ALTER TABLE public.restaurant_owners ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.restaurant_owners
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.restaurant_owners.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.device_tokens
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_device_tokens_tenant_id
    ON public.device_tokens USING btree (tenant_id);
ALTER TABLE public.device_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.device_tokens
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.device_tokens.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.favorite_restaurants
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_favorite_restaurants_tenant_id
    ON public.favorite_restaurants USING btree (tenant_id);
ALTER TABLE public.favorite_restaurants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.favorite_restaurants
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.favorite_restaurants.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.customer_memberships
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_customer_memberships_tenant_id
    ON public.customer_memberships USING btree (tenant_id);
ALTER TABLE public.customer_memberships ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.customer_memberships
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.customer_memberships.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.customer_notification_preferences
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_customer_notification_preferences_tenant_id
    ON public.customer_notification_preferences USING btree (tenant_id);
ALTER TABLE public.customer_notification_preferences ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.customer_notification_preferences
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.customer_notification_preferences.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.consent_records
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_consent_records_tenant_id
    ON public.consent_records USING btree (tenant_id);
ALTER TABLE public.consent_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.consent_records
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.consent_records.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

-- Tables intentionally NOT altered: tenants (the tenant registry itself),
-- api_keys / membership_plans (platform-level reference data), and the
-- outbox/idempotency/dead-letter/saga tables (platform infra, not domain data).
-- Annotate their entities with @Filter(name="tenant") on a case-by-case basis
-- once a backfill strategy is agreed (see docs/multi-tenancy-migration.md).
