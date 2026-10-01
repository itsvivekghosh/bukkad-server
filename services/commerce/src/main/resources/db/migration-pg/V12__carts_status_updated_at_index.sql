-- PERF-3 (V-carts): the hourly stale-cart sweeper predicates on
-- (status, updated_at) — the composite index this migration adds turns
-- `WHERE status = 'ACTIVE' AND updated_at < :cutoff ORDER BY id LIMIT n`
-- into a bounded index scan. Before this, CartRecoveryService materialized
-- all carts per tick (and was never scheduled at all). Additive only.

CREATE INDEX IF NOT EXISTS idx_carts_status_updated_at
    ON carts (status, updated_at);
