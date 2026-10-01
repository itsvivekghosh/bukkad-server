-- PERF-4 / ADR-003 rider matching: per-agent active delivery load.
-- Additive-only (zero-downtime rules apply): one nullable-safe column with a
-- backfill default. No constraint is added, so no dup-sweep is required —
-- the RAISE tripwire pattern from V9 applies only when an existing column
-- would be tightened; here the column is new and defaults to 0 for every
-- existing rider.

-- Conditional load-cap anchor: rider matching increments this counter with
-- UPDATE delivery_agents SET active_load = active_load + 1
--  WHERE id = :id AND active_load < :cap   (1 row = the rider took the order;
-- 0 rows = the rider is at cap, try the next candidate) and decrements it on
-- markDelivered (DeliveryService), keeping the counter within [0, cap].
ALTER TABLE ONLY public.delivery_agents
    ADD COLUMN IF NOT EXISTS active_load integer NOT NULL DEFAULT 0;
