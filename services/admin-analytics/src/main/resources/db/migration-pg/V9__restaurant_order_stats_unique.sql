-- PERF-2/V-12: guard the atomic projection upsert.
-- AdminCqrsEventConsumer.upsertStat switched from findById→+1→save to a native
-- INSERT ... ON CONFLICT (restaurant_id) DO UPDATE, which needs an indexed
-- conflict target. In the V1 baseline restaurant_id is already the PRIMARY KEY,
-- so this statement is a defensive no-op IF NOT EXISTS on today's schemas —
-- but it makes the ON CONFLICT dependency explicit, and it fails the migration
-- (instead of silently skipping the constraint) if a forked schema ever grew
-- duplicate rows or lost the key.

DO $$
BEGIN
    IF EXISTS (
        SELECT restaurant_id
        FROM public.restaurant_order_stats
        GROUP BY restaurant_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'PERF-2/V-12: duplicate rows in restaurant_order_stats — dedupe (keep min id per restaurant) before re-running';
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_restaurant_order_stats_restaurant_id
    ON public.restaurant_order_stats (restaurant_id);
