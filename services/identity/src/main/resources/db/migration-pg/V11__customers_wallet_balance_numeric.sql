-- P0 money-integrity: customers.wallet_balance leaves binary floating point.
-- The legacy read-model column was double precision (V1) while the
-- authoritative wallet ledger is numeric(12,2); binary doubles cannot
-- represent decimal cents exactly (0.1 + 0.2 != 0.3), so every sync from the
-- wallet risked rounding drift. Additive widening to numeric(12,2) keeps the
-- exact cents the wallet domain writes; existing values round to 2 dp.
ALTER TABLE public.customers
    ALTER COLUMN wallet_balance TYPE numeric(12, 2) USING wallet_balance::numeric;
