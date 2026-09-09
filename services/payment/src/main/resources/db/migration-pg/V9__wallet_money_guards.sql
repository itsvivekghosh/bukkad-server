-- Wallet money-path hardening (audit V-01 finish, perf guide §4.3).
-- Additive-only: one optimistic-lock column, one value-domain constraint, and
-- a defensive uniqueness guard. The FOR UPDATE pessimistic path in
-- WalletService is the adopted locking position (docs §3.7); this migration
-- adds belt braces, never replaces it — the DB now also refuses to persist a
-- negative balance under any future code path.

-- 1. Dup-sweep guard for UNIQUE(customer_id). The consolidated V1 baseline
--    already declared uk_wallet_customer, but legacy/prod-derived schemas may
--    lack it or hold duplicate wallets for one customer (the exact drift this
--    audit keeps catching). Sweep in a maintenance window first:
--      DELETE FROM wallet_balances a USING wallet_balances b
--       WHERE a.customer_id = b.customer_id AND a.id > b.id;  -- keep earliest
--    and reconcile their balances into the survivor. This migration raises
--    loudly instead of silently dropping money rows.
DO $$
BEGIN
    IF EXISTS (
        SELECT customer_id FROM public.wallet_balances
        GROUP BY customer_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION
            USING MESSAGE := ('wallet_balances contains duplicate customer_id rows; reconcile and '
                || 'sweep them (sweep SQL in this migration''s header) before the '
                || 'UNIQUE(customer_id) guard is added');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_wallet_customer') THEN
        ALTER TABLE ONLY public.wallet_balances
            ADD CONSTRAINT uk_wallet_customer UNIQUE (customer_id);
    END IF;
END
$$;

-- 2. Non-negativity CHECK. Validate first so the migration fails loudly
--    pre-constraint instead of aborting mid-lock, then add (guarded because
--    ALTER ... ADD CONSTRAINT has no IF NOT EXISTS).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.wallet_balances WHERE balance < 0) THEN
        RAISE EXCEPTION
            USING MESSAGE := ('wallet_balances contains negative balances; reconcile before the '
                || 'ck_wallet_balance_nonneg CHECK can be enforced');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_wallet_balance_nonneg') THEN
        ALTER TABLE ONLY public.wallet_balances
            ADD CONSTRAINT ck_wallet_balance_nonneg CHECK (balance >= 0);
    END IF;
END
$$;

-- 3. Optimistic-lock column for the JPA @Version on WalletBalance. The
--    pessimistic FOR UPDATE read stays the primary serialization mechanism;
--    this makes accidental detached-entity writes (stale saves outside the
--    lock window) fail as OptimisticLockException instead of clobbering the
--    row, and gives Hibernate a correctness anchor on the update flush.
ALTER TABLE public.wallet_balances
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
