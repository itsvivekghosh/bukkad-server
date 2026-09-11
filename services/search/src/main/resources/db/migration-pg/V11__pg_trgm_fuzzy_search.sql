-- ADR-002 search engine: pg_trgm similarity substrate for the PostgreSQL
-- search tables. IMPLEMENTED BEHIND FLAG (app.search.fuzzy.enabled, default
-- FALSE) — PRODUCT ROLLOUT PENDING: the compatibility LIKE/prefix path stays
-- the default until the flag has soaked (ADR-002 keeps the bounded LIKE
-- queries as the compatibility surface).
--
-- Additive-only: one extension + GIN trigram indexes on the columns the
-- search queries actually target (name/description/cuisine on
-- restaurant_search; name/description/category on menu_item_search). The
-- landed varchar_pattern_ops prefix indexes (V9) are untouched and remain
-- the default autocomplete path.
--
-- NOTE: CREATE EXTENSION needs elevated privileges (superuser or a
-- pre-approved extension allowlist). The app's migration user must have the
-- extension pre-created or granted; on Testcontainers/dev the test user is
-- the cluster superuser.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_restaurant_search_name_trgm
    ON public.restaurant_search USING gin ((lower(name)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_restaurant_search_description_trgm
    ON public.restaurant_search USING gin ((lower(description)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_restaurant_search_cuisine_trgm
    ON public.restaurant_search USING gin ((lower(cuisine_summary)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_menu_item_search_name_trgm
    ON public.menu_item_search USING gin ((lower(name)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_menu_item_search_description_trgm
    ON public.menu_item_search USING gin ((lower(description)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_menu_item_search_category_trgm
    ON public.menu_item_search USING gin ((lower(category_name)) gin_trgm_ops);
