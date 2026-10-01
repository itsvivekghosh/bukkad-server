-- P3 / P-08 (open product decision, shipped OFF): trigram fuzzy search.
-- ============================================================================
-- DECISION NOTE: the product call between prefix-LIKE and trigram similarity
-- ranking is NOT made here. This migration is the OPTIONAL groundwork only:
--   * the default search path remains the bounded lower(...) LIKE queries
--     backed by the V9 varchar_pattern_ops prefix indexes (untouched);
--   * similarity()/word_similarity() ranking is used EXCLUSIVELY behind
--     app.search.fuzzy.enabled (default FALSE) in SearchServiceImpl via
--     word_similarity() with a bound cutoff (per-word semantics; the plain
--     similarity()/% pair collapses on long columns at 0.3);
--   * keep the varchar_pattern_ops prefix indexes as the default until
--     product flips fuzzy on for an environment.
-- Additive-only (zero-downtime rules): no unique constraints → no dup-sweep
-- tripwire needed; every statement is IF NOT EXISTS / IF NOT and forward-safe
-- to re-run. GIN trigram builds take a SHARE lock (no writes during build) —
-- on a live fleet apply during low traffic, same posture as V9.
-- pg_trgm is a TRUSTED extension since PostgreSQL 13 → the per-domain app
-- role can CREATE it without superuser (dev/CI containers are superuser).

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Columns the LIKE text-search predicates hit (search module repositories):
-- restaurant_search: name, description, cuisine_summary
CREATE INDEX IF NOT EXISTS idx_restaurant_search_name_trgm
    ON public.restaurant_search USING gin (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_restaurant_search_description_trgm
    ON public.restaurant_search USING gin (description gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_restaurant_search_cuisine_summary_trgm
    ON public.restaurant_search USING gin (cuisine_summary gin_trgm_ops);

-- menu_item_search: name, description, category_name, food_type
CREATE INDEX IF NOT EXISTS idx_menu_item_search_name_trgm
    ON public.menu_item_search USING gin (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_menu_item_search_description_trgm
    ON public.menu_item_search USING gin (description gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_menu_item_search_category_name_trgm
    ON public.menu_item_search USING gin (category_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_menu_item_search_food_type_trgm
    ON public.menu_item_search USING gin (food_type gin_trgm_ops);
