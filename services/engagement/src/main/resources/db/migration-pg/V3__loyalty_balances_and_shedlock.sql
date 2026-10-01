-- W1-LOYALTY (feature #4 / ADR-005): loyalty points become a durable ledger.
-- The append-only loyalty_points_ledger (V1 baseline) is the source of truth;
-- this migration adds the O(1) balance read-model rows derived from it.
--
-- 1. loyalty_point_balances: one row per customer, maintained ONLY through
--    atomic single-statement upserts/conditional updates (never a Java
--    read-modify-write), and re-derivable at any time as
--    SUM(CREDIT.points) - SUM(DEBIT.points) over the ledger.

CREATE TABLE public.loyalty_point_balances (
    customer_id bigint NOT NULL,
    points bigint DEFAULT 0 NOT NULL,
    lifetime_points bigint DEFAULT 0 NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT loyalty_point_balances_pkey PRIMARY KEY (customer_id)
);

-- 2. Ledger access path for the per-customer daily credit cap (sum of today's
--    CREDIT rows for one customer).
CREATE INDEX idx_loyalty_ledger_customer_type_created
    ON public.loyalty_points_ledger USING btree (customer_id, transaction_type, created_at);

-- 3. One active referral per referred customer on the growth-side tracking
--    table (partial: the column is nullable while a record is PENDING).
CREATE UNIQUE INDEX IF NOT EXISTS uq_referral_records_referred_customer
    ON public.referral_records (referred_customer_id)
    WHERE referred_customer_id IS NOT NULL;

-- 4. ShedLock registry for the nightly loyalty reconciliation job
--    (@SchedulerLock). Standard schema from the shedlock 5.x docs.
CREATE TABLE IF NOT EXISTS public.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
