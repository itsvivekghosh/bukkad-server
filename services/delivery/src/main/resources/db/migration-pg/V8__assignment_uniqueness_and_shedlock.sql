-- Audit B10/PERF-4: delivery assignment atomicity + retention scheduling basis.
-- Additive-only (zero-downtime rules apply): one unique constraint, one guard,
-- one operational table.

-- 1. Dup-sweep guard (RUNBOOK FIRST, migration raises after).
--    Historical duplicate assignments could exist from the check-then-act
--    race (DeliveryService.assign pre-UNIQUE). This migration REFUSES to run
--    while duplicates exist — sweep them in a maintenance window first:
--      DELETE FROM delivery_assignments a
--       USING delivery_assignments b
--       WHERE a.order_id = b.order_id
--         AND a.id > b.id;               -- keep the earliest row per order
--    The RAISE below is the loud tripwire that the sweep has not happened.
DO $$
BEGIN
    IF EXISTS (
        SELECT order_id FROM public.delivery_assignments
        GROUP BY order_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION
            USING MESSAGE := ('delivery_assignments contains duplicate order_id rows; run the '
                || 'dup-sweep (keep earliest id per order_id) documented in this '
                || 'migration before adding uq_delivery_assignments_order');
    END IF;
END
$$;

-- 2. Single assignment per order (the atomicity anchor used by
--    INSERT ... ON CONFLICT (order_id) DO NOTHING). The pre-existing
--    idx_assign_order btree is left in place (harmless redundancy; removal
--    is a separate destructive-index decision).
ALTER TABLE ONLY public.delivery_assignments
    ADD CONSTRAINT uq_delivery_assignments_order UNIQUE (order_id);

-- 3. Rider location retention purge: cutoff-only DELETE ... ORDER BY
--    recorded_at LIMIT n (RiderLocationRetentionScheduler). The existing
--    idx_rider_location_agent leads with agent_id and cannot serve it, so the
--    purge gets its own (additive) range index.
CREATE INDEX IF NOT EXISTS idx_rider_location_recorded
    ON public.rider_location_updates (recorded_at);

-- 4. ShedLock registry for the rider-location retention purge
--    (@SchedulerLock). Standard schema from the shedlock 5.x docs;
--    CREATE IF NOT EXISTS keeps this forward-safe once other delivery
--    schedulers adopt the same table.
CREATE TABLE IF NOT EXISTS public.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
