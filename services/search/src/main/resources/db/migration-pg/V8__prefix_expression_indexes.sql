-- PERF-3 (§4.3/§6.8): expression indexes backing the already-landed bounded
-- LIKE queries in the search module. The sync queries filter with
-- `lower(name) LIKE 'prefix%'` (prefix surface); the plain btree indexes from
-- the baseline cannot serve lower(...) prefix scans, so every autocomplete
-- / prefix search hit did a sequential scan. These are additive, IF NOT
-- EXISTS, and CONCURRENTLY-safe patterns are not used here because Flyway
-- runs this migration outside a lock-sensitive window in the dev/CI topology;
-- on a live fleet, apply during low traffic (index build holds SHARE lock).

CREATE INDEX IF NOT EXISTS idx_restaurant_search_name_lower_prefix
    ON restaurant_search ((lower(name)) varchar_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_menu_item_search_name_lower_prefix
    ON menu_item_search ((lower(name)) varchar_pattern_ops);
