-- Dual-write migration for orders table (Phase 8.5).
-- This migration adds a trigger that mirrors INSERTs on public.orders
-- into shard_0.orders_v2 during the dual-write window.
--
-- Backfill script (run once after deploy):
--   INSERT INTO shard_0.orders_v2
--     (id, customer_id, restaurant_id, status, total_amount, currency, created_at, updated_at)
--   SELECT id, customer_id, restaurant_id, status, total_amount, currency, created_at, updated_at
--   FROM public.orders
--   WHERE id > :lastId
--   ORDER BY id
--   LIMIT 10000;
--
-- Cutover:
--   1. Verify shard_0.orders_v2 is fully caught up
--   2. Update application config to route order reads/writes to shard_0
--   3. After 1 week validation, drop trigger and old table

CREATE OR REPLACE FUNCTION mirror_order_to_shard()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO shard_0.orders_v2
        (id, customer_id, restaurant_id, status, total_amount, currency, created_at, updated_at)
    OVERRIDING SYSTEM VALUE
    VALUES
        (NEW.id, NEW.customer_id, NEW.restaurant_id, NEW.status, NEW.total_amount, NEW.currency, NEW.created_at, NEW.updated_at)
    ON CONFLICT (id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_mirror_order ON public.orders;
CREATE TRIGGER trg_mirror_order
    AFTER INSERT ON public.orders
    FOR EACH ROW
    EXECUTE FUNCTION mirror_order_to_shard();
