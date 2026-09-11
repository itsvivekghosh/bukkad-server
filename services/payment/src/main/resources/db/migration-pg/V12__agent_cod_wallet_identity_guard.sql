-- Audit V-02 finish, Wave-1 runbook step 2 (docs
-- PRODUCTION-READINESS-AUDIT-GUIDE.md §11): identity + optimistic-lock guards
-- for agent_cod_wallets, mirroring V9's wallet_balances treatment.
-- Additive-only: one optimistic-lock column and one uniqueness guard.

-- 1. Dup-sweep guard for UNIQUE(agent_id). Legacy/prod-derived schemas may
--    hold duplicate wallets for one agent (the exact drift this audit keeps
--    catching). Sweep in a maintenance window first:
--      DELETE FROM agent_cod_wallets a USING agent_cod_wallets b
--       WHERE a.agent_id = b.agent_id AND a.id > b.id;  -- keep earliest
--    and reconcile their balances into the survivor. This migration raises
--    loudly instead of silently dropping money rows.
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

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_agent_cod_wallet_agent') THEN
        ALTER TABLE ONLY public.agent_cod_wallets
            ADD CONSTRAINT uk_agent_cod_wallet_agent UNIQUE (agent_id);
    END IF;
END
$$;

-- 2. Optimistic-lock column for the JPA @Version on AgentCodWallet. The
--    pessimistic FOR UPDATE read stays the primary serialization mechanism;
--    this makes accidental detached-entity writes (stale saves outside the
--    lock window) fail as OptimisticLockException instead of clobbering the
--    row, and gives Hibernate a correctness anchor on the update flush.
ALTER TABLE public.agent_cod_wallets
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
