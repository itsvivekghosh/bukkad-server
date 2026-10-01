-- Menu versioning columns (label + workflow status + publish timestamp)
-- used by the merchant app's draft → publish flow. Additive-only.
ALTER TABLE public.menu_versions
    ADD COLUMN IF NOT EXISTS label character varying(100),
    ADD COLUMN IF NOT EXISTS status character varying(20)
        DEFAULT 'DRAFT' NOT NULL,
    ADD COLUMN IF NOT EXISTS published_at timestamp(6) without time zone;

CREATE INDEX IF NOT EXISTS idx_menu_versions_restaurant
    ON public.menu_versions (restaurant_id);
