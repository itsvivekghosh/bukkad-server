-- V-02 finish (COD wallet ledger + wallet guard, Wave-1 runbook steps 1-2):
-- additive-only, PostgreSQL dialect.
--
-- 1. cod_wallet_ledger — append-only audit trail for every rider COD balance
--    mutation. One row per committed CREDIT/DEBIT, written inside the SAME
--    transaction as the balance change (CodWalletService), so the trail can
--    never disagree with the balance. balance_after carries the post-mutation
--    persisted balance; the per-agent chain (agent_id, created_at) must be
--    monotonic — the 72h soak gate checks exactly that.
CREATE TABLE IF NOT EXISTS public.cod_wallet_ledger (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id bigint NOT NULL,
    order_id bigint,
    type character varying(6) NOT NULL,
    amount numeric(12,2) NOT NULL,
    balance_after numeric(12,2) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL DEFAULT localtimestamp(6),
    CONSTRAINT ck_cod_wallet_ledger_type CHECK (type IN ('CREDIT', 'DEBIT'))
);

CREATE INDEX IF NOT EXISTS idx_cod_ledger_agent
    ON public.cod_wallet_ledger (agent_id, created_at);

-- 2. agent_cod_wallets guard (runbook step 2): optimistic-lock column plus a
--    UNIQUE(agent_id) constraint. Dup-sweep tripwire first — run the sweep in
--    a maintenance window before deploying this migration:
--      DELETE FROM agent_cod_wallets a USING agent_cod_wallets b
--       WHERE a.agent_id = b.agent_id AND a.id > b.id;  -- keep earliest
--    and reconcile the swept balances into the survivor. This migration
--    raises loudly instead of silently dropping money rows.
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

ALTER TABLE public.agent_cod_wallets
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
