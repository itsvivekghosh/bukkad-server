-- ============================================================================
-- Backfill tenant_id for pre-multi-tenancy rows and enforce NOT NULL.
-- ============================================================================
--
-- Run ONCE per database, after applying:
--   services/identity/.../db/migration-pg/V13__tenant_isolation.sql
--   services/admin/.../db/migration-pg/V8__tenant_isolation.sql
--
-- This script does NOT run automatically (Flyway applies only the schema
-- change). Existing rows are assigned to the well-known platform tenant:
--   00000000-0000-0000-0000-000000000001   (TenantContext.PLATFORM_TENANT_ID)
--
-- Recommended rollout per database:
--   1. Apply V13 / V8 (adds nullable tenant_id + indexes + RLS policies).
--   2. Run this backfill script (populates tenant_id, sets NOT NULL).
--   3. (Optional) insert a tenants row for the platform tenant if you want a
--      registry row; no FK is declared so the UUID alone is sufficient.
--   4. Make each application connection run SET app.current_tenant = <uuid>
--      for the request, then ALTER TABLE ... FORCE ROW LEVEL SECURITY on the
--      tables you want enforced.
-- ============================================================================

-- Identity service core domain tables.
UPDATE public.customers
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.customers ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.addresses
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.addresses ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.admins
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.admins ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.users
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.users ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.delivery_agents
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.delivery_agents ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.restaurant_owners
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.restaurant_owners ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.device_tokens
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.device_tokens ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.favorite_restaurants
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.favorite_restaurants ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.customer_memberships
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.customer_memberships ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.customer_notification_preferences
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.customer_notification_preferences ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.consent_records
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.consent_records ALTER COLUMN tenant_id SET NOT NULL;

-- Admin service core domain tables (run against the admin database).
UPDATE public.support_tickets
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.support_tickets ALTER COLUMN tenant_id SET NOT NULL;

UPDATE public.disputes
    SET tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
    WHERE tenant_id IS NULL;
ALTER TABLE public.disputes ALTER COLUMN tenant_id SET NOT NULL;
