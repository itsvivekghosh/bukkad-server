-- Audit V-02 finish: append-only COD wallet ledger (docs
-- PRODUCTION-READINESS-AUDIT-GUIDE.md, V-02 / R-04 audit trail).
--
-- One row per rider COD wallet movement, written by CodWalletService inside
-- the SAME transaction as the agent_cod_wallets balance update: the balance
-- and its evidence commit or roll back together. Rows are never updated or
-- deleted (reconciliation replays the (agent_id, created_at) sequence).
-- Additive-only: a new table + index; no existing table or row is touched.

CREATE TABLE IF NOT EXISTS public.cod_wallet_ledger (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id bigint NOT NULL,
    order_id bigint,
    type character varying(6) NOT NULL,
    amount numeric(12,2) NOT NULL,
    balance_after numeric(12,2) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL DEFAULT localtimestamp(6)
);

CREATE INDEX IF NOT EXISTS idx_cod_ledger_agent
    ON public.cod_wallet_ledger (agent_id, created_at);
