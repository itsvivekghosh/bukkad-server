-- Audit V-02 finish (docs/PRODUCTION-READINESS-AUDIT-GUIDE.md §11 runbook):
-- optimistic-lock column + uniqueness guard on agent_cod_wallets. The
-- pessimistic FOR UPDATE read in CodWalletService stays the adopted
-- serialization mechanism (docs §3.7); these are the same second fences
-- the customer wallet got in V9 (audit V-01 finish).
--
-- Dup-sweep precondition (run in a maintenance window FIRST if it fires):
--   SELECT agent_id, count(*) FROM agent_cod_wallets
--    GROUP BY agent_id HAVING count(*) > 1;
-- Rows found = split-brain wallets already fired in prod → ledger-reconcile
-- them into the survivor before re-running this migration. This migration
-- raises loudly instead of silently dropping money rows.

DO $$
BEGIN
    IF EXISTS (
        SELECT agent_id FROM public.agent_cod_wallets
        GROUP BY agent_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION
            USING MESSAGE := ('agent_cod_wallets contains duplicate agent_id rows; reconcile and '
                || 'sweep them (sweep SQL in this migration''s header) before the '
                || 'UNIQUE(agent_id) guard is added');
    END IF;

    -- The entity has declared a unique index on agent_id all along, but the
    -- consolidated V1 baseline never created it (prod is flyway +
    -- ddl-auto=none), so the dup window was schema-wide. Guarded so
    -- re-application stays a no-op once the constraint exists.
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_agent_cod_wallet_agent') THEN
        ALTER TABLE ONLY public.agent_cod_wallets
            ADD CONSTRAINT uk_agent_cod_wallet_agent UNIQUE (agent_id);
    END IF;
END
$$;

-- Optimistic-lock column for the JPA @Version on AgentCodWallet: accidental
-- detached saves outside the lock window now fail as OptimisticLockException
-- instead of clobbering the row.
ALTER TABLE public.agent_cod_wallets
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
