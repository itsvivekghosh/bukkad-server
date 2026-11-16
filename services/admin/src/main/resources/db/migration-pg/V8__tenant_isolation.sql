-- ============================================================================
-- V8: Multi-tenancy data isolation — tenant_id on admin core domain tables.
-- ============================================================================
--
-- Adds a nullable tenant_id (UUID) column + btree index to the admin service's
-- core domain tables. The column is deliberately NULLABLE here: existing rows
-- are NOT touched automatically. Populate the column and enforce NOT NULL with
-- scripts/db/backfill_tenant_id.sql (see docs/multi-tenancy-migration.md).
--
-- Row-level security policies are also created (not FORCED). They take effect
-- once rows are backfilled, NOT NULL is enforced, and each application
-- connection runs SET app.current_tenant = <tenant-uuid> for the request.
-- Until then the table owner still bypasses RLS, so existing behavior is
-- unchanged.
-- ============================================================================

ALTER TABLE public.support_tickets
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_support_tickets_tenant_id
    ON public.support_tickets USING btree (tenant_id);
ALTER TABLE public.support_tickets ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.support_tickets
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.support_tickets.tenant_id IS 'Owning tenant for multi-tenancy data isolation';

ALTER TABLE public.disputes
    ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_disputes_tenant_id
    ON public.disputes USING btree (tenant_id);
ALTER TABLE public.disputes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.disputes
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
COMMENT ON COLUMN public.disputes.tenant_id IS 'Owning tenant for multi-tenancy data isolation';
