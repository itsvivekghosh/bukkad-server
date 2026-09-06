-- Owner attribution on restaurants (entity/DDL parity fix).
--
-- The Restaurant entity maps owner_id, but V1__baseline.sql omitted the
-- column, so every restaurants-table read failed with
-- "column r1_0.owner_id does not exist" (HTTP 500 on the entire public
-- catalog surface). Additive-only per zero-breakage rules.
ALTER TABLE public.restaurants
    ADD COLUMN IF NOT EXISTS owner_id bigint;

CREATE INDEX IF NOT EXISTS idx_restaurants_owner
    ON public.restaurants (owner_id);
