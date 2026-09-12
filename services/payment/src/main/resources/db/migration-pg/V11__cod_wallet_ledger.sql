-- Audit V-02 finish: append-only ledger for agent COD wallet balance
-- mutations (docs/PRODUCTION-READINESS-AUDIT-GUIDE.md §V-02 / §11 runbook).
-- Additive-only, PostgreSQL dialect: the wallet row stays the authoritative
-- balance; the ledger records every committed CREDIT/DEBIT with the balance
-- as persisted (written in the SAME transaction as the mutation by
-- CodWalletService). A silent drain of rider cash can now be reconstructed
-- from the row history.

CREATE TABLE IF NOT EXISTS public.cod_wallet_ledger (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id bigint NOT NULL,
    -- Optional order attribution: the internal credit/debit surface accepts
    -- an orderId (e.g. the COD order whose cash arrived) but the amount-only
    -- contract must stay callable, so the column is nullable by design.
    order_id bigint,
    type character varying(6) NOT NULL,
    amount numeric(12,2) NOT NULL,
    balance_after numeric(12,2) NOT NULL,
    created_at timestamp(6) without time zone DEFAULT localtimestamp(6) NOT NULL
);

-- History reads are always "the agent's entries since T" — the audit
-- monotonicity check and the ops console both run on this access path.
CREATE INDEX IF NOT EXISTS idx_cod_ledger_agent_created
    ON public.cod_wallet_ledger (agent_id, created_at);
